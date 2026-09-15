package com.gs.ais.integration;

import com.gs.ais.client.LlmClient;
import com.gs.ais.dto.request.DrawRequest;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.service.ImageGenerationService;
import com.gs.ais.service.LlmDebugService;
import com.gs.ais.service.ModelProviderService;
import com.gs.ais.service.SessionProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「实际调用哪个模型」的统一解析链：
 * <b>请求临时值 → 会话值 → 会话所属用户默认值 → 系统启用项</b>。
 *
 * <p>重点是它必须**只看会话所属用户**（{@code session.userId → AppUser}），
 * 不依赖请求上下文里的登录态，并且解析结果会写进消息的模型快照，
 * 从而与前端「会话默认（X）」的显示一致。
 */
@SpringBootTest(properties = "feishu.enabled=false")
@AutoConfigureMockMvc
@Transactional
class ProviderResolutionIntegrationTests {

    private static final String BASE_URL = "https://mock-resolution.test/v1";
    private static final String API_KEY = "test-api-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ImageGenerationService imageGenerationService;

    @Autowired
    private SessionProviderResolver sessionProviderResolver;

    @Autowired
    private ModelProviderService modelProviderService;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private LlmDebugService llmDebugService;

    private MockRestServiceServer server;

    @BeforeEach
    void setUpMockServer() {
        llmDebugService.setEnabled(false);
        llmDebugService.clear();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(llmClient, "restTemplate");
        assertNotNull(restTemplate, "LlmClient must expose an internal RestTemplate for HTTP mocking");
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    }

    @AfterEach
    void tearDown() {
        llmDebugService.setEnabled(false);
        llmDebugService.clear();
    }

    // 1. 会话值为 null + 用户有默认 → 用用户默认，且消息快照就是用户默认。
    @Test
    void sessionWithoutModelUsesOwnerUserDefaultAndRecordsSnapshot() {
        ModelProvider userDefault = saveProvider(ProviderType.CHAT, "resolution-user-default");
        ModelProvider systemActive = saveProvider(ProviderType.CHAT, "resolution-system-active");
        modelProviderService.setActive(systemActive.getId());

        AppUser owner = saveUser(userDefault.getId(), null);
        Session session = saveSession(owner.getId(), null, null);

        expectChat("resolution-user-default", "user default reply");
        imageGenerationService.chat(session.getId(), "使用用户默认模型", List.of(), null);

        Message assistant = assistantMessage(session);
        assertEquals(userDefault.getId(), assistant.getChatProviderId(),
                "会话无显式模型时，实际调用必须是该会话所属用户的默认模型");
        assertEquals(snapshot(userDefault), assistant.getChatProviderName());
        assertEquals(userDefault.getId(), sessionProviderResolver.resolveChat(session, null).getId());
    }

    // 2. 会话值为 null + 用户无默认 → 用系统启用项。
    @Test
    void sessionWithoutModelAndWithoutUserDefaultFallsBackToSystemActive() {
        ModelProvider systemActive = saveProvider(ProviderType.CHAT, "resolution-system-fallback");
        modelProviderService.setActive(systemActive.getId());

        AppUser owner = saveUser(null, null);
        Session session = saveSession(owner.getId(), null, null);

        expectChat("resolution-system-fallback", "system fallback reply");
        imageGenerationService.chat(session.getId(), "使用系统启用项", List.of(), null);

        Message assistant = assistantMessage(session);
        assertEquals(systemActive.getId(), assistant.getChatProviderId());
        assertEquals(snapshot(systemActive), assistant.getChatProviderName());
        assertEquals(systemActive.getId(), sessionProviderResolver.resolveChat(session, null).getId());
    }

