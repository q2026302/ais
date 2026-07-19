package com.gs.ais.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "llm")
public class LlmConfigProperties {

    private ModelConfig model;
    private Map<String, List<ProviderConfig>> providers;
    private DebugLoggingConfig debugLogging = new DebugLoggingConfig();

    public ModelConfig getModel() {
        return model;
    }

    public void setModel(ModelConfig model) {
        this.model = model;
    }

    public Map<String, List<ProviderConfig>> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, List<ProviderConfig>> providers) {
        this.providers = providers;
    }

    public DebugLoggingConfig getDebugLogging() {
        return debugLogging;
    }

    public void setDebugLogging(DebugLoggingConfig debugLogging) {
        this.debugLogging = debugLogging != null ? debugLogging : new DebugLoggingConfig();
    }

    public static class DebugLoggingConfig {
        private boolean enabled;
        private int maxRecords = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRecords() {
            return maxRecords;
        }

        public void setMaxRecords(int maxRecords) {
            this.maxRecords = maxRecords;
        }
    }

    public static class ModelConfig {
        private String chat;
        private String image;

        public String getChat() {
            return chat;
        }

        public void setChat(String chat) {
            this.chat = chat;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }
    }

    public static class ProviderConfig {
        private String name;
        private String model;
        private String baseUrl;
        private String apiKey;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
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
    }
}
