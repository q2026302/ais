package com.gs.ais.controller;

import com.gs.ais.config.FeishuProperties;
import com.gs.ais.feishu.FeishuEventService;
import com.gs.ais.feishu.FeishuWebSocketLifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** HTTP event subscription endpoint for the optional Feishu HTTP transport. */
@RestController
@RequestMapping("/api/feishu")
public class FeishuEventController {

    private final FeishuProperties properties;
    private final FeishuEventService eventService;
    private final FeishuWebSocketLifecycle webSocketLifecycle;

    public FeishuEventController(FeishuProperties properties,
                                 FeishuEventService eventService,
                                 FeishuWebSocketLifecycle webSocketLifecycle) {
        this.properties = properties;
        this.eventService = eventService;
        this.webSocketLifecycle = webSocketLifecycle;
    }

    @PostMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receiveEvent(@RequestBody String rawBody,
                                                            @RequestHeader HttpHeaders headers) {
        if (!properties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", 404, "message", "Feishu integration is disabled"));
        }
        if (!properties.isHttpMode()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", 409,
                    "message", "Feishu is configured for " + properties.normalizedTransport() + " transport; HTTP callbacks are disabled"));
        }
        try {
            FeishuEventService.CallbackResponse response = eventService.acceptWebhook(rawBody, headers);
            return ResponseEntity.status(response.status()).body(response.body());
        } catch (FeishuEventService.FeishuWebhookException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        }
    }

    /** Safe-to-expose deployment health information; no credential is included. */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "configured", properties.isConfigured(),
                "transport", properties.normalizedTransport(),
                "websocketStatus", webSocketLifecycle.getStatus());
    }
}
