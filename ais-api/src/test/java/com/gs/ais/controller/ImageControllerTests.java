package com.gs.ais.controller;

import com.gs.ais.config.SecurityProperties;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Message;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.security.ResourceUrlSigner;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImageControllerTests {

    @TempDir
    Path tempDir;

    private MessageRepository messageRepository;
    private AttachmentRepository attachmentRepository;
    private ResourceAccessService resourceAccessService;
    private ResourceUrlSigner resourceUrlSigner;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        attachmentRepository = mock(AttachmentRepository.class);
        resourceAccessService = mock(ResourceAccessService.class);
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setTokenSecret("unit-test-secret");
        resourceUrlSigner = new ResourceUrlSigner(securityProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.base-dir", tempDir.toString())
                .withProperty("app.upload-dir", "uploads");
        ImageController controller = new ImageController(
                messageRepository, attachmentRepository, resourceAccessService, resourceUrlSigner,
                new StoragePaths(environment));
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

    @Test
    void signedImageResponseIsPrivatelyCacheableForSignatureValidity() throws Exception {
        write(tempDir.resolve("uploads/generated/x.png"), new byte[]{1, 2, 3});
        when(resourceAccessService.canAccessImageByPath(anyString())).thenReturn(true);
        String signed = resourceUrlSigner.signFor("/api/images/generated/x.png", "alice");

        mockMvc.perform(get("/api/images/generated/x.png").queryParam("sig", extractSig(signed)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("no-store"))));
    }

    @Test
    void signedThumbnailResponseIsPrivatelyCacheable() throws Exception {
        write(tempDir.resolve("uploads/generated/x.png"), new byte[]{1, 2, 3});
        Message message = new Message();
        message.setId(7L);
        message.setImageUrl("/api/images/generated/x.png");
        when(messageRepository.findById(7L)).thenReturn(Optional.of(message));
        when(resourceAccessService.canAccessMessage(message)).thenReturn(true);
        String signed = resourceUrlSigner.signFor("/api/images/7/thumbnail", "alice");

        mockMvc.perform(get("/api/images/7/thumbnail").queryParam("sig", extractSig(signed)).queryParam("size", "small"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("no-store"))));
    }

    @Test
    void publicTestImageKeepsNoStore() throws Exception {
        write(tempDir.resolve("uploads/test_1_20260831_00000000.png"), new byte[]{9});

        mockMvc.perform(get("/api/images/test_1_20260831_00000000.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void forgedSignatureNeverProducesCacheableResponse() throws Exception {
        write(tempDir.resolve("uploads/generated/x.png"), new byte[]{1, 2, 3});
        when(resourceAccessService.canAccessImageByPath(anyString())).thenReturn(true);
        String signed = resourceUrlSigner.signFor("/api/images/generated/x.png", "alice");
        String forged = forgeSig(extractSig(signed));

        mockMvc.perform(get("/api/images/generated/x.png").queryParam("sig", forged))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }

    @Test
    void missingSignatureIsNoStore() throws Exception {
        write(tempDir.resolve("uploads/generated/x.png"), new byte[]{1, 2, 3});
        when(resourceAccessService.canAccessImageByPath(anyString())).thenReturn(true);

        mockMvc.perform(get("/api/images/generated/x.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void maxAgeDoesNotExceedVerifiedRemainingValidity() throws Exception {
        write(tempDir.resolve("uploads/generated/x.png"), new byte[]{1, 2, 3});
        when(resourceAccessService.canAccessImageByPath(anyString())).thenReturn(true);
        String signed = resourceUrlSigner.signFor("/api/images/generated/x.png", "alice");
        String sig = extractSig(signed);
        long verifiedRemaining = resourceUrlSigner.remainingValiditySecondsVerified(
                "/api/images/generated/x.png", sig);

        var result = mockMvc.perform(get("/api/images/generated/x.png").queryParam("sig", sig))
                .andExpect(status().isOk())
                .andReturn();
        String cacheControl = result.getResponse().getHeader("Cache-Control");
        assertTrue(cacheControl != null && cacheControl.contains("private"), "response must be private");
        long maxAge = parseMaxAge(cacheControl);
        assertTrue(maxAge > 0L, "max-age must be positive for a verified signature");
        assertTrue(maxAge <= verifiedRemaining,
                "max-age must not exceed the verified signature's remaining validity");
    }

    private static String forgeSig(String sig) {
        int dot = sig.indexOf('.');
        return sig.substring(0, dot + 1) + "forged-hmac-not-valid";
    }

    private static long parseMaxAge(String cacheControl) {
        for (String directive : cacheControl.split(",")) {
            String trimmed = directive.trim();
            if (trimmed.startsWith("max-age=")) {
                return Long.parseLong(trimmed.substring("max-age=".length()));
            }
        }
        return -1L;
    }

    private static String extractSig(String signed) {
        int idx = signed.indexOf("sig=");
        return signed.substring(idx + "sig=".length());
    }

    private Path write(Path path, byte[] content) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.write(path, content);
    }
}
