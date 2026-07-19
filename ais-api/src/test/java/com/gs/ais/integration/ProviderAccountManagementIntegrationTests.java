package com.gs.ais.integration;

import com.gs.ais.dto.request.ProviderAccountRequest;
import com.gs.ais.dto.request.ProviderModelRequest;
import com.gs.ais.model.entity.ApiProvider;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.service.ModelProviderService;
import com.gs.ais.service.ProviderAccountService;
import com.gs.ais.service.SystemModelSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "feishu.enabled=false")
@Transactional
class ProviderAccountManagementIntegrationTests {

    @Autowired
    private ProviderAccountService providerAccountService;

    @Autowired
    private SystemModelSettingsService settingsService;

    @Autowired
    private ModelProviderService modelProviderService;

    @Test
    void oneProviderOwnsChatAndImageModelsWithSharedCredentials() {
        String key = "account-" + UUID.randomUUID();
        ProviderAccountRequest request = accountRequest(key, "https://shared.example/v1", "shared-key");
        request.setModels(List.of(model(ProviderType.CHAT, "chat-model"), model(ProviderType.IMAGE, "image-model")));

        ApiProvider saved = providerAccountService.create(request);
        assertEquals(2, saved.getModels().size());
        assertTrue(saved.getModels().stream().allMatch(model -> model.getApiProvider().getId().equals(saved.getId())));
        assertTrue(saved.getModels().stream().allMatch(model -> "https://shared.example/v1".equals(model.getBaseUrl())));
        assertTrue(saved.getModels().stream().allMatch(model -> "shared-key".equals(model.getApiKey())));
    }

    @Test
    void chatAndImageDefaultsAreConfiguredIndependently() {
        ProviderAccountRequest request = accountRequest(
                "defaults-" + UUID.randomUUID(), "https://defaults.example/v1", "key");
        request.setModels(List.of(model(ProviderType.CHAT, "chat-a"), model(ProviderType.IMAGE, "image-a")));
        ApiProvider saved = providerAccountService.create(request);
        ModelProvider chat = saved.getModels().stream().filter(m -> m.getType() == ProviderType.CHAT).findFirst().orElseThrow();
        ModelProvider image = saved.getModels().stream().filter(m -> m.getType() == ProviderType.IMAGE).findFirst().orElseThrow();

        settingsService.update(chat.getId(), image.getId());

        assertEquals(chat.getId(), settingsService.getSnapshot().defaultChatModelId());
        assertEquals(image.getId(), settingsService.getSnapshot().defaultImageModelId());
        assertEquals(chat.getId(), modelProviderService.getActiveProvider(ProviderType.CHAT).getId());
        assertEquals(image.getId(), modelProviderService.getActiveProvider(ProviderType.IMAGE).getId());
    }

    @Test
    void rejectsUsingImageModelAsDefaultChatModel() {
        ProviderAccountRequest request = accountRequest(
                "invalid-default-" + UUID.randomUUID(), "https://defaults.example/v1", "key");
        request.setModels(List.of(model(ProviderType.IMAGE, "image-only")));
        ModelProvider image = providerAccountService.create(request).getModels().getFirst();

        assertThrows(IllegalArgumentException.class, () -> settingsService.update(image.getId(), null));
    }

    private ProviderAccountRequest accountRequest(String key, String baseUrl, String apiKey) {
        ProviderAccountRequest request = new ProviderAccountRequest();
        request.setProviderKey(key);
        request.setName("Test " + key);
        request.setBaseUrl(baseUrl);
        request.setApiKey(apiKey);
        return request;
    }

    private ProviderModelRequest model(ProviderType type, String name) {
        ProviderModelRequest request = new ProviderModelRequest();
        request.setType(type);
        request.setModelName(name);
        return request;
    }
}
