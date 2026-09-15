package com.gs.ais.integration;

import com.gs.ais.model.entity.Session;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.service.SessionService;
import com.gs.ais.settings.SessionSettingsRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话绘画参数持久化（{@code PATCH /api/sessions/{id}/settings}）的端到端契约：
 * 往返持久化、分组内合并不整体替换、显式 null 清空回默认、未识别键被忽略、
 * 未设置时回退注册表默认值，以及 {@code /providers} 委托别名不被破坏。
 */
@SpringBootTest(properties = "feishu.enabled=false")
@AutoConfigureMockMvc
@Transactional
class SessionSettingsIntegrationTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MockMvc mockMvc;

    private Session newSession() {
        return sessionService.createSession("设置测试会话", 1L);
    }

    private Map<String, Object> patchSettings(Long id, String json) throws Exception {
        String body = mockMvc.perform(patch("/api/sessions/" + id + "/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> getSessionJson(Long id) throws Exception {
        String body = mockMvc.perform(get("/api/sessions/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> draw(Map<String, Object> settings) {
        return (Map<String, Object>) settings.get("draw");
    }

    @Test
    void settingsRoundTripThroughDatabaseAndReadEndpoints() throws Exception {
        Session session = newSession();

        Map<String, Object> response = patchSettings(session.getId(),
                "{\"draw\":{\"quality\":\"high\",\"size\":\"1536x1024\"}}");
        assertEquals("high", draw((Map<String, Object>) response.get("settings")).get("quality"));
        assertEquals("1536x1024", draw((Map<String, Object>) response.get("settings")).get("size"));
        // 未出现的键保持注册表默认值。
        assertEquals("png", draw((Map<String, Object>) response.get("settings")).get("format"));

        // 真正落库：清空一级缓存后重新读，稀疏 JSON 仍在。
        sessionRepository.flush();
        entityManager.clear();
        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        Map<String, Object> stored = SessionSettingsRegistry.parse(reloaded.rawSettings());
        assertEquals("high", ((Map<?, ?>) stored.get("draw")).get("quality"));
        assertEquals("1536x1024", ((Map<?, ?>) stored.get("draw")).get("size"));
        assertFalse(stored.containsKey("format"));

        // 单个会话读接口带上 settings（生效值）。
        Map<String, Object> single = getSessionJson(session.getId());
        assertEquals("high", draw((Map<String, Object>) single.get("settings")).get("quality"));
        // 暴露的是解析后的 settings 对象，不是内部存储列 / 字段名。
        assertFalse(single.containsKey("settingsJson"));

        // 会话列表同样带上 settings。
        String listBody = mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> sessions = JSON.readValue(listBody, new TypeReference<List<Map<String, Object>>>() {});
        Map<String, Object> listed = sessions.stream()
                .filter(item -> session.getId().equals(((Number) item.get("id")).longValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("high", draw((Map<String, Object>) listed.get("settings")).get("quality"));
    }

    @Test
    void groupPatchMergesKeyByKeyInsteadOfReplacingTheGroup() throws Exception {
        Session session = newSession();

        patchSettings(session.getId(), "{\"draw\":{\"size\":\"1536x1024\"}}");
        patchSettings(session.getId(), "{\"draw\":{\"quality\":\"high\"}}");

        entityManager.flush();
        entityManager.clear();
        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        Map<String, Object> stored = SessionSettingsRegistry.parse(reloaded.rawSettings());
        assertEquals("1536x1024", ((Map<?, ?>) stored.get("draw")).get("size"));
        assertEquals("high", ((Map<?, ?>) stored.get("draw")).get("quality"));

        entityManager.clear();
        Map<String, Object> settings = (Map<String, Object>) getSessionJson(session.getId()).get("settings");
        assertEquals("png", draw(settings).get("format"));
    }

    @Test
    void explicitNullClearsGroupAndKeyBackToRegistryDefaults() throws Exception {
        Session session = newSession();
        patchSettings(session.getId(), "{\"draw\":{\"size\":\"1536x1024\",\"quality\":\"high\"}}");

        // 分组内 key 为 null：只清该键，其它保留。
        patchSettings(session.getId(), "{\"draw\":{\"size\":null}}");
        entityManager.flush();
        entityManager.clear();
        Session afterKeyReset = sessionRepository.findById(session.getId()).orElseThrow();
        Map<String, Object> stored = SessionSettingsRegistry.parse(afterKeyReset.rawSettings());
        assertFalse(((Map<?, ?>) stored.get("draw")).containsKey("size"));
        assertEquals("high", ((Map<?, ?>) stored.get("draw")).get("quality"));
        assertEquals("1024x1024", draw((Map<String, Object>) getSessionJson(session.getId()).get("settings")).get("size"));

        // 整组显式 null：清空回默认值，列本身也回到 null。
        patchSettings(session.getId(), "{\"draw\":null}");
        entityManager.flush();
        entityManager.clear();
        Session cleared = sessionRepository.findById(session.getId()).orElseThrow();
        assertNull(cleared.rawSettings());
        assertEquals(SessionSettingsRegistry.defaults(),
                getSessionJson(session.getId()).get("settings"));
    }

    @Test
    void unrecognizedKeysAreIgnoredWithoutFailing() throws Exception {
        Session session = newSession();

        // 未注册的分组、未注册的键、非标量值都应被静默忽略。
        Map<String, Object> response = patchSettings(session.getId(),
                "{\"drawQuality\":\"high\",\"chat\":{\"foo\":\"bar\"},\"draw\":{\"nope\":\"x\",\"size\":{\"nested\":1}}}");

        assertTrue(response.containsKey("settings"));
        assertEquals(SessionSettingsRegistry.defaults(), response.get("settings"));
        entityManager.flush();
        entityManager.clear();
        assertNull(sessionRepository.findById(session.getId()).orElseThrow().rawSettings());
    }

    @Test
    void repeatedIllegalPatchesKeepSettingsConsistentWithoutDirtyData() throws Exception {
        Session session = newSession();
        // 先写入一组合法设置。
        patchSettings(session.getId(), "{\"draw\":{\"size\":\"16:9\",\"quality\":\"high\"}}");

        // 连续多次非法补丁：未知分组 / 未知键 / 非标量值（对象、数组）/
        // 非对象分组，都不得污染已存值，也不得在库里留下任何脏键。
        patchSettings(session.getId(), "{\"unregistered\":{\"size\":\"1:1\"},\"drawExtra\":1}");
        patchSettings(session.getId(), "{\"draw\":{\"unknownKey\":\"x\",\"size\":{\"nested\":1}}}");
        patchSettings(session.getId(), "{\"draw\":{\"quality\":[1,2,3]}}");
        patchSettings(session.getId(), "{\"draw\":\"not-a-map\",\"chat\":{\"a\":1}}");

        entityManager.flush();
        entityManager.clear();
        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        Map<String, Object> stored = SessionSettingsRegistry.parse(reloaded.rawSettings());

        // 存储形状与正常写入完全一致：只有 draw 组，且只含合法标量键。
        assertEquals(java.util.Set.of("draw"), stored.keySet());
        Map<?, ?> draw = (Map<?, ?>) stored.get("draw");
        assertEquals(java.util.Set.of("size", "quality"), draw.keySet());
        assertEquals("16:9", draw.get("size"));
        assertEquals("high", draw.get("quality"));

        // 生效值也不含任何脏键，缺失项回注册表默认。
        entityManager.clear();
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) getSessionJson(session.getId()).get("settings");
        assertEquals(java.util.Set.of("draw"), settings.keySet());
        assertEquals(java.util.Set.of("size", "quality", "format"), draw(settings).keySet());
        assertEquals("16:9", draw(settings).get("size"));
        assertEquals("high", draw(settings).get("quality"));
        assertEquals("png", draw(settings).get("format"));
    }

    @Test
    void sessionWithoutSettingsFallsBackToRegistryDefaults() throws Exception {
        Session session = newSession();
        entityManager.flush();
        entityManager.clear();
        assertNull(sessionRepository.findById(session.getId()).orElseThrow().rawSettings());

        Map<String, Object> body = getSessionJson(session.getId());
        assertEquals(SessionSettingsRegistry.defaults(), body.get("settings"));
        Map<String, Object> settings = (Map<String, Object>) body.get("settings");
        assertEquals("1024x1024", draw(settings).get("size"));
        assertEquals("auto", draw(settings).get("quality"));
        assertEquals("png", draw(settings).get("format"));
    }

    @Test
    void providersEndpointRemainsADelegatingAlias() throws Exception {
        Session session = newSession();

        // 旧入口继续可用，并且同样回显 settings。
        String providersBody = mockMvc.perform(patch("/api/sessions/" + session.getId() + "/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatProviderId\":42}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> providersResponse = JSON.readValue(providersBody, new TypeReference<>() {});
        assertEquals(42, ((Number) providersResponse.get("chatProviderId")).intValue());
        assertNotNull(providersResponse.get("settings"));

        // 新入口同时改模型与绘画参数；未出现的 chatProviderId 不动。
        Map<String, Object> response = patchSettings(session.getId(),
                "{\"imageProviderId\":7,\"draw\":{\"format\":\"webp\"}}");
        assertEquals(42, ((Number) response.get("chatProviderId")).intValue());
        assertEquals(7, ((Number) response.get("imageProviderId")).intValue());
        assertEquals("webp", draw((Map<String, Object>) response.get("settings")).get("format"));

        // 显式 null 仍能清空模型回用户/系统默认（containsKey 语义）。
        Map<String, Object> cleared = patchSettings(session.getId(), "{\"chatProviderId\":null}");
        assertNull(cleared.get("chatProviderId"));
        assertEquals("webp", draw((Map<String, Object>) cleared.get("settings")).get("format"));
        assertNotNull(cleared.get("settings"));
    }

    @Test
    void settingsSurviveUnrelatedProviderPatch() throws Exception {
        Session session = newSession();
        patchSettings(session.getId(), "{\"draw\":{\"quality\":\"high\"}}");

        // 只改模型的补丁不会覆盖或清掉绘画参数。
        patchSettings(session.getId(), "{\"imageProviderId\":7}");

        entityManager.flush();
        entityManager.clear();
        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        Map<String, Object> stored = SessionSettingsRegistry.parse(reloaded.rawSettings());
        assertEquals("high", ((Map<?, ?>) stored.get("draw")).get("quality"));
    }
}
