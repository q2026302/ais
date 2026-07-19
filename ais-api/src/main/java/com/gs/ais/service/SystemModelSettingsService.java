package com.gs.ais.service;

import com.gs.ais.exception.ProviderNotFoundException;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.SystemModelSettings;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.repository.SystemModelSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SystemModelSettingsService {

    private final SystemModelSettingsRepository settingsRepository;
    private final ModelProviderRepository modelRepository;

    public SystemModelSettingsService(SystemModelSettingsRepository settingsRepository,
                                      ModelProviderRepository modelRepository) {
        this.settingsRepository = settingsRepository;
        this.modelRepository = modelRepository;
    }

    @Transactional(readOnly = true)
    public SettingsSnapshot getSnapshot() {
        return settingsRepository.findById(SystemModelSettings.SINGLETON_ID)
                .map(settings -> new SettingsSnapshot(
                        settings.getDefaultChatModelId(), settings.getDefaultImageModelId()))
                .orElseGet(this::legacySnapshot);
    }

    @Transactional(readOnly = true)
    public ModelProvider getDefaultProvider(ProviderType type) {
        SettingsSnapshot snapshot = getSnapshot();
        Long modelId = type == ProviderType.CHAT
                ? snapshot.defaultChatModelId()
                : snapshot.defaultImageModelId();
        if (modelId == null) {
            throw new ProviderNotFoundException("No default model configured for type: " + type);
        }
        ModelProvider model = modelRepository.findById(modelId)
                .orElseThrow(() -> new ProviderNotFoundException(modelId));
        if (model.getType() != type) {
            throw new ProviderNotFoundException("Configured default model " + modelId
                    + " is not of type: " + type);
        }
        return model;
    }

    public SettingsSnapshot update(Long chatModelId, Long imageModelId) {
        validateType(chatModelId, ProviderType.CHAT);
        validateType(imageModelId, ProviderType.IMAGE);

        SystemModelSettings settings = settingsRepository.findById(SystemModelSettings.SINGLETON_ID)
                .orElseGet(SystemModelSettings::new);
        settings.setDefaultChatModelId(chatModelId);
        settings.setDefaultImageModelId(imageModelId);
        settingsRepository.save(settings);
        synchronizeLegacyFlags(chatModelId, imageModelId);
        return new SettingsSnapshot(chatModelId, imageModelId);
    }

    public ModelProvider setDefault(ProviderType type, Long modelId) {
        ModelProvider model = requireType(modelId, type);
        SettingsSnapshot current = getSnapshot();
        Long chatId = type == ProviderType.CHAT ? modelId : current.defaultChatModelId();
        Long imageId = type == ProviderType.IMAGE ? modelId : current.defaultImageModelId();
        update(chatId, imageId);
        return model;
    }

    public void clearModel(Long modelId) {
        settingsRepository.findById(SystemModelSettings.SINGLETON_ID).ifPresent(settings -> {
            boolean changed = false;
            if (modelId.equals(settings.getDefaultChatModelId())) {
                settings.setDefaultChatModelId(null);
                changed = true;
            }
            if (modelId.equals(settings.getDefaultImageModelId())) {
                settings.setDefaultImageModelId(null);
                changed = true;
            }
            if (changed) {
                settingsRepository.save(settings);
            }
        });
    }

    @Transactional(readOnly = true)
    public boolean isDefault(ModelProvider model) {
        SettingsSnapshot settings = getSnapshot();
        Long defaultId = model.getType() == ProviderType.CHAT
                ? settings.defaultChatModelId()
                : settings.defaultImageModelId();
        return model.getId() != null && model.getId().equals(defaultId);
    }

    private void validateType(Long modelId, ProviderType type) {
        if (modelId != null) requireType(modelId, type);
    }

    private ModelProvider requireType(Long modelId, ProviderType type) {
        ModelProvider model = modelRepository.findById(modelId)
                .orElseThrow(() -> new ProviderNotFoundException(modelId));
        if (model.getType() != type) {
            throw new IllegalArgumentException("Model " + modelId + " must be of type " + type);
        }
        return model;
    }

    private SettingsSnapshot legacySnapshot() {
        Long chatId = modelRepository.findByTypeAndIsActiveTrue(ProviderType.CHAT)
                .map(ModelProvider::getId).orElse(null);
        Long imageId = modelRepository.findByTypeAndIsActiveTrue(ProviderType.IMAGE)
                .map(ModelProvider::getId).orElse(null);
        return new SettingsSnapshot(chatId, imageId);
    }

    private void synchronizeLegacyFlags(Long chatModelId, Long imageModelId) {
        for (ModelProvider model : modelRepository.findAll()) {
            if (model == null || model.getId() == null || model.getType() == null) {
                continue;
            }
            boolean active = model.getType() == ProviderType.CHAT
                    ? model.getId().equals(chatModelId)
                    : model.getId().equals(imageModelId);
            if (model.isActive() != active) {
                model.setActive(active);
                modelRepository.save(model);
            }
        }
    }

    public record SettingsSnapshot(Long defaultChatModelId, Long defaultImageModelId) {}
}
