package com.gs.ais.service;

import com.gs.ais.client.LlmClient;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.dto.request.DrawRequest;
import com.gs.ais.model.entity.Session;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the "引用即复用，不产生新附件" invariant: a draw request carrying
 * {@code referenceUrls} (existing server-side files) must not create any attachment
 * record and must not copy the physical file — the source is read in place.
 */
class ImageGenerationQueueServiceTests {

    @TempDir
    Path tempDir;

    private AttachmentRepository attachmentRepository;
    private MessageRepository messageRepository;
    private ImageGenerationQueueService queueService;
    private Path uploadDir;

    @BeforeEach
    void setUp() throws Exception {
        AuthContext.set(new AuthPrincipal(AuthRole.ADMIN, "admin"));

        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.base-dir", tempDir.toString())
                .withProperty("app.upload-dir", "uploads");
        StoragePaths storagePaths = new StoragePaths(environment);
        uploadDir = storagePaths.uploadDir();

        attachmentRepository = mock(AttachmentRepository.class);
        messageRepository = mock(MessageRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);

        ResourceAccessService resourceAccessService = new ResourceAccessService(
                appUserRepository, messageRepository, attachmentRepository);
        AttachmentService attachmentService = new AttachmentService(
                attachmentRepository, messageRepository, resourceAccessService, storagePaths);

        LlmClient llmClient = mock(LlmClient.class);
        ModelProviderService modelProviderService = mock(ModelProviderService.class);
        SessionService sessionService = mock(SessionService.class);
        BillingService billingService = mock(BillingService.class);
        GeneratedImageFileService generatedImageFileService = mock(GeneratedImageFileService.class);

        Session session = new Session();
        session.setId(1L);
        when(sessionService.getSession(1L)).thenReturn(session);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        AtomicLong idSequence = new AtomicLong(1000L);
        when(messageRepository.save(any(com.gs.ais.model.entity.Message.class))).thenAnswer(invocation -> {
            com.gs.ais.model.entity.Message message = invocation.getArgument(0);
            if (message.getId() == null) message.setId(idSequence.incrementAndGet());
            return message;
        });
        when(messageRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(attachmentRepository.findByMessageId(anyLong())).thenReturn(List.of());

        queueService = new ImageGenerationQueueService(
                llmClient, modelProviderService, sessionService, messageRepository,
                attachmentRepository, attachmentService, billingService,
                generatedImageFileService, storagePaths);
    }

    @AfterEach
    void tearDown() {
        if (queueService != null) {
            queueService.shutdown();
        }
        AuthContext.clear();
    }

    @Test
    void referencingExistingFileCreatesNoAttachmentAndNoCopy() throws Exception {
        Path source = write(uploadDir.resolve("generated/history.png"), new byte[]{1, 2, 3, 4});
        long filesBefore = Files.walk(uploadDir).filter(Files::isRegularFile).count();

        DrawRequest request = new DrawRequest();
        request.setPrompt("a redrawn cat");
        request.setReferenceUrls(List.of("/api/images/generated/history.png"));

        Long assistantMessageId = queueService.submitDraw(1L, request, 1L);

        assertNotNull(assistantMessageId);
        // The reuse path must never persist an attachment record.
        verify(attachmentRepository, never()).save(any());
        // The physical file must not be copied or joined by a new sibling.
        long filesAfter = Files.walk(uploadDir).filter(Files::isRegularFile).count();
        assertEquals(filesBefore, filesAfter, "referencing an existing file must not add physical files");
        assertEquals(source, uploadDir.resolve("generated/history.png"));
    }

    private static Path write(Path path, byte[] content) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.write(path, content);
    }
}
