package com.gs.ais.controller;

import com.gs.ais.model.entity.SecuritySettings;
import com.gs.ais.service.SecuritySettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/security-settings")
public class SecuritySettingsController {

    private final SecuritySettingsService securitySettingsService;

    public SecuritySettingsController(SecuritySettingsService securitySettingsService) {
        this.securitySettingsService = securitySettingsService;
    }

    @GetMapping
    public Map<String, Object> get() {
        return toResponse(securitySettingsService.getOrCreate());
    }

    @PutMapping
    public Map<String, Object> update(@Valid @RequestBody UpdateRequest request) {
        SecuritySettings settings = securitySettingsService.update(
                request.maxFailures(),
                request.failureWindowMinutes(),
                request.lockDurationMinutes(),
                request.captchaEnabled());
        return toResponse(settings);
    }

    private Map<String, Object> toResponse(SecuritySettings settings) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("maxFailures", settings.getMaxFailures());
        body.put("failureWindowMinutes", settings.getFailureWindowMinutes());
        body.put("lockDurationMinutes", settings.getLockDurationMinutes());
        body.put("captchaEnabled", settings.isCaptchaEnabled());
        body.put("updatedAt", settings.getUpdatedAt());
        return body;
    }

    public record UpdateRequest(
            @Min(1) @Max(100) int maxFailures,
            @Min(1) @Max(1440) int failureWindowMinutes,
            @Min(1) @Max(10080) int lockDurationMinutes,
            boolean captchaEnabled
    ) {
    }
}
