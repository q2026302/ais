package com.gs.ais.service;

import com.gs.ais.dto.request.ModelProviderRequest;
import com.gs.ais.dto.request.ProviderAccountRequest;
import com.gs.ais.dto.request.ProviderModelRequest;
import com.gs.ais.exception.DuplicateProviderException;
import com.gs.ais.exception.ProviderNotFoundException;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.model.entity.ApiProvider;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.ApiProviderRepository;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.util.ApiRequestUrlUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ModelProviderService {

    private final ModelProviderRepository repository;
    private final ApiProviderRepository apiProviderRepository;
    private final ProviderAccountService providerAccountService;
    private final SystemModelSettingsService settingsService;

    public ModelProviderService(ModelProviderRepository repository,
                                ApiProviderRepository apiProviderRepository,
                                ProviderAccountService providerAccountService,
                                SystemModelSettingsService settingsService) {
        this.repository = repository;
        this.apiProviderRepository = apiProviderRepository;
        this.providerAccountService = providerAccountService;
        this.settingsService = settingsService;
    }

    @Transactional(readOnly = true)
    public ModelProvider getActiveProvider(ProviderType type) {
        return settingsService.getDefaultProvider(type);
    }

    @Transactional(readOnly = true)
    public ModelProvider getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ProviderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<ModelProvider> getAll(ProviderType type) {
        return type == null ? repository.findAll() : repository.findByType(type);
    }

    /** Compatibility API: creates one supplier account containing one model. */
    public ModelProvider create(ModelProviderRequest request) {
        String providerKey = request.getProviderId().trim();
        if (apiProviderRepository.existsByProviderKey(providerKey)
                || repository.existsByProviderId(providerKey)) {
            throw duplicate(providerKey);
        }

        ProviderAccountRequest accountRequest = new ProviderAccountRequest();
        accountRequest.setProviderKey(providerKey);
        accountRequest.setName(request.getName());
        accountRequest.setBaseUrl(request.getBaseUrl());
        accountRequest.setApiKey(request.getApiKey());
        accountRequest.setModels(List.of(toModelRequest(request, null)));

        ApiProvider account = providerAccountService.create(accountRequest);
        ModelProvider model = account.getModels().getFirst();
        if (Boolean.TRUE.equals(request.getIsActive())) {
            model = setActive(model.getId());
        }
        return model;
    }

    /** Compatibility API: updates the selected model and its owning supplier account. */
    public ModelProvider update(Long id, ModelProviderRequest request) {
        ModelProvider target = getById(id);
        if (target.getApiProvider() == null) {
            return updateLegacyModel(target, request);
        }

        ApiProvider account = providerAccountService.getById(target.getApiProvider().getId());
        String providerKey = request.getProviderId().trim();
        apiProviderRepository.findByProviderKey(providerKey)
                .filter(existing -> !existing.getId().equals(account.getId()))
                .ifPresent(existing -> { throw duplicate(providerKey); });

        ProviderAccountRequest accountRequest = new ProviderAccountRequest();
        accountRequest.setProviderKey(providerKey);
        accountRequest.setName(request.getName());
        accountRequest.setBaseUrl(request.getBaseUrl());
        accountRequest.setApiKey(request.getApiKey());
        List<ProviderModelRequest> models = new ArrayList<>();
        for (ModelProvider model : account.getModels()) {
            if (model.getId().equals(id)) {
                models.add(toModelRequest(request, id));
            } else {
                models.add(toModelRequest(model));
            }
        }
        accountRequest.setModels(models);
        providerAccountService.update(account.getId(), accountRequest);

        ModelProvider updated = getById(id);
        if (Boolean.TRUE.equals(request.getIsActive())) {
            return setActive(id);
        }
        if (settingsService.isDefault(updated)) {
            settingsService.clearModel(id);
            updated.setActive(false);
            repository.save(updated);
        }
        return updated;
    }

    public void delete(Long id) {
        ModelProvider model = getById(id);
        settingsService.clearModel(id);
        ApiProvider account = model.getApiProvider();
        if (account == null) {
            repository.delete(model);
            return;
        }
        ApiProvider detailed = providerAccountService.getById(account.getId());
        if (detailed.getModels().size() == 1) {
            providerAccountService.delete(detailed.getId());
        } else {
            ModelProvider attached = detailed.getModels().stream()
                    .filter(item -> item.getId().equals(id))
                    .findFirst().orElseThrow(() -> new ProviderNotFoundException(id));
            detailed.removeModel(attached);
            apiProviderRepository.save(detailed);
        }
    }

    public ModelProvider setActive(Long id) {
        ModelProvider target = getById(id);
        return settingsService.setDefault(target.getType(), id);
    }

    @Transactional(readOnly = true)
    public boolean isDefault(ModelProvider model) {
        return settingsService.isDefault(model);
    }

    private ModelProvider updateLegacyModel(ModelProvider provider, ModelProviderRequest request) {
        boolean providerIdChanged = !provider.getLegacyProviderId().equals(request.getProviderId());
        if (providerIdChanged) {
            Optional<ModelProvider> existing = repository.findByProviderId(request.getProviderId());
            if (existing.isPresent() && !existing.get().getId().equals(provider.getId())) {
                throw duplicate(request.getProviderId());
            }
        }
        applyLegacyRequest(provider, request);
        provider = repository.save(provider);
        if (Boolean.TRUE.equals(request.getIsActive())) return setActive(provider.getId());
        if (settingsService.isDefault(provider)) settingsService.clearModel(provider.getId());
        provider.setActive(false);
        return repository.save(provider);
    }

    private void applyLegacyRequest(ModelProvider provider, ModelProviderRequest request) {
        provider.setProviderId(request.getProviderId());
        provider.setName(hasText(request.getName()) ? request.getName().trim() : request.getProviderId());
        provider.setType(request.getType());
        provider.setModelName(request.getModelName());
        provider.setBaseUrl(ApiRequestUrlUtils.normalizeBaseUrl(request.getBaseUrl()));
        if (hasText(request.getApiKey())) provider.setApiKey(request.getApiKey());
        applyModelSettings(provider, toModelRequest(request, provider.getId()));
    }

    private ProviderModelRequest toModelRequest(ModelProviderRequest request, Long id) {
        ProviderModelRequest model = new ProviderModelRequest();
        model.setId(id);
        model.setType(request.getType());
        model.setModelName(request.getModelName());
        model.setSystemPrompt(request.getSystemPrompt());
        model.setReasoningEffort(request.getReasoningEffort());
        model.setTemperature(request.getTemperature());
        model.setTimeoutSeconds(request.getTimeoutSeconds());
        model.setMaxRetries(request.getMaxRetries());
        model.setRetryBackoffSeconds(request.getRetryBackoffSeconds());
        model.setAdapterType(request.getAdapterType());
        model.setConfigJson(request.getConfigJson());
        return model;
    }

    private ProviderModelRequest toModelRequest(ModelProvider source) {
        ProviderModelRequest model = new ProviderModelRequest();
        model.setId(source.getId());
        model.setType(source.getType());
        model.setModelName(source.getModelName());
        model.setSystemPrompt(source.getSystemPrompt());
        model.setReasoningEffort(source.getReasoningEffort());
        model.setTemperature(source.getTemperature());
        model.setTimeoutSeconds(source.getTimeoutSeconds());
        model.setMaxRetries(source.getMaxRetries());
        model.setRetryBackoffSeconds(source.getRetryBackoffSeconds());
        model.setAdapterType(source.getAdapterType());
        model.setConfigJson(source.getConfigJson());
        return model;
    }

    private void applyModelSettings(ModelProvider provider, ProviderModelRequest request) {
        provider.setSystemPrompt(request.getType() == ProviderType.CHAT ? request.getSystemPrompt() : null);
        provider.setReasoningEffort(request.getType() == ProviderType.CHAT
                ? defaultText(request.getReasoningEffort(), ModelProviderDefaults.REASONING_EFFORT) : null);
        provider.setTemperature(request.getType() == ProviderType.CHAT ? request.getTemperature() : null);
        int timeout = request.getType() == ProviderType.IMAGE
                ? ModelProviderDefaults.IMAGE_TIMEOUT_SECONDS : ModelProviderDefaults.CHAT_TIMEOUT_SECONDS;
        provider.setTimeoutSeconds(defaultPositive(request.getTimeoutSeconds(), timeout));
        provider.setMaxRetries(request.getType() == ProviderType.IMAGE
                ? defaultNonNegative(request.getMaxRetries(), ModelProviderDefaults.IMAGE_MAX_RETRIES) : null);
        provider.setRetryBackoffSeconds(request.getType() == ProviderType.IMAGE
                ? defaultPositive(request.getRetryBackoffSeconds(), ModelProviderDefaults.IMAGE_RETRY_BACKOFF_SECONDS) : null);
        provider.setAdapterType(request.getType() == ProviderType.IMAGE
                ? defaultText(request.getAdapterType(), ModelProviderDefaults.IMAGE_ADAPTER_TYPE) : null);
        provider.setConfigJson(request.getConfigJson());
    }

    private DuplicateProviderException duplicate(String providerKey) {
        return new DuplicateProviderException("Provider with name '" + providerKey + "' already exists");
    }

    private int defaultPositive(Integer value, int fallback) { return value == null || value <= 0 ? fallback : value; }
    private int defaultNonNegative(Integer value, int fallback) { return value == null || value < 0 ? fallback : value; }
    private String defaultText(String value, String fallback) { return hasText(value) ? value.trim() : fallback; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
