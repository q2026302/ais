package com.gs.ais.dto.request;

import com.gs.ais.model.enums.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@Schema(description = "创建/更新模型供应商请求参数")
public class ModelProviderRequest {

    @NotBlank(message = "providerId is required")
    @Schema(description = "供应商名称（全局唯一，创建后不可修改）", example = "my-provider", requiredMode = Schema.RequiredMode.REQUIRED)
    private String providerId;

    @Schema(description = "供应商显示名称（不传则默认使用 providerId）", example = "我的供应商")
    private String name;

    @NotNull(message = "type is required")
    @Schema(description = "供应商类型：CHAT（对话模型）或 IMAGE（图像生成模型）", example = "CHAT", requiredMode = Schema.RequiredMode.REQUIRED)
    private ProviderType type;

    @NotBlank(message = "modelName is required")
    @Schema(description = "模型名称", example = "gpt-4o", requiredMode = Schema.RequiredMode.REQUIRED)
    private String modelName;

    @NotBlank(message = "baseUrl is required")
    @Schema(description = "API 请求地址；仅填写域名时会自动补全为 https://域名/v1", example = "https://api.openai.com/v1", requiredMode = Schema.RequiredMode.REQUIRED)
    private String baseUrl;

    @Schema(description = "API 密钥。编辑时留空则不修改已有密钥", example = "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
    private String apiKey;

    @Schema(description = "是否设为活跃供应商", example = "true")
    private Boolean isActive;

    @Schema(description = "对话模型 system prompt；仅 CHAT 使用")
    private String systemPrompt;
    @Schema(description = "推理强度，如 default、low、medium、high", defaultValue = "default")
    private String reasoningEffort;
    @Schema(description = "采样温度；普通对话默认 0.7，推理模型可留空", defaultValue = "0.7")
    private Double temperature;
    @Schema(description = "请求超时秒数；对话默认 600，图片默认 900")
    private Integer timeoutSeconds;
    @Schema(description = "图片失败重试次数", defaultValue = "2")
    private Integer maxRetries;
    @Schema(description = "图片重试退避基准秒数", defaultValue = "3")
    private Integer retryBackoffSeconds;
    @Schema(description = "图片适配器：AUTO、OPENAI_IMAGE、GEMINI_IMAGE", defaultValue = "AUTO")
    private String adapterType;
    @Schema(description = "模型特有的额外配置 JSON")
    private String configJson;

    @Schema(description = "计费模式：per_request（按次）、per_token（按 Token）")
    private String billingMode;
    @Schema(description = "单价；按次计费时每次请求的价格，按 Token 计费时每个 Token 的价格")
    private BigDecimal pricePerUnit;

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

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
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
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String value) { this.configJson = value; }
    public String getBillingMode() { return billingMode; }
    public void setBillingMode(String value) { this.billingMode = value; }
    public BigDecimal getPricePerUnit() { return pricePerUnit; }
    public void setPricePerUnit(BigDecimal value) { this.pricePerUnit = value; }
}
