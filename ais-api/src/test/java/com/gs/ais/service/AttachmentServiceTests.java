package com.gs.ais.service;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Message;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttachmentServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void reuseMessageThumbnailCopiesRequestedSmallThumbnail() throws Exception {
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
    }

    @Test
    void reuseAttachmentThumbnailCopiesRequestedMediumThumbnail() throws Exception {
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

    private AttachmentService service(
            AttachmentRepository attachments,
            MessageRepository messages,
            Path uploadDir) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.base-dir", tempDir.toString())
                .withProperty("app.upload-dir", uploadDir.toString());
        return new AttachmentService(attachments, messages, new StoragePaths(environment));
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

    private static Attachment captureSavedAttachment(AttachmentRepository attachments) {
        return org.mockito.Mockito.mockingDetails(attachments).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("save"))
                .map(invocation -> (Attachment) invocation.getArgument(0))
                .findFirst()
                .orElseThrow();
    }

    private static Path write(Path path, byte[] content) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.write(path, content);
    }
}
