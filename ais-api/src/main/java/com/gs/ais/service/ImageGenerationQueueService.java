package com.gs.ais.service;

import com.gs.ais.client.LlmClient;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.dto.request.DrawRequest;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.model.entity.*;
import com.gs.ais.model.enums.*;
import com.gs.ais.repository.*;
import com.gs.ais.util.LlmErrorMessageUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ImageGenerationQueueService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationQueueService.class);

    private final LlmClient llmClient;
    private final ModelProviderService modelProviderService;
    private final SessionService sessionService;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final BillingService billingService;
    private final Path uploadDir;
    private final Path attachmentDir;

    @Value("${ais.image.queue.max-concurrent:2}")
    private int maxConcurrent;

    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<Long, CompletableFuture<Void>> pendingFutures = new ConcurrentHashMap<>();
    /** Latest human-readable queue stage for each pending message. */
    private final Map<Long, String> processingInfo = new ConcurrentHashMap<>();

    public ImageGenerationQueueService(LlmClient llmClient,
                                       ModelProviderService modelProviderService,
                                       SessionService sessionService,
                                       MessageRepository messageRepository,
                                       AttachmentRepository attachmentRepository,
                                       BillingService billingService,
                                       StoragePaths storagePaths) {
        this.llmClient = llmClient;
        this.modelProviderService = modelProviderService;
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.billingService = billingService;
        this.uploadDir = storagePaths.uploadDir();
        this.attachmentDir = storagePaths.attachmentDir();
        initUploadDir();
    }

    @PostConstruct
    public void init() {
        log.info("Image generation queue initialized with maxConcurrent={}", maxConcurrent);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void initUploadDir() {
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory", e);
        }
    }

    /**
     * Submit a draw request to the queue. Returns immediately with the assistant message ID.
     * The message status is PENDING until the queue processes it.
     */
    @Transactional
    public Long submitDraw(Long sessionId, DrawRequest request, Long userId) {
        Session session = sessionService.getSession(sessionId);
        DrawRequest safeRequest = request != null ? request : new DrawRequest();

        String prompt = safeRequest.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = findLastAssistantContent(sessionId);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new RuntimeException("Prompt is required for image generation");
        }
        prompt = prompt.trim();

        boolean isFirstMessage = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).isEmpty();
        Long imageProviderId = safeRequest.getImageProviderId() != null
                ? safeRequest.getImageProviderId()
                : session.getImageProviderId();

        Message userMessage = new Message();
        userMessage.setSession(session);
        userMessage.setRole(MessageRole.USER);
        userMessage.setMessageType(MessageType.DRAW_REQUEST);
        userMessage.setStatus(MessageStatus.SUCCESS);
        userMessage.setContent(buildDrawMessageContent(prompt, safeRequest));
        applyDrawMetadata(userMessage, prompt, safeRequest, imageProviderId);
        userMessage = messageRepository.save(userMessage);
        attachRequestAttachments(userMessage, safeRequest.getAttachmentIds());

        Message assistantMessage = new Message();
        assistantMessage.setSession(session);
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setMessageType(MessageType.DRAW_RESPONSE);
        assistantMessage.setStatus(MessageStatus.PENDING);
        assistantMessage.setContent("图片生成中...");
        assistantMessage.setParentMessageId(userMessage.getId());
        applyDrawMetadata(assistantMessage, prompt, safeRequest, imageProviderId);
        assistantMessage = messageRepository.save(assistantMessage);

        if (isFirstMessage) {
            String title = prompt.length() > 30 ? prompt.substring(0, 30) + "..." : prompt;
            sessionService.updateProvisionalSessionTitle(sessionId, title);
        }

        final Long assistantMessageId = assistantMessage.getId();
        final Long userMessageId = userMessage.getId();
        final Long finalUserId = userId;
        final String finalPrompt = prompt;
        processingInfo.put(assistantMessageId, "正在排队");

        Runnable task = () -> processDraw(sessionId, userMessageId, assistantMessageId,
                finalPrompt, imageProviderId, safeRequest, finalUserId, null);
        submitAfterCommit(assistantMessageId, task);

        return assistantMessageId;
    }

    /**
     * Queue a new attempt for messages that have already been created by a
     * resend/regenerate operation. This preserves the original draw request and
     * its reference attachments while reusing the standard queue, retry, and
     * billing flow.
     */
    @Transactional
    public Long submitExistingDraw(Long sessionId, Long userMessageId, Long assistantMessageId,
                                   String prompt, Long imageProviderId, DrawRequest request,
                                   Long userId, String previousImageUrl) {
        if (prompt == null || prompt.isBlank()) {
            throw new RuntimeException("Prompt is required for image generation");
        }
        DrawRequest safeRequest = request != null ? request : new DrawRequest();
        String finalPrompt = prompt.trim();
        processingInfo.put(assistantMessageId, "正在排队");

        Runnable task = () -> processDraw(sessionId, userMessageId, assistantMessageId,
                finalPrompt, imageProviderId, safeRequest, userId, previousImageUrl);
        submitAfterCommit(assistantMessageId, task);
        return assistantMessageId;
    }

    private void processDraw(Long sessionId, Long userMessageId, Long assistantMessageId, String prompt,
                             Long imageProviderId, DrawRequest request, Long userId,
                             String previousImageUrl) {
        try {
            // Wait for capacity
            while (true) {
                int current = activeCount.get();
                if (current < maxConcurrent && activeCount.compareAndSet(current, current + 1)) {
                    break;
                }
                updateOperationStage(assistantMessageId, "正在排队");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            try {
                updateOperationStage(assistantMessageId, "已提交出图");
                Session session = sessionService.getSession(sessionId);

                Message assistantMessage = messageRepository.findById(assistantMessageId)
                        .orElseThrow(() -> new RuntimeException("Message not found: " + assistantMessageId));

                // Check if cancelled
                if (wasCancelled(assistantMessageId)) return;

                List<LlmClient.ReferenceImage> referenceImages = loadReferenceImages(userMessageId);
                ModelProvider imageProvider = resolveImageProvider(imageProviderId);
                LlmClient.ImageGenerationOptions options = new LlmClient.ImageGenerationOptions(
                        cleanOption(request.getSize()),
                        cleanOption(request.getQuality()),
                        cleanOption(request.getFormat()));

                byte[] imageData = generateImageWithRetry(prompt, imageProvider, options, referenceImages,
                        assistantMessageId);

                if (wasCancelled(assistantMessageId)) return;

                updateOperationStage(assistantMessageId, "正在保存图片");
                String imageUrl = saveGeneratedImage(sessionId, imageData);

                if (wasCancelled(assistantMessageId)) {
                    deleteGeneratedImageUrl(imageUrl);
                    return;
                }

                if (previousImageUrl != null && !previousImageUrl.equals(imageUrl)) {
                    deleteGeneratedImageUrl(previousImageUrl);
                }
                assistantMessage.setStatus(MessageStatus.SUCCESS);
                assistantMessage.setContent("已根据提示词生成图片。");
                assistantMessage.setImageUrl(imageUrl);
                assistantMessage.setErrorMessage(null);
                messageRepository.save(assistantMessage);

                // Record billing
                if (userId != null && imageProvider != null) {
                    billingService.recordGeneration(imageProvider, userId, sessionId, assistantMessageId);
                }

                log.info("Image generated successfully for message {} (session {})", assistantMessageId, sessionId);
            } finally {
                activeCount.decrementAndGet();
            }
        } catch (Exception e) {
            try {
                String error = LlmErrorMessageUtils.describe(e);
                log.error("Image generation failed for message {}: {}", assistantMessageId, error);

                Message msg = messageRepository.findById(assistantMessageId).orElse(null);
                if (msg != null && msg.getStatus() == MessageStatus.PENDING) {
                    msg.setStatus(MessageStatus.FAILED);
                    msg.setContent("图片生成失败。请检查提示词、模型供应商或输出参数后重试。");
                    msg.setErrorMessage(error);
                    if (previousImageUrl != null) {
                        msg.setImageUrl(previousImageUrl);
                    }
                    messageRepository.save(msg);
                }
            } catch (Exception ex) {
                log.error("Failed to update failed message status", ex);
            }
        } finally {
            pendingFutures.remove(assistantMessageId);
        }
    }

    private void submitAfterCommit(Long messageId, Runnable task) {
        Runnable submit = () -> {
            CompletableFuture<Void> future = CompletableFuture.runAsync(task, executor);
            pendingFutures.put(messageId, future);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    private void updateOperationStage(Long messageId, String stage) {
        processingInfo.put(messageId, stage);
        log.debug("Message {} stage: {}", messageId, stage);
    }

    public String getProcessingInfo(Long messageId) {
        return processingInfo.get(messageId);
    }

    private byte[] generateImageWithRetry(String prompt, ModelProvider provider,
                                           LlmClient.ImageGenerationOptions options,
                                           List<LlmClient.ReferenceImage> references,
                                           Long assistantMessageId) {
        int retries = ModelProviderDefaults.maxRetries(provider);
        int backoff = ModelProviderDefaults.retryBackoffSeconds(provider);
        for (int attempt = 0; ; attempt++) {
            try {
                String stage = attempt == 0 ? "已提交出图..." : "第 " + (attempt + 1) + " 次提交重试...";
                updateOperationStage(assistantMessageId, stage);
                return llmClient.generateImage(prompt, provider, options, references);
            } catch (Exception e) {
                if (attempt >= retries || !isRetryableImageFailure(e)) {
                    if (e instanceof RuntimeException runtime) throw runtime;
                    throw new RuntimeException(e);
                }
                long waitSeconds = (long) backoff * (1L << Math.min(attempt, 5));
                String stage2 = "正在退避 " + waitSeconds + " 秒...";
                updateOperationStage(assistantMessageId, stage2);
                log.warn("Image request failed (attempt {}/{}), retrying in {}s: {}",
                        attempt + 1, retries + 1, waitSeconds, e.getMessage());
                try {
                    Thread.sleep(waitSeconds * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Image retry interrupted", interrupted);
                }
            }
        }
    }

    private boolean isRetryableImageFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ResourceAccessException) return true;
            if (current instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                return status == 429 || status >= 500;
            }
            current = current.getCause();
        }
        return false;
    }

    @Transactional(readOnly = true)
    public MessageStatus getMessageStatus(Long messageId) {
        return messageRepository.findById(messageId)
                .map(Message::getStatus)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Message getMessage(Long messageId) {
        return messageRepository.findById(messageId)
                .orElse(null);
    }

    public Long cancelPending(Long sessionId) {
        List<Message> all = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        for (int i = all.size() - 1; i >= 0; i--) {
            Message pending = all.get(i);
            if (pending.getRole() == MessageRole.ASSISTANT && pending.getStatus() == MessageStatus.PENDING) {
                pending.setStatus(MessageStatus.FAILED);
                pending.setErrorMessage("用户已终止本次请求。");
                pending.setContent(pending.getMessageType() == MessageType.DRAW_RESPONSE
                        ? "图片生成已终止。您可以点击重新生成再次尝试。"
                        : "AI 请求已终止。您可以点击重新生成再次尝试。");
                messageRepository.save(pending);

                // Cancel any pending future
                CompletableFuture<Void> future = pendingFutures.remove(pending.getId());
                if (future != null) {
                    future.cancel(true);
                }
                return pending.getId();
            }
        }
        return null;
    }

    private boolean wasCancelled(Long messageId) {
        if (messageId == null) return false;
        return messageRepository.findById(messageId)
                .map(current -> current.getStatus() == MessageStatus.FAILED
                        && current.getErrorMessage() != null
                        && current.getErrorMessage().contains("用户已终止"))
                .orElse(false);
    }

    private String saveGeneratedImage(Long sessionId, byte[] imageData) {
        String ext = detectImageFormat(imageData);
        String datePath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String imgFilename = "generated/" + datePath + "/" + UUID.randomUUID() + "." + ext;
        Path imagePath = uploadDir.resolve(imgFilename);
        try {
            Files.createDirectories(imagePath.getParent());
            Files.write(imagePath, imageData);
            log.info("Image saved: {} ({} bytes, format: {})", imagePath, imageData.length, ext);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save image file", e);
        }
        return "/api/images/" + imgFilename;
    }

    private String detectImageFormat(byte[] data) {
        if (data == null || data.length < 4) return "png";
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return "png";
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return "jpg";
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "gif";
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data.length > 12 && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return "webp";
        return "png";
    }

    private void deleteGeneratedImageUrl(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("/api/images/")) return;
        try {
            String filename = imageUrl.replace("/api/images/", "");
            Files.deleteIfExists(uploadDir.resolve(filename));
        } catch (IOException e) {
            log.warn("Failed to delete generated image {}: {}", imageUrl, e.getMessage());
        }
    }

    private String cleanOption(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void attachRequestAttachments(Message userMessage, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return;
        for (Long attachmentId : attachmentIds) {
            if (attachmentId == null) continue;
            attachmentRepository.findById(attachmentId).ifPresent(attachment -> {
                attachment.setMessage(userMessage);
                attachmentRepository.save(attachment);
            });
        }
    }

    private String findLastAssistantContent(Long sessionId) {
        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getRole() == MessageRole.ASSISTANT
                    && (message.getMessageType() == null || message.getMessageType() == MessageType.CHAT)
                    && message.getStatus() != MessageStatus.FAILED
                    && message.getImageUrl() == null
                    && message.getContent() != null
                    && !message.getContent().isBlank()) {
                return message.getContent();
            }
        }
        return null;
    }

    private String buildDrawMessageContent(String prompt, DrawRequest request) {
        List<String> options = new ArrayList<>();
        if (request != null) {
            if (cleanOption(request.getSize()) != null) options.add("尺寸 " + request.getSize().trim());
            if (cleanOption(request.getQuality()) != null) options.add("质量 " + request.getQuality().trim());
            if (cleanOption(request.getFormat()) != null) options.add("格式 " + request.getFormat().trim());
        }
        if (options.isEmpty()) {
            return "绘画提示词：" + prompt;
        }
        return "绘画提示词：" + prompt + "\n输出配置：" + String.join("；", options);
    }

    private void applyDrawMetadata(Message message, String prompt, DrawRequest request, Long imageProviderId) {
        message.setDrawPrompt(prompt);
        if (request != null) {
            message.setDrawSize(cleanOption(request.getSize()));
            message.setDrawQuality(cleanOption(request.getQuality()));
            message.setDrawFormat(cleanOption(request.getFormat()));
        }
        message.setDrawProviderId(imageProviderId);
    }

    private List<LlmClient.ReferenceImage> loadReferenceImages(Long messageId) {
        List<LlmClient.ReferenceImage> references = new ArrayList<>();
        for (Attachment att : attachmentRepository.findByMessageId(messageId)) {
            if (att.getContentType() == null || !att.getContentType().startsWith("image/")) continue;
            try {
                references.add(new LlmClient.ReferenceImage(att.getOriginalName(), att.getContentType(),
                        Files.readAllBytes(attachmentDir.resolve(att.getFilename()))));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read reference image: " + att.getOriginalName(), e);
            }
        }
        return references;
    }

    private ModelProvider resolveImageProvider(Long imageProviderId) {
        if (imageProviderId != null) {
            return modelProviderService.getById(imageProviderId);
        }
        return modelProviderService.getActiveProvider(ProviderType.IMAGE);
    }
}