    // 3. 会话值非 null → 会话值优先，不被用户默认顶掉。
    @Test
    void sessionModelWinsOverOwnerUserDefault() {
        ModelProvider sessionProvider = saveProvider(ProviderType.CHAT, "resolution-session-model");
        ModelProvider userDefault = saveProvider(ProviderType.CHAT, "resolution-user-default-loses");
        ModelProvider systemActive = saveProvider(ProviderType.CHAT, "resolution-system-loses");
        modelProviderService.setActive(systemActive.getId());

        AppUser owner = saveUser(userDefault.getId(), null);
        Session session = saveSession(owner.getId(), sessionProvider.getId(), null);

        expectChat("resolution-session-model", "session model reply");
        imageGenerationService.chat(session.getId(), "会话模型优先", List.of(), null);

        Message assistant = assistantMessage(session);
        assertEquals(sessionProvider.getId(), assistant.getChatProviderId());
        assertEquals(snapshot(sessionProvider), assistant.getChatProviderName());
    }

    // 4. 请求临时值非 null → 请求值优先（覆盖会话值 / 用户默认 / 系统启用项）。
    @Test
    void requestOverrideWinsOverSessionAndUserDefault() {
        ModelProvider requestProvider = saveProvider(ProviderType.CHAT, "resolution-request-model");
        ModelProvider sessionProvider = saveProvider(ProviderType.CHAT, "resolution-session-loses");
        ModelProvider userDefault = saveProvider(ProviderType.CHAT, "resolution-user-loses");
        ModelProvider systemActive = saveProvider(ProviderType.CHAT, "resolution-system-loses-too");
        modelProviderService.setActive(systemActive.getId());

        AppUser owner = saveUser(userDefault.getId(), null);
        Session session = saveSession(owner.getId(), sessionProvider.getId(), null);

        expectChat("resolution-request-model", "request override reply");
        imageGenerationService.chat(session.getId(), "临时指定模型", List.of(), requestProvider.getId());

        Message assistant = assistantMessage(session);
        assertEquals(requestProvider.getId(), assistant.getChatProviderId());
        assertEquals(snapshot(requestProvider), assistant.getChatProviderName());
        assertEquals(requestProvider.getId(),
                sessionProviderResolver.resolveChat(session, requestProvider.getId()).getId());
    }

    // 4b. 请求体里**显式**传 "chatProviderId": null（不是省略该键）时，
    //     必须继续走会话默认这一环，而不是跳回用户默认 / 系统启用项。
    @Test
    void explicitNullChatProviderIdInRequestBodyDoesNotSkipSessionDefault() throws Exception {
        ModelProvider sessionProvider = saveProvider(ProviderType.CHAT, "resolution-session-explicit-null");
        ModelProvider userDefault = saveProvider(ProviderType.CHAT, "resolution-user-explicit-null-loses");
        ModelProvider systemActive = saveProvider(ProviderType.CHAT, "resolution-system-explicit-null-loses");
        modelProviderService.setActive(systemActive.getId());

        AppUser owner = saveUser(userDefault.getId(), null);
        Session session = saveSession(owner.getId(), sessionProvider.getId(), null);

        expectChat("resolution-session-explicit-null", "explicit null reply");
        mockMvc.perform(post("/api/sessions/" + session.getId() + "/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"显式 null 请求体\",\"chatProviderId\":null}"))
                .andExpect(status().isOk());

        Message assistant = assistantMessage(session);
        assertEquals(sessionProvider.getId(), assistant.getChatProviderId(),
                "显式 null 的请求临时值必须继续走会话默认，不得跳过它");
        assertEquals(snapshot(sessionProvider), assistant.getChatProviderName());
    }

    // 5. 用户默认指向已被删除的 provider → 回退到系统启用项。
    @Test
    void deletedOwnerUserDefaultFallsBackToSystemActive() {
        ModelProvider deletedUserDefault = saveProvider(ProviderType.CHAT, "resolution-deleted-default");
        ModelProvider systemActive = saveProvider(ProviderType.CHAT, "resolution-system-after-delete");
        modelProviderService.setActive(systemActive.getId());

        AppUser owner = saveUser(deletedUserDefault.getId(), null);
        Session session = saveSession(owner.getId(), null, null);

        // 模拟「用户默认指向的模型后来被删除」：用户行仍保存着这个 id。
        modelProviderService.delete(deletedUserDefault.getId());

        expectChat("resolution-system-after-delete", "fallback after delete");
        imageGenerationService.chat(session.getId(), "用户默认已删除", List.of(), null);

        Message assistant = assistantMessage(session);
        assertEquals(systemActive.getId(), assistant.getChatProviderId());
        assertEquals(snapshot(systemActive), assistant.getChatProviderName());
        assertEquals(systemActive.getId(), sessionProviderResolver.resolveChat(session, null).getId());
    }

