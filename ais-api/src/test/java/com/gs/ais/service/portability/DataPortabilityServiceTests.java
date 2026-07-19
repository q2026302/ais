package com.gs.ais.service.portability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.dto.request.DataExportRequest;
import com.gs.ais.model.entity.Session;
import com.gs.ais.repository.ApiProviderRepository;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.repository.SystemModelSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataPortabilityServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void exportIncludesSelectedSectionsOnly() throws Exception {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
        ApiProviderRepository apiProviderRepository = mock(ApiProviderRepository.class);
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        SystemModelSettingsRepository settingsRepository = mock(SystemModelSettingsRepository.class);

        Session session = new Session();
        session.setId(1L);
        session.setTitle("demo");
        session.setAutoTitleEnabled(true);
        when(sessionRepository.findAll()).thenReturn(List.of(session));
        when(messageRepository.findBySessionIdInOrderByCreatedAtAsc(List.of(1L))).thenReturn(List.of());
        when(apiProviderRepository.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(settingsRepository.findById(1L)).thenReturn(java.util.Optional.empty());

        DataPortabilityService service = new DataPortabilityService(
                sessionRepository,
                messageRepository,
                attachmentRepository,
                apiProviderRepository,
                modelProviderRepository,
                settingsRepository,
                new ObjectMapper());

        DataExportRequest request = new DataExportRequest();
        request.setSections(List.of("sessions", "settings"));
        request.setIncludeApiKeys(false);

        DataPortabilityService.ExportResult result = service.exportData(request);
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(result.content()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }

        assertTrue(names.contains("manifest.json"));
        assertTrue(names.contains("data/sessions.json"));
        assertTrue(names.contains("data/messages.json"));
        assertTrue(names.contains("data/attachments.json"));
        assertTrue(names.contains("data/system_settings.json"));
        assertTrue(!names.contains("data/providers.json"));
        assertTrue(new String(result.content(), StandardCharsets.ISO_8859_1).length() > 0);
    }
}
