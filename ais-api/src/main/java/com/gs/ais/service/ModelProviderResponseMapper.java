package com.gs.ais.service;

import com.gs.ais.dto.response.ModelProviderResponse;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;
import org.springframework.stereotype.Component;

@Component
public class ModelProviderResponseMapper {

    private final SystemModelSettingsService settingsService;

    public ModelProviderResponseMapper(SystemModelSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public ModelProviderResponse toResponse(ModelProvider provider) {
        ModelProviderResponse response = new ModelProviderResponse();
        response.setId(provider.getId());
        response.setApiProviderId(provider.getApiProvider() != null ? provider.getApiProvider().getId() : null);
        response.setProviderId(provider.getProviderId());
        response.setName(provider.getName() != null ? provider.getName() : provider.getProviderId());
        response.setType(provider.getType());
        response.setModelName(provider.getModelName());
        response.setBaseUrl(provider.getBaseUrl());
        response.setApiKey(maskApiKey(provider.getApiKey()));
        response.setActive(settingsService.isDefault(provider));
        response.setSystemPrompt(provider.getSystemPrompt());
        response.setReasoningEffort(provider.getType() == ProviderType.CHAT
                ? ModelProviderDefaults.reasoningEffort(provider) : null);
        response.setTemperature(provider.getType() == ProviderType.CHAT
                ? ModelProviderDefaults.temperature(provider) : null);
        response.setTimeoutSeconds(ModelProviderDefaults.timeoutSeconds(provider));
        response.setMaxRetries(provider.getType() == ProviderType.IMAGE
                ? ModelProviderDefaults.maxRetries(provider) : null);
        response.setRetryBackoffSeconds(provider.getType() == ProviderType.IMAGE
                ? ModelProviderDefaults.retryBackoffSeconds(provider) : null);
        response.setAdapterType(provider.getType() == ProviderType.IMAGE
                ? ModelProviderDefaults.adapterType(provider) : null);
        response.setConfigJson(provider.getConfigJson());
        response.setSupportsTextToImage(provider.getSupportsTextToImage());
        response.setSupportsImageToImage(provider.getSupportsImageToImage());
        response.setPriceCreditsMin(provider.getPriceCreditsMin());
        response.setPriceCreditsMax(provider.getPriceCreditsMax());
        response.setPriceCnyMin(provider.getPriceCnyMin());
        response.setPriceCnyMax(provider.getPriceCnyMax());
        response.setPriceDescription(provider.getPriceDescription());
        response.setCreatedAt(provider.getCreatedAt());
        response.setUpdatedAt(provider.getUpdatedAt());
        return response;
    }

    public String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 3) + "******" + apiKey.substring(apiKey.length() - 4);
    }
}
