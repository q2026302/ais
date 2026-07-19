package com.gs.ais.config;

import com.gs.ais.model.entity.ApiProvider;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.SystemModelSettings;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.ApiProviderRepository;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.repository.SystemModelSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(20)
public class ProviderAccountMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProviderAccountMigrationRunner.class);

    private final ModelProviderRepository modelRepository;
    private final ApiProviderRepository providerRepository;
    private final SystemModelSettingsRepository settingsRepository;

    public ProviderAccountMigrationRunner(ModelProviderRepository modelRepository,
                                          ApiProviderRepository providerRepository,
                                          SystemModelSettingsRepository settingsRepository) {
        this.modelRepository = modelRepository;
        this.providerRepository = providerRepository;
        this.settingsRepository = settingsRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        migrateLegacyProviders();
        initializeDefaults();
    }

    private void migrateLegacyProviders() {
        List<ModelProvider> legacyModels = modelRepository.findByApiProviderIsNull();
        if (legacyModels.isEmpty()) return;

        Map<CredentialKey, ApiProvider> grouped = new LinkedHashMap<>();
        for (ModelProvider model : legacyModels) {
            CredentialKey key = new CredentialKey(model.getLegacyBaseUrl(), model.getLegacyApiKey());
            ApiProvider account = grouped.get(key);
            if (account == null) {
                account = new ApiProvider();
                account.setProviderKey(uniqueProviderKey(model.getLegacyProviderId()));
                account.setName(hasText(model.getLegacyName())
                        ? model.getLegacyName() : model.getLegacyProviderId());
                account.setBaseUrl(model.getLegacyBaseUrl());
                account.setApiKey(model.getLegacyApiKey());
                account = providerRepository.save(account);
                grouped.put(key, account);
            }
            model.setApiProvider(account);
            modelRepository.save(model);
        }
        log.info("Migrated {} legacy model configuration(s) into {} API provider account(s)",
                legacyModels.size(), grouped.size());
    }

    private void initializeDefaults() {
        if (settingsRepository.existsById(SystemModelSettings.SINGLETON_ID)) return;
        SystemModelSettings settings = new SystemModelSettings();
        settings.setDefaultChatModelId(modelRepository.findByTypeAndIsActiveTrue(ProviderType.CHAT)
                .map(ModelProvider::getId).orElse(null));
        settings.setDefaultImageModelId(modelRepository.findByTypeAndIsActiveTrue(ProviderType.IMAGE)
                .map(ModelProvider::getId).orElse(null));
        settingsRepository.save(settings);
        log.info("Initialized independent default model settings: chat={}, image={}",
                settings.getDefaultChatModelId(), settings.getDefaultImageModelId());
    }

    private String uniqueProviderKey(String preferred) {
        String base = hasText(preferred) ? preferred.trim() : "provider";
        String candidate = base;
        int suffix = 2;
        while (providerRepository.existsByProviderKey(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record CredentialKey(String baseUrl, String apiKey) {}
}
