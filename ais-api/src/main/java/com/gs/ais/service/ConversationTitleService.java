package com.gs.ais.service;

import com.gs.ais.client.LlmClient;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Produces a concise title once a newly created conversation has completed its
 * first three chat turns.  A title-generation failure must never affect the
 * user's normal conversation response, so callers can safely invoke it after
 * saving an assistant reply.
 */
@Service
public class ConversationTitleService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTitleService.class);
    private static final int REQUIRED_USER_TURNS = 3;
    private static final int MAX_TITLE_LENGTH = 30;

    private static final String TITLE_SYSTEM_PROMPT = """
            你是会话标题生成器。请根据给出的对话内容生成一个准确、简短的中文标题。
            要求：
            - 概括对话的核心主题；
            - 最好为 8 到 20 个汉字，最长不超过 30 个字符；
            - 只输出标题文本，不要加引号、前缀、Markdown 或解释。
            """;

    private final LlmClient llmClient;
    private final MessageRepository messageRepository;
    private final SessionService sessionService;

    public ConversationTitleService(LlmClient llmClient,
                                    MessageRepository messageRepository,
                                    SessionService sessionService) {
        this.llmClient = llmClient;
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
    }

    /**
     * Attempts title generation only when automatic titles are enabled for the
     * session and at least three successful user chat messages are present.
     */
    public void generateTitleWhenReady(Long sessionId, ModelProvider chatProvider) {
        if (sessionId == null || chatProvider == null) {
            return;
        }

        Session session = sessionService.getSession(sessionId);
        if (!session.isAutoTitleEnabled()) {
            return;
        }

        List<Message> chatHistory = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .filter(this::isSuccessfulChatMessage)
                .toList();
        long userTurnCount = chatHistory.stream()
                .filter(message -> message.getRole() == MessageRole.USER)
                .count();
        if (userTurnCount < REQUIRED_USER_TURNS) {
            return;
        }

        List<Map<String, Object>> titleMessages = buildTitleMessages(chatHistory);
        if (titleMessages.size() < 2) {
            return;
        }

        try {
            LlmClient.ChatResult result = llmClient.chat(titleMessages, chatProvider);
            String title = sanitizeTitle(result.content());
            if (title.isBlank()) {
                log.warn("Conversation title model returned empty content for session {}", sessionId);
                return;
            }
            sessionService.updateGeneratedSessionTitle(sessionId, title);
            log.info("Generated automatic conversation title for session {}", sessionId);
        } catch (Exception e) {
            // Do not turn an already successful third chat response into a failure.
            log.warn("Failed to generate automatic title for session {}: {}", sessionId, e.getMessage());
        }
    }

    private boolean isSuccessfulChatMessage(Message message) {
        return message.getMessageType() == MessageType.CHAT
                && message.getStatus() == MessageStatus.SUCCESS
                && (message.getRole() == MessageRole.USER || message.getRole() == MessageRole.ASSISTANT);
    }

    private List<Map<String, Object>> buildTitleMessages(List<Message> chatHistory) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(Map.of("role", "system", "content", TITLE_SYSTEM_PROMPT));

        StringBuilder conversation = new StringBuilder();
        int userTurns = 0;
        for (Message message : chatHistory) {
            if (message.getRole() == MessageRole.USER) {
                if (userTurns >= REQUIRED_USER_TURNS) {
                    break;
                }
                userTurns++;
            }
            String role = message.getRole() == MessageRole.USER ? "用户" : "助手";
            String content = message.getContent() == null ? "" : message.getContent().trim();
            if (!content.isBlank()) {
                conversation.append(role).append("：").append(content).append('\n');
            }
        }

        if (!conversation.isEmpty()) {
            result.add(Map.of("role", "user", "content", "对话内容：\n" + conversation));
        }
        return result;
    }

    private String sanitizeTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        String title = rawTitle.trim()
                .replaceAll("^[\\\"'“”‘’]+|[\\\"'“”‘’]+$", "")
                .replaceFirst("^(?i:标题)\\s*[:：]\\s*", "");
        int lineBreak = title.indexOf('\n');
        if (lineBreak >= 0) {
            title = title.substring(0, lineBreak);
        }
        title = title.replaceAll("\\s+", " ").trim();
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }
}
