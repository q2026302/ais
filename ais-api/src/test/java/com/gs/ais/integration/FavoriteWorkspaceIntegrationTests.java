package com.gs.ais.integration;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.dto.response.FavoriteResponse;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Favorite;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.FavoriteRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.service.FavoriteService;
import com.gs.ais.service.SessionService;
import com.gs.ais.security.AuthException;
import com.gs.ais.util.ReferenceFileUrls;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end snapshot semantics: a saved work is visible to its owner only, and
 * it stays complete — including its physical files — after the originating
 * session/message are deleted through the real service flow.
 *
 * <p>Uploads are redirected into {@code build/} so the tests never touch a
 * developer's real {@code data/uploads} directory.
 */
@SpringBootTest(properties = {
        "feishu.enabled=false",
        "app.upload-dir=build/favorite-it-uploads"
})
@AutoConfigureMockMvc
@Transactional
class FavoriteWorkspaceIntegrationTests {

    private static final byte[] RESULT_BYTES = {10, 20, 30, 40};
    private static final byte[] SOURCE_BYTES = {50, 60, 70, 80};
    private static final byte[] CAT_BYTES = {90, 100, 110, 120};

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private StoragePaths storagePaths;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void favoriteIsUserScopedAndSurvivesSessionDeletion() {
        Session session = sessionRepository.saveAndFlush(new Session());
        session.setUserId(1L);
        session = sessionRepository.saveAndFlush(session);

        Message drawRequest = new Message();
        drawRequest.setSession(session);
        drawRequest.setRole(MessageRole.USER);
        drawRequest.setMessageType(MessageType.DRAW_REQUEST);
        drawRequest.setStatus(MessageStatus.SUCCESS);
        drawRequest.setContent("绘画提示词：一只橘猫");
        drawRequest.setDrawPrompt("一只橘猫");
        drawRequest.setReferenceFileUrls("/api/images/generated/source.png");
        drawRequest = messageRepository.saveAndFlush(drawRequest);

        Attachment attachment = new Attachment();
        attachment.setMessage(drawRequest);
        attachment.setOwnerId(1L);
        attachment.setFilename("cat.png");
        attachment.setOriginalName("cat.png");
        attachment.setContentType("image/png");
        attachment.setFileUrl("/api/attachments/cat.png");
        attachment = attachmentRepository.saveAndFlush(attachment);

        Message drawResponse = new Message();
        drawResponse.setSession(session);
        drawResponse.setRole(MessageRole.ASSISTANT);
        drawResponse.setMessageType(MessageType.DRAW_RESPONSE);
        drawResponse.setStatus(MessageStatus.SUCCESS);
        drawResponse.setParentMessageId(drawRequest.getId());
        drawResponse.setImageUrl("/api/images/generated/result.png");
        final Message savedResponse = messageRepository.saveAndFlush(drawResponse);

        // Clear the persistence context so the favourite is built from a real
        // reload (as it is in production), including the lazily-loaded attachments.
        entityManager.flush();
        entityManager.clear();

        FavoriteResponse created = favoriteService.add(1L, false, savedResponse.getId());
        assertTrue(created.isSessionAvailable());
        assertEquals("一只橘猫", created.getDrawPrompt());
        assertEquals(
                List.of("/api/attachments/cat.png", "/api/images/generated/source.png"),
                created.getReferenceImages().stream().map(ref -> ref.getFileUrl()).toList());

        // Owner sees it; another user's library stays empty; admin scope sees all.
        Page<FavoriteResponse> ownerPage = favoriteService.list(1L, 0, 20);
        assertEquals(1, ownerPage.getTotalElements());
        assertEquals(0, favoriteService.list(2L, 0, 20).getTotalElements());
        assertEquals(1, favoriteService.list(null, 0, 20).getTotalElements());

        // Deleting the session/messages must not remove the saved work.
        messageRepository.deleteBySessionId(session.getId());
        messageRepository.flush();
        attachmentRepository.deleteAll();
        sessionRepository.delete(session);
        sessionRepository.flush();

        Page<FavoriteResponse> afterDelete = favoriteService.list(1L, 0, 20);
        assertEquals(1, afterDelete.getTotalElements());
        FavoriteResponse survivor = afterDelete.getContent().get(0);
        assertEquals("/api/images/generated/result.png", survivor.getImageUrl());
        assertEquals("一只橘猫", survivor.getDrawPrompt());
        assertFalse(survivor.isSessionAvailable(), "the source session is gone but the work stays usable");
        assertEquals(2, survivor.getReferenceImages().size());

        // Unfavouriting is by the favourite record id and finally empties the library.
        assertTrue(favoriteService.remove(created.getId(), 1L, false));
        assertEquals(0, favoriteService.list(1L, 0, 20).getTotalElements());
        assertEquals(0, favoriteRepository.count());
    }

