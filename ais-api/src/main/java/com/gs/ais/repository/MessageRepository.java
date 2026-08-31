package com.gs.ais.repository;

import com.gs.ais.model.entity.Message;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

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
}
