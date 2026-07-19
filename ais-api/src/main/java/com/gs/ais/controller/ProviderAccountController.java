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

    public ProviderAccountController(ProviderAccountService providerService,
                                     SystemModelSettingsService settingsService,
                                     ModelProviderResponseMapper modelMapper,
                                     LlmClient llmClient) {
        this.providerService = providerService;
        this.settingsService = settingsService;
        this.modelMapper = modelMapper;
        this.llmClient = llmClient;
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
            @Valid @RequestBody ProviderAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(providerService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProviderAccountResponse> update(
            @PathVariable Long id, @Valid @RequestBody ProviderAccountRequest request) {
        return ResponseEntity.ok(toResponse(providerService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
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
            @RequestBody SystemModelSettingsRequest request) {
        var settings = settingsService.update(
                request.getDefaultChatModelId(), request.getDefaultImageModelId());
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
