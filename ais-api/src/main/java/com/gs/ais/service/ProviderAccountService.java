package com.gs.ais.service;

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

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProviderAccountService {

    private final ApiProviderRepository providerRepository;
    private final ModelProviderRepository modelRepository;
    private final SystemModelSettingsService settingsService;

    public ProviderAccountService(ApiProviderRepository providerRepository,
                                  ModelProviderRepository modelRepository,
                                  SystemModelSettingsService settingsService) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.settingsService = settingsService;
    }

    @Transactional(readOnly = true)
    public List<ApiProvider> getAll() {
        List<ApiProvider> providers = providerRepository.findAllByOrderByNameAsc();
        providers.forEach(this::hydrateModels);
        return providers;
    }

    @Transactional(readOnly = true)
    public ApiProvider getById(Long id) {
        ApiProvider provider = providerRepository.findById(id)
                .orElseThrow(() -> new ProviderNotFoundException("API provider not found with id: " + id));
        hydrateModels(provider);
        return provider;
    }

    public ApiProvider create(ProviderAccountRequest request) {
        String providerKey = normalizedKey(request.getProviderKey());
        if (providerRepository.existsByProviderKey(providerKey)) {
            throw duplicate(providerKey);
        }

        ApiProvider provider = new ApiProvider();
        provider.setProviderKey(providerKey);
        applyProviderFields(provider, request, false);
        ApiProvider saved = providerRepository.saveAndFlush(provider);
        for (ProviderModelRequest modelRequest : request.getModels()) {
            ModelProvider model = newModel(saved, modelRequest);
            model.setApiProvider(saved);
            modelRepository.save(model);
        }
        hydrateModels(saved);
        return saved;
    }

    public ApiProvider update(Long id, ProviderAccountRequest request) {
        ApiProvider provider = getById(id);
        String providerKey = normalizedKey(request.getProviderKey());
        providerRepository.findByProviderKey(providerKey)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> { throw duplicate(providerKey); });

        provider.setProviderKey(providerKey);
        applyProviderFields(provider, request, true);

        Map<Long, ModelProvider> existingById = provider.getModels().stream()
                .filter(model -> model.getId() != null)
                .collect(Collectors.toMap(ModelProvider::getId, Function.identity()));
        Set<Long> retainedIds = new HashSet<>();

        for (ProviderModelRequest modelRequest : request.getModels()) {
            ModelProvider model;
            if (modelRequest.getId() == null) {
                model = newModel(provider, modelRequest);
                provider.addModel(model);
            } else {
                model = existingById.get(modelRequest.getId());
                if (model == null) {
                    throw new IllegalArgumentException("Model " + modelRequest.getId()
                            + " does not belong to provider " + id);
                }
                retainedIds.add(model.getId());
                ProviderType oldType = model.getType();
                applyModelFields(model, modelRequest);
                if (oldType != model.getType()) {
                    settingsService.clearModel(model.getId());
                }
                copyProviderCompatibilityFields(model, provider);
            }
        }

        List<ModelProvider> removed = provider.getModels().stream()
                .filter(model -> model.getId() != null && !retainedIds.contains(model.getId()))
                .toList();
        for (ModelProvider model : removed) {
            settingsService.clearModel(model.getId());
            provider.removeModel(model);
        }

        ApiProvider saved = providerRepository.save(provider);
        hydrateModels(saved);
        return saved;
    }

    private void hydrateModels(ApiProvider provider) {
        if (provider == null || provider.getId() == null) {
            return;
        }
        List<ModelProvider> models = modelRepository.findByApiProviderId(provider.getId());
        // Keep the managed collection instance so Hibernate orphanRemoval stays happy.
        if (provider.getModels() == null) {
            provider.setModels(new ArrayList<>());
        } else {
            provider.getModels().clear();
        }
        for (ModelProvider model : models) {
            model.setApiProvider(provider);
            provider.getModels().add(model);
        }
    }

    public void delete(Long id) {
        ApiProvider provider = getById(id);
        for (ModelProvider model : provider.getModels()) {
            settingsService.clearModel(model.getId());
        }
        providerRepository.delete(provider);
    }

    private void applyProviderFields(ApiProvider provider, ProviderAccountRequest request, boolean editing) {
        provider.setName(hasText(request.getName()) ? request.getName().trim() : provider.getProviderKey());
        provider.setBaseUrl("grsai".equalsIgnoreCase(provider.getProviderKey())
                ? ApiRequestUrlUtils.normalizeRootBaseUrl(request.getBaseUrl())
                : ApiRequestUrlUtils.normalizeBaseUrl(request.getBaseUrl()));
        if (!editing || hasText(request.getApiKey())) {
            provider.setApiKey(request.getApiKey() == null ? "" : request.getApiKey().trim());
        }
        for (ModelProvider model : provider.getModels()) {
            copyProviderCompatibilityFields(model, provider);
        }
    }

    private ModelProvider newModel(ApiProvider provider, ProviderModelRequest request) {
        ModelProvider model = new ModelProvider();
        model.setProviderId(uniqueLegacyModelKey(provider.getProviderKey()));
        model.setActive(false);
        applyModelFields(model, request);
        copyProviderCompatibilityFields(model, provider);
        return model;
    }

    private void applyModelFields(ModelProvider model, ProviderModelRequest request) {
        model.setType(request.getType());
        model.setModelName(request.getModelName().trim());
        model.setSystemPrompt(request.getType() == ProviderType.CHAT ? request.getSystemPrompt() : null);
        model.setReasoningEffort(request.getType() == ProviderType.CHAT
                ? defaultText(request.getReasoningEffort(), ModelProviderDefaults.REASONING_EFFORT)
                : null);
        model.setTemperature(request.getType() == ProviderType.CHAT ? request.getTemperature() : null);
        int timeoutFallback = request.getType() == ProviderType.IMAGE
                ? ModelProviderDefaults.IMAGE_TIMEOUT_SECONDS
                : ModelProviderDefaults.CHAT_TIMEOUT_SECONDS;
        model.setTimeoutSeconds(defaultPositive(request.getTimeoutSeconds(), timeoutFallback));
        model.setMaxRetries(request.getType() == ProviderType.IMAGE
                ? defaultNonNegative(request.getMaxRetries(), ModelProviderDefaults.IMAGE_MAX_RETRIES)
                : null);
        model.setRetryBackoffSeconds(request.getType() == ProviderType.IMAGE
                ? defaultPositive(request.getRetryBackoffSeconds(), ModelProviderDefaults.IMAGE_RETRY_BACKOFF_SECONDS)
                : null);
        model.setAdapterType(request.getType() == ProviderType.IMAGE
                ? defaultText(request.getAdapterType(), ModelProviderDefaults.IMAGE_ADAPTER_TYPE)
                : null);
        model.setConfigJson(request.getConfigJson());
        model.setSupportsTextToImage(request.getType() == ProviderType.IMAGE
                ? request.getSupportsTextToImage() : null);
        model.setSupportsImageToImage(request.getType() == ProviderType.IMAGE
                ? request.getSupportsImageToImage() : null);
        model.setPriceCreditsMin(request.getType() == ProviderType.IMAGE
                ? request.getPriceCreditsMin() : null);
        model.setPriceCreditsMax(request.getType() == ProviderType.IMAGE
                ? request.getPriceCreditsMax() : null);
        model.setPriceCnyMin(request.getType() == ProviderType.IMAGE
                ? request.getPriceCnyMin() : null);
        model.setPriceCnyMax(request.getType() == ProviderType.IMAGE
                ? request.getPriceCnyMax() : null);
        model.setPriceDescription(request.getType() == ProviderType.IMAGE
                ? request.getPriceDescription() : null);
        model.setBillingMode(request.getBillingMode());
        model.setPricePerUnit(request.getPricePerUnit());
    }

    private void copyProviderCompatibilityFields(ModelProvider model, ApiProvider provider) {
        model.setName(provider.getName());
        model.setBaseUrl(provider.getBaseUrl());
        model.setApiKey(provider.getApiKey());
    }

    private String uniqueLegacyModelKey(String providerKey) {
        String prefix = providerKey.length() > 80 ? providerKey.substring(0, 80) : providerKey;
        String candidate;
        do {
            candidate = prefix + "-model-" + UUID.randomUUID();
        } while (modelRepository.existsByProviderId(candidate));
        return candidate;
    }

    private String normalizedKey(String value) {
        return value == null ? "" : value.trim();
    }

    private DuplicateProviderException duplicate(String providerKey) {
        return new DuplicateProviderException("Provider with name '" + providerKey + "' already exists");
    }

    private int defaultPositive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int defaultNonNegative(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
