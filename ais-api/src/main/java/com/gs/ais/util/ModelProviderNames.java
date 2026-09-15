package com.gs.ais.util;

import com.gs.ais.model.entity.ModelProvider;

/**
 * Builds the human-readable model name snapshot stored on a message
 * ({@code 名称 / 模型名}). The same format is rendered by the web clients, so a
 * message keeps showing the model that actually produced it even after the
 * provider row is renamed or deleted.
 */
public final class ModelProviderNames {

    private ModelProviderNames() {
    }

    /** @return the snapshot label, or {@code null} when no provider is available. */
    public static String snapshot(ModelProvider provider) {
        if (provider == null) {
            return null;
        }
        String name = blankToNull(provider.getName());
        if (name == null) {
            name = blankToNull(provider.getProviderId());
        }
        String model = blankToNull(provider.getModelName());
        if (name == null) {
            return model;
        }
        return model == null ? name : name + " / " + model;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
