package com.gs.ais.repository;

import com.gs.ais.model.entity.Favorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    /**
     * A single user's snapshot of a message. Scoping by owner is what keeps work
     * libraries independent: two users may each store their own row for the same
     * message, so lookups must always include the user dimension.
     */
    Optional<Favorite> findByUserIdAndMessageId(Long userId, Long messageId);

    /** Every user's snapshot of a message; identity checks and tests, never a bulk cancel. */
    List<Favorite> findAllByMessageId(Long messageId);

    /** A user's work library is sorted newest first; id breaks ties deterministically. */
    Page<Favorite> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    /** Admin view: every user's favourites. */
    Page<Favorite> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /**
     * One user's own record per requested message, as {@code [favoriteId, messageId]}
     * pairs. Message flags are resolved against the current user only, so this is
     * deliberately scoped by owner and never returns another user's record.
     */
    @Query("select f.id, f.messageId from Favorite f "
            + "where f.userId = :userId and f.messageId in :messageIds")
    List<Object[]> findIdAndMessageIdByUserIdAndMessageIdIn(@Param("userId") Long userId,
                                                            @Param("messageIds") Collection<Long> messageIds);

    @Query("select f.imageUrl from Favorite f where f.imageUrl is not null and f.imageUrl <> ''")
    List<String> findAllImageUrls();

    @Query("select f.referenceFileUrls from Favorite f "
            + "where f.referenceFileUrls is not null and f.referenceFileUrls <> ''")
    List<String> findAllReferenceFileUrls();

    boolean existsByImageUrl(String imageUrl);

    boolean existsByUserIdAndImageUrl(Long userId, String imageUrl);

    /**
     * Reference-file snapshots containing {@code path} as a substring. Callers must
     * still confirm an exact newline-delimited match (see
     * {@code ReferenceFileUrls.containsPath}) so {@code a.png} cannot be protected
     * by a {@code xa.png} reference.
     */
    @Query("select f.referenceFileUrls from Favorite f "
            + "where f.referenceFileUrls like concat('%', :path, '%')")
    List<String> findReferenceFileUrlsContaining(@Param("path") String path);

    @Query("select f.referenceFileUrls from Favorite f "
            + "where f.userId = :userId and f.referenceFileUrls like concat('%', :path, '%')")
    List<String> findReferenceFileUrlsContainingForUser(@Param("userId") Long userId,
                                                        @Param("path") String path);
}
