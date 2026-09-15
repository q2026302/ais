package com.gs.ais.util;

import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelProviderNamesTests {

    @Test
    void buildsNameAndModelSnapshot() {
        assertEquals("OpenAI / gpt-4o", ModelProviderNames.snapshot(provider("OpenAI", "openai", "gpt-4o")));
    }

    @Test
    void fallsBackToProviderKeyWhenNameIsMissing() {
        assertEquals("openai / gpt-4o", ModelProviderNames.snapshot(provider("  ", "openai", "gpt-4o")));
    }

    @Test
    void toleratesMissingModelName() {
        assertEquals("OpenAI", ModelProviderNames.snapshot(provider("OpenAI", "openai", null)));
        assertEquals("openai / gpt-4o", ModelProviderNames.snapshot(provider(null, "openai", "gpt-4o")));
    }

    @Test
    void returnsNullWhenNoProviderIsAvailable() {
        // 前端据此显示「未记录」，绝不回退到当前选中的模型。
        assertNull(ModelProviderNames.snapshot(null));
    }

    private ModelProvider provider(String name, String providerKey, String modelName) {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.getName()).thenReturn(name);
        when(provider.getProviderId()).thenReturn(providerKey);
        when(provider.getModelName()).thenReturn(modelName);
        when(provider.getType()).thenReturn(ProviderType.CHAT);
        return provider;
    }
}
