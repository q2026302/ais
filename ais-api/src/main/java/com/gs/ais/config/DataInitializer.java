package com.gs.ais.config;

import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.util.ApiRequestUrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Map;

@Component
@Order(10)
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final ModelProviderRepository repository;
    private final LlmConfigProperties llmConfig;

    public DataInitializer(ModelProviderRepository repository, LlmConfigProperties llmConfig) {
        this.repository = repository;
        this.llmConfig = llmConfig;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            log.info("Database already contains model providers, skipping initialization");
            return;
        }

        if (llmConfig.getProviders() == null || llmConfig.getProviders().isEmpty()) {
            log.info("No LLM provider configuration found in application.yml, skipping initialization");
            return;
        }

        log.info("Seeding model providers from application.yml configuration");

        Map<String, List<LlmConfigProperties.ProviderConfig>> providers = llmConfig.getProviders();
        int count = 0;

        for (Map.Entry<String, List<LlmConfigProperties.ProviderConfig>> entry : providers.entrySet()) {
            String typeKey = entry.getKey().toUpperCase();
            ProviderType providerType;
            try {
                providerType = ProviderType.valueOf(typeKey);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown provider type key: {}, skipping", entry.getKey());
                continue;
            }

            String activeProviderId = providerType == ProviderType.CHAT
                    ? llmConfig.getModel().getChat()
                    : llmConfig.getModel().getImage();

            for (LlmConfigProperties.ProviderConfig config : entry.getValue()) {
                ModelProvider provider = new ModelProvider();
                provider.setProviderId(config.getName());
                provider.setName(config.getName());
                provider.setType(providerType);
                provider.setModelName(config.getModel());
                provider.setBaseUrl(ApiRequestUrlUtils.normalizeBaseUrl(config.getBaseUrl()));
                provider.setApiKey(config.getApiKey());
                provider.setActive(config.getName().equals(activeProviderId));

                repository.save(provider);
                count++;
                log.info("Seeded {} provider: {} (model: {}, active: {})",
                        providerType, config.getName(), config.getModel(), provider.isActive());
            }
        }

        log.info("Seeded {} model provider(s) from application.yml configuration", count);
    }
}
