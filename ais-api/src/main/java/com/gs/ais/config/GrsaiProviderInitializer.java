package com.gs.ais.config;

import com.gs.ais.dto.catalog.GrsaiModelCatalogItem;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.model.entity.ApiProvider;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.ApiProviderRepository;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.service.GrsaiModelCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Order(30)
public class GrsaiProviderInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GrsaiProviderInitializer.class);

    private final ApiProviderRepository providerRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final GrsaiModelCatalogService catalogService;

    public GrsaiProviderInitializer(ApiProviderRepository providerRepository,
                                    ModelProviderRepository modelProviderRepository,
                                    GrsaiModelCatalogService catalogService) {
        this.providerRepository = providerRepository;
        this.modelProviderRepository = modelProviderRepository;
        this.catalogService = catalogService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        ApiProvider provider = providerRepository.findByProviderKey(GrsaiModelCatalogService.PROVIDER_KEY)
                .orElseGet(() -> {
                    ApiProvider created = new ApiProvider();
                    created.setProviderKey(GrsaiModelCatalogService.PROVIDER_KEY);
                    created.setName(GrsaiModelCatalogService.PROVIDER_NAME);
                    created.setBaseUrl(GrsaiModelCatalogService.DEFAULT_BASE_URL);
                    created.setApiKey("");
                    return providerRepository.saveAndFlush(created);
                });

        Map<String, ModelProvider> existing = new HashMap<>();
        for (ModelProvider model : modelProviderRepository.findByApiProviderId(provider.getId())) {
            existing.put(model.getModelName(), model);
        }
        int added = 0;
        int updated = 0;
        for (GrsaiModelCatalogItem item : catalogService.getCatalog()) {
            ModelProvider existingModel = existing.get(item.modelName());
            if (existingModel != null) {
                existingModel.setAdapterType(GrsaiModelCatalogService.ADAPTER_TYPE);
                applyCatalogMetadata(existingModel, item);
                modelProviderRepository.save(existingModel);
                updated++;
                continue;
            }
            ModelProvider model = new ModelProvider();
            model.setProviderId("grsai-model-" + UUID.randomUUID());
            model.setName(provider.getName());
            model.setType(ProviderType.IMAGE);
            model.setModelName(item.modelName());
            model.setBaseUrl(provider.getBaseUrl());
            model.setApiKey(provider.getApiKey());
            model.setActive(false);
            model.setTimeoutSeconds(ModelProviderDefaults.IMAGE_TIMEOUT_SECONDS);
            model.setMaxRetries(ModelProviderDefaults.IMAGE_MAX_RETRIES);
            model.setRetryBackoffSeconds(ModelProviderDefaults.IMAGE_RETRY_BACKOFF_SECONDS);
            model.setAdapterType(GrsaiModelCatalogService.ADAPTER_TYPE);
            applyCatalogMetadata(model, item);
            model.setApiProvider(provider);
            modelProviderRepository.save(model);
            added++;
        }
        if (added > 0 || updated > 0) {
            log.info("Synchronized Grsai provider catalog: {} added, {} metadata record(s) updated",
                    added, updated);
        }
    }

    private void applyCatalogMetadata(ModelProvider model, GrsaiModelCatalogItem item) {
        model.setSupportsTextToImage(item.supportsTextToImage());
        model.setSupportsImageToImage(item.supportsImageToImage());
        model.setPriceCreditsMin(item.priceCreditsMin());
        model.setPriceCreditsMax(item.priceCreditsMax());
        model.setPriceCnyMin(item.priceCnyMin());
        model.setPriceCnyMax(item.priceCnyMax());
        model.setPriceDescription(item.priceDescription());
    }
}
