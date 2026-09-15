package com.gs.ais.service;

import com.gs.ais.model.entity.AppUser;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.security.AuthContext;
import com.gs.ais.security.AuthPrincipal;
import com.gs.ais.settings.SessionSettingsRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class SessionService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AppUserRepository appUserRepository;
    private final GeneratedImageFileService generatedImageFileService;

    public SessionService(SessionRepository sessionRepository,
                          MessageRepository messageRepository,
                          AppUserRepository appUserRepository,
                          GeneratedImageFileService generatedImageFileService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.appUserRepository = appUserRepository;
        this.generatedImageFileService = generatedImageFileService;
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
        Long defaultChatProviderId = resolveCurrentUserDefaultChatProviderId();
        if (defaultChatProviderId != null) {
            session.setChatProviderId(defaultChatProviderId);
        }
        return sessionRepository.save(session);
    }

    /**
     * Reads the authenticated user's default chat model so newly created sessions
     * inherit Profile defaults instead of leaving chatProviderId unset.
     */
    private Long resolveCurrentUserDefaultChatProviderId() {
        AuthPrincipal principal = AuthContext.get();
        if (principal == null || principal.subject() == null || principal.subject().isBlank()) {
            return null;
        }
        return appUserRepository.findByUsernameIgnoreCase(principal.subject())
                .map(AppUser::getDefaultChatProviderId)
                .orElse(null);
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

        // Delete associated generated image files only when no other message or
        // attachment record still references them (de-duplication/materialization
        // means a physical file may be shared). The database relationships are
        // dropped below regardless; the physical bytes are left for the remaining
        // references.
        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(id);
        Set<Long> deletedMessageIds = messages.stream()
                .map(Message::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        for (Message msg : messages) {
            if (msg.getImageUrl() != null) {
                generatedImageFileService.deleteIfUnreferenced(msg.getImageUrl(), deletedMessageIds);
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

    /** Backwards-compatible variant: only non-null values are applied. */
    public Session updateProviders(Long id, Long chatProviderId, Long imageProviderId) {
        return updateProviders(id, chatProviderId != null, chatProviderId,
                imageProviderId != null, imageProviderId);
    }

    /**
     * Updates the session default models. {@code chatProvided} / {@code imageProvided}
     * distinguish "clear back to the user/system default" (provided + null) from
     * "leave unchanged" (not provided), which the 会话配置 UI needs.
     */
    public Session updateProviders(Long id,
                                   boolean chatProvided, Long chatProviderId,
                                   boolean imageProvided, Long imageProviderId) {
        java.util.Map<String, Object> body = new HashMap<>();
        if (chatProvided) body.put("chatProviderId", chatProviderId);
        if (imageProvided) body.put("imageProviderId", imageProviderId);
        return updateSettings(id, body, chatProviderId, imageProviderId);
    }

    /**
     * 统一的会话设置入口（{@code PATCH /api/sessions/{id}/settings}）。
     *
     * <p>两类内容共用一次保存：
     * <ul>
     *   <li>会话默认**模型 id**：{@code chatProviderId}/{@code imageProviderId}
     *       仍是独立列，不在 JSON 里。{@code body.containsKey} 决定是否写入，
     *       因此“未出现 = 不动、出现且为 null = 清空回用户/系统默认”。</li>
     *   <li>其余键交给 {@link com.gs.ais.settings.SessionSettingsRegistry} 做
     *       分组稀疏合并：分组内只覆盖传入的 key，显式 null 清空该组/该键回
     *       注册表默认值，未识别的键忽略。新增参数/分组不需要改这里。</li>
     * </ul>
     */
    public Session updateSettings(Long id, Map<String, Object> body,
                                  Long chatProviderId, Long imageProviderId) {
        Session session = getSession(id);
        Map<String, Object> patch = body != null ? body : Map.of();
        if (patch.containsKey("chatProviderId")) {
            session.setChatProviderId(chatProviderId);
        }
        if (patch.containsKey("imageProviderId")) {
            session.setImageProviderId(imageProviderId);
        }
        if (SessionSettingsRegistry.containsGroupPatch(patch)) {
            session.setRawSettings(SessionSettingsRegistry.merge(session.rawSettings(), patch));
        }
        return sessionRepository.save(session);
    }
}