package com.gs.ais.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.config.FeishuProperties;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.dto.request.DrawRequest;
import com.gs.ais.dto.response.UploadResponse;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.service.AttachmentService;
import com.gs.ais.service.BillingService;
import com.gs.ais.service.ImageGenerationService;
import com.gs.ais.service.ModelProviderService;
import com.gs.ais.service.SessionService;
import com.gs.ais.util.LlmErrorMessageUtils;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives Feishu event callbacks and delegates model work to a background
 * executor. The callback thread always returns promptly so Feishu does not
 * retry a request merely because a model takes time to respond.
 */
@Service
public class FeishuEventService {

    private static final Logger log = LoggerFactory.getLogger(FeishuEventService.class);
    private static final String FEISHU_CHANNEL = "FEISHU";
    private static final Duration EVENT_DEDUPLICATION_WINDOW = Duration.ofMinutes(10);
    private static final String HELP_TEXT = """
            使用说明：
            • 直接发送文字：与 AI 对话
            • 发送“绘图：一只橘猫……”或“/draw 一只橘猫……”：生成图片
            • 发送图片：AI 会结合图片进行对话；图片也可以与绘图指令一起作为参考图
            • 发送“帮助”或“/help”：查看本说明
            """;

    private final FeishuProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor feishuTaskExecutor;
    private final FeishuApiClient feishuApiClient;
    private final SessionService sessionService;
    private final AttachmentService attachmentService;
    private final ImageGenerationService imageGenerationService;
    private final BillingService billingService;
    private final ModelProviderService modelProviderService;
    private final StoragePaths storagePaths;
    private final ConcurrentHashMap<String, Instant> receivedEventIds = new ConcurrentHashMap<>();

