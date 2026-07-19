package com.gs.ais.service;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.Session;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        return sessionRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Session> getSessionsByUserId(Long userId) {
        if (userId == null) {
            return sessionRepository.findAllByOrderByUpdatedAtDesc();
        }
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Session getSession(Long id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Session not found: " + id));
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