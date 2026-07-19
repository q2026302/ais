package com.gs.ais.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.config.FeishuProperties;
import com.gs.ais.service.AttachmentService;
import com.gs.ais.service.ImageGenerationService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
                mock(SessionService.class), mock(AttachmentService.class), mock(ImageGenerationService.class));
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
                mock(SessionService.class), mock(AttachmentService.class), mock(ImageGenerationService.class));
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
        TaskExecutor executor = Runnable::run;
        return new FeishuEventService(
                properties,
                new ObjectMapper(),
                executor,
                mock(FeishuApiClient.class),
                mock(SessionService.class),
                mock(AttachmentService.class),
                mock(ImageGenerationService.class));
    }
}