    // 绘画路径也走同一条链：会话无绘画模型 + 用户有默认 → 出图用用户默认并记录快照。
    @Test
    void drawWithoutImageModelUsesOwnerUserDefaultAndRecordsSnapshot() {
        ModelProvider userDefault = saveProvider(ProviderType.IMAGE, "resolution-draw-user-default");
        ModelProvider systemActive = saveProvider(ProviderType.IMAGE, "resolution-draw-system-active");
        modelProviderService.setActive(systemActive.getId());

        AppUser owner = saveUser(null, userDefault.getId());
        Session session = saveSession(owner.getId(), null, null);

        DrawRequest request = new DrawRequest();
        request.setPrompt("会话无绘画模型时使用用户默认");
        ImageGenerationService.DrawResult result = imageGenerationService.draw(session.getId(), request);

        Message assistant = messageRepository.findById(result.assistantMessageId()).orElseThrow();
        assertEquals(userDefault.getId(), assistant.getDrawProviderId(),
                "绘画提交也必须按 会话 → 用户默认 → 系统启用 解析");
        assertEquals(snapshot(userDefault), assistant.getDrawProviderName());
        assertEquals(userDefault.getId(), sessionProviderResolver.resolveImage(session, null).getId());

        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertEquals(2, messages.size());
        assertEquals(userDefault.getId(), messages.get(0).getDrawProviderId());
        assertEquals(snapshot(userDefault), messages.get(0).getDrawProviderName());
    }

    // ===================== helpers =====================

    private void expectChat(String modelName, String reply) {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"model\":\"" + modelName + "\"")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"%s"}}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """.formatted(reply), MediaType.APPLICATION_JSON));
    }

    private Message assistantMessage(Session session) {
        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertEquals(2, messages.size(), "一次对话应产生 user + assistant 两条消息");
        assertEquals(MessageRole.ASSISTANT, messages.get(1).getRole());
        assertEquals(MessageStatus.SUCCESS, messages.get(1).getStatus());
        return messages.get(1);
    }

    private static String snapshot(ModelProvider provider) {
        return provider.getName() + " / " + provider.getModelName();
    }

    private ModelProvider saveProvider(ProviderType type, String modelName) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ModelProvider provider = new ModelProvider();
        provider.setProviderId("resolution-" + type.name().toLowerCase() + "-" + suffix);
        provider.setName("Resolution " + type);
        provider.setType(type);
        provider.setModelName(modelName);
        provider.setBaseUrl(BASE_URL);
        provider.setApiKey(API_KEY);
        provider.setTimeoutSeconds(ModelProviderDefaults.CHAT_TIMEOUT_SECONDS);
        provider.setActive(false);
        return modelProviderRepository.saveAndFlush(provider);
    }

    private AppUser saveUser(Long defaultChatProviderId, Long defaultImageProviderId) {
        AppUser user = new AppUser();
        user.setUsername("resolution-user-" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("bcrypt-hash");
        user.setDefaultChatProviderId(defaultChatProviderId);
        user.setDefaultImageProviderId(defaultImageProviderId);
        return appUserRepository.saveAndFlush(user);
    }

    private Session saveSession(Long userId, Long chatProviderId, Long imageProviderId) {
        Session session = new Session();
        session.setTitle("provider-resolution-test");
        session.setUserId(userId);
        session.setChatProviderId(chatProviderId);
        session.setImageProviderId(imageProviderId);
        return sessionRepository.saveAndFlush(session);
    }
}
