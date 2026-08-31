package com.gs.ais.controller;

import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Message;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.service.ResourceAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImageControllerTests {

    @TempDir
    Path tempDir;

    private MessageRepository messageRepository;
    private AttachmentRepository attachmentRepository;
    private ResourceAccessService resourceAccessService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        attachmentRepository = mock(AttachmentRepository.class);
        resourceAccessService = mock(ResourceAccessService.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.base-dir", tempDir.toString())
                .withProperty("app.upload-dir", "uploads");
        ImageController controller = new ImageController(
                messageRepository, attachmentRepository, resourceAccessService, new StoragePaths(environment));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void numericImageThumbnailRoutesToThumbnailHandlerNotCatchAll() throws Exception {
        Path image = write(tempDir.resolve("uploads/generated/x.png"), new byte[]{1, 2, 3});
        Message message = new Message();
        message.setId(7L);
        message.setImageUrl("/api/images/generated/x.png");
        when(messageRepository.findById(7L)).thenReturn(Optional.of(message));
        when(resourceAccessService.canAccessMessage(message)).thenReturn(true);

        mockMvc.perform(get("/api/images/7/thumbnail?size=small"))
                .andExpect(status().isOk());

        verify(resourceAccessService).canAccessMessage(message);
        verify(resourceAccessService, never()).canAccessImageByPath(anyString());
        assertTrue(Files.exists(image));
    }

    @Test
    void nestedImagePathRoutesToFileHandler() throws Exception {
        write(tempDir.resolve("uploads/generated/2026/08/31/abc.png"), new byte[]{9, 9});
        when(resourceAccessService.canAccessImageByPath(anyString())).thenReturn(true);

        mockMvc.perform(get("/api/images/generated/2026/08/31/abc.png"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(resourceAccessService).canAccessImageByPath(captor.capture());
        assertEquals("generated/2026/08/31/abc.png", captor.getValue());
    }

    @Test
    void attachmentRoutesToFileHandler() throws Exception {
        write(tempDir.resolve("uploads/attachments/doc.pdf"), new byte[]{5});
        when(resourceAccessService.canAccessAttachmentByFilename(anyString())).thenReturn(true);

        mockMvc.perform(get("/api/attachments/doc.pdf"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(resourceAccessService).canAccessAttachmentByFilename(captor.capture());
        assertEquals("doc.pdf", captor.getValue());
    }

    @Test
    void attachmentThumbnailResolvesByNumericId() throws Exception {
        write(tempDir.resolve("uploads/attachments/pic.png"), new byte[]{1, 2, 3});
        Attachment attachment = new Attachment();
        attachment.setId(9L);
        attachment.setContentType("image/png");
        attachment.setFileUrl("/api/attachments/pic.png");
        when(attachmentRepository.findById(9L)).thenReturn(Optional.of(attachment));
        when(resourceAccessService.canAccessAttachment(attachment)).thenReturn(true);

        mockMvc.perform(get("/api/attachments/9/thumbnail"))
                .andExpect(status().isOk());

        verify(resourceAccessService).canAccessAttachment(attachment);
    }

    private Path write(Path path, byte[] content) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.write(path, content);
    }
}
