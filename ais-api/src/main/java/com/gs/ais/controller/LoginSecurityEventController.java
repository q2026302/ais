package com.gs.ais.controller;

import com.gs.ais.model.entity.LoginSecurityEvent;
import com.gs.ais.service.LoginSecurityEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/security-events")
public class LoginSecurityEventController {

    private final LoginSecurityEventService service;

    public LoginSecurityEventController(LoginSecurityEventService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "100") int limit) {
        return service.recent(limit).stream()
                .map(LoginSecurityEventController::toResponse)
                .toList();
    }

    private static Map<String, Object> toResponse(LoginSecurityEvent event) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", event.getId());
        response.put("eventType", event.getEventType().name());
        response.put("username", event.getUsername());
        response.put("ipAddress", event.getIpAddress());
        response.put("failureCount", event.getFailureCount());
        response.put("lockedUntil", event.getLockedUntil());
        response.put("occurredAt", event.getOccurredAt());
        response.put("detail", event.getDetail());
        return response;
    }
}
