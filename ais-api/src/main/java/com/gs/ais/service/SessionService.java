package com.gs.ais.service;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final Path uploadDir;

    public SessionService(SessionRepository sessionRepository,
                          MessageRepository messageRepository,
                          StoragePaths storagePaths) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.uploadDir = storagePaths.uploadDir();
    }

    @Transactional(readOnly = true)
    public List<Session> getAllSessions() {
        List<Session> sessions = sessionRepository.findAllByOrderByUpdatedAtDesc();
        attachLastMessageInfo(sessions);
        return sessions;
    }

    @Transactional(readOnly = true)
    public List<Session> getSessionsByUserId(Long userId) {
        List<Session> sessions;
        if (userId == null) {
            sessions = sessionRepository.findAllByOrderByUpdatedAtDesc();
        } else {
            sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        }
        attachLastMessageInfo(sessions);
        return sessions;
    }

    @Transactional(readOnly = true)
    public Session getSession(Long id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Session not found: " + id));
    }

    /**
     * Populate transient lastMessageAt / lastMessagePreview from each session's
     * latest message. The frontend auto-unread red-dot and list previews depend
     * on these fields; they are not persisted on the sessions table.
     *
     * <p>Activity watermark = coalesce(message.updatedAt, message.createdAt).
     * updatedAt advances on PENDING → SUCCESS/FAILED so a reply that finishes
     * after the user left the chat still shows as newer activity. Legacy rows
     * with null updatedAt fall back to createdAt (no migration required).
     */
    private void attachLastMessageInfo(List<Session> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        List<Long> sessionIds = sessions.stream().map(Session::getId).toList();
        List<Message> latestMessages = messageRepository.findLatestBySessionIds(sessionIds);
        Map<Long, Message> bySessionId = new HashMap<>();
        for (Message message : latestMessages) {
            if (message.getSession() == null || message.getSession().getId() == null) {
                continue;
            }
            bySessionId.put(message.getSession().getId(), message);
        }
        for (Session session : sessions) {
            Message latest = bySessionId.get(session.getId());
            if (latest == null) {
                continue;
            }
            session.setLastMessageAt(messageActivityAt(latest));
            session.setLastMessagePreview(buildLastMessagePreview(latest));
        }
    }

    /** Same activity clock the frontend uses for last-viewed / auto-unread. */
    public static LocalDateTime messageActivityAt(Message message) {
        if (message == null) {
            return null;
        }
        return message.getUpdatedAt() != null ? message.getUpdatedAt() : message.getCreatedAt();
    }

    private static String buildLastMessagePreview(Message message) {
        String content = message.getContent() != null ? message.getContent().trim() : "";
        if (!content.isEmpty()) {
            return truncatePreview(content);
        }
        if (message.getImageUrl() != null && !message.getImageUrl().isBlank()) {
            return "[图片]";
        }
        if (message.getMessageType() == MessageType.DRAW_REQUEST) {
            String prompt = message.getDrawPrompt() != null ? message.getDrawPrompt().trim() : "";
            if (!prompt.isEmpty()) {
                return truncatePreview(prompt);
            }
            return "[绘画请求]";
        }
        return "";
    }

    private static String truncatePreview(String text) {
        if (text.length() <= 60) {
            return text;
        }
        return text.substring(0, 60) + "…";
    }

    public Session createSession() {
        return createSession(null, null);
    }

    public Session createSession(String title) {
        return createSession(title, null);
    }

    public Session createSession(String title, Long userId) {
        Session session = new Session();
        boolean hasCustomTitle = title != null && !title.isBlank();
        session.setTitle(normalizeTitle(title));
        session.setAutoTitleEnabled(!hasCustomTitle);
        session.setUserId(userId);
        return sessionRepository.save(session);
    }

    /**
     * Returns the durable conversation used by an external channel.  One Feishu
     * chat maps to one local session, so the normal conversation context and
     * generated image history are preserved across incoming events and restarts.
     */
    public synchronized Session getOrCreateExternalSession(String channel, String externalChatId) {
        if (channel == null || channel.isBlank() || externalChatId == null || externalChatId.isBlank()) {
            throw new IllegalArgumentException("External channel and chat ID are required");
        }
        return sessionRepository.findFirstByExternalChannelAndExternalChatId(channel, externalChatId)
                .orElseGet(() -> {
                    Session session = new Session();
                    session.setTitle("" + channel + " 会话");
                    session.setAutoTitleEnabled(true);
                    session.setExternalChannel(channel);
                    session.setExternalChatId(externalChatId);
                    return sessionRepository.save(session);
                });
    }

    /** Records an explicitly user-managed title and disables automatic replacement. */
    public Session updateSessionTitle(Long id, String title) {
        Session session = getSession(id);
        session.setTitle(normalizeTitle(title));
        session.setAutoTitleEnabled(false);
        return sessionRepository.save(session);
    }

    /** Updates the short provisional title shown after the first user message. */
    public Session updateProvisionalSessionTitle(Long id, String title) {
        Session session = getSession(id);
        if (!session.isAutoTitleEnabled()) {
            return session;
        }
        session.setTitle(normalizeTitle(title));
        return sessionRepository.save(session);
    }

    /** Saves the LLM-generated title and prevents further automatic changes. */
    public Session updateGeneratedSessionTitle(Long id, String title) {
        Session session = getSession(id);
        session.setTitle(normalizeTitle(title));
        session.setAutoTitleEnabled(false);
        return sessionRepository.save(session);
    }

    public void deleteSession(Long id) {
        Session session = getSession(id);

        // Delete associated image files
        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(id);
        for (Message msg : messages) {
            if (msg.getImageUrl() != null) {
                // imageUrl format: /api/images/filename
                String filename = msg.getImageUrl().replace("/api/images/", "");
                Path imagePath = uploadDir.resolve(filename);
                try {
                    Files.deleteIfExists(imagePath);
                    int lastDot = filename.lastIndexOf('.');
                    String thumbFilename = (lastDot >= 0 ? filename.substring(0, lastDot) : filename) + "_thumb.png";
                    Files.deleteIfExists(uploadDir.resolve(thumbFilename));
                } catch (IOException e) {
                    log.warn("Failed to delete image file: {}", imagePath, e);
                }
            }
        }

        // Delete messages then session
        messageRepository.deleteBySessionId(id);
        sessionRepository.delete(session);
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) return "新会话";
        String normalized = title.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("会话标题不能超过 255 个字符");
        }
        return normalized;
    }

    public Session updateProviders(Long id, Long chatProviderId, Long imageProviderId) {
        Session session = getSession(id);
        if (chatProviderId != null) {
            session.setChatProviderId(chatProviderId);
        }
        if (imageProviderId != null) {
            session.setImageProviderId(imageProviderId);
        }
        return sessionRepository.save(session);
    }
}