    public FeishuEventService(FeishuProperties properties,
                              @Qualifier("feishuObjectMapper") ObjectMapper objectMapper,
                              @Qualifier("feishuTaskExecutor") TaskExecutor feishuTaskExecutor,
                              FeishuApiClient feishuApiClient,
                              SessionService sessionService,
                              AttachmentService attachmentService,
                              ImageGenerationService imageGenerationService,
                              BillingService billingService,
                              ModelProviderService modelProviderService) {
        this(properties, objectMapper, feishuTaskExecutor, feishuApiClient, sessionService,
                attachmentService, imageGenerationService, billingService, modelProviderService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FeishuEventService(FeishuProperties properties,
                              @Qualifier("feishuObjectMapper") ObjectMapper objectMapper,
                              @Qualifier("feishuTaskExecutor") TaskExecutor feishuTaskExecutor,
                              FeishuApiClient feishuApiClient,
                              SessionService sessionService,
                              AttachmentService attachmentService,
                              ImageGenerationService imageGenerationService,
                              BillingService billingService,
                              ModelProviderService modelProviderService,
                              StoragePaths storagePaths) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.feishuTaskExecutor = feishuTaskExecutor;
        this.feishuApiClient = feishuApiClient;
        this.sessionService = sessionService;
        this.attachmentService = attachmentService;
        this.imageGenerationService = imageGenerationService;
        this.billingService = billingService;
        this.modelProviderService = modelProviderService;
        this.storagePaths = storagePaths;
    }

    /** Parses, validates, and schedules one callback without waiting for model output. */
    public CallbackResponse acceptWebhook(String rawBody, HttpHeaders headers) {
        JsonNode payload = readPayload(rawBody, headers);
        validateVerificationToken(payload);

        if ("url_verification".equals(payload.path("type").asText())) {
            String challenge = payload.path("challenge").asText();
            if (challenge.isBlank()) {
                throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书 URL 验证请求缺少 challenge");
            }
            return new CallbackResponse(HttpStatus.OK, Map.of("challenge", challenge));
        }

        String eventType = payload.path("header").path("event_type").asText();
        if (!"im.message.receive_v1".equals(eventType)) {
            return CallbackResponse.accepted();
        }

        JsonNode event = payload.path("event");
        if (event.isMissingNode() || event.isNull()) {
            throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书消息事件缺少 event 内容");
        }
        scheduleMessage(IncomingFeishuEvent.fromHttp(
                payload.path("header").path("event_id").asText(), event));
        return CallbackResponse.accepted();
    }

    /**
     * Accepts a message event received by Feishu's WebSocket SDK. The SDK has
     * already handled the long-connection protocol and event decryption; this
     * method only normalizes the event and queues the existing bot workflow.
     */
    public void acceptWebSocketMessage(P2MessageReceiveV1 event) {
        if (event == null) {
            log.warn("Ignoring empty Feishu WebSocket message event");
            return;
        }
        scheduleMessage(IncomingFeishuEvent.fromWebSocket(event));
    }

    private void scheduleMessage(IncomingFeishuEvent event) {
        // Feishu may redeliver a message event. message_id is the stable idempotency
        // key; event_id is retained only as a fallback for malformed event payloads.
        String deduplicationId = !isBlank(event.messageId()) ? event.messageId() : event.eventId();
        if (!isBlank(deduplicationId) && isDuplicate(deduplicationId)) {
            log.info("Ignored duplicate Feishu message event {}", deduplicationId);
            return;
        }
        try {
            feishuTaskExecutor.execute(() -> processMessageEvent(event));
        } catch (RuntimeException e) {
            if (!isBlank(deduplicationId)) receivedEventIds.remove(deduplicationId);
            throw new FeishuWebhookException(HttpStatus.SERVICE_UNAVAILABLE, "飞书消息处理队列暂时不可用", e);
        }
    }

    private JsonNode readPayload(String rawBody, HttpHeaders headers) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书回调内容为空");
        }
        try {
            JsonNode outer = objectMapper.readTree(rawBody);
            if (outer == null || !outer.isObject()) {
                throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书回调格式不正确");
            }
            if (outer.hasNonNull("encrypt")) {
                verifySignature(rawBody, headers);
                String decrypted = decrypt(outer.path("encrypt").asText());
                JsonNode inner = objectMapper.readTree(decrypted);
                if (inner == null || !inner.isObject()) {
                    throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书加密回调解密后的格式不正确");
                }
                return inner;
            }
            return outer;
        } catch (FeishuWebhookException e) {
            throw e;
        } catch (IOException e) {
            throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书回调不是有效 JSON", e);
        }
    }

    private void validateVerificationToken(JsonNode payload) {
        String verificationToken = properties.getVerificationToken();
        if (verificationToken == null || verificationToken.isBlank()) return;
        String token = payload.path("token").asText();
        if (token.isBlank() || !MessageDigest.isEqual(
                verificationToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new FeishuWebhookException(HttpStatus.FORBIDDEN, "飞书 verification token 校验失败");
        }
    }

    /** Validates Feishu's SHA-256 callback signature for encrypted callbacks. */
    private void verifySignature(String rawBody, HttpHeaders headers) {
        String encryptKey = properties.getEncryptKey();
        if (encryptKey == null || encryptKey.isBlank()) {
            throw new FeishuWebhookException(HttpStatus.FORBIDDEN, "收到加密飞书回调，但未配置 FEISHU_ENCRYPT_KEY");
        }
        String timestamp = headers.getFirst("X-Lark-Request-Timestamp");
        String nonce = headers.getFirst("X-Lark-Request-Nonce");
        String signature = headers.getFirst("X-Lark-Signature");
        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            throw new FeishuWebhookException(HttpStatus.FORBIDDEN, "飞书加密回调缺少签名请求头");
        }
        try {
            String source = timestamp + nonce + encryptKey + rawBody;
            String expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
                throw new FeishuWebhookException(HttpStatus.FORBIDDEN, "飞书回调签名校验失败");
            }
        } catch (FeishuWebhookException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuWebhookException(HttpStatus.INTERNAL_SERVER_ERROR, "飞书回调签名校验异常", e);
        }
    }

    /** Feishu uses SHA-256(encryptKey) as AES-256 key and its first 16 bytes as IV. */
    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书加密回调内容为空");
        }
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(properties.getEncryptKey().getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            byte[] padded = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            int padding = padded[padded.length - 1] & 0xff;
            if (padding < 1 || padding > 32 || padding > padded.length) {
                throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书加密回调填充不正确");
            }
            for (int i = padded.length - padding; i < padded.length; i++) {
                if ((padded[i] & 0xff) != padding) {
                    throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书加密回调填充不正确");
                }
            }
            return new String(padded, 0, padded.length - padding, StandardCharsets.UTF_8);
        } catch (FeishuWebhookException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuWebhookException(HttpStatus.BAD_REQUEST, "飞书加密回调解密失败", e);
        }
    }

    private boolean isDuplicate(String eventId) {
        Instant now = Instant.now();
        receivedEventIds.entrySet().removeIf(entry -> entry.getValue().isBefore(now.minus(EVENT_DEDUPLICATION_WINDOW)));
        return receivedEventIds.putIfAbsent(eventId, now) != null;
    }

    private void processMessageEvent(IncomingFeishuEvent event) {
        String senderType = event.senderType();
        if ("bot".equals(senderType) || "app".equals(senderType)) {
            return;
        }
        String messageId = event.messageId();
        String chatId = event.chatId();
        String messageType = event.messageType();
        if (isBlank(messageId) || isBlank(chatId)) {
            log.warn("Ignoring Feishu message event without message_id or chat_id");
            return;
        }

        try {
            IncomingMessage incoming = parseIncomingMessage(event.content(), messageType);
            if (isHelpCommand(incoming.text())) {
                feishuApiClient.replyText(messageId, HELP_TEXT);
                return;
            }
            if (!"text".equals(messageType) && !"image".equals(messageType) && !"post".equals(messageType)) {
                feishuApiClient.replyText(messageId, "暂不支持该消息类型。请发送文字、图片，或使用“绘图：提示词”。");
                return;
            }

            List<Long> attachmentIds = saveImageAttachments(messageId, incoming.imageKeys());
            Session session = sessionService.getOrCreateExternalSession(FEISHU_CHANNEL, chatId);
            Optional<String> drawPrompt = parseDrawPrompt(incoming.text());
            if (drawPrompt.isPresent()) {
                if (drawPrompt.get().isBlank()) {
                    feishuApiClient.replyText(messageId, "请在绘图指令后输入提示词，例如：绘图：雨夜霓虹城市。");
                    return;
                }
                DrawRequest request = new DrawRequest();
                request.setPrompt(drawPrompt.get());
                request.setAttachmentIds(attachmentIds);
                ImageGenerationService.DrawResult result = imageGenerationService.draw(session.getId(), request);
                replyDrawResult(messageId, result);
                return;
            }

            String prompt = incoming.text();
            if (prompt.isBlank() && !attachmentIds.isEmpty()) {
                prompt = "请分析这张图片，并简要描述其中的内容。";
            }
            if (prompt.isBlank()) {
                feishuApiClient.replyText(messageId, "请发送文字与我对话，或发送“绘图：提示词”生成图片。");
                return;
            }
            long chatStart = System.currentTimeMillis();
            ImageGenerationService.ChatMessageResult result = imageGenerationService.chat(
                    session.getId(), prompt, attachmentIds, null);
            long chatDuration = System.currentTimeMillis() - chatStart;

            if (result.status() == MessageStatus.SUCCESS) {
                try {
                    Session persistedSession = sessionService.getSession(session.getId());
                    Long providerId = persistedSession.getChatProviderId();
                    ModelProvider provider = providerId != null
                            ? modelProviderService.getById(providerId)
                            : null;
                    if (provider == null) {
                        provider = modelProviderService.getActiveProvider(ProviderType.CHAT);
                    }
                    if (provider != null && persistedSession.getUserId() != null) {
                        var usage = result.tokenUsage();
                        billingService.recordChat(provider, persistedSession.getUserId(), persistedSession.getId(),
                                result.assistantMessageId(),
                                usage != null ? usage.getPromptTokens() : null,
                                usage != null ? usage.getCompletionTokens() : null,
                                usage != null ? usage.getTotalTokens() : null,
                                usage != null ? usage.getCacheReadTokens() : null,
                                usage != null ? usage.getCacheWriteTokens() : null,
                                usage != null ? usage.getReasoningTokens() : null,
                                chatDuration);
                    }
                } catch (Exception e) {
                    log.warn("Failed to record billing for Feishu chat", e);
                }
            }

            if (result.status() == MessageStatus.SUCCESS) {
                feishuApiClient.replyText(messageId, result.content());
            } else {
                feishuApiClient.replyText(messageId, failureText(result.errorMessage(), result.content()));
            }
        } catch (Exception e) {
            String error = LlmErrorMessageUtils.describe(e);
            log.error("Failed to process Feishu message {}", messageId, e);
            try {
                feishuApiClient.replyText(messageId, failureText(error, null));
            } catch (Exception replyError) {
                log.error("Failed to send Feishu failure reply for message {}", messageId, replyError);
            }
        }
    }

    private List<Long> saveImageAttachments(String messageId, List<String> imageKeys) {
        List<Long> attachmentIds = new ArrayList<>();
        for (String imageKey : imageKeys) {
            FeishuApiClient.DownloadedFile downloaded = feishuApiClient.downloadMessageImage(messageId, imageKey);
            String contentType = normalizeContentType(downloaded.contentType());
            UploadResponse uploaded = attachmentService.uploadBytes(
                    "feishu-" + imageKey + extensionFor(contentType), contentType, downloaded.content());
            attachmentIds.add(uploaded.getId());
        }
        return attachmentIds;
    }

    private void replyDrawResult(String messageId, ImageGenerationService.DrawResult result) {
        if (result.status() != MessageStatus.SUCCESS || isBlank(result.imageUrl())) {
            feishuApiClient.replyText(messageId, failureText(result.errorMessage(), "图片生成失败，请稍后重试。"));
            return;
        }
        try {
            Path image = localGeneratedImage(result.imageUrl());
            byte[] imageContent = Files.readAllBytes(image);
            String contentType = normalizeContentType(Files.probeContentType(image));
            feishuApiClient.replyImage(messageId, imageContent, image.getFileName().toString(), contentType);
        } catch (Exception e) {
            log.error("Failed to upload generated image to Feishu for message {}", messageId, e);
            feishuApiClient.replyText(messageId, failureText(LlmErrorMessageUtils.describe(e), "图片已生成，但回传飞书失败。"));
        }
    }

    private Path localGeneratedImage(String imageUrl) {
        if (!imageUrl.startsWith("/api/images/")) {
            throw new IllegalArgumentException("生成图片地址不是本地文件");
        }
        String filename = imageUrl.substring("/api/images/".length());
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("生成图片文件名不安全");
        }
        Path root = storagePaths != null ? storagePaths.uploadDir() : Path.of("uploads").toAbsolutePath().normalize();
        Path file = root.resolve(filename).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("找不到生成图片文件");
        }
        return file;
    }

    private IncomingMessage parseIncomingMessage(String content, String messageType) {
        if ("text".equals(messageType)) {
            return new IncomingMessage(readText(content), List.of());
        }
        if ("image".equals(messageType)) {
            JsonNode node = readContentJson(content);
            return new IncomingMessage("", collectImageKeys(node));
        }
        if ("post".equals(messageType)) {
            JsonNode node = readContentJson(content);
            return new IncomingMessage(collectText(node).trim(), collectImageKeys(node));
        }
        return new IncomingMessage("", List.of());
    }

    private String readText(String content) {
        JsonNode node = readContentJson(content);
        return node.path("text").asText("").trim();
    }

    private JsonNode readContentJson(String content) {
        if (content == null || content.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode node = objectMapper.readTree(content);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (IOException e) {
            throw new IllegalArgumentException("飞书消息内容不是有效 JSON", e);
        }
    }

    private List<String> collectImageKeys(JsonNode node) {
        Set<String> imageKeys = new LinkedHashSet<>();
        collectImageKeys(node, imageKeys);
        return List.copyOf(imageKeys);
    }

    private void collectImageKeys(JsonNode node, Set<String> imageKeys) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if ("image_key".equals(field.getKey()) && field.getValue().isTextual() && !field.getValue().asText().isBlank()) {
                    imageKeys.add(field.getValue().asText());
                } else {
                    collectImageKeys(field.getValue(), imageKeys);
                }
            });
        } else if (node.isArray()) {
            node.forEach(item -> collectImageKeys(item, imageKeys));
        }
    }

    private String collectText(JsonNode node) {
        StringBuilder text = new StringBuilder();
        collectText(node, text);
        return text.toString();
    }

    private void collectText(JsonNode node, StringBuilder text) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            if (node.has("text") && node.path("text").isTextual()) appendText(text, node.path("text").asText());
            if (node.has("content") && node.path("content").isTextual()) appendText(text, node.path("content").asText());
            node.fields().forEachRemaining(field -> {
                if (!"text".equals(field.getKey()) && !"content".equals(field.getKey())) {
                    collectText(field.getValue(), text);
                }
            });
        } else if (node.isArray()) {
            node.forEach(item -> collectText(item, text));
        }
    }

    private void appendText(StringBuilder target, String value) {
        if (value == null || value.isBlank()) return;
        if (!target.isEmpty()) target.append(' ');
        target.append(value.trim());
    }

    static Optional<String> parseDrawPrompt(String text) {
        if (text == null) return Optional.empty();
        String value = text.trim();
        String lower = value.toLowerCase();
        if (lower.equals("/draw") || lower.startsWith("/draw ")) return Optional.of(value.substring(5).stripLeading());
        if (lower.equals("/image") || lower.startsWith("/image ")) return Optional.of(value.substring(6).stripLeading());
        for (String prefix : List.of("绘图", "生图", "画图")) {
            if (value.startsWith(prefix)) {
                String prompt = value.substring(prefix.length()).trim();
                if (prompt.startsWith(":" ) || prompt.startsWith("：")) prompt = prompt.substring(1).trim();
                return Optional.of(prompt);
            }
        }
        return Optional.empty();
    }

    static boolean isHelpCommand(String text) {
        if (text == null) return false;
        String command = text.trim().toLowerCase();
        return List.of("帮助", "help", "/help", "使用说明").contains(command);
    }

    private String failureText(String error, String fallback) {
        String details = error == null ? "" : error.trim();
        if (!details.isBlank()) return "处理失败：" + details;
        return fallback == null || fallback.isBlank() ? "处理失败，请稍后重试。" : fallback;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) return "image/png";
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
    }

    private String extensionFor(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record IncomingMessage(String text, List<String> imageKeys) { }

    /** Transport-neutral subset of a Feishu message event. */
    private record IncomingFeishuEvent(String eventId, String senderType, String messageId,
                                       String chatId, String messageType, String content) {
        static IncomingFeishuEvent fromHttp(String eventId, JsonNode event) {
            JsonNode message = event.path("message");
            return new IncomingFeishuEvent(
                    eventId,
                    event.path("sender").path("sender_type").asText(),
                    message.path("message_id").asText(),
                    message.path("chat_id").asText(),
                    message.path("message_type").asText(),
                    message.path("content").asText());
        }

        static IncomingFeishuEvent fromWebSocket(P2MessageReceiveV1 event) {
            P2MessageReceiveV1Data data = event.getEvent();
            EventSender sender = data == null ? null : data.getSender();
            EventMessage message = data == null ? null : data.getMessage();
            return new IncomingFeishuEvent(
                    event.getHeader() == null ? "" : event.getHeader().getEventId(),
                    sender == null ? "" : sender.getSenderType(),
                    message == null ? "" : message.getMessageId(),
                    message == null ? "" : message.getChatId(),
                    message == null ? "" : message.getMessageType(),
                    message == null ? "" : message.getContent());
        }
    }

    public record CallbackResponse(HttpStatus status, Map<String, Object> body) {
        static CallbackResponse accepted() {
            return new CallbackResponse(HttpStatus.OK, Map.of("code", 0));
        }
    }

    public static class FeishuWebhookException extends RuntimeException {
        private final HttpStatus status;

        FeishuWebhookException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        FeishuWebhookException(HttpStatus status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public HttpStatus getStatus() {
            return status;
        }
    }
}
