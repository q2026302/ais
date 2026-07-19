package com.gs.ais.integration;

import com.gs.ais.client.LlmClient;
import com.gs.ais.dto.request.DrawRequest;
import com.gs.ais.dto.response.TestConnectionResponse;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.service.ImageGenerationService;
import com.gs.ais.service.LlmDebugService;
import com.gs.ais.service.SessionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest(properties = "feishu.enabled=false")
@Transactional
class ModelConversationAndImageIntegrationTests {

    private static final String BASE_URL = "https://mock-model.test/v1";
    private static final String API_KEY = "test-api-key";
    private static final String ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";
    private static final byte[] ONE_PIXEL_PNG_BYTES = java.util.Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64);

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ImageGenerationService imageGenerationService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private LlmDebugService llmDebugService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private EntityManager entityManager;

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
    void verifyMockServer() {
        server.verify();
        llmDebugService.setEnabled(false);
        llmDebugService.clear();
    }

    @Test
    void runtimeDebugSwitchCapturesCompleteRequestAndResponseWithoutRestart() {
        ModelProvider chatProvider = saveProvider(ProviderType.CHAT, "runtime-debug-model");
        llmDebugService.setEnabled(true);

        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {"message": {"role": "assistant", "content": "debug response"}}
                          ],
                          "usage": {"total_tokens": 3}
                        }
                        """, MediaType.APPLICATION_JSON));

        LlmClient.ChatResult result = llmClient.chat(
                List.of(Map.of("role", "user", "content", "debug request")),
                chatProvider);

        assertEquals("debug response", result.content());
        assertEquals(1, llmDebugService.getStatus().recordCount());
        var summary = llmDebugService.listExchanges(10).getFirst();
        var exchange = llmDebugService.getExchange(summary.id()).orElseThrow();
        assertTrue(exchange.requestBody().contains("debug request"));
        assertTrue(exchange.responseBody().contains("debug response"));
        assertEquals(200, exchange.responseStatus());
        assertEquals(List.of("***REDACTED***"), exchange.requestHeaders().get(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void chatSavesUserAndAssistantMessagesAndParsesTokenUsage() {
        ModelProvider chatProvider = saveProvider(ProviderType.CHAT, "chat-test-model");
        chatProvider.setSystemPrompt("You are the configured conversation assistant.");
        chatProvider.setConfigJson("{\"max_tokens\":2048,\"top_p\":0.8}");
        chatProvider = modelProviderRepository.save(chatProvider);
        Session session = saveSession(chatProvider.getId(), null);

        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(content().string(containsString("\"model\":\"chat-test-model\"")))
                .andExpect(content().string(containsString("You are the configured conversation assistant.")))
                .andExpect(content().string(containsString("\"max_tokens\":2048")))
                .andExpect(content().string(containsString("\"top_p\":0.8")))
                .andExpect(content().string(containsString("\"content\":\"把猫画成油画风格\"")))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {"message": {"role": "assistant", "content": "Use warm oil-paint lighting and a fluffy orange cat."}}
                          ],
                          "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
                        }
                        """, MediaType.APPLICATION_JSON));

        ImageGenerationService.ChatMessageResult result = imageGenerationService.chat(
                session.getId(), "把猫画成油画风格", List.of(), chatProvider.getId());

        assertEquals("Use warm oil-paint lighting and a fluffy orange cat.", result.content());
        assertEquals(18, result.tokenUsage().getTotalTokens());

        flushAndClearPersistenceContext();
        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertEquals(2, messages.size());
        assertEquals(MessageRole.USER, messages.get(0).getRole());
        assertEquals("把猫画成油画风格", messages.get(0).getContent());
        assertEquals(MessageRole.ASSISTANT, messages.get(1).getRole());
        assertEquals("Use warm oil-paint lighting and a fluffy orange cat.", messages.get(1).getContent());
        assertEquals(11, messages.get(1).getPromptTokens());
        assertEquals(7, messages.get(1).getCompletionTokens());
        assertEquals(18, messages.get(1).getTotalTokens());
        assertEquals(messages.get(0).getId(), messages.get(1).getParentMessageId());
    }

    @Test
    void thirdSuccessfulChatGeneratesAndPersistsConciseAutomaticTitle() {
        ModelProvider chatProvider = saveProvider(ProviderType.CHAT, "auto-title-chat-model");
        Session session = sessionService.createSession();
        session.setChatProviderId(chatProvider.getId());
        session = sessionRepository.saveAndFlush(session);

        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(BASE_URL + "/chat/completions"))
                    .andExpect(content().string(not(containsString("会话标题生成器"))))
                    .andRespond(withSuccess("""
                            {"choices":[{"message":{"role":"assistant","content":"常规对话回复"}}]}
                            """, MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(content().string(containsString("会话标题生成器")))
                .andExpect(content().string(containsString("第一轮：讨论猫咪油画的主题")))
                .andExpect(content().string(containsString("第二轮：补充暖色光影")))
                .andExpect(content().string(containsString("第三轮：确定画面构图")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"标题：暖光猫咪油画创作"}}]}
                        """, MediaType.APPLICATION_JSON));

        imageGenerationService.chat(session.getId(), "第一轮：讨论猫咪油画的主题", List.of(), chatProvider.getId());
        imageGenerationService.chat(session.getId(), "第二轮：补充暖色光影", List.of(), chatProvider.getId());
        imageGenerationService.chat(session.getId(), "第三轮：确定画面构图", List.of(), chatProvider.getId());

        flushAndClearPersistenceContext();
        Session savedSession = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals("暖光猫咪油画创作", savedSession.getTitle());
        assertFalse(savedSession.isAutoTitleEnabled());
    }

    @Test
    void chatEmbedsUploadedImageAsBase64DataUrl() throws IOException {
        ModelProvider chatProvider = saveProvider(ProviderType.CHAT, "vision-chat-test-model");
        Session session = saveSession(chatProvider.getId(), null);
        Attachment image = saveReferenceImage();

        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"type\":\"text\"")))
                .andExpect(content().string(containsString("请描述这张图片")))
                .andExpect(content().string(containsString("\"type\":\"image_url\"")))
                .andExpect(content().string(containsString(
                        "data:image/png;base64," + ONE_PIXEL_PNG_BASE64)))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {"message": {"role": "assistant", "content": "这是一张一像素的 PNG 图片。"}}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ImageGenerationService.ChatMessageResult result = imageGenerationService.chat(
                session.getId(), "请描述这张图片", List.of(image.getId()), chatProvider.getId());

        assertEquals(MessageStatus.SUCCESS, result.status());
        assertEquals("这是一张一像素的 PNG 图片。", result.content());

        flushAndClearPersistenceContext();
        Attachment linkedImage = attachmentRepository.findById(image.getId()).orElseThrow();
        assertNotNull(linkedImage.getMessage());
        assertEquals(MessageType.CHAT, linkedImage.getMessage().getMessageType());
        assertEquals(MessageRole.USER, linkedImage.getMessage().getRole());
    }

    @Test
    void resendingHistoricalUserMessageCreatesAndInsertsANewReply() {
        ModelProvider chatProvider = saveProvider(ProviderType.CHAT, "chat-test-model");
        Session session = saveSession(chatProvider.getId(), null);

        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(content().string(containsString("第一问")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"第一答"}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(content().string(containsString("第二问")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"第二答"}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(content().string(containsString("第一问")))
                .andExpect(content().string(not(containsString("第二问"))))
                .andExpect(content().string(not(containsString("第一答"))))
                .andRespond(withSuccess("""
                        {
                          "choices":[{"message":{"role":"assistant","content":"第一问的新回答"}}],
                          "usage":{"prompt_tokens":5,"completion_tokens":4,"total_tokens":9}
                        }
                        """, MediaType.APPLICATION_JSON));

        imageGenerationService.chat(session.getId(), "第一问", List.of(), chatProvider.getId());
        imageGenerationService.chat(session.getId(), "第二问", List.of(), chatProvider.getId());

        Message firstUserMessage = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .filter(message -> message.getRole() == MessageRole.USER)
                .findFirst()
                .orElseThrow();

        ImageGenerationService.GenerationResult result = imageGenerationService.regenerateMessage(
                session.getId(), firstUserMessage.getId());

        assertNotNull(result.messageId());
        assertEquals("第一问的新回答", result.optimizedPrompt());
        assertEquals(9, result.tokenUsage().getTotalTokens());

        flushAndClearPersistenceContext();
        List<Message> displayMessages = imageGenerationService.getMessages(session.getId());
        assertEquals(5, displayMessages.size());
        assertEquals("第一问", displayMessages.get(0).getContent());
        assertEquals("第一答", displayMessages.get(1).getContent());
        assertEquals("第一问的新回答", displayMessages.get(2).getContent());
        assertEquals(firstUserMessage.getId(), displayMessages.get(2).getParentMessageId());
        assertEquals("第二问", displayMessages.get(3).getContent());
        assertEquals("第二答", displayMessages.get(4).getContent());
    }

    @Test
    void resendingHistoricalUserMessageCanTemporarilyOverrideChatModel() {
        ModelProvider defaultProvider = saveProvider(ProviderType.CHAT, "chat-default-model");
        ModelProvider temporaryProvider = saveProvider(ProviderType.CHAT, "chat-temporary-model");
        Session session = saveSession(defaultProvider.getId(), null);

        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(content().string(containsString("\"model\":\"chat-default-model\"")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"默认模型回答"}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(content().string(containsString("\"model\":\"chat-temporary-model\"")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"临时模型回答"}}]}
                        """, MediaType.APPLICATION_JSON));

        imageGenerationService.chat(session.getId(), "使用临时模型再次发送", List.of(), defaultProvider.getId());
        Message userMessage = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .filter(message -> message.getRole() == MessageRole.USER)
                .findFirst()
                .orElseThrow();

        ImageGenerationService.GenerationResult result = imageGenerationService.regenerateMessage(
                session.getId(), userMessage.getId(), temporaryProvider.getId(), null);

        assertEquals("临时模型回答", result.optimizedPrompt());
        flushAndClearPersistenceContext();
        Session persistedSession = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(defaultProvider.getId(), persistedSession.getChatProviderId());

        List<Message> messages = imageGenerationService.getMessages(session.getId());
        assertEquals(3, messages.size());
        assertEquals("默认模型回答", messages.get(1).getContent());
        assertEquals("临时模型回答", messages.get(2).getContent());
        assertEquals(userMessage.getId(), messages.get(2).getParentMessageId());
    }

    @Test
    void regeneratingImageCanTemporarilyOverrideImageModel() {
        ModelProvider defaultProvider = saveProvider(ProviderType.IMAGE, "gpt-image-default-model");
        ModelProvider temporaryProvider = saveProvider(ProviderType.IMAGE, "gpt-image-temporary-model");
        Session session = saveSession(null, defaultProvider.getId());

        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(content().string(containsString("\"model\":\"gpt-image-default-model\"")))
                .andRespond(withSuccess("""
                        {"data":[{"b64_json":"%s"}]}
                        """.formatted(ONE_PIXEL_PNG_BASE64), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(content().string(containsString("\"model\":\"gpt-image-temporary-model\"")))
                .andRespond(withSuccess("""
                        {"data":[{"b64_json":"%s"}]}
                        """.formatted(ONE_PIXEL_PNG_BASE64), MediaType.APPLICATION_JSON));

        DrawRequest request = new DrawRequest();
        request.setPrompt("临时切换绘图模型");
        request.setImageProviderId(defaultProvider.getId());
        request.setSize("1024x1024");
        ImageGenerationService.DrawResult drawResult = imageGenerationService.draw(session.getId(), request);

        Message assistantMessage = messageRepository.findById(drawResult.assistantMessageId()).orElseThrow();
        ImageGenerationService.GenerationResult regenerateResult = imageGenerationService.regenerateMessage(
                session.getId(), assistantMessage.getId(), null, temporaryProvider.getId());

        assertNotNull(regenerateResult.imageUrl());
        flushAndClearPersistenceContext();
        Session persistedSession = sessionRepository.findById(session.getId()).orElseThrow();
        Message regeneratedMessage = messageRepository.findById(assistantMessage.getId()).orElseThrow();
        assertEquals(defaultProvider.getId(), persistedSession.getImageProviderId());
        assertEquals(temporaryProvider.getId(), regeneratedMessage.getDrawProviderId());
        assertEquals(MessageStatus.SUCCESS, regeneratedMessage.getStatus());
        assertSavedImageExists(regeneratedMessage.getImageUrl());
    }

    @Test
    void drawWithoutReferenceUsesGenerationsEndpointAndSavesGeneratedImage() {
        ModelProvider imageProvider = saveProvider(ProviderType.IMAGE, "gpt-image-test");
        imageProvider.setConfigJson("{\"background\":\"transparent\"}");
        imageProvider = modelProviderRepository.save(imageProvider);
        Session session = saveSession(null, imageProvider.getId());

        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(content().string(containsString("\"model\":\"gpt-image-test\"")))
                .andExpect(content().string(containsString("\"prompt\":\"一只水彩风格的猫\"")))
                .andExpect(content().string(containsString("\"size\":\"1024x1024\"")))
                .andExpect(content().string(containsString("\"quality\":\"high\"")))
                .andExpect(content().string(containsString("\"output_format\":\"png\"")))
                .andExpect(content().string(containsString("\"response_format\":\"b64_json\"")))
                .andExpect(content().string(containsString("\"background\":\"transparent\"")))
                .andRespond(withSuccess("""
                        {"data": [{"b64_json": "%s"}]}
                        """.formatted(ONE_PIXEL_PNG_BASE64), MediaType.APPLICATION_JSON));

        DrawRequest request = new DrawRequest();
        request.setPrompt("一只水彩风格的猫");
        request.setImageProviderId(imageProvider.getId());
        request.setSize("1024x1024");
        request.setQuality("high");
        request.setFormat("png");

        ImageGenerationService.DrawResult result = imageGenerationService.draw(session.getId(), request);

        assertEquals("一只水彩风格的猫", result.prompt());
        assertEquals(MessageStatus.SUCCESS, result.status());
        assertNull(result.errorMessage());
        assertNotNull(result.assistantMessageId());
        assertTrue(result.imageUrl().startsWith("/api/images/"));
        assertSavedImageExists(result.imageUrl());

        flushAndClearPersistenceContext();
        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertEquals(2, messages.size());
        assertEquals(MessageRole.USER, messages.get(0).getRole());
        assertEquals(MessageType.DRAW_REQUEST, messages.get(0).getMessageType());
        assertEquals(MessageStatus.SUCCESS, messages.get(0).getStatus());
        assertEquals("一只水彩风格的猫", messages.get(0).getDrawPrompt());
        assertEquals("1024x1024", messages.get(0).getDrawSize());
        assertEquals("high", messages.get(0).getDrawQuality());
        assertEquals("png", messages.get(0).getDrawFormat());
        assertEquals(imageProvider.getId(), messages.get(0).getDrawProviderId());
        assertTrue(messages.get(0).getContent().contains("绘画提示词：一只水彩风格的猫"));
        assertTrue(messages.get(0).getContent().contains("尺寸 1024x1024"));
        assertEquals(MessageRole.ASSISTANT, messages.get(1).getRole());
        assertEquals(MessageType.DRAW_RESPONSE, messages.get(1).getMessageType());
        assertEquals(MessageStatus.SUCCESS, messages.get(1).getStatus());
        assertEquals(result.assistantMessageId(), messages.get(1).getId());
        assertEquals(result.imageUrl(), messages.get(1).getImageUrl());
        assertEquals(messages.get(0).getId(), messages.get(1).getParentMessageId());
    }

    @Test
    void drawWithReferenceUsesEditsEndpointAndDownloadsReturnedImageUrl() throws IOException {
        ModelProvider imageProvider = saveProvider(ProviderType.IMAGE, "gpt-image-test");
        Session session = saveSession(null, imageProvider.getId());
        Attachment reference = saveReferenceImage();

        server.expect(requestTo(BASE_URL + "/images/edits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(content().string(containsString("name=\"model\"")))
                .andExpect(content().string(containsString("gpt-image-test")))
                .andExpect(content().string(containsString("name=\"prompt\"")))
                .andExpect(content().string(containsString("用参考图生成海报")))
                .andExpect(content().string(containsString("name=\"image\"")))
                .andExpect(content().string(containsString("reference.png")))
                .andExpect(content().string(containsString("name=\"size\"")))
                .andExpect(content().string(containsString("1536x1024")))
                .andExpect(content().string(containsString("name=\"quality\"")))
                .andExpect(content().string(containsString("high")))
                .andExpect(content().string(containsString("name=\"output_format\"")))
                .andExpect(content().string(containsString("webp")))
                .andExpect(content().string(containsString("name=\"response_format\"")))
                .andExpect(content().string(containsString("b64_json")))
                .andRespond(withSuccess("""
                        {"data": [{"url": "https://cdn.mock-model.test/generated.png"}]}
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://cdn.mock-model.test/generated.png"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ONE_PIXEL_PNG_BYTES, MediaType.IMAGE_PNG));

        DrawRequest request = new DrawRequest();
        request.setPrompt("用参考图生成海报");
        request.setAttachmentIds(List.of(reference.getId()));
        request.setImageProviderId(imageProvider.getId());
        request.setSize("1536x1024");
        request.setQuality("high");
        request.setFormat("webp");

        ImageGenerationService.DrawResult result = imageGenerationService.draw(session.getId(), request);

        assertEquals(MessageStatus.SUCCESS, result.status());
        assertNull(result.errorMessage());
        assertNotNull(result.assistantMessageId());
        assertTrue(result.imageUrl().startsWith("/api/images/"));
        assertSavedImageExists(result.imageUrl());

        flushAndClearPersistenceContext();
        Attachment linkedReference = attachmentRepository.findById(reference.getId()).orElseThrow();
        assertNotNull(linkedReference.getMessage());
        assertEquals(MessageRole.USER, linkedReference.getMessage().getRole());

        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertEquals(2, messages.size());
        assertEquals(MessageType.DRAW_REQUEST, messages.get(0).getMessageType());
        assertEquals(MessageStatus.SUCCESS, messages.get(0).getStatus());
        assertEquals("用参考图生成海报", messages.get(0).getDrawPrompt());
        assertEquals("1536x1024", messages.get(0).getDrawSize());
        assertEquals("high", messages.get(0).getDrawQuality());
        assertEquals("webp", messages.get(0).getDrawFormat());
        assertFalse(messages.get(0).getAttachments().isEmpty());
        assertEquals(MessageType.DRAW_RESPONSE, messages.get(1).getMessageType());
        assertEquals(MessageStatus.SUCCESS, messages.get(1).getStatus());
        assertEquals(result.assistantMessageId(), messages.get(1).getId());
        assertEquals(result.imageUrl(), messages.get(1).getImageUrl());
    }

    @Test
    void grsaiConnectionTestAcceptsStructuredMissingTaskIdResponse() {
        server.expect(requestTo("https://mock-model.test/v1/api/result"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":400,\"msg\":\"id不能为空\"}"));

        TestConnectionResponse result = llmClient.testGrsaiConnection(
                "https://mock-model.test", API_KEY);

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("连接成功"));
    }

    @Test
    void grsaiConnectionTestRejectsInvalidApiKey() {
        server.expect(requestTo("https://mock-model.test/v1/api/result"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":401,\"msg\":\"API key 无效\"}"));

        TestConnectionResponse result = llmClient.testGrsaiConnection(
                "https://mock-model.test", API_KEY);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("API Key"));
    }

    @Test
    void grsaiConnectionTestDoesNotAcceptHtmlNotFoundPage() {
        server.expect(requestTo("https://wrong-grsai.test/v1/api/result"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html><body>404 Not Found</body></html>"));

        TestConnectionResponse result = llmClient.testGrsaiConnection(
                "https://wrong-grsai.test", API_KEY);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("HTTP 404"));
    }

    @Test
    void grsaiNanoBananaUsesDocumentedFieldsAndBase64ReferenceImages() {
        ModelProvider imageProvider = saveProvider(ProviderType.IMAGE, "nano-banana-2");
        imageProvider.setBaseUrl("https://mock-model.test");
        imageProvider.setAdapterType("GRS_AI");
        imageProvider = modelProviderRepository.saveAndFlush(imageProvider);

        server.expect(requestTo("https://mock-model.test/v1/api/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(content().string(containsString("\"model\":\"nano-banana-2\"")))
                .andExpect(content().string(containsString("\"aspectRatio\":\"1:1\"")))
                .andExpect(content().string(containsString("\"replyType\":\"json\"")))
                .andExpect(content().string(containsString("\"images\":[")))
                .andExpect(content().string(not(containsString("\"urls\""))))
                .andExpect(content().string(containsString("\"imageSize\":\"2K\"")))
                .andExpect(content().string(containsString(
                        "data:image/png;base64," + ONE_PIXEL_PNG_BASE64)))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": {
                            "id": "task-sync",
                            "status": "succeeded",
                            "results": [{"url": "data:image/png;base64,%s"}]
                          }
                        }
                        """.formatted(ONE_PIXEL_PNG_BASE64), MediaType.APPLICATION_JSON));

        byte[] result = llmClient.generateImage(
                "把参考图改成海报",
                imageProvider,
                new LlmClient.ImageGenerationOptions("1:1", "2K", "png"),
                List.of(new LlmClient.ReferenceImage(
                        "reference.png", MediaType.IMAGE_PNG_VALUE, ONE_PIXEL_PNG_BYTES)));

        assertArrayEquals(ONE_PIXEL_PNG_BYTES, result);
        server.verify();
    }

    @Test
    void grsaiDownloadsTopLevelSucceededResultWithoutPolling() {
        ModelProvider imageProvider = saveProvider(ProviderType.IMAGE, "nano-banana-pro");
        imageProvider.setBaseUrl("https://mock-model.test");
        imageProvider.setAdapterType("GRS_AI");
        imageProvider = modelProviderRepository.saveAndFlush(imageProvider);

        String resultImageUrl = "https://file1.aitohumanize.com/file/generated.jpeg";
        server.expect(requestTo("https://mock-model.test/v1/api/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"replyType\":\"json\"")))
                .andExpect(content().string(containsString("\"images\":[]")))
                .andExpect(content().string(containsString("\"aspectRatio\":\"auto\"")))
                .andRespond(withSuccess("""
                        {
                          "id":"3-70ec4e47-e033-4894-91c2-506dc3082e81",
                          "status":"succeeded",
                          "results":[{"url":"%s"}],
                          "progress":100
                        }
                        """.formatted(resultImageUrl), MediaType.APPLICATION_JSON));
        server.expect(requestTo(resultImageUrl))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ONE_PIXEL_PNG_BYTES, MediaType.IMAGE_JPEG));

        byte[] result = llmClient.generateImage("生成一张图片", imageProvider);

        assertArrayEquals(ONE_PIXEL_PNG_BYTES, result);
        server.verify();
    }

    @Test
    void grsaiPollsResultEndpointUntilAsyncTaskSucceeds() {
        ModelProvider imageProvider = saveProvider(ProviderType.IMAGE, "gpt-image-2");
        imageProvider.setBaseUrl("https://mock-model.test");
        imageProvider.setAdapterType("GRS_AI");
        imageProvider.setConfigJson("{\"grsaiPollIntervalMillis\":100,\"replyType\":\"async\"}");
        imageProvider = modelProviderRepository.saveAndFlush(imageProvider);

        server.expect(requestTo("https://mock-model.test/v1/api/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(not(containsString("grsaiPollIntervalMillis"))))
                .andExpect(content().string(containsString("\"images\":[]")))
                .andExpect(content().string(containsString("\"aspectRatio\":\"1536x1024\"")))
                .andExpect(content().string(containsString("\"replyType\":\"async\"")))
                .andExpect(content().string(not(containsString("\"imageSize\""))))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"id":"task-async","status":"running"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://mock-model.test/v1/api/result?id=task-async"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"id":"task-async","status":"succeeded",
                        "results":[{"url":"data:image/png;base64,%s"}]}}
                        """.formatted(ONE_PIXEL_PNG_BASE64), MediaType.APPLICATION_JSON));

        byte[] result = llmClient.generateImage(
                "生成一张海报", imageProvider,
                new LlmClient.ImageGenerationOptions("1536x1024", "high", "png"),
                List.of());

        assertArrayEquals(ONE_PIXEL_PNG_BYTES, result);
        server.verify();
    }

    @Test
    void grsaiSurfacesUpstreamErrorMessage() {
        ModelProvider imageProvider = saveProvider(ProviderType.IMAGE, "nano-banana-fast");
        imageProvider.setBaseUrl("https://mock-model.test");
        imageProvider.setAdapterType("GRS_AI");
        imageProvider = modelProviderRepository.saveAndFlush(imageProvider);
        ModelProvider failedProvider = imageProvider;

        server.expect(requestTo("https://mock-model.test/v1/api/generate"))
                .andRespond(withSuccess("""
                        {"code":1001,"msg":"API key 无效"}
                        """, MediaType.APPLICATION_JSON));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> llmClient.generateImage("测试", failedProvider));
        assertTrue(error.getMessage().contains("API key 无效"));
        server.verify();
    }

    @Test
    void geminiGatewayUsesV1BetaBearerAuthAndInlineReferenceImage() {
        ModelProvider imageProvider = saveProvider(ProviderType.IMAGE, "gemini-2.5-flash-image");
        imageProvider.setBaseUrl("https://mock-gemini.test");
        imageProvider.setAdapterType("GEMINI_IMAGE");
        imageProvider = modelProviderRepository.saveAndFlush(imageProvider);

        server.expect(requestTo(
                        "https://mock-gemini.test/v1beta/models/gemini-2.5-flash-image:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(content().string(containsString("\"text\":\"保持主体并更换背景\"")))
                .andExpect(content().string(containsString("\"inline_data\"")))
                .andExpect(content().string(containsString("\"mime_type\":\"image/png\"")))
                .andExpect(content().string(containsString("\"data\":\"" + ONE_PIXEL_PNG_BASE64 + "\"")))
                .andExpect(content().string(containsString("\"responseModalities\":[\"TEXT\",\"IMAGE\"]")))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{
                            "content": {"parts": [{
                              "inlineData": {
                                "mimeType": "image/png",
                                "data": "data:image/png;base64,%s"
                              }
                            }]}
                          }]
                        }
                        """.formatted(ONE_PIXEL_PNG_BASE64), MediaType.APPLICATION_JSON));

        byte[] result = llmClient.generateImage(
                "保持主体并更换背景",
                imageProvider,
                new LlmClient.ImageGenerationOptions("1:1", "1K", "png"),
                List.of(new LlmClient.ReferenceImage(
                        "reference.png", MediaType.IMAGE_PNG_VALUE, ONE_PIXEL_PNG_BYTES)));

        assertArrayEquals(ONE_PIXEL_PNG_BYTES, result);
    }


    @Test
    void failedDrawPersistsRequestAndFailedAssistantResponseInConversation() {
        ModelProvider imageProvider = saveProvider(ProviderType.IMAGE, "gpt-image-test");
        Session session = saveSession(null, imageProvider.getId());

        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"temporary upstream failure\",\"type\":\"upstream_error\"}}"));

        DrawRequest request = new DrawRequest();
        request.setPrompt("生成失败也要进入会话");
        request.setImageProviderId(imageProvider.getId());
        request.setSize("1024x1024");

        ImageGenerationService.DrawResult result = imageGenerationService.draw(session.getId(), request);

        assertEquals(MessageStatus.FAILED, result.status());
        assertNull(result.imageUrl());
        assertNotNull(result.assistantMessageId());
        assertTrue(result.errorMessage().contains("temporary upstream failure"));
        assertTrue(result.errorMessage().contains("HTTP 502"));

        flushAndClearPersistenceContext();
        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertEquals(2, messages.size());
        assertEquals(MessageType.DRAW_REQUEST, messages.get(0).getMessageType());
        assertEquals(MessageStatus.SUCCESS, messages.get(0).getStatus());
        assertEquals(MessageType.DRAW_RESPONSE, messages.get(1).getMessageType());
        assertEquals(MessageStatus.FAILED, messages.get(1).getStatus());
        assertNotNull(messages.get(1).getErrorMessage());
        assertTrue(messages.get(1).getErrorMessage().contains("temporary upstream failure"));
        assertTrue(messages.get(1).getErrorMessage().contains("type: upstream_error"));
        assertEquals(messages.get(0).getId(), messages.get(1).getParentMessageId());
    }

    private void flushAndClearPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }

    private ModelProvider saveProvider(ProviderType type, String modelName) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ModelProvider provider = new ModelProvider();
        provider.setProviderId("it-" + type.name().toLowerCase() + "-" + suffix);
        provider.setName("Integration Test " + type);
        provider.setType(type);
        provider.setModelName(modelName);
        provider.setBaseUrl(BASE_URL);
        provider.setApiKey(API_KEY);
        provider.setTimeoutSeconds(ModelProviderDefaults.CHAT_TIMEOUT_SECONDS);
        if (type == ProviderType.IMAGE) {
            provider.setMaxRetries(0);
            provider.setRetryBackoffSeconds(1);
        }
        provider.setActive(false);
        return modelProviderRepository.saveAndFlush(provider);
    }

    private Session saveSession(Long chatProviderId, Long imageProviderId) {
        Session session = new Session();
        session.setTitle("integration-test");
        session.setChatProviderId(chatProviderId);
        session.setImageProviderId(imageProviderId);
        return sessionRepository.saveAndFlush(session);
    }

    private Attachment saveReferenceImage() throws IOException {
        Path dir = Paths.get("uploads", "attachments");
        Files.createDirectories(dir);
        String filename = "it-reference-" + UUID.randomUUID() + ".png";
        Files.write(dir.resolve(filename), ONE_PIXEL_PNG_BYTES);

        Attachment attachment = new Attachment();
        attachment.setFilename(filename);
        attachment.setOriginalName("reference.png");
        attachment.setContentType(MediaType.IMAGE_PNG_VALUE);
        attachment.setFileSize((long) ONE_PIXEL_PNG_BYTES.length);
        attachment.setFileUrl("/api/attachments/" + filename);
        return attachmentRepository.saveAndFlush(attachment);
    }

    private void assertSavedImageExists(String imageUrl) {
        String filename = imageUrl.replace("/api/images/", "");
        Path path = Paths.get("uploads").resolve(filename);
        assertTrue(Files.exists(path), () -> "Generated image file should exist: " + path);
    }
}
