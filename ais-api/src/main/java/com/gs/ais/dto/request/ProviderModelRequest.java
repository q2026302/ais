package com.gs.ais.dto.request;

import com.gs.ais.model.enums.ProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class ProviderModelRequest {
    private Long id;

    @NotNull(message = "type is required")
    private ProviderType type;

    @NotBlank(message = "modelName is required")
    private String modelName;

    private String systemPrompt;
    private String reasoningEffort;
    private Double temperature;
    private Integer timeoutSeconds;
    private Integer maxRetries;
    private Integer retryBackoffSeconds;
    private String adapterType;
    private String configJson;
    private Boolean supportsTextToImage;
    private Boolean supportsImageToImage;
    private Integer priceCreditsMin;
    private Integer priceCreditsMax;
    private BigDecimal priceCnyMin;
    private BigDecimal priceCnyMax;
    private String priceDescription;
    private String billingMode;
    private BigDecimal pricePerUnit;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ProviderType getType() { return type; }
    public void setType(ProviderType type) { this.type = type; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public Integer getRetryBackoffSeconds() { return retryBackoffSeconds; }
    public void setRetryBackoffSeconds(Integer retryBackoffSeconds) { this.retryBackoffSeconds = retryBackoffSeconds; }
    public String getAdapterType() { return adapterType; }
    public void setAdapterType(String adapterType) { this.adapterType = adapterType; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public Boolean getSupportsTextToImage() { return supportsTextToImage; }
    public void setSupportsTextToImage(Boolean value) { this.supportsTextToImage = value; }
    public Boolean getSupportsImageToImage() { return supportsImageToImage; }
    public void setSupportsImageToImage(Boolean value) { this.supportsImageToImage = value; }
    public Integer getPriceCreditsMin() { return priceCreditsMin; }
    public void setPriceCreditsMin(Integer value) { this.priceCreditsMin = value; }
    public Integer getPriceCreditsMax() { return priceCreditsMax; }
    public void setPriceCreditsMax(Integer value) { this.priceCreditsMax = value; }
    public BigDecimal getPriceCnyMin() { return priceCnyMin; }
    public void setPriceCnyMin(BigDecimal value) { this.priceCnyMin = value; }
    public BigDecimal getPriceCnyMax() { return priceCnyMax; }
    public void setPriceCnyMax(BigDecimal value) { this.priceCnyMax = value; }
    public String getPriceDescription() { return priceDescription; }
    public void setPriceDescription(String value) { this.priceDescription = value; }
    public String getBillingMode() { return billingMode; }
    public void setBillingMode(String value) { this.billingMode = value; }
    public BigDecimal getPricePerUnit() { return pricePerUnit; }
    public void setPricePerUnit(BigDecimal value) { this.pricePerUnit = value; }
}
