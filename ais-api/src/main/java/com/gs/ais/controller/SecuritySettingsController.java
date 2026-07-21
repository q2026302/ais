package com.gs.ais.controller;

import com.gs.ais.model.entity.SecuritySettings;
import com.gs.ais.service.SecuritySettingsService;
import com.gs.ais.service.OperationLogService;
import com.gs.ais.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
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
    private final OperationLogService operationLogService;

    public SecuritySettingsController(SecuritySettingsService securitySettingsService,
                                      OperationLogService operationLogService) {
        this.securitySettingsService = securitySettingsService;
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public Map<String, Object> get() {
        return toResponse(securitySettingsService.getOrCreate());
    }

    @PutMapping
    public Map<String, Object> update(@Valid @RequestBody UpdateRequest request,
                                      HttpServletRequest httpRequest) {
        SecuritySettings settings = securitySettingsService.update(
                request.maxFailures(),
                request.failureWindowMinutes(),
                request.lockDurationMinutes(),
                request.captchaEnabled());
        operationLogService.record(AuthContext.get(), "ADMIN_SECURITY_UPDATE", "SECURITY_SETTINGS", null,
                "更新登录防护配置", httpRequest);
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
