package com.gs.ais.integration;

import com.gs.ais.dto.request.DataImportRequest;
import com.gs.ais.model.entity.Session;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.service.portability.DataPortabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 备份导入路径必须与正常写入路径走同一套白名单清洗：会话 settings 里的
 * 未知分组 / 未知键 / 非标量值 / 与默认值相同的值都不得入库。
 */
@SpringBootTest(properties = "feishu.enabled=false")
@Transactional
class SessionSettingsImportIntegrationTests {

    @Autowired
    private DataPortabilityService dataPortabilityService;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void importedSessionSettingsAreCleanedLikeNormalWrites() throws Exception {
        String dirtySettings = "{\"draw\":{\"size\":\"16:9\",\"quality\":{\"nested\":1},"
                + "\"format\":\"png\",\"nope\":\"x\"},\"legacy\":{\"a\":1}}";
        String sessionsJson = "[{\"id\":1,\"title\":\"imported-settings-session\",\"settings\":"
                + toJsonString(dirtySettings) + "}]";

        byte[] zip = buildZip(Map.of(
                "manifest.json", "{\"formatVersion\":\"1\",\"sections\":[\"sessions\"]}",
                "data/sessions.json", sessionsJson,
                "data/messages.json", "[]",
                "data/attachments.json", "[]"));

        DataImportRequest request = new DataImportRequest();
        request.setMode("merge");

        dataPortabilityService.importData(
                new MockMultipartFile("file", "export.zip", "application/zip", zip), request);

        Session imported = sessionRepository.findAll().stream()
                .filter(session -> "imported-settings-session".equals(session.getTitle()))
                .findFirst()
                .orElse(null);
        assertNotNull(imported, "导入应创建会话");
        // 只有合法标量且不同于默认值的键被保留：形状与正常写入完全一致。
        assertEquals("{\"draw\":{\"size\":\"16:9\"}}", imported.rawSettings());
    }

    private static String toJsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static byte[] buildZip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Map<String, String> ordered = new LinkedHashMap<>(entries);
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> entry : ordered.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
