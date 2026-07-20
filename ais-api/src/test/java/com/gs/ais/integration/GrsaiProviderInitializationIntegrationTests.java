package com.gs.ais.integration;

import com.gs.ais.config.GrsaiProviderInitializer;
import com.gs.ais.dto.request.ProviderAccountRequest;
import com.gs.ais.dto.request.ProviderModelRequest;
import com.gs.ais.model.entity.ApiProvider;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.ApiProviderRepository;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.service.GrsaiModelCatalogService;
import com.gs.ais.service.ProviderAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "feishu.enabled=false")
@Transactional
class GrsaiProviderInitializationIntegrationTests {

    @Autowired
    private ApiProviderRepository providerRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private GrsaiModelCatalogService catalogService;

    @Autowired
    private GrsaiProviderInitializer initializer;

    @Autowired
    private ProviderAccountService providerAccountService;

    @Test
    void grsaiProviderIsNotAutoInsertedIntoList() {
        assertTrue(providerRepository.findByProviderKey(GrsaiModelCatalogService.PROVIDER_KEY).isEmpty());
        assertFalse(catalogService.getCatalog().isEmpty());
    }

    @Test
    void catalogSyncsWhenGrsaiProviderIsAdded() {
        ApiProvider provider = new ApiProvider();
        provider.setProviderKey(GrsaiModelCatalogService.PROVIDER_KEY);
        provider.setName(GrsaiModelCatalogService.PROVIDER_NAME);
        provider.setBaseUrl(GrsaiModelCatalogService.DEFAULT_BASE_URL);
        provider.setApiKey("");
        provider = providerRepository.saveAndFlush(provider);

        initializer.syncCatalog(provider);

        var models = modelProviderRepository.findByApiProviderId(provider.getId());
        assertEquals(GrsaiModelCatalogService.DEFAULT_BASE_URL, provider.getBaseUrl());
        assertFalse(models.isEmpty());
        assertTrue(models.stream().allMatch(model ->
                model != null && GrsaiModelCatalogService.ADAPTER_TYPE.equals(model.getAdapterType())));
        assertTrue(models.stream().allMatch(model ->
                Boolean.TRUE.equals(model.getSupportsTextToImage())
                        && Boolean.TRUE.equals(model.getSupportsImageToImage())
                        && model.getPriceCnyMin() != null));
    }

    @Test
    void creatingGrsaiViaAccountServiceEnrichesCatalogMetadata() {
        var catalogItem = catalogService.getCatalog().getFirst();

        ProviderModelRequest modelRequest = new ProviderModelRequest();
        modelRequest.setType(ProviderType.IMAGE);
        modelRequest.setModelName(catalogItem.modelName());
        // Intentionally leave adapter/metadata empty — service should fill from catalog.

        ProviderAccountRequest request = new ProviderAccountRequest();
        request.setProviderKey(GrsaiModelCatalogService.PROVIDER_KEY);
        request.setName(GrsaiModelCatalogService.PROVIDER_NAME);
        request.setBaseUrl(GrsaiModelCatalogService.DEFAULT_BASE_URL);
        request.setApiKey("test-key");
        request.setModels(List.of(modelRequest));

        ApiProvider created = providerAccountService.create(request);
        assertEquals(1, created.getModels().size());
        var model = created.getModels().getFirst();
        assertEquals(GrsaiModelCatalogService.ADAPTER_TYPE, model.getAdapterType());
        assertEquals(Boolean.TRUE, model.getSupportsTextToImage());
        assertEquals(Boolean.TRUE, model.getSupportsImageToImage());
        assertEquals(catalogItem.priceDescription(), model.getPriceDescription());
        assertEquals(catalogItem.priceCnyMin(), model.getPriceCnyMin());
    }
}