    /**
     * Problem 1 regression: an administrator saving user B's generated image must
     * not shadow B's own save. Each user gets an independent row; B's save is real
     * (visible after a "refresh", i.e. a fresh list query) and B can cancel it
     * without touching the administrator's copy.
     */
    @Test
    void sameMessageIsSavedIndependentlyByDifferentUsers() {
        Session session = sessionRepository.saveAndFlush(new Session());
        session.setUserId(2L);
        session = sessionRepository.saveAndFlush(session);

        Message message = new Message();
        message.setSession(session);
        message.setRole(MessageRole.ASSISTANT);
        message.setMessageType(MessageType.DRAW_RESPONSE);
        message.setStatus(MessageStatus.SUCCESS);
        message.setImageUrl("/api/images/generated/shared.png");
        final Message saved = messageRepository.saveAndFlush(message);
        entityManager.flush();
        entityManager.clear();

        // Administrator (user 1) saves it first...
        FavoriteResponse adminFavorite = favoriteService.add(1L, true, saved.getId());
        // ...then the owner (user 2) saves the very same message.
        FavoriteResponse ownerFavorite = favoriteService.add(2L, false, saved.getId());

        assertNotEquals(adminFavorite.getId(), ownerFavorite.getId());
        assertEquals(1, favoriteService.list(1L, 0, 20).getTotalElements());
        assertEquals(1, favoriteService.list(2L, 0, 20).getTotalElements());
        assertEquals(2, favoriteService.list(null, 0, 20).getTotalElements());

        // B's save survives a refresh (a fresh server read) and B can cancel it by
        // its own record id.
        assertNotNull(favoriteService.favoriteId(2L, saved.getId()));
        assertTrue(favoriteService.remove(ownerFavorite.getId(), 2L, false));
        assertEquals(0, favoriteService.list(2L, 0, 20).getTotalElements());
        assertEquals(1, favoriteService.list(1L, 0, 20).getTotalElements(),
                "cancelling one user's save must leave the other user's row untouched");
        // Cancelling again is a harmless no-op, never "无权取消此收藏".
        assertFalse(favoriteService.remove(ownerFavorite.getId(), 2L, false));
        // The administrator cancels the remaining row one record at a time.
        assertTrue(favoriteService.remove(adminFavorite.getId(), 1L, true));
        assertEquals(0, favoriteService.list(null, 0, 20).getTotalElements());
    }

    /**
     * Acceptance criteria 1 &amp; 2: the owner and an administrator each save the same
     * message; a record-level cancel removes exactly the operated record. The
     * administrator's cancel of the owner's record leaves the administrator's record
     * intact, and vice versa.
     */
    @Test
    void recordLevelCancelNeverTouchesAnotherUsersRecordOfTheSameMessage() {
        Session session = sessionRepository.saveAndFlush(new Session());
        session.setUserId(1L);
        session = sessionRepository.saveAndFlush(session);

        Message message = new Message();
        message.setSession(session);
        message.setRole(MessageRole.ASSISTANT);
        message.setMessageType(MessageType.DRAW_RESPONSE);
        message.setStatus(MessageStatus.SUCCESS);
        message.setImageUrl("/api/images/generated/record-level.png");
        final Message saved = messageRepository.saveAndFlush(message);
        entityManager.flush();
        entityManager.clear();

        FavoriteResponse ownerRecord = favoriteService.add(1L, false, saved.getId());
        FavoriteResponse adminRecord = favoriteService.add(2L, true, saved.getId());
        assertNotEquals(ownerRecord.getId(), adminRecord.getId());

        // The administrator cancels the owner's record: only that one disappears.
        assertTrue(favoriteService.remove(ownerRecord.getId(), 2L, true));
        assertEquals(1, favoriteService.list(null, 0, 20).getTotalElements());
        assertEquals(1, favoriteService.list(2L, 0, 20).getTotalElements(),
                "the administrator's own record must survive cancelling the owner's");
        assertEquals(0, favoriteService.list(1L, 0, 20).getTotalElements());

        // The reverse direction: the administrator cancels their own record, the
        // owner's (re-added) record is unaffected.
        FavoriteResponse ownerAgain = favoriteService.add(1L, false, saved.getId());
        assertTrue(favoriteService.remove(adminRecord.getId(), 2L, true));
        assertEquals(1, favoriteService.list(null, 0, 20).getTotalElements());
        assertEquals(1, favoriteService.list(1L, 0, 20).getTotalElements());

        // A regular user may not cancel someone else's record.
        FavoriteResponse adminAgain = favoriteService.add(2L, true, saved.getId());
        AuthException denied = assertThrows(AuthException.class,
                () -> favoriteService.remove(adminAgain.getId(), 1L, false));
        assertEquals(403, denied.getStatus());
        assertEquals(1, favoriteService.list(2L, 0, 20).getTotalElements());
        assertTrue(favoriteRepository.findById(ownerAgain.getId()).isPresent(),
                "the owner's record is untouched by the rejected cancel");
    }

