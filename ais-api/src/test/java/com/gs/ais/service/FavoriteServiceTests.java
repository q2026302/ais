package com.gs.ais.service;

import com.gs.ais.dto.response.FavoriteResponse;
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Favorite;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.FavoriteRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.security.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshot semantics of the work library: a favourite must carry the prompt and
 * the reference images by itself, and must be scoped to its owner.
 */
class FavoriteServiceTests {

    private FavoriteRepository favoriteRepository;
    private MessageRepository messageRepository;
    private SessionRepository sessionRepository;
    private AppUserRepository appUserRepository;
    private FavoriteService service;

    @BeforeEach
    void setUp() {
        favoriteRepository = mock(FavoriteRepository.class);
        messageRepository = mock(MessageRepository.class);
        sessionRepository = mock(SessionRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        // A pass-through transaction manager: the retry loop's TransactionTemplate
        // boundaries are irrelevant to these mock-level assertions.
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new FavoriteService(favoriteRepository, messageRepository, sessionRepository,
                appUserRepository, transactionManager);
        when(favoriteRepository.saveAndFlush(any(Favorite.class))).thenAnswer(invocation -> {
            Favorite favorite = invocation.getArgument(0);
            if (favorite.getId() == null) {
                favorite.setId(5L);
            }
            return favorite;
        });
    }

    @Test
    void addSnapshotsPromptAndReferenceImagesFromTheParentDrawRequest() {
        Session session = new Session();
        session.setId(7L);
        session.setUserId(1L);

        Message drawRequest = new Message();
        drawRequest.setId(41L);
        drawRequest.setSession(session);
        drawRequest.setRole(MessageRole.USER);
        drawRequest.setMessageType(MessageType.DRAW_REQUEST);
        drawRequest.setStatus(MessageStatus.SUCCESS);
        drawRequest.setDrawPrompt("一只橘猫");
        drawRequest.setDrawSize("1024x1024");
        drawRequest.setDrawQuality("high");
        drawRequest.setDrawFormat("png");
        drawRequest.setReferenceFileUrls("/api/images/generated/ref.png");
        Attachment uploaded = new Attachment();
        uploaded.setId(3L);
        uploaded.setContentType("image/png");
        uploaded.setFileUrl("/api/attachments/cat.png?sig=stale");
        drawRequest.setAttachments(List.of(uploaded));

        Message drawResponse = new Message();
        drawResponse.setId(42L);
        drawResponse.setSession(session);
        drawResponse.setRole(MessageRole.ASSISTANT);
        drawResponse.setMessageType(MessageType.DRAW_RESPONSE);
        drawResponse.setStatus(MessageStatus.SUCCESS);
        drawResponse.setImageUrl("/api/images/generated/result.png");
        drawResponse.setParentMessageId(41L);

        when(messageRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(drawResponse));
        when(messageRepository.findById(41L)).thenReturn(Optional.of(drawRequest));
        when(favoriteRepository.findByUserIdAndMessageId(1L, 42L)).thenReturn(Optional.empty());
        when(sessionRepository.existsById(7L)).thenReturn(true);

        FavoriteResponse response = service.add(1L, false, 42L);

        assertEquals(5L, response.getId());
        assertEquals(42L, response.getMessageId());
        assertEquals(7L, response.getSessionId());
        assertTrue(response.isSessionAvailable());
        assertEquals("/api/images/generated/result.png", response.getImageUrl());
        assertEquals("/api/favorites/5/thumbnail", response.getThumbnailUrl());
        assertEquals("一只橘猫", response.getDrawPrompt());
        assertEquals("1024x1024", response.getDrawSize());
        assertEquals("high", response.getDrawQuality());
        assertEquals("png", response.getDrawFormat());
        // Both the raw existing-file reference and the uploaded attachment are kept,
        // stored signature-less so the snapshot never carries a stale credential.
        assertEquals(
                List.of("/api/attachments/cat.png", "/api/images/generated/ref.png"),
                response.getReferenceImages().stream().map(ref -> ref.getFileUrl()).toList());
        assertEquals("/api/favorites/5/references/0/thumbnail", response.getReferenceImages().get(0).getThumbnailUrl());
    }

    @Test
    void addIsIdempotentForAnAlreadyFavouritedMessage() {
        Session session = new Session();
        session.setId(7L);
        session.setUserId(1L);
        Message message = new Message();
        message.setId(42L);
        message.setSession(session);
        message.setImageUrl("/api/images/generated/result.png");
        when(messageRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(message));

        Favorite existing = new Favorite();
        existing.setId(9L);
        existing.setUserId(1L);
        existing.setMessageId(42L);
        existing.setImageUrl("/api/images/generated/result.png");
        when(favoriteRepository.findByUserIdAndMessageId(1L, 42L)).thenReturn(Optional.of(existing));

        FavoriteResponse response = service.add(1L, false, 42L);

        assertEquals(9L, response.getId());
        verify(favoriteRepository, never()).saveAndFlush(any(Favorite.class));
    }

    /**
     * The production database is SQLite, where {@code SELECT ... FOR UPDATE} is a
     * no-op, so two writers can both pass the existence check. The loser's INSERT is
     * then rejected by the unique index; the service must swallow that, re-read and
     * return the winner's row instead of surfacing an error or inserting a duplicate.
     */
    @Test
    void addReturnsTheWinnersRowWhenTheUniqueIndexRejectsALostRace() {
        Session session = new Session();
        session.setId(7L);
        session.setUserId(1L);
        Message message = new Message();
        message.setId(42L);
        message.setSession(session);
        message.setImageUrl("/api/images/generated/result.png");
        when(messageRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(message));

        Favorite winner = new Favorite();
        winner.setId(9L);
        winner.setUserId(1L);
        winner.setMessageId(42L);
        winner.setImageUrl("/api/images/generated/result.png");
        // First attempt sees nothing and tries to insert; the insert is rejected by the
        // unique index. The retry sees the row the other writer committed.
        when(favoriteRepository.findByUserIdAndMessageId(1L, 42L))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(favoriteRepository.saveAndFlush(any(Favorite.class)))
                .thenThrow(new DataIntegrityViolationException("UNIQUE constraint failed"));

        FavoriteResponse response = service.add(1L, false, 42L);

        assertEquals(9L, response.getId());
        verify(favoriteRepository, times(2)).findByUserIdAndMessageId(1L, 42L);
        verify(favoriteRepository, times(1)).saveAndFlush(any(Favorite.class));
    }

    /**
     * SQLite reports {@code SQLITE_BUSY} (surfaced by Spring as
     * {@code CannotAcquireLockException}) when a reader tries to upgrade to a writer
     * while another writer holds the lock. That is transient contention, not a failed
     * save: the attempt must be retried rather than escaping as a 500.
     */
    @Test
    void addRetriesTransientSqliteLockContentionInsteadOfFailing() {
        Session session = new Session();
        session.setId(7L);
        session.setUserId(1L);
        Message message = new Message();
        message.setId(42L);
        message.setSession(session);
        message.setImageUrl("/api/images/generated/result.png");
        when(messageRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(message));
        when(sessionRepository.existsById(7L)).thenReturn(true);

        Favorite winner = new Favorite();
        winner.setId(9L);
        winner.setUserId(1L);
        winner.setMessageId(42L);
        winner.setImageUrl("/api/images/generated/result.png");
        // First attempt: the write lock is taken, the insert is refused with SQLITE_BUSY.
        // Second attempt: the other writer has committed, so its row is returned.
        when(favoriteRepository.findByUserIdAndMessageId(1L, 42L))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(favoriteRepository.saveAndFlush(any(Favorite.class)))
                .thenThrow(new CannotAcquireLockException(
                        "[SQLITE_BUSY] The database file is locked (database is locked)"));

        FavoriteResponse response = service.add(1L, false, 42L);

        assertEquals(9L, response.getId());
        verify(favoriteRepository, times(2)).findByUserIdAndMessageId(1L, 42L);
    }

    /** Non-transient, non-conflict failures must still reach the caller unchanged. */
    @Test
    void addPropagatesUnrelatedDataAccessFailures() {
        Session session = new Session();
        session.setId(7L);
        session.setUserId(1L);
        Message message = new Message();
        message.setId(42L);
        message.setSession(session);
        message.setImageUrl("/api/images/generated/result.png");
        when(messageRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(message));
        when(favoriteRepository.findByUserIdAndMessageId(1L, 42L)).thenReturn(Optional.empty());
        when(favoriteRepository.saveAndFlush(any(Favorite.class)))
                .thenThrow(new InvalidDataAccessResourceUsageException("no such table: favorites"));

        assertThrows(InvalidDataAccessResourceUsageException.class, () -> service.add(1L, false, 42L));
        verify(favoriteRepository, times(1)).saveAndFlush(any(Favorite.class));
    }

    @Test
    void addRejectsMessagesWithoutAGeneratedImage() {
        Session session = new Session();
        session.setId(7L);
        session.setUserId(1L);
        Message chat = new Message();
        chat.setId(42L);
        chat.setSession(session);
        when(messageRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(chat));

        assertThrows(IllegalArgumentException.class, () -> service.add(1L, false, 42L));
    }

    @Test
    void addRejectsAnotherUsersMessageUnlessAdmin() {
        Session session = new Session();
        session.setId(7L);
        session.setUserId(2L);
        Message message = new Message();
        message.setId(42L);
        message.setSession(session);
        message.setImageUrl("/api/images/generated/result.png");
        when(messageRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(message));

        AuthException denied = assertThrows(AuthException.class, () -> service.add(1L, false, 42L));
        assertEquals(403, denied.getStatus());

        // An administrator may save any user's generated image.
        when(favoriteRepository.findByUserIdAndMessageId(1L, 42L)).thenReturn(Optional.empty());
        when(sessionRepository.existsById(7L)).thenReturn(false);
        FavoriteResponse response = service.add(1L, true, 42L);
        assertEquals(42L, response.getMessageId());
        assertFalse(response.isSessionAvailable());
    }

    @Test
    void addStoresAnIndependentRowPerUser() {
        Session session = new Session();
        session.setId(7L);
        session.setUserId(2L);
        Message message = new Message();
        message.setId(42L);
        message.setSession(session);
        message.setImageUrl("/api/images/generated/result.png");
        when(messageRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(message));
        when(sessionRepository.existsById(7L)).thenReturn(true);
        when(favoriteRepository.findByUserIdAndMessageId(anyLong(), eq(42L))).thenReturn(Optional.empty());
        AtomicLong ids = new AtomicLong(100L);
        when(favoriteRepository.saveAndFlush(any(Favorite.class))).thenAnswer(invocation -> {
            Favorite favorite = invocation.getArgument(0);
            favorite.setId(ids.incrementAndGet());
            return favorite;
        });

        // The owner and a different (administrator) user each save the same message:
        // two rows, one per owner, and neither call sees the other user's snapshot
        // as its own.
        service.add(2L, false, 42L);
        service.add(3L, true, 42L);

        ArgumentCaptor<Favorite> captor = ArgumentCaptor.forClass(Favorite.class);
        verify(favoriteRepository, times(2)).saveAndFlush(captor.capture());
        assertEquals(List.of(2L, 3L), captor.getAllValues().stream().map(Favorite::getUserId).toList());
        verify(favoriteRepository).findByUserIdAndMessageId(2L, 42L);
        verify(favoriteRepository).findByUserIdAndMessageId(3L, 42L);
    }

    @Test
    void removeTargetsExactlyTheRecordItIsGiven() {
        Favorite favorite = new Favorite();
        favorite.setId(9L);
        favorite.setUserId(1L);
        favorite.setMessageId(42L);
        when(favoriteRepository.findById(9L)).thenReturn(Optional.of(favorite));
        when(favoriteRepository.findById(99L)).thenReturn(Optional.empty());

        // The owner cancels their own record.
        assertTrue(service.remove(9L, 1L, false));
        verify(favoriteRepository).delete(favorite);

        // An unknown record is a harmless no-op (idempotent).
        assertFalse(service.remove(99L, 1L, false));
        verify(favoriteRepository, never()).deleteAll(any());
    }

    /**
     * The regression this feature fixes: cancelling must never be keyed by message
     * id, because one message can hold one record per user. A regular user may only
     * cancel their own row; an administrator may cancel any single row without
     * touching the others.
     */
    @Test
    void removeRejectsAnotherUsersRecordForRegularUsersButAllowsAdmins() {
        Favorite otherUsers = new Favorite();
        otherUsers.setId(10L);
        otherUsers.setUserId(2L);
        otherUsers.setMessageId(42L);
        when(favoriteRepository.findById(10L)).thenReturn(Optional.of(otherUsers));

        AuthException denied = assertThrows(AuthException.class, () -> service.remove(10L, 1L, false));
        assertEquals(403, denied.getStatus());
        verify(favoriteRepository, never()).delete(otherUsers);

        // An administrator cancels exactly that one record.
        assertTrue(service.remove(10L, 1L, true));
        verify(favoriteRepository).delete(otherUsers);
    }

    @Test
    void removeRejectsMissingCallerIdentity() {
        Favorite favorite = new Favorite();
        favorite.setId(10L);
        favorite.setUserId(2L);
        when(favoriteRepository.findById(10L)).thenReturn(Optional.of(favorite));

        assertThrows(AuthException.class, () -> service.remove(10L, null, false));
        verify(favoriteRepository, never()).delete(favorite);
    }

    @Test
    void favoriteIdsAreScopedToTheGivenUser() {
        assertTrue(service.favoriteIdsByMessageId(1L, List.of()).isEmpty());
        assertTrue(service.favoriteIdsByMessageId(null, List.of(42L)).isEmpty());
        assertTrue(service.favoriteIdsByMessageId(1L, java.util.Arrays.asList((Long) null)).isEmpty());

        when(favoriteRepository.findIdAndMessageIdByUserIdAndMessageIdIn(1L, List.of(42L, 43L)))
                .thenReturn(List.<Object[]>of(new Object[]{5L, 42L}));

        assertEquals(java.util.Map.of(42L, 5L),
                service.favoriteIdsByMessageId(1L, List.of(42L, 43L)));
        assertNull(service.favoriteId(1L, 43L));
    }

    /** The list carries the owning user so an administrator can tell records apart. */
    @Test
    void listExposesTheOwnerIdentityOfEveryRecord() {
        Favorite favorite = new Favorite();
        favorite.setId(5L);
        favorite.setUserId(2L);
        favorite.setMessageId(42L);
        when(favoriteRepository.findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(favorite)));
        AppUser owner = new AppUser();
        owner.setId(2L);
        owner.setUsername("bob");
        when(appUserRepository.findAllById(Set.of(2L))).thenReturn(List.of(owner));

        FavoriteResponse response = service.list(null, 0, 20).getContent().get(0);

        assertEquals(2L, response.getUserId());
        assertEquals("bob", response.getUserName());
        assertFalse(response.isSessionAvailable());
    }
}
