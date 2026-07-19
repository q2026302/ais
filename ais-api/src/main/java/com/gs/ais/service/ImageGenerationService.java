package com.gs.ais.service;

import com.gs.ais.client.LlmClient;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.dto.request.DrawRequest;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.util.LlmErrorMessageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private final LlmClient llmClient;
    private final ModelProviderService modelProviderService;
    private final SessionService sessionService;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final ChatService chatService;
    private final ConversationTitleService conversationTitleService;
    private final BillingService billingService;

    private final Path uploadDir;
    private final Path attachmentDir;

    public ImageGenerationService(LlmClient llmClient,
                                  ModelProviderService modelProviderService,
                                  SessionService sessionService,
                                  MessageRepository messageRepository,
                                  AttachmentRepository attachmentRepository,
                                  ChatService chatService,
                                  ConversationTitleService conversationTitleService,
                                  BillingService billingService,
                                  StoragePaths storagePaths) {
        this.llmClient = llmClient;
        this.modelProviderService = modelProviderService;
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.chatService = chatService;
        this.conversationTitleService = conversationTitleService;
        this.billingService = billingService;
        this.uploadDir = storagePaths.uploadDir();
        this.attachmentDir = storagePaths.attachmentDir();
        initUploadDir();
    }

    private void initUploadDir() {
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory", e);
        }
    }

    private ModelProvider resolveChatProvider(Long sessionChatProviderId) {
        if (sessionChatProviderId != null) {
            return modelProviderService.getById(sessionChatProviderId);
        }
        return modelProviderService.getActiveProvider(ProviderType.CHAT);
    }

    private ModelProvider resolveImageProvider(Long sessionImageProviderId) {
        if (sessionImageProviderId != null) {
            return modelProviderService.getById(sessionImageProviderId);
        }
        return modelProviderService.getActiveProvider(ProviderType.IMAGE);
    }

    public ModelProvider getProviderById(Long providerId) {
        if (providerId == null) return null;
        try {
            return modelProviderService.getById(providerId);
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== Flow A: Chat message =====================

    /**
     * Send a chat message with full conversation context.
     * Saves both user and assistant messages and embeds uploaded images in the
     * current multimodal user request.
     */
    public ChatMessageResult chat(Long sessionId, String userInput,
                                   List<Long> attachmentIds,
                                   Long chatProviderId) {
        Session session = sessionService.getSession(sessionId);
        String safeUserInput = userInput != null ? userInput.trim() : "";
        boolean isFirstMessage = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).isEmpty();
        List<LlmClient.ReferenceImage> chatImages = loadImageAttachments(attachmentIds);

        // Resolve the provider before building context so its system prompt is applied.
        ModelProvider chatProvider = resolveChatProvider(
                chatProviderId != null ? chatProviderId : session.getChatProviderId());
        // Build context before saving the current user message to avoid sending it twice.
        List<Map<String, Object>> messages = chatService.buildMessages(
                sessionId, safeUserInput, chatProvider, chatImages);

        // 1. Save user message immediately.
        Message userMessage = new Message();
        userMessage.setSession(session);
        userMessage.setRole(MessageRole.USER);
        userMessage.setMessageType(MessageType.CHAT);
        userMessage.setStatus(MessageStatus.SUCCESS);
        userMessage.setContent(safeUserInput);
        userMessage = messageRepository.save(userMessage);

        final Message savedUserMsg = userMessage;
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            for (Long attId : attachmentIds) {
                attachmentRepository.findById(attId).ifPresent(att -> {
                    att.setMessage(savedUserMsg);
                    attachmentRepository.save(att);
                });
            }
        }

        // 2. Save an assistant placeholder so the conversation has a durable pending response.
        Message assistantMessage = new Message();
        assistantMessage.setSession(session);
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setMessageType(MessageType.CHAT);
        assistantMessage.setStatus(MessageStatus.PENDING);
        assistantMessage.setContent("等待回应...");
        assistantMessage.setParentMessageId(userMessage.getId());
        assistantMessage = messageRepository.save(assistantMessage);

        if (isFirstMessage) {
            String titleSource = !safeUserInput.isBlank()
                    ? safeUserInput
                    : chatImages.stream().findFirst().map(LlmClient.ReferenceImage::filename).orElse("图片对话");
            String title = titleSource.length() > 30 ? titleSource.substring(0, 30) + "..." : titleSource;
            sessionService.updateProvisionalSessionTitle(sessionId, title);
        }

        // 3. Call chat model, then replace the placeholder with success/failure content.
        try {
            LlmClient.ChatResult chatResult = llmClient.chat(messages, chatProvider);
            if (wasCancelled(assistantMessage.getId())) {
                return new ChatMessageResult("AI 请求已由用户终止。", assistantMessage.getId(), null,
                        MessageStatus.FAILED, "用户已终止本次请求。");
            }
            log.info("Chat response ({} tokens): {}", chatResult.totalTokens(),
                    chatResult.content().substring(0, Math.min(100, chatResult.content().length())));

            assistantMessage.setStatus(MessageStatus.SUCCESS);
            assistantMessage.setContent(chatResult.content());
            assistantMessage.setErrorMessage(null);
            assistantMessage.setPromptTokens(chatResult.promptTokens());
            assistantMessage.setCompletionTokens(chatResult.completionTokens());
            assistantMessage.setTotalTokens(chatResult.totalTokens());
            messageRepository.save(assistantMessage);
            conversationTitleService.generateTitleWhenReady(sessionId, chatProvider);

            return new ChatMessageResult(chatResult.content(), assistantMessage.getId(),
                    new com.gs.ais.dto.response.TokenUsage(
                            chatResult.promptTokens(), chatResult.completionTokens(), chatResult.totalTokens()),
                    MessageStatus.SUCCESS, null);
        } catch (Exception e) {
            if (wasCancelled(assistantMessage.getId())) {
                return new ChatMessageResult("AI 请求已由用户终止。", assistantMessage.getId(), null,
                        MessageStatus.FAILED, "用户已终止本次请求。");
            }
            String error = LlmErrorMessageUtils.describe(e);
            log.error("Chat failed", e);
            assistantMessage.setStatus(MessageStatus.FAILED);
            assistantMessage.setContent("AI 回应失败。请检查对话模型供应商或稍后重试。");
            assistantMessage.setErrorMessage(error);
            messageRepository.save(assistantMessage);
            return new ChatMessageResult(assistantMessage.getContent(), assistantMessage.getId(), null,
                    MessageStatus.FAILED, error);
        }
    }

    // ===================== Flow B: Draw (generate image) =====================

    /**
     * Generate an image from an explicit draw request. The prompt and reference
     * images are saved as a user message, and the generated image is saved as the
     * following assistant message.
     */
    public DrawResult draw(Long sessionId, DrawRequest request) {
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

        try {
            List<LlmClient.ReferenceImage> referenceImages = attachReferences(userMessage, safeRequest.getAttachmentIds());
            ModelProvider imageProvider = resolveImageProvider(imageProviderId);
            LlmClient.ImageGenerationOptions options = new LlmClient.ImageGenerationOptions(
                    cleanOption(safeRequest.getSize()),
                    cleanOption(safeRequest.getQuality()),
                    cleanOption(safeRequest.getFormat()));
            byte[] imageData = generateImageWithRetry(prompt, imageProvider, options, referenceImages);
            if (wasCancelled(assistantMessage.getId())) {
                return new DrawResult(assistantMessage.getId(), null, prompt, MessageStatus.FAILED,
                        "用户已终止本次请求。");
            }
            String imageUrl = saveGeneratedImage(sessionId, imageData);
            if (wasCancelled(assistantMessage.getId())) {
                deleteGeneratedImageUrl(imageUrl);
                return new DrawResult(assistantMessage.getId(), null, prompt, MessageStatus.FAILED,
                        "用户已终止本次请求。");
            }

            assistantMessage.setStatus(MessageStatus.SUCCESS);
            assistantMessage.setContent("已根据提示词生成图片。");
            assistantMessage.setImageUrl(imageUrl);
            assistantMessage.setErrorMessage(null);
            messageRepository.save(assistantMessage);

            return new DrawResult(assistantMessage.getId(), imageUrl, prompt, MessageStatus.SUCCESS, null);
        } catch (Exception e) {
            if (wasCancelled(assistantMessage.getId())) {
                return new DrawResult(assistantMessage.getId(), null, prompt, MessageStatus.FAILED,
                        "用户已终止本次请求。");
            }
            String error = LlmErrorMessageUtils.describe(e);
            log.error("Image generation failed", e);
            assistantMessage.setStatus(MessageStatus.FAILED);
            assistantMessage.setContent("图片生成失败。请检查提示词、模型供应商或输出参数后重试。");
            assistantMessage.setErrorMessage(error);
            messageRepository.save(assistantMessage);
            return new DrawResult(assistantMessage.getId(), null, prompt, MessageStatus.FAILED, error);
        }
    }

    /**
     * Backward-compatible draw endpoint: use the last assistant message as prompt.
     */
    public DrawResult draw(Long sessionId, Long imageProviderId) {
        DrawRequest request = new DrawRequest();
        request.setImageProviderId(imageProviderId);
        return draw(sessionId, request);
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

    private List<LlmClient.ReferenceImage> loadImageAttachments(List<Long> attachmentIds) {
        List<LlmClient.ReferenceImage> images = new ArrayList<>();
        if (attachmentIds == null || attachmentIds.isEmpty()) return images;

        for (Long attachmentId : attachmentIds) {
            attachmentRepository.findById(attachmentId).ifPresent(attachment -> {
                if (attachment.getContentType() == null || !attachment.getContentType().startsWith("image/")) {
                    return;
                }
                try {
                    images.add(new LlmClient.ReferenceImage(
                            attachment.getOriginalName(),
                            attachment.getContentType(),
                            Files.readAllBytes(attachmentDir.resolve(attachment.getFilename()))));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read chat image: " + attachment.getOriginalName(), e);
                }
            });
        }
        return images;
    }

    private List<LlmClient.ReferenceImage> attachReferences(Message userMessage, List<Long> attachmentIds) {
        List<LlmClient.ReferenceImage> references = new ArrayList<>();
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return references;
        }

        for (Long attId : attachmentIds) {
            attachmentRepository.findById(attId).ifPresent(att -> {
                att.setMessage(userMessage);
                attachmentRepository.save(att);

                if (att.getContentType() != null && att.getContentType().startsWith("image/")) {
                    Path path = attachmentDir.resolve(att.getFilename());
                    try {
                        references.add(new LlmClient.ReferenceImage(
                                att.getOriginalName(),
                                att.getContentType(),
                                Files.readAllBytes(path)));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read reference image: " + att.getOriginalName(), e);
                    }
                }
            });
        }
        return references;
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

    private String cleanOption(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Detect image format from magic bytes.
     */
    private String detectImageFormat(byte[] data) {
        if (data == null || data.length < 4) return "png";
        // PNG: 89 50 4E 47
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return "png";
        // JPEG: FF D8
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return "jpg";
        // GIF: 47 49 46
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "gif";
        // WebP: 52 49 46 46 ... 57 45 42 50
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data.length > 12 && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return "webp";
        return "png";
    }

    // ===================== Regenerate =====================

    public GenerationResult regenerateMessage(Long sessionId, Long messageId) {
        return regenerateMessage(sessionId, messageId, null, null);
    }

    public GenerationResult regenerateMessage(Long sessionId, Long messageId,
                                               Long chatProviderId,
                                               Long imageProviderId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found: " + messageId));
        if (message.getSession() == null || !sessionId.equals(message.getSession().getId())) {
            throw new RuntimeException("Message does not belong to session: " + sessionId);
        }
        Session session = sessionService.getSession(sessionId);

        if (message.getRole() == MessageRole.USER) {
            return resendUserMessage(session, message, chatProviderId, imageProviderId);
        }
        if (message.getRole() != MessageRole.ASSISTANT) {
            throw new RuntimeException("Only user or assistant messages can be regenerated");
        }

        Message userMessage = message.getParentMessageId() != null
                ? messageRepository.findById(message.getParentMessageId())
                    .orElseThrow(() -> new RuntimeException("Parent message not found"))
                : findLatestUserMessage(sessionId);

        if (message.getMessageType() == MessageType.DRAW_RESPONSE) {
            return regenerateDrawOnly(session, message, userMessage, imageProviderId);
        }

        ModelProvider chatProvider = resolveChatProvider(
                chatProviderId != null ? chatProviderId : session.getChatProviderId());
        List<Map<String, Object>> chatMessages = chatService.buildMessagesThroughUserMessage(
                sessionId, userMessage, chatProvider, referencesFromMessage(userMessage));
        message.setStatus(MessageStatus.PENDING);
        message.setContent("等待回应...");
        message.setErrorMessage(null);
        messageRepository.save(message);
        try {
            LlmClient.ChatResult chatResult = llmClient.chat(chatMessages, chatProvider);
            if (wasCancelled(message.getId())) {
                return new GenerationResult(message.getId(), "AI 请求已由用户终止。", null, null);
            }
            message.setContent(chatResult.content());
            message.setStatus(MessageStatus.SUCCESS);
            message.setErrorMessage(null);
            message.setImageUrl(null);
            message.setPromptTokens(chatResult.promptTokens());
            message.setCompletionTokens(chatResult.completionTokens());
            message.setTotalTokens(chatResult.totalTokens());
            messageRepository.save(message);
            return new GenerationResult(message.getId(), chatResult.content(), null,
                    new com.gs.ais.dto.response.TokenUsage(
                            chatResult.promptTokens(), chatResult.completionTokens(), chatResult.totalTokens()));
        } catch (Exception e) {
            if (wasCancelled(message.getId())) {
                return new GenerationResult(message.getId(), "AI 请求已由用户终止。", null, null);
            }
            String error = LlmErrorMessageUtils.describe(e);
            message.setStatus(MessageStatus.FAILED);
            message.setContent("AI 重新回应失败。请稍后重试。");
            message.setErrorMessage(error);
            messageRepository.save(message);
            throw new RuntimeException(error, e);
        }
    }

    private GenerationResult resendUserMessage(Session session, Message userMessage,
                                               Long chatProviderId,
                                               Long imageProviderId) {
        if (userMessage.getMessageType() == MessageType.DRAW_REQUEST) {
            return resendDrawRequest(session, userMessage, imageProviderId);
        }

        ModelProvider chatProvider = resolveChatProvider(
                chatProviderId != null ? chatProviderId : session.getChatProviderId());
        List<Map<String, Object>> chatMessages = chatService.buildMessagesThroughUserMessage(
                session.getId(), userMessage, chatProvider, referencesFromMessage(userMessage));

        Message assistantMessage = new Message();
        assistantMessage.setSession(session);
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setMessageType(MessageType.CHAT);
        assistantMessage.setStatus(MessageStatus.PENDING);
        assistantMessage.setContent("等待回应...");
        assistantMessage.setParentMessageId(userMessage.getId());
        assistantMessage = messageRepository.save(assistantMessage);

        try {
            LlmClient.ChatResult chatResult = llmClient.chat(chatMessages, chatProvider);
            if (wasCancelled(assistantMessage.getId())) {
                return new GenerationResult(
                        assistantMessage.getId(), "AI 请求已由用户终止。", null, null);
            }
            assistantMessage.setContent(chatResult.content());
            assistantMessage.setStatus(MessageStatus.SUCCESS);
            assistantMessage.setErrorMessage(null);
            assistantMessage.setPromptTokens(chatResult.promptTokens());
            assistantMessage.setCompletionTokens(chatResult.completionTokens());
            assistantMessage.setTotalTokens(chatResult.totalTokens());
            messageRepository.save(assistantMessage);
            return new GenerationResult(
                    assistantMessage.getId(),
                    chatResult.content(),
                    null,
                    new com.gs.ais.dto.response.TokenUsage(
                            chatResult.promptTokens(), chatResult.completionTokens(), chatResult.totalTokens()));
        } catch (Exception e) {
            if (wasCancelled(assistantMessage.getId())) {
                return new GenerationResult(
                        assistantMessage.getId(), "AI 请求已由用户终止。", null, null);
            }
            String error = LlmErrorMessageUtils.describe(e);
            assistantMessage.setStatus(MessageStatus.FAILED);
            assistantMessage.setContent("AI 再次回应失败。请稍后重试。");
            assistantMessage.setErrorMessage(error);
            messageRepository.save(assistantMessage);
            throw new RuntimeException(error, e);
        }
    }

    private GenerationResult resendDrawRequest(Session session, Message userMessage,
                                               Long requestedImageProviderId) {
        String prompt = userMessage.getDrawPrompt();
        if (prompt == null || prompt.isBlank()) prompt = userMessage.getContent();
        Long imageProviderId = requestedImageProviderId != null
                ? requestedImageProviderId
                : userMessage.getDrawProviderId() != null
                    ? userMessage.getDrawProviderId()
                    : session.getImageProviderId();
        ModelProvider imageProvider = resolveImageProvider(imageProviderId);
        LlmClient.ImageGenerationOptions options = new LlmClient.ImageGenerationOptions(
                userMessage.getDrawSize(), userMessage.getDrawQuality(), userMessage.getDrawFormat());

        Message assistantMessage = new Message();
        assistantMessage.setSession(session);
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setMessageType(MessageType.DRAW_RESPONSE);
        assistantMessage.setStatus(MessageStatus.PENDING);
        assistantMessage.setContent("图片生成中...");
        assistantMessage.setParentMessageId(userMessage.getId());
        assistantMessage.setDrawPrompt(prompt);
        assistantMessage.setDrawSize(userMessage.getDrawSize());
        assistantMessage.setDrawQuality(userMessage.getDrawQuality());
        assistantMessage.setDrawFormat(userMessage.getDrawFormat());
        assistantMessage.setDrawProviderId(imageProviderId);
        assistantMessage = messageRepository.save(assistantMessage);

        try {
            byte[] imageData = generateImageWithRetry(
                    prompt, imageProvider, options, referencesFromMessage(userMessage));
            if (wasCancelled(assistantMessage.getId())) {
                return new GenerationResult(assistantMessage.getId(), prompt, null, null);
            }
            String imageUrl = saveGeneratedImage(session.getId(), imageData);
            if (wasCancelled(assistantMessage.getId())) {
                deleteGeneratedImageUrl(imageUrl);
                return new GenerationResult(assistantMessage.getId(), prompt, null, null);
            }
            assistantMessage.setStatus(MessageStatus.SUCCESS);
            assistantMessage.setContent("已根据提示词生成图片。");
            assistantMessage.setImageUrl(imageUrl);
            assistantMessage.setErrorMessage(null);
            messageRepository.save(assistantMessage);
            return new GenerationResult(assistantMessage.getId(), prompt, imageUrl, null);
        } catch (Exception e) {
            if (wasCancelled(assistantMessage.getId())) {
                return new GenerationResult(assistantMessage.getId(), prompt, null, null);
            }
            String error = LlmErrorMessageUtils.describe(e);
            assistantMessage.setStatus(MessageStatus.FAILED);
            assistantMessage.setContent("图片再次生成失败。请稍后重试。");
            assistantMessage.setErrorMessage(error);
            messageRepository.save(assistantMessage);
            throw new RuntimeException(error, e);
        }
    }

    private Message findLatestUserMessage(Long sessionId) {
        List<Message> all = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).getRole() == MessageRole.USER) return all.get(i);
        }
        throw new RuntimeException("No user message found to regenerate from");
    }

    private GenerationResult regenerateDrawOnly(Session session, Message message, Message userMessage,
                                                Long requestedImageProviderId) {
        String prompt = message.getDrawPrompt();
        if (prompt == null || prompt.isBlank()) prompt = userMessage.getDrawPrompt();
        if (prompt == null || prompt.isBlank()) prompt = userMessage.getContent();
        Long imageProviderId = requestedImageProviderId != null
                ? requestedImageProviderId
                : message.getDrawProviderId() != null
                    ? message.getDrawProviderId()
                    : session.getImageProviderId();
        ModelProvider imageProvider = resolveImageProvider(imageProviderId);
        LlmClient.ImageGenerationOptions options = new LlmClient.ImageGenerationOptions(
                message.getDrawSize() != null ? message.getDrawSize() : userMessage.getDrawSize(),
                message.getDrawQuality() != null ? message.getDrawQuality() : userMessage.getDrawQuality(),
                message.getDrawFormat() != null ? message.getDrawFormat() : userMessage.getDrawFormat());
        message.setStatus(MessageStatus.PENDING);
        message.setContent("图片生成中...");
        message.setErrorMessage(null);
        message.setDrawProviderId(imageProviderId);
        messageRepository.save(message);
        try {
            byte[] imageData = generateImageWithRetry(prompt, imageProvider, options, referencesFromMessage(userMessage));
            if (wasCancelled(message.getId())) {
                return new GenerationResult(message.getId(), prompt, null, null);
            }
            String imageUrl = saveGeneratedImage(session.getId(), imageData);
            if (wasCancelled(message.getId())) {
                deleteGeneratedImageUrl(imageUrl);
                return new GenerationResult(message.getId(), prompt, null, null);
            }
            deleteOldImage(message);
            message.setStatus(MessageStatus.SUCCESS);
            message.setContent("已根据提示词生成图片。");
            message.setImageUrl(imageUrl);
            message.setErrorMessage(null);
            messageRepository.save(message);
            return new GenerationResult(message.getId(), prompt, imageUrl, null);
        } catch (Exception e) {
            if (wasCancelled(message.getId())) {
                return new GenerationResult(message.getId(), prompt, null, null);
            }
            String error = LlmErrorMessageUtils.describe(e);
            message.setStatus(MessageStatus.FAILED);
            message.setContent("图片重新生成失败。请稍后重试。");
            message.setErrorMessage(error);
            messageRepository.save(message);
            throw new RuntimeException(error, e);
        }
    }

    private List<LlmClient.ReferenceImage> referencesFromMessage(Message message) {
        List<LlmClient.ReferenceImage> references = new ArrayList<>();
        for (Attachment att : attachmentRepository.findByMessageId(message.getId())) {
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

    private void deleteOldImage(Message message) {
        if (message.getImageUrl() == null) return;
        try {
            Files.deleteIfExists(uploadDir.resolve(message.getImageUrl().replace("/api/images/", "")));
        } catch (IOException e) {
            log.warn("Failed to delete old image: {}", e.getMessage());
        }
    }

    private byte[] generateImageWithRetry(String prompt, ModelProvider provider,
                                           LlmClient.ImageGenerationOptions options,
                                           List<LlmClient.ReferenceImage> references) {
        int retries = ModelProviderDefaults.maxRetries(provider);
        int backoff = ModelProviderDefaults.retryBackoffSeconds(provider);
        for (int attempt = 0; ; attempt++) {
            try {
                return llmClient.generateImage(prompt, provider, options, references);
            } catch (Exception e) {
                if (attempt >= retries || !isRetryableImageFailure(e)) {
                    if (e instanceof RuntimeException runtime) throw runtime;
                    throw new RuntimeException(e);
                }
                long waitSeconds = (long) backoff * (1L << Math.min(attempt, 5));
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

    // ===================== Edit & Delete =====================

    public Message editMessage(Long sessionId, Long messageId, String newContent) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found: " + messageId));

        if (message.getRole() != MessageRole.USER) {
            throw new RuntimeException("Can only edit user messages");
        }

        message.setContent(newContent);
        message.setEdited(true);
        messageRepository.save(message);

        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        for (Message m : messages) {
            if (m.getCreatedAt().isAfter(message.getCreatedAt())) {
                deleteGeneratedImageIfPresent(m);
                messageRepository.delete(m);
            }
        }

        return message;
    }

    public void deleteMessage(Long sessionId, Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found: " + messageId));

        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        for (Message m : messages) {
            if (!m.getCreatedAt().isBefore(message.getCreatedAt())) {
                deleteGeneratedImageIfPresent(m);
                messageRepository.delete(m);
            }
        }
    }

    private void deleteGeneratedImageIfPresent(Message message) {
        String imageUrl = message.getImageUrl();
        if (imageUrl == null || !imageUrl.startsWith("/api/images/")) {
            return;
        }
        deleteGeneratedImageUrl(imageUrl);
    }

    private void deleteGeneratedImageUrl(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("/api/images/")) {
            return;
        }
        try {
            String filename = imageUrl.replace("/api/images/", "");
            Files.deleteIfExists(uploadDir.resolve(filename));
        } catch (IOException e) {
            log.warn("Failed to delete generated image {}: {}", imageUrl, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<Message> getMessages(Long sessionId) {
        List<Message> chronological = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        Set<Long> userMessageIds = new HashSet<>();
        for (Message message : chronological) {
            if (message.getRole() == MessageRole.USER) userMessageIds.add(message.getId());
        }

        Map<Long, List<Message>> repliesByUserMessage = new LinkedHashMap<>();
        Set<Long> groupedReplyIds = new HashSet<>();
        for (Message message : chronological) {
            Long parentId = message.getParentMessageId();
            if (message.getRole() == MessageRole.ASSISTANT
                    && parentId != null
                    && userMessageIds.contains(parentId)) {
                repliesByUserMessage.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(message);
                groupedReplyIds.add(message.getId());
            }
        }

        List<Message> ordered = new ArrayList<>(chronological.size());
        for (Message message : chronological) {
            if (groupedReplyIds.contains(message.getId())) continue;
            ordered.add(message);
            if (message.getRole() == MessageRole.USER) {
                ordered.addAll(repliesByUserMessage.getOrDefault(message.getId(), List.of()));
            }
        }
        return ordered;
    }

    public void deleteMessagesBySession(Long sessionId) {
        messageRepository.deleteBySessionId(sessionId);
    }

    // ===================== Result records =====================

    public record ChatMessageResult(String content, Long assistantMessageId,
                                     com.gs.ais.dto.response.TokenUsage tokenUsage,
                                     MessageStatus status,
                                     String errorMessage) {}

    public record DrawResult(Long assistantMessageId, String imageUrl, String prompt,
                             MessageStatus status, String errorMessage) {}

    public record GenerationResult(Long messageId, String optimizedPrompt, String imageUrl,
                                    com.gs.ais.dto.response.TokenUsage tokenUsage) {}
}
