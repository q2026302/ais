package com.gs.ais.integration;

import com.gs.ais.repository.ApiProviderRepository;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.service.GrsaiModelCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

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

    @Test
    void grsaiProviderIsBuiltInWithSupportedImageModels() {
        var provider = providerRepository.findByProviderKey(GrsaiModelCatalogService.PROVIDER_KEY)
                .orElseThrow();
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
}
