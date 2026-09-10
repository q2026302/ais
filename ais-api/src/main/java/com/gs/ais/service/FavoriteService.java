package com.gs.ais.service;

import com.gs.ais.dto.response.FavoriteResponse;
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Favorite;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.FavoriteRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.security.AuthException;
import com.gs.ais.util.ReferenceFileUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * User-scoped work library (收藏 / 作品库).
 *
 * <p>Adding a favourite stores a snapshot of the generated image, its draw
 * options and the reference images used at generation time. The snapshot never
 * joins the originating message afterwards, so deleting the message or its whole
 * session leaves the saved work complete.
 *
 * <p>Visibility mirrors {@code SessionController}: a regular user only sees and
 * mutates their own favourites; an administrator sees everything. That scope is
 * expressed as a nullable {@code scopeUserId} ({@code null} = all users) for the
 * <em>list</em>. Cancellation is different: it always targets a single favourite
 * record id ({@link #remove}), so an all-users view can never cancel another
 * user's save as a side effect. The per-message {@code favorited} flag is likewise
 * always the current user's own record ({@link #favoriteIdsByMessageId}).
 */
@Service
@Transactional
public class FavoriteService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteService.class);

    private static final String IMAGE_URL_PREFIX = "/api/images/";
    private static final String ATTACHMENT_URL_PREFIX = "/api/attachments/";
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Bounded retries for the "existence check → INSERT" race and for SQLite write-lock
     * contention. A single conflict retry is enough in theory (the loser's next read
     * sees the committed winner); the extra attempts plus the jittered backoff absorb
     * an interleaved delete/insert or a busy write lock without ever looping unbounded.
     */
    private static final int MAX_ADD_ATTEMPTS = 5;

    private final FavoriteRepository favoriteRepository;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final AppUserRepository appUserRepository;
    private final TransactionTemplate transactionTemplate;

    public FavoriteService(FavoriteRepository favoriteRepository,
                           MessageRepository messageRepository,
                           SessionRepository sessionRepository,
                           AppUserRepository appUserRepository,
                           PlatformTransactionManager transactionManager) {
        this.favoriteRepository = favoriteRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.appUserRepository = appUserRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public Page<FavoriteResponse> list(Long scopeUserId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<Favorite> favorites = scopeUserId == null
                ? favoriteRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : favoriteRepository.findByUserIdOrderByCreatedAtDescIdDesc(scopeUserId, pageable);
        Map<Long, String> userNames = resolveUserNames(favorites.getContent());
        return favorites.map(favorite -> toResponse(favorite, userNames.get(favorite.getUserId())));
    }

    @Transactional(readOnly = true)
    public FavoriteResponse get(Long favoriteId) {
        Favorite favorite = favoriteRepository.findById(favoriteId)
                .orElseThrow(() -> new AuthException(404, "收藏不存在"));
        return toResponse(favorite);
    }

    /**
     * Saves the message's generated image as a work owned by {@code userId}.
     *
     * <p>The row is keyed by {@code (userId, messageId)}, so a message can be saved
     * independently by several users.
     *
     * <p>Idempotency does <strong>not</strong> rely on the message row lock: the
     * community {@code SQLiteDialect} returns an empty {@code FOR UPDATE} string, so
     * {@code findByIdForUpdate} emits a plain read on the production database and the
     * lock is a silent no-op there. The real guarantee is the database-level unique
     * index on {@code (user_id, message_id)} (see
     * {@link com.gs.ais.config.FavoriteUniquenessMigrationRunner}). This method
     * cooperates with it: an existence check short-circuits the common case, and if
     * two writers still pass that check at the same instant, the loser's {@code INSERT}
     * is rejected by the index and retried — by which time the winner's row is
     * committed and is returned to the caller. A duplicate save therefore never
     * surfaces an error and never creates a second row, on SQLite, MySQL and H2 alike.
     *
     * <p>A second, SQLite-specific race is absorbed by the same loop: because the
     * attempt reads before it writes, SQLite refuses to upgrade the reader's shared
     * lock to a write lock while another writer holds it and reports {@code SQLITE_BUSY}
     * immediately (its busy handler deliberately does not wait, to avoid deadlock).
     * Left unhandled that surfaces as a 500 whenever two favourites are saved at once
     * from a pool with more than one connection. Such lock failures are retried with a
     * short jittered backoff instead of being reported to the caller.
     *
     * <p>Each attempt runs in its own transaction ({@link TransactionTemplate}, joining
     * an ambient transaction when the caller already has one) precisely so that a
     * rejected {@code INSERT} can be rolled back and retried instead of poisoning a
     * transaction that must go on to commit.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public FavoriteResponse add(Long userId, boolean admin, Long messageId) {
        if (userId == null) {
            throw new AuthException(401, "未登录");
        }
        if (messageId == null) {
            throw new IllegalArgumentException("messageId 不能为空");
        }

        for (int attempt = 1; attempt <= MAX_ADD_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> addOnce(userId, admin, messageId));
            } catch (DataAccessException failure) {
                RetryDecision decision = classify(failure);
                if (decision == RetryDecision.NONE || attempt == MAX_ADD_ATTEMPTS) {
                    throw failure;
                }
                log.debug("Favorite save (user={}, message={}) attempt {}/{} failed with {} [{}]; retrying",
                        userId, messageId, attempt, MAX_ADD_ATTEMPTS,
                        failure.getClass().getSimpleName(), rootMessage(failure));
                if (decision == RetryDecision.RETRY_AFTER_CONTENTION) {
                    backoff(attempt);
                }
            }
        }
        throw new IllegalStateException("收藏保存失败: user=" + userId + ", message=" + messageId);
    }

    /** How {@link #add} should react to a failed attempt. */
    private enum RetryDecision {
        /** The unique index rejected the insert; another writer owns the row now. */
        RE_READ,
        /** Transient lock contention (SQLite {@code SQLITE_BUSY}); back off, then retry. */
        RETRY_AFTER_CONTENTION,
        /** Anything else: propagate unchanged, exactly as before. */
        NONE
    }

    private static RetryDecision classify(DataAccessException failure) {
        if (failure instanceof DataIntegrityViolationException
                || containsIgnoreCase(failure, "unique constraint")
                || containsIgnoreCase(failure, "duplicate entry")) {
            return RetryDecision.RE_READ;
        }
        if (failure instanceof TransientDataAccessException
                || containsIgnoreCase(failure, "sqlite_busy")
                || containsIgnoreCase(failure, "database is locked")) {
            return RetryDecision.RETRY_AFTER_CONTENTION;
        }
        return RetryDecision.NONE;
    }

    /**
     * Jittered, bounded pause before re-attempting a lock-contended save. Runs after
     * the failed attempt's transaction has been rolled back, so no database
     * connection is held while sleeping.
     */
    private static void backoff(int attempt) {
        long millis = Math.min(150L, 20L * attempt) + ThreadLocalRandom.current().nextLong(20L);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean containsIgnoreCase(Throwable failure, String needle) {
        String message = rootMessage(failure);
        return message != null && message.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return message;
    }

    /** One transactional save attempt; returns the new snapshot or the winner's existing one. */
    private FavoriteResponse addOnce(Long userId, boolean admin, Long messageId) {
        Message message = messageRepository.findByIdForUpdate(messageId)
                .orElseThrow(() -> new AuthException(404, "消息不存在"));
        checkMessageAccess(message, userId, admin);
        if (message.getImageUrl() == null || message.getImageUrl().isBlank()) {
            throw new IllegalArgumentException("只能收藏包含图片的消息");
        }

        Favorite existing = favoriteRepository.findByUserIdAndMessageId(userId, messageId).orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        Message context = contextMessage(message);
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setMessageId(messageId);
        favorite.setSessionId(message.getSession() != null ? message.getSession().getId() : null);
        favorite.setImageUrl(message.getImageUrl());
        favorite.setDrawPrompt(firstNonBlank(message.getDrawPrompt(), context.getDrawPrompt()));
        favorite.setDrawSize(firstNonBlank(message.getDrawSize(), context.getDrawSize()));
        favorite.setDrawQuality(firstNonBlank(message.getDrawQuality(), context.getDrawQuality()));
        favorite.setDrawFormat(firstNonBlank(message.getDrawFormat(), context.getDrawFormat()));
        favorite.setReferenceFileUrls(collectReferenceFileUrls(message, context));
        // Flush inside the attempt so a unique-index rejection is raised here, where it
        // can be retried, rather than during commit after the attempt has returned.
        return toResponse(favoriteRepository.saveAndFlush(favorite));
    }

    /**
     * Cancels one saved work, identified by its own record id. Idempotent: an
     * absent record is not an error.
     *
     * <p>The locator is the favourite row itself, never the source message. A
     * message may be saved independently by several users, so cancelling by
     * message id would let an administrator's cancel sweep away every other
     * user's record of that message. With a record id the operation can only ever
     * remove the single row the caller operated on: a regular user may cancel
     * only their own record, while an administrator may cancel any user's record
     * one by one.
     *
     * @throws AuthException 403 when a non-admin tries to cancel someone else's record
     */
    public boolean remove(Long favoriteId, Long actingUserId, boolean admin) {
        if (favoriteId == null) {
            throw new IllegalArgumentException("favoriteId 不能为空");
        }
        Favorite favorite = favoriteRepository.findById(favoriteId).orElse(null);
        if (favorite == null) {
            return false;
        }
        if (!admin && (actingUserId == null || !actingUserId.equals(favorite.getUserId()))) {
            throw new AuthException(403, "无权取消此收藏");
        }
        favoriteRepository.delete(favorite);
        return true;
    }

    /**
     * Message id → the current user's own favourite record id, for
     * {@code MessageResponse.favoriteId}. Scoped strictly by owner so an
     * administrator's chat flags reflect the administrator's own records and
     * never another user's.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> favoriteIdsByMessageId(Long userId, Collection<Long> messageIds) {
        if (userId == null || messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = messageIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> byMessage = new LinkedHashMap<>();
        for (Object[] row : favoriteRepository.findIdAndMessageIdByUserIdAndMessageIdIn(userId, distinct)) {
            Long favoriteId = (Long) row[0];
            Long messageId = (Long) row[1];
            if (favoriteId != null && messageId != null) {
                byMessage.putIfAbsent(messageId, favoriteId);
            }
        }
        return byMessage;
    }

    /** The current user's own record id for one message, or {@code null}. */
    @Transactional(readOnly = true)
    public Long favoriteId(Long userId, Long messageId) {
        if (userId == null || messageId == null) {
            return null;
        }
        return favoriteIdsByMessageId(userId, List.of(messageId)).get(messageId);
    }

    private FavoriteResponse toResponse(Favorite favorite) {
        return toResponse(favorite, resolveUserName(favorite.getUserId()));
    }

    private FavoriteResponse toResponse(Favorite favorite, String userName) {
        boolean sessionAvailable = favorite.getSessionId() != null
                && sessionRepository.existsById(favorite.getSessionId());
        return FavoriteResponse.from(favorite, sessionAvailable, userName);
    }

    /** Batch owner-name lookup so the list endpoint does not issue one query per record. */
    private Map<Long, String> resolveUserNames(Collection<Favorite> favorites) {
        Set<Long> userIds = new HashSet<>();
        for (Favorite favorite : favorites) {
            if (favorite.getUserId() != null) {
                userIds.add(favorite.getUserId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (AppUser user : appUserRepository.findAllById(userIds)) {
            names.put(user.getId(), user.getUsername());
        }
        return names;
    }

    private String resolveUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        return appUserRepository.findById(userId).map(AppUser::getUsername).orElse(null);
    }

    private void checkMessageAccess(Message message, Long userId, boolean admin) {
        if (admin) {
            return;
        }
        if (message.getSession() == null || !userId.equals(message.getSession().getUserId())) {
            throw new AuthException(403, "无权收藏此消息");
        }
    }

    /**
     * The message carrying the draw context. A DRAW_RESPONSE owns the generated
     * image but the prompt/reference attachments live on its parent DRAW_REQUEST,
     * so fall back to the parent when the response has no values of its own.
     */
    private Message contextMessage(Message message) {
        if (message.getMessageType() == MessageType.DRAW_RESPONSE && message.getParentMessageId() != null) {
            return messageRepository.findById(message.getParentMessageId()).orElse(message);
        }
        return message;
    }

    /**
     * Snapshot of every reference image used for the generation: the message's own
     * image attachments plus the parent draw request's attachments and persisted
     * existing-file references. Stored raw (signature- and query-less) in the same
     * newline-separated format as {@code Message.reference_file_urls}.
     */
    private String collectReferenceFileUrls(Message message, Message context) {
        LinkedHashSet<String> references = new LinkedHashSet<>();
        addAttachments(references, message);
        if (context != message) {
            addAttachments(references, context);
        }
        addStoredReferences(references, context.getReferenceFileUrls());
        if (context != message) {
            addStoredReferences(references, message.getReferenceFileUrls());
        }
        return references.isEmpty() ? null : String.join("\n", references);
    }

    private void addAttachments(Set<String> references, Message message) {
        if (message.getAttachments() == null) {
            return;
        }
        for (Attachment attachment : message.getAttachments()) {
            String contentType = attachment.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                continue;
            }
            String fileUrl = ReferenceFileUrls.stripQuery(attachment.getFileUrl());
            if (fileUrl != null
                    && (fileUrl.startsWith(IMAGE_URL_PREFIX) || fileUrl.startsWith(ATTACHMENT_URL_PREFIX))) {
                references.add(fileUrl);
            }
        }
    }

    private void addStoredReferences(Set<String> references, String stored) {
        for (String reference : ReferenceFileUrls.split(stored)) {
            if (reference.startsWith(IMAGE_URL_PREFIX) || reference.startsWith(ATTACHMENT_URL_PREFIX)) {
                references.add(reference);
            }
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
