package com.gs.ais.service;

import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrphanFileCleanupServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void dryRunReportsOrphansWithoutDeletingFiles() throws Exception {
        Path referencedImage = write("kept.png", 3);
        Path referencedAttachment = write("attachments/kept.txt", 4);
        Path orphan = write("generated/orphan.png", 5);

        OrphanFileCleanupService service = serviceWithReferences(
                List.of("/api/images/kept.png"),
                List.of("/api/attachments/kept.txt"));

        OrphanFileCleanupService.CleanupResult result = service.cleanup(true);

        assertTrue(result.dryRun());
        assertEquals(2, result.referencedFiles());
        assertEquals(1, result.orphanFiles());
        assertEquals(5, result.orphanBytes());
        assertEquals(0, result.deletedFiles());
        assertEquals(List.of("generated/orphan.png"), result.candidates());
        assertTrue(Files.exists(referencedImage));
        assertTrue(Files.exists(referencedAttachment));
        assertTrue(Files.exists(orphan));
    }

    @Test
    void executeDeletesOnlyUnreferencedFilesAndRemovesEmptyDirectories() throws Exception {
        Path referencedImage = write("kept.png", 3);
        Path referencedAttachment = write("attachments/kept.txt", 4);
        Path orphan = write("generated/old/orphan.png", 5);

        OrphanFileCleanupService service = serviceWithReferences(
                List.of("/api/images/kept.png"),
                List.of("/api/attachments/kept.txt"));

        OrphanFileCleanupService.CleanupResult result = service.cleanup(false);

        assertFalse(result.dryRun());
        assertEquals(1, result.orphanFiles());
        assertEquals(1, result.deletedFiles());
        assertTrue(Files.exists(referencedImage));
        assertTrue(Files.exists(referencedAttachment));
        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(tempDir.resolve("generated")));
        assertTrue(Files.isDirectory(tempDir.resolve("attachments")));
    }

    @Test
    void unsafeAndExternalUrlsDoNotProtectLocalFiles() throws Exception {
        Path orphan = write("outside-looking.png", 2);
        MessageRepository messages = mock(MessageRepository.class);
        AttachmentRepository attachments = mock(AttachmentRepository.class);
        when(messages.findAllImageUrls()).thenReturn(List.of(
                "/api/images/../outside-looking.png",
                "https://cdn.example.test/image.png"));
        when(attachments.findAllFileUrls()).thenReturn(List.of());

        OrphanFileCleanupService service = new OrphanFileCleanupService(messages, attachments, tempDir);
        OrphanFileCleanupService.CleanupResult result = service.cleanup(true);

        assertEquals(0, result.referencedFiles());
        assertEquals(1, result.orphanFiles());
        assertTrue(Files.exists(orphan));
    }

    private OrphanFileCleanupService serviceWithReferences(
            List<String> imageUrls,
            List<String> attachmentUrls) {
        MessageRepository messages = mock(MessageRepository.class);
        AttachmentRepository attachments = mock(AttachmentRepository.class);
        when(messages.findAllImageUrls()).thenReturn(imageUrls);
        when(attachments.findAllFileUrls()).thenReturn(attachmentUrls);
        return new OrphanFileCleanupService(messages, attachments, tempDir);
    }

    private Path write(String relativePath, int bytes) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
        return file;
    }
}
