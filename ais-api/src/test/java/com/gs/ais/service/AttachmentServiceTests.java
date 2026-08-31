package com.gs.ais.service;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Message;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.security.AuthContext;
import com.gs.ais.security.AuthPrincipal;
import com.gs.ais.security.AuthRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttachmentServiceTests {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Reuse-source access checks pass for admins regardless of ownership.
        AuthContext.set(new AuthPrincipal(AuthRole.ADMIN, "admin"));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void reuseMessageThumbnailMaterializesRequestedSmallThumbnail() throws Exception {
        Path uploadDir = tempDir.resolve("uploads");
        Path original = write(uploadDir.resolve("generated/image.png"), new byte[]{1, 2, 3});
        byte[] thumbnail = {4, 5};
        write(uploadDir.resolve("generated/image_thumb_256.png"), thumbnail);
        write(uploadDir.resolve("generated/image_thumb_512.png"), new byte[]{6});

        Message message = new Message();
        message.setImageUrl("/api/images/generated/image.png");
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.findById(7L)).thenReturn(Optional.of(message));

        AttachmentRepository attachments = savingAttachments();
        AttachmentService service = service(attachments, messages, uploadDir);

        service.reuseAttachment("/api/images/7/thumbnail?size=small", "reference.png", "image/png");

        Attachment saved = captureSavedAttachment(attachments);
        assertArrayEquals(thumbnail, Files.readAllBytes(uploadDir.resolve("attachments").resolve(saved.getFilename())));
        assertNotNull(saved.getContentSha256());
    }

    @Test
    void reuseAttachmentThumbnailReferencesExistingThumbnailWithoutCopy() throws Exception {
        Path uploadDir = tempDir.resolve("uploads");
        write(uploadDir.resolve("attachments/source.png"), new byte[]{1, 2, 3});
        write(uploadDir.resolve("attachments/source_thumb_256.png"), new byte[]{4});
        byte[] thumbnail = {5, 6};
        write(uploadDir.resolve("attachments/source_thumb_512.png"), thumbnail);

        Attachment source = new Attachment();
        source.setFileUrl("/api/attachments/source.png");
        AttachmentRepository attachments = savingAttachments();
        when(attachments.findById(9L)).thenReturn(Optional.of(source));

        AttachmentService service = service(attachments, mock(MessageRepository.class), uploadDir);

        service.reuseAttachment("/api/attachments/9/thumbnail?size=medium", "reference.png", "image/png");

        Attachment saved = captureSavedAttachment(attachments);
        // The attachment references the existing thumbnail file in place (no copy).
        assertEquals("source_thumb_512.png", saved.getFilename());
        assertArrayEquals(thumbnail, Files.readAllBytes(uploadDir.resolve("attachments").resolve(saved.getFilename())));
    }

    @Test
    void reuseThumbnailWithoutSizeUsesSmallThumbnail() throws Exception {
        Path uploadDir = tempDir.resolve("uploads");
        write(uploadDir.resolve("generated/image.png"), new byte[]{1, 2, 3});
        byte[] thumbnail = {4, 5};
        write(uploadDir.resolve("generated/image_thumb_256.png"), thumbnail);

        Message message = new Message();
        message.setImageUrl("/api/images/generated/image.png");
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.findById(7L)).thenReturn(Optional.of(message));

        AttachmentRepository attachments = savingAttachments();
        AttachmentService service = service(attachments, messages, uploadDir);

        service.reuseAttachment("/api/images/7/thumbnail", "reference.png", "image/png");

        Attachment saved = captureSavedAttachment(attachments);
        assertArrayEquals(thumbnail, Files.readAllBytes(uploadDir.resolve("attachments").resolve(saved.getFilename())));
    }

    @Test
    void reusePlainAttachmentReferencesExistingFileWithoutCopy() throws Exception {
        Path uploadDir = tempDir.resolve("uploads");
        byte[] content = {10, 20, 30};
        Path sourceFile = write(uploadDir.resolve("attachments/source.png"), content);

        AttachmentRepository attachments = inMemoryAttachments();
        AttachmentService service = service(attachments, mock(MessageRepository.class), uploadDir);

        service.reuseAttachment("/api/attachments/source.png", "ref.png", "image/png");

        Attachment saved = captureSavedAttachment(attachments);
        assertEquals("source.png", saved.getFilename());
        assertEquals("/api/attachments/source.png", saved.getFileUrl());
        // No new file was produced; the attachment directory still holds only the source.
        try (Stream<Path> files = Files.list(uploadDir.resolve("attachments"))) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
        assertArrayEquals(content, Files.readAllBytes(sourceFile));
    }

    @Test
    void uploadDeduplicatesIdenticalContent() throws Exception {
        Path uploadDir = tempDir.resolve("uploads");
        AttachmentRepository attachments = inMemoryAttachments();
        AttachmentService service = service(attachments, mock(MessageRepository.class), uploadDir);

        byte[] content = {10, 20, 30, 40};
        service.uploadBytes("pic.png", "image/png", content);
        service.uploadBytes("pic.png", "image/png", content);

        List<Attachment> saved = capturedSaved(attachments);
        assertEquals(2, saved.size());
        assertNotNull(saved.get(0).getContentSha256());
        assertEquals(saved.get(0).getContentSha256(), saved.get(1).getContentSha256());
        // Both records reference one physical file; only one file exists on disk.
        assertEquals(saved.get(0).getFilename(), saved.get(1).getFilename());
        try (Stream<Path> files = Files.list(uploadDir.resolve("attachments"))) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    private AttachmentService service(
            AttachmentRepository attachments,
            MessageRepository messages,
            Path uploadDir) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.base-dir", tempDir.toString())
                .withProperty("app.upload-dir", uploadDir.toString());
        ResourceAccessService resourceAccessService = new ResourceAccessService(
                mock(AppUserRepository.class), messages, attachments);
        return new AttachmentService(attachments, messages, resourceAccessService, new StoragePaths(environment));
    }

    private static AttachmentRepository savingAttachments() {
        AttachmentRepository attachments = mock(AttachmentRepository.class);
        when(attachments.save(any(Attachment.class))).thenAnswer(invocation -> {
            Attachment attachment = invocation.getArgument(0);
            attachment.setId(1L);
            return attachment;
        });
        return attachments;
    }

    /** A stateful in-memory repository so de-duplication lookups see previously saved rows. */
    private static AttachmentRepository inMemoryAttachments() {
        List<Attachment> saved = new ArrayList<>();
        AttachmentRepository attachments = mock(AttachmentRepository.class);
        when(attachments.save(any(Attachment.class))).thenAnswer(invocation -> {
            Attachment attachment = invocation.getArgument(0);
            if (attachment.getId() == null) {
                attachment.setId((long) (saved.size() + 1));
            }
            saved.add(attachment);
            return attachment;
        });
        when(attachments.findByContentSha256(anyString())).thenAnswer(invocation -> {
            String sha = invocation.getArgument(0);
            return saved.stream().filter(a -> sha.equals(a.getContentSha256())).toList();
        });
        when(attachments.findByFilename(anyString())).thenAnswer(invocation -> {
            String filename = invocation.getArgument(0);
            return saved.stream().filter(a -> filename.equals(a.getFilename())).toList();
        });
        when(attachments.findById(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return saved.stream().filter(a -> id.equals(a.getId())).findFirst().map(Optional::of).orElse(Optional.empty());
        });
        return attachments;
    }

    private static Attachment captureSavedAttachment(AttachmentRepository attachments) {
        return org.mockito.Mockito.mockingDetails(attachments).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("save"))
                .map(invocation -> (Attachment) invocation.getArgument(0))
                .findFirst()
                .orElseThrow();
    }

    private static List<Attachment> capturedSaved(AttachmentRepository attachments) {
        return org.mockito.Mockito.mockingDetails(attachments).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("save"))
                .map(invocation -> (Attachment) invocation.getArgument(0))
                .toList();
    }

    private static Path write(Path path, byte[] content) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.write(path, content);
    }
}
