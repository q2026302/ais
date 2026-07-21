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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final BillingRecordRepository billingRecordRepository;

    public BillingService(BillingRecordRepository billingRecordRepository) {
        this.billingRecordRepository = billingRecordRepository;
    }

    @Transactional
    public void recordGeneration(ModelProvider provider, Long userId, Long sessionId, Long messageId) {
        recordGeneration(provider, userId, sessionId, messageId, null);
    }

    @Transactional
    public void recordGeneration(ModelProvider provider, Long userId, Long sessionId, Long messageId,
                                 Long durationMs) {
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
        record.setDurationMs(durationMs);
        record.setDescription("图片生成 - " + provider.getModelName());
        billingRecordRepository.save(record);

        log.debug("Billing record created: user={}, provider={}, amount={}", userId, provider.getName(), amount);
    }

    @Transactional
    public void recordChat(ModelProvider provider, Long userId, Long sessionId, Long messageId,
                           Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        recordChat(provider, userId, sessionId, messageId, promptTokens, completionTokens, totalTokens,
                null, null, null, null);
    }

    @Transactional
    public void recordChat(ModelProvider provider, Long userId, Long sessionId, Long messageId,
                           Integer promptTokens, Integer completionTokens, Integer totalTokens,
                           Long durationMs) {
        recordChat(provider, userId, sessionId, messageId, promptTokens, completionTokens, totalTokens,
                null, null, null, durationMs);
    }

    @Transactional
    public void recordChat(ModelProvider provider, Long userId, Long sessionId, Long messageId,
                           Integer promptTokens, Integer completionTokens, Integer totalTokens,
                           Integer cacheReadTokens, Integer cacheWriteTokens, Integer reasoningTokens) {
        recordChat(provider, userId, sessionId, messageId, promptTokens, completionTokens, totalTokens,
                cacheReadTokens, cacheWriteTokens, reasoningTokens, null);
    }

    @Transactional
    public void recordChat(ModelProvider provider, Long userId, Long sessionId, Long messageId,
                           Integer promptTokens, Integer completionTokens, Integer totalTokens,
                           Integer cacheReadTokens, Integer cacheWriteTokens, Integer reasoningTokens,
                           Long durationMs) {
        if (provider == null || userId == null) return;

        String billingMode = provider.getBillingMode();
        BigDecimal pricePerUnit = provider.getPricePerUnit();
        if (billingMode == null || (pricePerUnit == null && "PER_CALL".equalsIgnoreCase(billingMode))) return;

        BigDecimal amount = BigDecimal.ZERO;
        if ("PER_CALL".equalsIgnoreCase(billingMode) || "per_request".equalsIgnoreCase(billingMode)) {
            amount = pricePerUnit;
        } else if ("PER_TOKEN".equalsIgnoreCase(billingMode) || "per_token".equalsIgnoreCase(billingMode)) {
            BigDecimal inputPrice = provider.getInputPricePerMillion();
            BigDecimal outputPrice = provider.getOutputPricePerMillion();
            BigDecimal cachePrice = provider.getCacheReadPricePerMillion();
            if (inputPrice != null && outputPrice != null && cachePrice != null) {
                amount = priceForTokens(promptTokens, inputPrice)
                        .add(priceForTokens(completionTokens, outputPrice))
                        .add(priceForTokens(cacheReadTokens, cachePrice));
            } else if (pricePerUnit != null) {
                int tokens = totalTokens != null ? totalTokens : (promptTokens != null ? promptTokens : 0);
                amount = pricePerUnit.multiply(BigDecimal.valueOf(tokens));
            }
        }

        BillingRecord record = new BillingRecord();
        record.setUserId(userId);
        record.setProviderId(provider.getId());
        record.setProviderName(provider.getName());
        record.setModelName(provider.getModelName());
        record.setPromptTokens(promptTokens);
        record.setCompletionTokens(completionTokens);
        record.setTotalTokens(totalTokens);
        record.setInputTokens(promptTokens);
        record.setOutputTokens(completionTokens);
        record.setCacheReadTokens(cacheReadTokens);
        record.setCacheWriteTokens(cacheWriteTokens);
        record.setReasoningTokens(reasoningTokens);
        record.setBillingMode(billingMode);
        record.setUnitPrice(pricePerUnit);
        record.setAmount(amount);
        record.setSessionId(sessionId);
        record.setMessageId(messageId);
        record.setDurationMs(durationMs);
        record.setDescription("对话 - " + provider.getModelName());
        billingRecordRepository.save(record);
    }

    private BigDecimal priceForTokens(Integer tokens, BigDecimal pricePerMillion) {
        return tokens == null || tokens <= 0 || pricePerMillion == null
                ? BigDecimal.ZERO
                : pricePerMillion.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000L));
    }
    @Transactional(readOnly = true)
    public Page<BillingRecord> getUserBillingLogs(Long userId, int page, int size,
                                                    LocalDate fromDate, LocalDate toDate) {
        LocalDate from = fromDate == null ? LocalDate.now() : fromDate;
        LocalDate to = toDate == null ? from : toDate;
        validateRange(from, to);
        return billingRecordRepository
                .findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        userId, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), PageRequest.of(page, size));
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("结束日期不能早于开始日期");
        if (ChronoUnit.DAYS.between(from, to) > 31) throw new IllegalArgumentException("查询时间范围不能超过 31 天");
    }

    @Transactional(readOnly = true)
    public Page<BillingRecord> getUserBillingLogs(Long userId, int page, int size) {
        return getUserBillingLogs(userId, page, size, LocalDate.now(), LocalDate.now());
    }

    @Transactional(readOnly = true)
    public Page<BillingRecord> getAllBillingLogs(Long userId, int page, int size) {
        if (userId != null) {
            return billingRecordRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        }
        return billingRecordRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }
}