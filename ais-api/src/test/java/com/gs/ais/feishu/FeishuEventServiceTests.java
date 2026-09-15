package com.gs.ais.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.config.FeishuProperties;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.service.AttachmentService;
import com.gs.ais.service.BillingService;
import com.gs.ais.service.ImageGenerationService;
import com.gs.ais.service.ModelProviderService;
import com.gs.ais.service.SessionService;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuEventServiceTests {

    @Test
    void returnsChallengeAfterVerificationTokenCheck() {
        FeishuProperties properties = new FeishuProperties();
        properties.setVerificationToken("verify-me");
        FeishuEventService service = service(properties);

        FeishuEventService.CallbackResponse response = service.acceptWebhook(
                "{\"type\":\"url_verification\",\"token\":\"verify-me\",\"challenge\":\"challenge-value\"}",
                new HttpHeaders());

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("challenge-value", response.body().get("challenge"));
    }

    @Test
    void rejectsCallbackWithWrongVerificationToken() {
        FeishuProperties properties = new FeishuProperties();
        properties.setVerificationToken("verify-me");
        FeishuEventService service = service(properties);

        FeishuEventService.FeishuWebhookException exception = assertThrows(
                FeishuEventService.FeishuWebhookException.class,
                () -> service.acceptWebhook("{\"type\":\"url_verification\",\"token\":\"wrong\",\"challenge\":\"x\"}", new HttpHeaders()));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void deduplicatesRedeliveredMessagesByMessageId() {
        FeishuProperties properties = new FeishuProperties();
        FeishuApiClient client = mock(FeishuApiClient.class);
        TaskExecutor executor = Runnable::run;
        FeishuEventService service = new FeishuEventService(
                properties, new ObjectMapper(), executor, client,
                mock(SessionService.class), mock(AttachmentService.class), mock(ImageGenerationService.class),
                mock(BillingService.class), mock(ModelProviderService.class));
        String firstDelivery = "{\"header\":{\"event_type\":\"im.message.receive_v1\",\"event_id\":\"evt-first\"},"
                + "\"event\":{\"sender\":{\"sender_type\":\"user\"},\"message\":{\"message_id\":\"om-same\","
                + "\"chat_id\":\"oc-1\",\"message_type\":\"text\",\"content\":\"{\\\"text\\\":\\\"帮助\\\"}\"}}}";
        String redelivery = firstDelivery.replace("evt-first", "evt-redelivered");

        service.acceptWebhook(firstDelivery, new HttpHeaders());
        service.acceptWebhook(redelivery, new HttpHeaders());

        verify(client, times(1)).replyText(org.mockito.ArgumentMatchers.eq("om-same"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deduplicatesWebSocketAndHttpDeliveriesByMessageId() {
        FeishuProperties properties = new FeishuProperties();
        FeishuApiClient client = mock(FeishuApiClient.class);
        FeishuEventService service = new FeishuEventService(
                properties, new ObjectMapper(), Runnable::run, client,
                mock(SessionService.class), mock(AttachmentService.class), mock(ImageGenerationService.class),
                mock(BillingService.class), mock(ModelProviderService.class));
        String httpDelivery = "{\"header\":{\"event_type\":\"im.message.receive_v1\",\"event_id\":\"evt-http\"},"
                + "\"event\":{\"sender\":{\"sender_type\":\"user\"},\"message\":{\"message_id\":\"om-shared\","
                + "\"chat_id\":\"oc-1\",\"message_type\":\"text\",\"content\":\"{\\\"text\\\":\\\"帮助\\\"}\"}}}";

        service.acceptWebhook(httpDelivery, new HttpHeaders());
        service.acceptWebSocketMessage(webSocketTextEvent("evt-ws", "om-shared", "oc-1", "帮助"));

        verify(client, times(1)).replyText(org.mockito.ArgumentMatchers.eq("om-shared"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void defaultsToWebSocketTransportAndAllowsHttpCompatibilityMode() {
        FeishuProperties properties = new FeishuProperties();
        assertTrue(properties.isWebSocketMode());
        assertTrue(properties.hasSupportedTransport());

        properties.setTransport("http");
        assertTrue(properties.isHttpMode());
        assertFalse(properties.isWebSocketMode());
    }

    @Test
    void recognizesSupportedDrawCommandsWithoutTreatingSimilarWordsAsCommands() {
        assertEquals(Optional.of("一只橘猫"), FeishuEventService.parseDrawPrompt("/draw 一只橘猫"));
        assertEquals(Optional.of("雨夜城市"), FeishuEventService.parseDrawPrompt("绘图：雨夜城市"));
        assertEquals(Optional.of(""), FeishuEventService.parseDrawPrompt("/image"));
        assertTrue(FeishuEventService.parseDrawPrompt("生图 太空站").isPresent());
        assertFalse(FeishuEventService.parseDrawPrompt("/drawings are fun").isPresent());
    }

    @Test
    void recognizesHelpCommands() {
        assertTrue(FeishuEventService.isHelpCommand(" /HELP "));
        assertTrue(FeishuEventService.isHelpCommand("帮助"));
        assertFalse(FeishuEventService.isHelpCommand("帮我画图"));
    }

    @Test
    void recognizesModelCommandsWithoutSwallowingNormalText() {
        assertTrue(FeishuEventService.isModelsCommand("/models"));
        assertTrue(FeishuEventService.isModelsCommand(" 模型列表 "));
        assertFalse(FeishuEventService.isModelsCommand("/model 3"));
        assertFalse(FeishuEventService.isModelsCommand("模型怎么选"));

        assertEquals("", FeishuEventService.parseModelCommand("/model").orElseThrow());
        assertEquals("3", FeishuEventService.parseModelCommand("/model 3").orElseThrow());
        assertEquals("#7", FeishuEventService.parseModelCommand("/MODEL   #7 ").orElseThrow());
        assertTrue(FeishuEventService.parseModelCommand("/models").isEmpty());
        assertTrue(FeishuEventService.parseModelCommand("模型怎么选").isEmpty());
    }

    @Test
    void modelsCommandRepliesWithAvailableChatModelsAndHelpTip() {
        FeishuApiClient client = mock(FeishuApiClient.class);
        ModelProviderService modelProviderService = mock(ModelProviderService.class);
        ModelProvider defaultProvider = chatProvider(3L, "OpenAI", "gpt-4o", true);
        ModelProvider secondProvider = chatProvider(4L, "Anthropic", "claude-3", false);
        when(modelProviderService.getAll(ProviderType.CHAT))
                .thenReturn(java.util.List.of(defaultProvider, secondProvider));
        FeishuEventService service = service(new FeishuProperties(), client,
                mock(SessionService.class), modelProviderService);

        service.acceptWebSocketMessage(webSocketTextEvent("evt-models", "om-models", "oc-1", "/models"));

        verify(client).replyText(eq("om-models"), contains("#3"));
        verify(client).replyText(eq("om-models"), contains("OpenAI / gpt-4o"));
        verify(client).replyText(eq("om-models"), contains("系统默认"));
        verify(client).replyText(eq("om-models"), contains("/model <模型ID>"));
    }

    @Test
    void helpTextMentionsTextChannelModelCommands() {
        FeishuApiClient client = mock(FeishuApiClient.class);
        FeishuEventService service = service(new FeishuProperties(), client,
                mock(SessionService.class), mock(ModelProviderService.class));

        service.acceptWebSocketMessage(webSocketTextEvent("evt-help", "om-help", "oc-1", "帮助"));

        verify(client).replyText(eq("om-help"), contains("/models"));
        verify(client).replyText(eq("om-help"), contains("/model <模型ID>"));
    }

    @Test
    void modelCommandSwitchesSessionChatModelThroughSessionWrite() {
        FeishuApiClient client = mock(FeishuApiClient.class);
        SessionService sessionService = mock(SessionService.class);
        ModelProviderService modelProviderService = mock(ModelProviderService.class);
        Session session = mock(Session.class);
        when(session.getId()).thenReturn(11L);
        when(sessionService.getOrCreateExternalSession("FEISHU", "oc-1")).thenReturn(session);
        ModelProvider provider = chatProvider(7L, "Anthropic", "claude-3", false);
        when(modelProviderService.getById(7L)).thenReturn(provider);
        FeishuEventService service = service(new FeishuProperties(), client, sessionService, modelProviderService);

        service.acceptWebSocketMessage(webSocketTextEvent("evt-switch", "om-switch", "oc-1", "/model 7"));

        // 复用既有的会话模型写入能力，不另建模型状态。
        verify(sessionService).updateProviders(11L, true, 7L, false, null);
        verify(client).replyText(eq("om-switch"), contains("Anthropic / claude-3"));
    }

    @Test
    void modelCommandWithoutArgumentReportsCurrentModelAndUsage() {
        FeishuApiClient client = mock(FeishuApiClient.class);
        SessionService sessionService = mock(SessionService.class);
        ModelProviderService modelProviderService = mock(ModelProviderService.class);
        Session session = mock(Session.class);
        when(session.getId()).thenReturn(11L);
        when(session.getChatProviderId()).thenReturn(null);
        when(sessionService.getOrCreateExternalSession("FEISHU", "oc-1")).thenReturn(session);
        when(sessionService.getSession(11L)).thenReturn(session);
        ModelProvider systemDefault = chatProvider(1L, "OpenAI", "gpt-4o-mini", true);
        when(modelProviderService.getActiveProvider(ProviderType.CHAT)).thenReturn(systemDefault);
        FeishuEventService service = service(new FeishuProperties(), client, sessionService, modelProviderService);

        service.acceptWebSocketMessage(webSocketTextEvent("evt-current", "om-current", "oc-1", "/model"));

        verify(client).replyText(eq("om-current"), contains("当前会话对话模型：系统默认（OpenAI / gpt-4o-mini）"));
        verify(client).replyText(eq("om-current"), contains("/models"));
        verify(sessionService, never()).updateProviders(anyLong(), anyBoolean(), any(), anyBoolean(), any());
    }

    @Test
    void modelCommandWithDefaultArgumentClearsSessionOverride() {
        FeishuApiClient client = mock(FeishuApiClient.class);
        SessionService sessionService = mock(SessionService.class);
        Session session = mock(Session.class);
        when(session.getId()).thenReturn(11L);
        when(sessionService.getOrCreateExternalSession("FEISHU", "oc-1")).thenReturn(session);
        FeishuEventService service = service(new FeishuProperties(), client, sessionService,
                mock(ModelProviderService.class));

        service.acceptWebSocketMessage(webSocketTextEvent("evt-default", "om-default", "oc-1", "/model default"));

        verify(sessionService).updateProviders(11L, true, null, false, null);
        verify(client).replyText(eq("om-default"), contains("系统默认"));
    }

    @Test
    void modelCommandRejectsUnknownModelIdWithoutWritingSession() {
        FeishuApiClient client = mock(FeishuApiClient.class);
        SessionService sessionService = mock(SessionService.class);
        ModelProviderService modelProviderService = mock(ModelProviderService.class);
        Session session = mock(Session.class);
        when(session.getId()).thenReturn(11L);
        when(sessionService.getOrCreateExternalSession("FEISHU", "oc-1")).thenReturn(session);
        when(modelProviderService.getById(99L)).thenThrow(new RuntimeException("not found"));
        FeishuEventService service = service(new FeishuProperties(), client, sessionService, modelProviderService);

        service.acceptWebSocketMessage(webSocketTextEvent("evt-unknown", "om-unknown", "oc-1", "/model 99"));

        verify(client).replyText(eq("om-unknown"), contains("未找到对话模型 99"));
        verify(sessionService, never()).updateProviders(anyLong(), anyBoolean(), any(), anyBoolean(), any());
    }

    private ModelProvider chatProvider(Long id, String name, String modelName, boolean active) {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.getId()).thenReturn(id);
        when(provider.getName()).thenReturn(name);
        when(provider.getProviderId()).thenReturn(name.toLowerCase());
        when(provider.getModelName()).thenReturn(modelName);
        when(provider.getType()).thenReturn(ProviderType.CHAT);
        when(provider.isActive()).thenReturn(active);
        return provider;
    }

    private P2MessageReceiveV1 webSocketTextEvent(String eventId, String messageId, String chatId, String text) {
        Header header = new Header();
        header.setEventId(eventId);
        header.setEventType("im.message.receive_v1");
        EventSender sender = new EventSender();
        sender.setSenderType("user");
        EventMessage message = new EventMessage();
        message.setMessageId(messageId);
        message.setChatId(chatId);
        message.setMessageType("text");
        message.setContent("{\"text\":\"" + text + "\"}");
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setSender(sender);
        data.setMessage(message);
        P2MessageReceiveV1 event = new P2MessageReceiveV1();
        event.setHeader(header);
        event.setEvent(data);
        return event;
    }

    private FeishuEventService service(FeishuProperties properties) {
        return service(properties, mock(FeishuApiClient.class), mock(SessionService.class),
                mock(ModelProviderService.class));
    }

    private FeishuEventService service(FeishuProperties properties, FeishuApiClient client,
                                      SessionService sessionService, ModelProviderService modelProviderService) {
        TaskExecutor executor = Runnable::run;
        return new FeishuEventService(
                properties,
                new ObjectMapper(),
                executor,
                client,
                sessionService,
                mock(AttachmentService.class),
                mock(ImageGenerationService.class),
                mock(BillingService.class),
                modelProviderService);
    }
}
