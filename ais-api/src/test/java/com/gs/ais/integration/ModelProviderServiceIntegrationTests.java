package com.gs.ais.integration;

import com.gs.ais.dto.request.ModelProviderRequest;
import com.gs.ais.exception.DuplicateProviderException;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.service.ModelProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "feishu.enabled=false")
@Transactional
class ModelProviderServiceIntegrationTests {

    @Autowired
    private ModelProviderService service;

    @Test
    void appliesTypeSpecificProviderDefaults() {
        ModelProvider chatProvider = service.create(
                providerRequest(uniqueSupplierName(), ProviderType.CHAT, "chat-model"));
        ModelProvider imageProvider = service.create(
                providerRequest(uniqueSupplierName(), ProviderType.IMAGE, "image-model"));

        assertEquals(ModelProviderDefaults.REASONING_EFFORT, chatProvider.getReasoningEffort());
        assertEquals(ModelProviderDefaults.CHAT_TEMPERATURE,
                ModelProviderDefaults.temperature(chatProvider));
        assertEquals(ModelProviderDefaults.CHAT_TIMEOUT_SECONDS, chatProvider.getTimeoutSeconds());

        assertEquals(ModelProviderDefaults.IMAGE_TIMEOUT_SECONDS, imageProvider.getTimeoutSeconds());
        assertEquals(ModelProviderDefaults.IMAGE_MAX_RETRIES, imageProvider.getMaxRetries());
        assertEquals(ModelProviderDefaults.IMAGE_RETRY_BACKOFF_SECONDS,
                imageProvider.getRetryBackoffSeconds());
        assertEquals(ModelProviderDefaults.IMAGE_ADAPTER_TYPE, imageProvider.getAdapterType());
    }

    @Test
    void rejectsDuplicateSupplierNameAcrossChatAndImageProviders() {
        String supplierName = uniqueSupplierName();
        service.create(providerRequest(supplierName, ProviderType.CHAT, "chat-model"));

        DuplicateProviderException ex = assertThrows(DuplicateProviderException.class, () ->
                service.create(providerRequest(supplierName, ProviderType.IMAGE, "image-model")));

        assertEquals("Provider with name '" + supplierName + "' already exists", ex.getMessage());
    }

    @Test
    void rejectsUpdatingSupplierNameToExistingProviderRegardlessOfType() {
        ModelProvider chatProvider = service.create(providerRequest(uniqueSupplierName(), ProviderType.CHAT, "chat-model"));
        ModelProvider imageProvider = service.create(providerRequest(uniqueSupplierName(), ProviderType.IMAGE, "image-model"));

        ModelProviderRequest duplicateNameRequest = providerRequest(
                chatProvider.getProviderId(),
                ProviderType.IMAGE,
                imageProvider.getModelName());

        DuplicateProviderException ex = assertThrows(DuplicateProviderException.class, () ->
                service.update(imageProvider.getId(), duplicateNameRequest));

        assertEquals("Provider with name '" + chatProvider.getProviderId() + "' already exists", ex.getMessage());
    }

    private static ModelProviderRequest providerRequest(String supplierName, ProviderType type, String modelName) {
        ModelProviderRequest request = new ModelProviderRequest();
        request.setProviderId(supplierName);
        request.setName("Test " + supplierName);
        request.setType(type);
        request.setModelName(modelName);
        request.setBaseUrl("https://mock-provider.test/v1");
        request.setApiKey("test-api-key");
        request.setIsActive(false);
        return request;
    }

    private static String uniqueSupplierName() {
        return "supplier-" + UUID.randomUUID();
    }
}
