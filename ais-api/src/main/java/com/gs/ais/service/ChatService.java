package com.gs.ais.service;

import com.gs.ais.client.LlmClient;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant specialized in creative image generation. \
            Engage in conversation with the user to understand their vision. \
            When the user is ready to generate an image, provide a detailed, \
            high-quality English prompt that describes exactly what should be drawn. \
            Include details about style, lighting, composition, mood, colors, and perspective. \
            The prompt should be self-contained and ready to pass to an image generation model.
            """;

    private static final int MAX_CONTEXT_CHARS = 100_000; // ~128k tokens at ~1.3 chars/token for non-English
    private static final int KEEP_RECENT = 6; // Keep last 3 user+assistant pairs uncompressed

    private final MessageRepository messageRepository;

    public ChatService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Build the messages array for a chat API call with full conversation context.
     */
    public List<Map<String, Object>> buildMessages(Long sessionId, String currentInput) {
        return buildMessages(sessionId, currentInput, null);
    }

    public List<Map<String, Object>> buildMessages(Long sessionId, String currentInput, ModelProvider provider) {
        return buildMessages(sessionId, currentInput, provider, List.of());
    }

    public List<Map<String, Object>> buildMessages(Long sessionId, String currentInput,
                                                   ModelProvider provider,
                                                   List<LlmClient.ReferenceImage> currentImages) {
        List<Message> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return buildMessagesFromHistory(history, currentInput, provider, currentImages);
    }

    /**
     * Re-send a historical user message using only the conversation that existed
     * before that message. Later turns and previous replies to the same message
     * are deliberately excluded from the model context.
     */
    public List<Map<String, Object>> buildMessagesThroughUserMessage(
            Long sessionId,
            Message currentUserMessage,
            ModelProvider provider,
            List<LlmClient.ReferenceImage> currentImages) {
        List<Message> allMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int currentIndex = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i).getId().equals(currentUserMessage.getId())) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            throw new RuntimeException("User message does not belong to session: " + sessionId);
        }
        return buildMessagesFromHistory(
                allMessages.subList(0, currentIndex),
                currentUserMessage.getContent(),
                provider,
                currentImages);
    }

    private List<Map<String, Object>> buildMessagesFromHistory(
            List<Message> history,
            String currentInput,
            ModelProvider provider,
            List<LlmClient.ReferenceImage> currentImages) {
        String safeCurrentInput = currentInput != null ? currentInput : "";
        List<Map<String, Object>> messages = new ArrayList<>();

        // 1. System prompt. Each configured chat provider may override the default.
        String systemPrompt = provider != null && provider.getSystemPrompt() != null
                && !provider.getSystemPrompt().isBlank()
                ? provider.getSystemPrompt().trim() : SYSTEM_PROMPT;
        messages.add(Map.of("role", "system", "content", (Object) systemPrompt));

        // 2. Add conversation context
        if (!history.isEmpty()) {
            // Estimate total context size
            int totalChars = safeCurrentInput.length();
            for (Message m : history) {
                if (shouldIncludeInContext(m) && m.getContent() != null) totalChars += m.getContent().length();
            }

            if (totalChars > MAX_CONTEXT_CHARS && history.size() > KEEP_RECENT) {
                // Compress: summarize old messages, keep recent ones
                int splitIdx = Math.max(0, history.size() - KEEP_RECENT);
                List<Message> oldMessages = history.subList(0, splitIdx);
                String summary = buildSummary(oldMessages);
                messages.add(Map.of("role", "system",
                        "content", (Object) ("[Previous conversation summary: " + summary + "]")));

                log.info("Compressed {} old messages into summary ({} chars)", oldMessages.size(), summary.length());

                // Add recent messages
                for (int i = splitIdx; i < history.size(); i++) {
                    Message message = history.get(i);
                    if (shouldIncludeInContext(message)) {
                        messages.add(messageToMap(message));
                    }
                }
            } else {
                // No compression needed, include all successful history
                for (Message m : history) {
                    if (shouldIncludeInContext(m)) {
                        messages.add(messageToMap(m));
                    }
                }
            }
        }

        // 3. Current user input
        messages.add(currentUserMessage(safeCurrentInput, currentImages));

        return messages;
    }

    /**
     * Build a simple text summary of older messages without calling the model.
     * For a more advanced compression, this could call the chat API,
     * but a simple concatenation is sufficient and avoids extra API costs.
     */
    private String buildSummary(List<Message> oldMessages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : oldMessages) {
            if (shouldIncludeInContext(m) && m.getContent() != null && !m.getContent().isBlank()) {
                String roleLabel = m.getRole() == MessageRole.USER ? "User" : "Assistant";
                String content = m.getContent();
                if (content.length() > 200) {
                    content = content.substring(0, 200) + "...";
                }
                sb.append(roleLabel).append(": ").append(content).append(" | ");
            }
        }
        String summary = sb.toString();
        if (summary.length() > 3000) {
            summary = summary.substring(0, 3000) + "...";
        }
        return summary;
    }

    /**
     * Compress conversation history by calling the chat model to produce a summary.
     * Used when the simple concatenated summary is too large.
     */
    public String compressHistory(List<Message> history, ModelProvider provider, LlmClient llmClient) {
        StringBuilder sb = new StringBuilder();
        for (Message m : history) {
            if (m.getContent() != null && !m.getContent().isBlank()) {
                String roleLabel = m.getRole() == MessageRole.USER ? "用户" : "助手";
                sb.append(roleLabel).append(": ").append(m.getContent()).append('\n');
            }
        }

        if (sb.isEmpty()) return "";

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", (Object) "请用中文将以下对话历史压缩为一到两段的简短摘要，保留关键信息:"),
                Map.of("role", "user", "content", (Object) sb.toString())
        );

        try {
            LlmClient.ChatResult result = llmClient.chat(messages, provider);
            return result.content();
        } catch (Exception e) {
            log.warn("History compression failed: {}", e.getMessage());
            return buildSummary(history);
        }
    }

    private Map<String, Object> currentUserMessage(String text, List<LlmClient.ReferenceImage> images) {
        List<LlmClient.ReferenceImage> safeImages = images == null ? List.of() : images.stream()
                .filter(image -> image != null && image.data() != null && image.data().length > 0)
                .toList();
        if (safeImages.isEmpty()) {
            return Map.of("role", "user", "content", (Object) text);
        }

        List<Map<String, Object>> content = new ArrayList<>();
        if (!text.isBlank()) {
            content.add(Map.of("type", "text", "text", text));
        }
        for (LlmClient.ReferenceImage image : safeImages) {
            String contentType = image.contentType() != null && image.contentType().startsWith("image/")
                    ? image.contentType() : "image/png";
            String dataUrl = "data:" + contentType + ";base64,"
                    + Base64.getEncoder().encodeToString(image.data());
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", dataUrl)));
        }
        return Map.of("role", "user", "content", (Object) content);
    }

    private boolean shouldIncludeInContext(Message message) {
        return message.getStatus() == null || message.getStatus() == MessageStatus.SUCCESS;
    }

    private Map<String, Object> messageToMap(Message m) {
        String role = m.getRole() == MessageRole.USER ? "user" : "assistant";
        return Map.of("role", role, "content", (Object) (m.getContent() != null ? m.getContent() : ""));
    }
}
