package com.gs.ais.model;

import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;

/**
 * Shared defaults for model provider configuration. These values are also
 * surfaced by the management UI when an older provider has null settings.
 */
public final class ModelProviderDefaults {

    public static final String REASONING_EFFORT = "default";
    public static final double CHAT_TEMPERATURE = 0.7D;
    public static final int CHAT_TIMEOUT_SECONDS = 600;
    public static final int IMAGE_TIMEOUT_SECONDS = 900;
    public static final int IMAGE_MAX_RETRIES = 2;
    public static final int IMAGE_RETRY_BACKOFF_SECONDS = 3;
    public static final String IMAGE_ADAPTER_TYPE = "AUTO";

    private ModelProviderDefaults() {
    }

    public static int timeoutSeconds(ModelProvider provider) {
        if (provider != null && provider.getTimeoutSeconds() != null && provider.getTimeoutSeconds() > 0) {
            return provider.getTimeoutSeconds();
        }
        return provider != null && provider.getType() == ProviderType.IMAGE
                ? IMAGE_TIMEOUT_SECONDS
                : CHAT_TIMEOUT_SECONDS;
    }

    public static int maxRetries(ModelProvider provider) {
        if (provider != null && provider.getMaxRetries() != null) {
            return Math.max(0, provider.getMaxRetries());
        }
        return provider != null && provider.getType() == ProviderType.IMAGE ? IMAGE_MAX_RETRIES : 0;
    }

    public static int retryBackoffSeconds(ModelProvider provider) {
        if (provider != null && provider.getRetryBackoffSeconds() != null) {
            return Math.max(1, provider.getRetryBackoffSeconds());
        }
        return IMAGE_RETRY_BACKOFF_SECONDS;
    }

    public static String reasoningEffort(ModelProvider provider) {
        String configured = provider != null ? provider.getReasoningEffort() : null;
        return configured == null || configured.isBlank() ? REASONING_EFFORT : configured;
    }

    public static Double temperature(ModelProvider provider) {
        if (provider != null && provider.getTemperature() != null) {
            return provider.getTemperature();
        }
        String reasoning = reasoningEffort(provider);
        return "default".equalsIgnoreCase(reasoning) || "auto".equalsIgnoreCase(reasoning)
                ? CHAT_TEMPERATURE
                : null;
    }

    public static String adapterType(ModelProvider provider) {
        String configured = provider != null ? provider.getAdapterType() : null;
        return configured == null || configured.isBlank() ? IMAGE_ADAPTER_TYPE : configured;
    }
}
