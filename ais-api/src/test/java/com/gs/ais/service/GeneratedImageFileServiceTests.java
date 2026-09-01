package com.gs.ais.service;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Message;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the reference-counting for generated-image physical deletion: a file
 * reused by a draw request through {@code Message.reference_file_urls} (no
 * attachment record) must survive deletion of its source message/session, while a
 * file referenced only by the message being deleted is still removed.
 */
class GeneratedImageFileServiceTests {

    @TempDir
    Path tempDir;

    private MessageRepository messageRepository;
    private AttachmentRepository attachmentRepository;
    private GeneratedImageFileService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        attachmentRepository = mock(AttachmentRepository.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.base-dir", tempDir.toString())
                .withProperty("app.upload-dir", "uploads");
        service = new GeneratedImageFileService(
                messageRepository, attachmentRepository, new StoragePaths(environment));
        when(messageRepository.findByImageUrl(anyString())).thenReturn(List.of());
        when(attachmentRepository.findByFileUrl(anyString())).thenReturn(List.of());
    }

    @Test
    void keepsGeneratedImageAndThumbnailsWhenReferencedByReferenceFileUrls() throws Exception {
        Path original = write("generated/x.png");
        Path thumb = write("generated/x_thumb_256.png");

        Message surviving = new Message();
        surviving.setId(99L);
        surviving.setReferenceFileUrls("/api/images/generated/x.png");
        when(messageRepository.findMessagesWithReferenceFileUrls()).thenReturn(List.of(surviving));

        boolean deleted = service.deleteIfUnreferenced("/api/images/generated/x.png", Set.of(1L));

        assertFalse(deleted, "a referenced generated image must not be deleted");
        assertTrue(Files.exists(original));
        assertTrue(Files.exists(thumb));
    }

    @Test
    void deletesGeneratedImageWhenOnlyTheDeletedMessageReferencesIt() throws Exception {
        Path original = write("generated/x.png");

        // The only reference lives on the message being deleted (id 1), so it must not
        // keep the file alive.
        Message deletedMessage = new Message();
        deletedMessage.setId(1L);
        deletedMessage.setReferenceFileUrls("/api/images/generated/x.png");
        when(messageRepository.findMessagesWithReferenceFileUrls()).thenReturn(List.of(deletedMessage));

        boolean deleted = service.deleteIfUnreferenced("/api/images/generated/x.png", Set.of(1L));

        assertTrue(deleted, "a file referenced only by the deleted message must be removed");
        assertFalse(Files.exists(original));
    }

    @Test
    void attachmentReferencePathDoesNotProtectSameNamedImagePath() throws Exception {
        Path original = write("generated/x.png");

        Message surviving = new Message();
        surviving.setId(99L);
        // Same basename, different root: must not protect the generated image.
        surviving.setReferenceFileUrls("/api/attachments/x.png");
        when(messageRepository.findMessagesWithReferenceFileUrls()).thenReturn(List.of(surviving));

        boolean deleted = service.deleteIfUnreferenced("/api/images/generated/x.png", Set.of(1L));

        assertTrue(deleted, "an attachment-path reference must not protect an image-path file");
        assertFalse(Files.exists(original));
    }

    private Path write(String relative) throws Exception {
        Path file = tempDir.resolve("uploads").resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[]{1, 2, 3});
    }
}
