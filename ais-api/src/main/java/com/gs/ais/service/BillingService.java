package com.gs.ais.service;

import com.gs.ais.model.entity.BillingRecord;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.repository.BillingRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final BillingRecordRepository billingRecordRepository;

    public BillingService(BillingRecordRepository billingRecordRepository) {
        this.billingRecordRepository = billingRecordRepository;
    }

    @Transactional
    public void recordGeneration(ModelProvider provider, Long userId, Long sessionId, Long messageId) {
        if (provider == null || userId == null) return;

        String billingMode = provider.getBillingMode();
        BigDecimal pricePerUnit = provider.getPricePerUnit();

        if (billingMode == null || pricePerUnit == null) return;

        BigDecimal amount = BigDecimal.ZERO;

        if ("PER_CALL".equalsIgnoreCase(billingMode)) {
            amount = pricePerUnit;
        } else if ("PER_TOKEN".equalsIgnoreCase(billingMode)) {
            // For image generation, we don't have token counts, so use a flat rate
            amount = pricePerUnit;
        }

        BillingRecord record = new BillingRecord();
        record.setUserId(userId);
        record.setProviderId(provider.getId());
        record.setProviderName(provider.getName());
        record.setModelName(provider.getModelName());
        record.setBillingMode(billingMode);
        record.setUnitPrice(pricePerUnit);
        record.setAmount(amount);
        record.setSessionId(sessionId);
        record.setMessageId(messageId);
        record.setDescription("图片生成 - " + provider.getModelName());
        billingRecordRepository.save(record);

        log.debug("Billing record created: user={}, provider={}, amount={}", userId, provider.getName(), amount);
    }

    @Transactional
    public void recordChat(ModelProvider provider, Long userId, Long sessionId, Long messageId,
                           Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        if (provider == null || userId == null) return;

        String billingMode = provider.getBillingMode();
        BigDecimal pricePerUnit = provider.getPricePerUnit();

        if (billingMode == null || pricePerUnit == null) return;

        BigDecimal amount = BigDecimal.ZERO;

        if ("PER_CALL".equalsIgnoreCase(billingMode)) {
            amount = pricePerUnit;
        } else if ("PER_TOKEN".equalsIgnoreCase(billingMode)) {
            int tokens = totalTokens != null ? totalTokens : (promptTokens != null ? promptTokens : 0);
            amount = pricePerUnit.multiply(BigDecimal.valueOf(tokens));
        }

        BillingRecord record = new BillingRecord();
        record.setUserId(userId);
        record.setProviderId(provider.getId());
        record.setProviderName(provider.getName());
        record.setModelName(provider.getModelName());
        record.setPromptTokens(promptTokens);
        record.setCompletionTokens(completionTokens);
        record.setTotalTokens(totalTokens);
        record.setBillingMode(billingMode);
        record.setUnitPrice(pricePerUnit);
        record.setAmount(amount);
        record.setSessionId(sessionId);
        record.setMessageId(messageId);
        record.setDescription("对话 - " + provider.getModelName());
        billingRecordRepository.save(record);

        log.debug("Billing record created: user={}, provider={}, tokens={}, amount={}",
                userId, provider.getName(), totalTokens, amount);
    }

    @Transactional(readOnly = true)
    public Page<BillingRecord> getUserBillingLogs(Long userId, int page, int size) {
        return billingRecordRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Page<BillingRecord> getAllBillingLogs(Long userId, int page, int size) {
        if (userId != null) {
            return billingRecordRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        }
        return billingRecordRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }
}