    /**
     * Problem 4.1: deleting the session through the real service must keep the
     * favourite row and the physical bytes of both the saved image and the
     * reference images it captured.
     */
    @Test
    void realSessionDeletionKeepsFavoriteAndItsPhysicalFiles() throws IOException {
        Scenario scenario = createSavedWork("del");
        try {
            Favorite favorite = scenario.favorite();
            assertEquals(2, ReferenceFileUrls.split(favorite.getReferenceFileUrls()).size());

            sessionService.deleteSession(scenario.session().getId());
            entityManager.flush();
            entityManager.clear();

            assertEquals(1, favoriteRepository.findAllByMessageId(scenario.message().getId()).size(),
                    "the saved work must survive the session deletion");
            assertTrue(messageRepository.findById(scenario.message().getId()).isEmpty(),
                    "the source message is gone");
            assertTrue(sessionRepository.findById(scenario.session().getId()).isEmpty(),
                    "the source session is gone");

            assertTrue(Files.exists(scenario.resultFile()),
                    "the saved generated image must not be physically deleted");
            assertTrue(Files.exists(scenario.sourceFile()),
                    "the referenced generated image must not be physically deleted");
            assertTrue(Files.exists(scenario.referenceAttachment()),
                    "the referenced uploaded image must not be physically deleted");
        } finally {
            cleanup(scenario);
        }
    }

