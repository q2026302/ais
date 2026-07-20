package com.gs.ais.controller;

import com.gs.ais.model.entity.BillingRecord;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.security.AuthContext;
import com.gs.ais.service.BillingService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BillingController {

    private final BillingService billingService;
    private final AppUserRepository appUserRepository;

    public BillingController(BillingService billingService,
                             AppUserRepository appUserRepository) {
        this.billingService = billingService;
        this.appUserRepository = appUserRepository;
    }

    private Long getCurrentUserId() {
        var principal = AuthContext.get();
        if (principal == null) return null;
        return appUserRepository.findByUsernameIgnoreCase(principal.subject())
                .map(user -> user.getId())
                .orElse(null);
    }

    @GetMapping("/api/billing/my-logs")
    public ResponseEntity<Map<String, Object>> myLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        Page<BillingRecord> records = billingService.getUserBillingLogs(userId, page, size, from, to);
        return ResponseEntity.ok(toPageResponse(records));
    }

    @GetMapping("/api/admin/billing/logs")
    public ResponseEntity<Map<String, Object>> adminLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!AuthContext.isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        }
        Page<BillingRecord> records = billingService.getAllBillingLogs(userId, page, size);
        return ResponseEntity.ok(toPageResponse(records));
    }

    private Map<String, Object> toPageResponse(Page<BillingRecord> page) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", page.getContent());
        body.put("totalElements", page.getTotalElements());
        body.put("totalPages", page.getTotalPages());
        body.put("number", page.getNumber());
        body.put("size", page.getSize());
        return body;
    }
}