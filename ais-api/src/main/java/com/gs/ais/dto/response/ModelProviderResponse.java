package com.gs.ais.dto.response;

import com.gs.ais.model.enums.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "模型供应商响应信息")
public class ModelProviderResponse {

    @Schema(description = "数据库 ID", example = "1")
    private Long id;

    private Long apiProviderId;

    @Schema(description = "供应商唯一标识", example = "my-provider")
    private String providerId;

    @Schema(description = "供应商显示名称", example = "我的供应商")
    private String name;

    @Schema(description = "供应商类型：CHAT（对话模型）或 IMAGE（图像生成模型）", example = "CHAT")
    private ProviderType type;

    @Schema(description = "模型名称", example = "gpt-4o")
    private String modelName;

    @Schema(description = "API 请求地址", example = "https://api.openai.com/v1")
    private String baseUrl;

    @Schema(description = "脱敏后的 API 密钥（仅显示首尾）", example = "sk-******f94")
    private String apiKey;

    @Schema(description = "是否为当前活跃供应商", example = "true")
    private boolean isActive;

    private String systemPrompt;
    private String reasoningEffort;
    private Double temperature;
    private Integer timeoutSeconds;
    private Integer maxRetries;
    private Integer retryBackoffSeconds;
    private String adapterType;
    @Schema(description = "图像模型并发上限，留空/0 则使用全局默认值")
    private Integer imageQueueConcurrency;
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
    private BigDecimal inputPricePerMillion;
    private BigDecimal outputPricePerMillion;
    private BigDecimal cacheReadPricePerMillion;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getApiProviderId() { return apiProviderId; }
    public void setApiProviderId(Long apiProviderId) { this.apiProviderId = apiProviderId; }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProviderType getType() {
        return type;
    }

    public void setType(ProviderType type) {
        this.type = type;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String value) { this.systemPrompt = value; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String value) { this.reasoningEffort = value; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double value) { this.temperature = value; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer value) { this.timeoutSeconds = value; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer value) { this.maxRetries = value; }
    public Integer getRetryBackoffSeconds() { return retryBackoffSeconds; }
    public void setRetryBackoffSeconds(Integer value) { this.retryBackoffSeconds = value; }
    public String getAdapterType() { return adapterType; }
    public void setAdapterType(String value) { this.adapterType = value; }
    public Integer getImageQueueConcurrency() { return imageQueueConcurrency; }
    public void setImageQueueConcurrency(Integer value) { this.imageQueueConcurrency = value; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String value) { this.configJson = value; }
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
    public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; }
    public void setInputPricePerMillion(BigDecimal value) { this.inputPricePerMillion = value; }
    public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; }
    public void setOutputPricePerMillion(BigDecimal value) { this.outputPricePerMillion = value; }
    public BigDecimal getCacheReadPricePerMillion() { return cacheReadPricePerMillion; }
    public void setCacheReadPricePerMillion(BigDecimal value) { this.cacheReadPricePerMillion = value; }
}
