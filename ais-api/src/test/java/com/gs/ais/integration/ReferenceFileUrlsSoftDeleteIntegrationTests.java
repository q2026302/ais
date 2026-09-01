package com.gs.ais.integration;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.service.GeneratedImageFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the soft-delete storage leak: a soft-deleted message's
 * {@code reference_file_urls} must not keep its referenced physical files alive.
 *
 * <p>The shared fix lives in
 * {@link MessageRepository#findMessagesWithReferenceFileUrls()}, which is the single
 * source of truth consumed by all three cleanup paths — generated-image deletion
 * ({@link GeneratedImageFileService}), attachment deletion
 * ({@code ImageGenerationService#deleteMessage}), and orphan sweep
 * ({@code OrphanFileCleanupService}). The generated-image path is exercised
 * end-to-end below; the attachment and orphan paths are equivalent because they
 * consume the exact same query result.
 */
@SpringBootTest(properties = "feishu.enabled=false")
@Transactional
class ReferenceFileUrlsSoftDeleteIntegrationTests {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @TempDir
    Path tempDir;

    @Test
    void findMessagesWithReferenceFileUrlsExcludesSoftDeletedMessages() {
        Session session = sessionRepository.saveAndFlush(new Session());

        Message surviving = messageRepository.saveAndFlush(
                message(session, "/api/images/generated/keep.png"));
        Message softDeleted = messageRepository.saveAndFlush(
                message(session, "/api/images/generated/drop.png"));
        softDeleted.setDeleted(true);
        messageRepository.saveAndFlush(softDeleted);

        List<Message> result = messageRepository.findMessagesWithReferenceFileUrls();

        assertEquals(1, result.size(), "soft-deleted messages must not count as live references");
        assertEquals(surviving.getId(), result.get(0).getId());
        assertFalse(result.get(0).isDeleted());
    }

    @Test
    void softDeletedReferenceDoesNotKeepGeneratedImageAlive() throws Exception {
        StoragePaths storagePaths = new StoragePaths(new MockEnvironment()
                .withProperty("app.base-dir", tempDir.toString())
                .withProperty("app.upload-dir", "uploads"));
        GeneratedImageFileService service = new GeneratedImageFileService(
                messageRepository, attachmentRepository, storagePaths);

        Path image = tempDir.resolve("uploads/generated/x.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});

        Session session = sessionRepository.saveAndFlush(new Session());
        Message surviving = messageRepository.saveAndFlush(
                message(session, "/api/images/generated/x.png"));

        // A surviving message's reference still keeps the file alive.
        assertFalse(service.deleteIfUnreferenced("/api/images/generated/x.png", Set.of()),
                "a surviving message reference must keep the generated image alive");
        assertTrue(Files.exists(image));

        // Soft-delete the only referencing message: it is a tombstone now and must
        // stop protecting the bytes.
        surviving.setDeleted(true);
        messageRepository.saveAndFlush(surviving);

        assertTrue(service.deleteIfUnreferenced("/api/images/generated/x.png", Set.of()),
                "a soft-deleted message reference must not keep the generated image alive");
        assertFalse(Files.exists(image));
    }

    private Message message(Session session, String referenceFileUrls) {
        Message message = new Message();
        message.setSession(session);
        message.setRole(MessageRole.USER);
        message.setMessageType(MessageType.DRAW_REQUEST);
        message.setReferenceFileUrls(referenceFileUrls);
        return message;
    }
}
