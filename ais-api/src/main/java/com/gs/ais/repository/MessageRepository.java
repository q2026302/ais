package com.gs.ais.repository;

import com.gs.ais.model.entity.Message;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    /**
     * Loads a message while asking for a database write lock on the row.
     *
     * <p>Used when saving a message to the work library. On dialects that support
     * {@code SELECT ... FOR UPDATE} (MySQL, H2) this serialises concurrent saves of
     * the same message and spares the second writer a rejected insert. It is an
     * <em>optimisation only</em>: the community {@code SQLiteDialect} returns an
     * empty {@code getForUpdateString()}, so on the production database this is a
     * plain read and no row lock is taken. Idempotency must never depend on it —
     * the real, cross-dialect guarantee is the unique index on
     * {@code favorites(user_id, message_id)} plus the insert-conflict retry in
     * {@code FavoriteService#add}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Message m where m.id = :id")
    Optional<Message> findByIdForUpdate(@Param("id") Long id);

    /**
     * Finds the message(s) whose generated image URL matches exactly. Used to
     * resolve image access through the message → session ownership chain. A list
     * (not Optional) is returned to tolerate duplicate/legacy rows safely.
     */
    List<Message> findByImageUrl(String imageUrl);

    /** Incremental fetch: include soft-deleted messages as tombstones so clients can evict them. */
    @Query("select m from Message m where m.session.id = :sessionId and m.updatedAt > :since order by m.createdAt asc")
    List<Message> findBySessionIdAndUpdatedAtAfter(
            @Param("sessionId") Long sessionId,
            @Param("since") LocalDateTime since);

    List<Message> findByMessageTypeAndStatus(MessageType messageType, MessageStatus status);

    List<Message> findBySessionIdInOrderByCreatedAtAsc(List<Long> sessionIds);

    /**
     * Latest message per session (max identity id). Used to populate session list
     * lastMessageAt / lastMessagePreview without loading full histories.
     */
    @Query("""
            select m from Message m
            where m.id in (
                select max(m2.id) from Message m2
                where m2.session.id in :sessionIds
                group by m2.session.id
            )
            """)
    List<Message> findLatestBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    void deleteBySessionId(Long sessionId);

    @Query("select m.imageUrl from Message m where m.imageUrl is not null and m.imageUrl <> ''")
    List<String> findAllImageUrls();

    /**
     * All surviving messages carrying at least one persisted existing-file
     * reference ({@code reference_file_urls}). Used by physical-file deletion
     * (generated images and attachments) to keep a file alive while any
     * surviving message still references it by raw path.
     *
     * <p>Soft-deleted messages ({@code deleted = true}) are excluded: they are
     * only kept as incremental-sync tombstones, so their reference_file_urls must
     * not keep physical files alive (otherwise soft-deleted history would leak
     * storage forever).
     */
    @Query("select m from Message m where m.referenceFileUrls is not null and m.referenceFileUrls <> '' and m.deleted = false")
    List<Message> findMessagesWithReferenceFileUrls();
}
