package com.gs.ais.model.entity;

import com.gs.ais.model.enums.ProviderType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "model_provider",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider_id"}),
        indexes = @Index(columnList = "type, is_active"))
@EntityListeners(AuditingEntityListener.class)
public class ModelProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(name = "provider_id", nullable = false)
    private String providerId;

    // GraalVM Native Image runs Hibernate with BytecodeProvider=none and cannot
    // generate runtime proxies for lazy to-one associations. Keep this relation
    // eager so provider responses can be mapped without requiring a proxy class.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "api_provider_id")
    private ApiProvider apiProvider;

    @Column(name = "name")
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ProviderType type;

    @NotBlank
    @Column(name = "model_name", nullable = false)
    private String modelName;

    @NotBlank
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "api_key", length = 1024)
    private String apiKey;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "reasoning_effort", length = 32)
    private String reasoningEffort;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    @Column(name = "max_retries")
    private Integer maxRetries;

    @Column(name = "retry_backoff_seconds")
    private Integer retryBackoffSeconds;

    @Column(name = "adapter_type", length = 32)
    private String adapterType;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "supports_text_to_image")
    private Boolean supportsTextToImage;

    @Column(name = "supports_image_to_image")
    private Boolean supportsImageToImage;

    @Column(name = "price_credits_min")
    private Integer priceCreditsMin;

    @Column(name = "price_credits_max")
    private Integer priceCreditsMax;

    @Column(name = "price_cny_min", precision = 10, scale = 4)
    private BigDecimal priceCnyMin;

    @Column(name = "price_cny_max", precision = 10, scale = 4)
    private BigDecimal priceCnyMax;

    @Column(name = "price_description", length = 255)
    private String priceDescription;

    @Column(name = "billing_mode", length = 32)
    private String billingMode;

    @Column(name = "price_per_unit", precision = 10, scale = 6)
    private BigDecimal pricePerUnit;

    @Column(name = "input_price_per_million", precision = 12, scale = 6)
    private BigDecimal inputPricePerMillion;

    @Column(name = "output_price_per_million", precision = 12, scale = 6)
    private BigDecimal outputPricePerMillion;

    @Column(name = "cache_read_price_per_million", precision = 12, scale = 6)
    private BigDecimal cacheReadPricePerMillion;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ModelProvider() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProviderId() {
        return apiProvider != null ? apiProvider.getProviderKey() : providerId;
    }

    public String getLegacyProviderId() {
        return providerId;
    }

    public ApiProvider getApiProvider() {
        return apiProvider;
    }

    public void setApiProvider(ApiProvider apiProvider) {
        this.apiProvider = apiProvider;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getName() {
        return apiProvider != null ? apiProvider.getName() : name;
    }

    public String getLegacyName() {
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
        return apiProvider != null ? apiProvider.getBaseUrl() : baseUrl;
    }

    public String getLegacyBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiProvider != null ? apiProvider.getApiKey() : apiKey;
    }

    public String getLegacyApiKey() {
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
    public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; }
    public void setInputPricePerMillion(BigDecimal value) { this.inputPricePerMillion = value; }
    public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; }
    public void setOutputPricePerMillion(BigDecimal value) { this.outputPricePerMillion = value; }
    public BigDecimal getCacheReadPricePerMillion() { return cacheReadPricePerMillion; }
    public void setCacheReadPricePerMillion(BigDecimal value) { this.cacheReadPricePerMillion = value; }

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
}
