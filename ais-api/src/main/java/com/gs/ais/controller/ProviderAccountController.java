package com.gs.ais.controller;

import com.gs.ais.client.LlmClient;
import com.gs.ais.dto.request.FetchModelsRequest;
import com.gs.ais.dto.request.ProviderAccountRequest;
import com.gs.ais.dto.request.SystemModelSettingsRequest;
import com.gs.ais.dto.request.TestConnectionRequest;
import com.gs.ais.dto.response.*;
import com.gs.ais.model.entity.ApiProvider;
import com.gs.ais.service.ModelProviderResponseMapper;
import com.gs.ais.service.ProviderAccountService;
import com.gs.ais.service.SystemModelSettingsService;
import com.gs.ais.service.OperationLogService;
import com.gs.ais.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/provider-accounts")
public class ProviderAccountController {

    private final ProviderAccountService providerService;
    private final SystemModelSettingsService settingsService;
    private final ModelProviderResponseMapper modelMapper;
    private final LlmClient llmClient;
    private final OperationLogService operationLogService;

    public ProviderAccountController(ProviderAccountService providerService,
                                     SystemModelSettingsService settingsService,
                                     ModelProviderResponseMapper modelMapper,
                                     LlmClient llmClient,
                                     OperationLogService operationLogService) {
        this.providerService = providerService;
        this.settingsService = settingsService;
        this.modelMapper = modelMapper;
        this.llmClient = llmClient;
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public ResponseEntity<List<ProviderAccountResponse>> list() {
        return ResponseEntity.ok(providerService.getAll().stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProviderAccountResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(toResponse(providerService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ProviderAccountResponse> create(
            @Valid @RequestBody ProviderAccountRequest request,
            HttpServletRequest httpRequest) {
        ApiProvider provider = providerService.create(request);
        operationLogService.record(AuthContext.get(), "ADMIN_PROVIDER_CREATE", "PROVIDER", provider.getId(),
                "创建供应商：" + provider.getName(), httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(provider));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProviderAccountResponse> update(
            @PathVariable Long id, @Valid @RequestBody ProviderAccountRequest request,
            HttpServletRequest httpRequest) {
        ApiProvider provider = providerService.update(id, request);
        operationLogService.record(AuthContext.get(), "ADMIN_PROVIDER_UPDATE", "PROVIDER", id,
                "更新供应商：" + provider.getName(), httpRequest);
        return ResponseEntity.ok(toResponse(provider));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        providerService.delete(id);
        operationLogService.record(AuthContext.get(), "ADMIN_PROVIDER_DELETE", "PROVIDER", id,
                "删除供应商", httpRequest);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/defaults")
    public ResponseEntity<SystemModelSettingsResponse> getDefaults() {
        var settings = settingsService.getSnapshot();
        return ResponseEntity.ok(new SystemModelSettingsResponse(
                settings.defaultChatModelId(), settings.defaultImageModelId()));
    }

    @PutMapping("/defaults")
    public ResponseEntity<SystemModelSettingsResponse> updateDefaults(
            @RequestBody SystemModelSettingsRequest request,
            HttpServletRequest httpRequest) {
        var settings = settingsService.update(
                request.getDefaultChatModelId(), request.getDefaultImageModelId());
        operationLogService.record(AuthContext.get(), "ADMIN_MODEL_DEFAULTS_UPDATE", "SYSTEM_MODEL_SETTINGS", null,
                "更新默认模型：对话=" + settings.defaultChatModelId() + "，绘画=" + settings.defaultImageModelId(), httpRequest);
        return ResponseEntity.ok(new SystemModelSettingsResponse(
                settings.defaultChatModelId(), settings.defaultImageModelId()));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<TestConnectionResponse> test(
            @PathVariable Long id, @RequestBody(required = false) TestConnectionRequest request) {
        ApiProvider provider = providerService.getById(id);
        String baseUrl = resolveBaseUrl(provider, request == null ? null : request.getBaseUrl());
        String apiKey = resolveApiKey(provider, request == null ? null : request.getApiKey());
        TestConnectionResponse response = isGrsai(provider.getProviderKey())
                ? llmClient.testGrsaiConnection(baseUrl, apiKey)
                : llmClient.testConnection(baseUrl, apiKey);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/models")
    public ResponseEntity<FetchModelsResponse> fetchModels(
            @PathVariable Long id, @RequestBody(required = false) FetchModelsRequest request) {
        ApiProvider provider = providerService.getById(id);
        return ResponseEntity.ok(llmClient.fetchModels(
                resolveBaseUrl(provider, request == null ? null : request.getBaseUrl()),
                resolveApiKey(provider, request == null ? null : request.getApiKey())));
    }

    private ProviderAccountResponse toResponse(ApiProvider provider) {
        ProviderAccountResponse response = new ProviderAccountResponse();
        response.setId(provider.getId());
        response.setProviderKey(provider.getProviderKey());
        response.setName(provider.getName());
        response.setBaseUrl(provider.getBaseUrl());
        response.setApiKey(modelMapper.maskApiKey(provider.getApiKey()));
        response.setModels(provider.getModels().stream().map(modelMapper::toResponse).toList());
        response.setCreatedAt(provider.getCreatedAt());
        response.setUpdatedAt(provider.getUpdatedAt());
        return response;
    }

    private String resolveBaseUrl(ApiProvider provider, String override) {
        return hasText(override) ? override : provider.getBaseUrl();
    }

    private String resolveApiKey(ApiProvider provider, String override) {
        return hasText(override) ? override : provider.getApiKey();
    }

    private boolean isGrsai(String providerKey) {
        return providerKey != null && "grsai".equalsIgnoreCase(providerKey.trim());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