    /**
     * Problem 4.2: after the session is gone, the work library's HTTP asset
     * endpoints still serve the saved image and every reference image.
     */
    @Test
    void favoriteAssetsAreServedOverHttpAfterSessionDeletion() throws Exception {
        Scenario scenario = createSavedWork("http");
        try {
            sessionService.deleteSession(scenario.session().getId());
            entityManager.flush();
            entityManager.clear();

            String favoriteId = String.valueOf(scenario.favorite().getId());
            mockMvc.perform(get("/api/favorites/" + favoriteId + "/thumbnail"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(RESULT_BYTES));
            // The snapshot keeps the uploaded reference first, then the reused
            // server-side generated image.
            mockMvc.perform(get("/api/favorites/" + favoriteId + "/references/0/thumbnail"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(CAT_BYTES));
            mockMvc.perform(get("/api/favorites/" + favoriteId + "/references/1/thumbnail"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(SOURCE_BYTES));
        } finally {
            cleanup(scenario);
        }
    }

    /**
     * Problem 2 regression: the test profile runs with
     * {@code app.security.enabled=false}, i.e. the request is handled as the
     * synthetic security-disabled administrator. Saving over the real HTTP API must
     * resolve that principal to the persisted admin account and succeed, instead of
     * reporting 401 "未登录" while the list endpoint works.
     */
    @Test
    void savingThroughTheApiWorksWithSecurityDisabled() throws Exception {
        Session session = sessionRepository.saveAndFlush(new Session());
        session.setUserId(2L);
        session = sessionRepository.saveAndFlush(session);

        Message message = new Message();
        message.setSession(session);
        message.setRole(MessageRole.ASSISTANT);
        message.setMessageType(MessageType.DRAW_RESPONSE);
        message.setStatus(MessageStatus.SUCCESS);
        message.setImageUrl("/api/images/generated/security-disabled.png");
        final Message saved = messageRepository.saveAndFlush(message);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/favorites")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":" + saved.getId() + "}"))
                .andExpect(status().isCreated());

        // The snapshot got a concrete owner (the seeded administrator), not null.
        List<Favorite> stored = favoriteRepository.findAllByMessageId(saved.getId());
        assertEquals(1, stored.size());
        assertNotNull(stored.get(0).getUserId(), "the security-disabled principal must resolve to a real user");

        mockMvc.perform(get("/api/favorites"))
                .andExpect(status().isOk());

        Long favoriteId = favoriteRepository.findAllByMessageId(saved.getId()).get(0).getId();
        mockMvc.perform(delete("/api/favorites/" + favoriteId))
                .andExpect(status().isNoContent());
    }

    private Scenario createSavedWork(String suffix) throws IOException {
        Path resultFile = write("generated/" + suffix + "-result.png", RESULT_BYTES);
        Path sourceFile = write("generated/" + suffix + "-source.png", SOURCE_BYTES);
        Path referenceAttachment = write("attachments/" + suffix + "-cat.png", CAT_BYTES);

        Session session = sessionRepository.saveAndFlush(new Session());
        session.setUserId(1L);
        session = sessionRepository.saveAndFlush(session);

        // A previously generated image that the new draw reuses as a reference.
        messageRepository.saveAndFlush(message(session, MessageType.DRAW_RESPONSE,
                "/api/images/generated/" + suffix + "-source.png", null, null));

        Message drawRequest = message(session, MessageType.DRAW_REQUEST, null,
                "/api/images/generated/" + suffix + "-source.png", null);
        drawRequest.setDrawPrompt("一只橘猫");
        drawRequest = messageRepository.saveAndFlush(drawRequest);

        Attachment attachment = new Attachment();
        attachment.setMessage(drawRequest);
        attachment.setOwnerId(1L);
        attachment.setFilename(suffix + "-cat.png");
        attachment.setOriginalName(suffix + "-cat.png");
        attachment.setContentType("image/png");
        attachment.setFileUrl("/api/attachments/" + suffix + "-cat.png");
        attachmentRepository.saveAndFlush(attachment);

        Message drawResponse = message(session, MessageType.DRAW_RESPONSE,
                "/api/images/generated/" + suffix + "-result.png", null, drawRequest.getId());
        final Message savedResponse = messageRepository.saveAndFlush(drawResponse);

        entityManager.flush();
        entityManager.clear();

        FavoriteResponse created = favoriteService.add(1L, false, savedResponse.getId());
        Favorite favorite = favoriteRepository.findById(created.getId()).orElseThrow();
        return new Scenario(session, savedResponse, favorite, resultFile, sourceFile, referenceAttachment);
    }

    private Message message(Session session, MessageType type, String imageUrl,
                            String referenceFileUrls, Long parentMessageId) {
        Message message = new Message();
        message.setSession(session);
        message.setRole(type == MessageType.DRAW_REQUEST ? MessageRole.USER : MessageRole.ASSISTANT);
        message.setMessageType(type);
        message.setStatus(MessageStatus.SUCCESS);
        message.setImageUrl(imageUrl);
        message.setReferenceFileUrls(referenceFileUrls);
        message.setParentMessageId(parentMessageId);
        return message;
    }

    private Path write(String relative, byte[] content) throws IOException {
        Path path = storagePaths.uploadDir().resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, content);
        return path;
    }

    private void cleanup(Scenario scenario) throws IOException {
        for (Path path : List.of(scenario.resultFile(), scenario.sourceFile(), scenario.referenceAttachment())) {
            Files.deleteIfExists(path);
            Files.deleteIfExists(thumbnail(path));
        }
    }

    private static Path thumbnail(Path original) {
        String name = original.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        return original.resolveSibling(base + "_thumb_256.png");
    }

    private record Scenario(Session session,
                            Message message,
                            Favorite favorite,
                            Path resultFile,
                            Path sourceFile,
                            Path referenceAttachment) {
    }
}
