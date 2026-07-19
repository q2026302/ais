package com.gs.ais.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.ArrayList;
import java.util.List;

public class ProviderAccountRequest {
    @NotBlank(message = "providerKey is required")
    private String providerKey;

    private String name;

    @NotBlank(message = "baseUrl is required")
    private String baseUrl;

    private String apiKey;

    @Valid
    @NotEmpty(message = "at least one model is required")
    private List<ProviderModelRequest> models = new ArrayList<>();

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public List<ProviderModelRequest> getModels() { return models; }
    public void setModels(List<ProviderModelRequest> models) { this.models = models; }
}
