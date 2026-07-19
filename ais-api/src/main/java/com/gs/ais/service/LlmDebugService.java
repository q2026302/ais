package com.gs.ais.service;

import com.gs.ais.config.LlmConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime switch and bounded in-memory store for complete LLM HTTP exchanges.
 * The store is intentionally in memory so it can be enabled and cleared without
 * restarting the service or writing prompts/images to persistent storage.
 */
@Service
public class LlmDebugService {

    private static final Logger log = LoggerFactory.getLogger(LlmDebugService.class);

    private final AtomicBoolean enabled;
    private final int maxRecords;
    private final Object monitor = new Object();
    private final LinkedHashMap<String, LlmDebugExchange> exchanges = new LinkedHashMap<>();

    public LlmDebugService(LlmConfigProperties config) {
        LlmConfigProperties.DebugLoggingConfig debugConfig = config.getDebugLogging();
        this.enabled = new AtomicBoolean(debugConfig != null && debugConfig.isEnabled());
        int configuredMax = debugConfig != null ? debugConfig.getMaxRecords() : 20;
        this.maxRecords = Math.max(1, Math.min(configuredMax, 200));
        if (enabled.get()) logEnabledWarning();
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public LlmDebugStatus getStatus() {
        synchronized (monitor) {
            return new LlmDebugStatus(enabled.get(), exchanges.size(), maxRecords);
        }
    }

    public LlmDebugStatus setEnabled(boolean value) {
        boolean previous = enabled.getAndSet(value);
        if (value && !previous) logEnabledWarning();
        if (!value && previous) {
            log.info("LLM full traffic capture disabled at runtime");
        }
        return getStatus();
    }

    public void beginExchange(LlmDebugExchange exchange) {
        if (exchange == null) return;
        synchronized (monitor) {
            exchanges.put(exchange.id(), exchange);
            trimToLimit();
        }
    }

    public void completeExchange(String id, int responseStatus,
                                 Map<String, List<String>> responseHeaders,
                                 int responseBodyLength, String responseBodyEncoding,
                                 String responseBody, long elapsedMs) {
        synchronized (monitor) {
            LlmDebugExchange current = exchanges.get(id);
            if (current == null) return;
            exchanges.put(id, current.complete(
                    responseStatus,
                    immutableHeaders(responseHeaders),
                    responseBodyLength,
                    responseBodyEncoding,
                    responseBody,
                    elapsedMs));
        }
    }

    public void failExchange(String id, Throwable error, long elapsedMs) {
        synchronized (monitor) {
            LlmDebugExchange current = exchanges.get(id);
            if (current == null) return;
            exchanges.put(id, current.fail(
                    error != null ? error.getClass().getName() : "UnknownTransportError",
                    error != null ? error.getMessage() : "Unknown transport error",
                    elapsedMs));
        }
    }

    public List<LlmDebugExchangeSummary> listExchanges(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, maxRecords));
        synchronized (monitor) {
            List<LlmDebugExchange> values = new ArrayList<>(exchanges.values());
            Collections.reverse(values);
            return values.stream().limit(limit).map(LlmDebugExchange::toSummary).toList();
        }
    }

    public Optional<LlmDebugExchange> getExchange(String id) {
        synchronized (monitor) {
            return Optional.ofNullable(exchanges.get(id));
        }
    }

    public LlmDebugStatus clear() {
        synchronized (monitor) {
            exchanges.clear();
            return new LlmDebugStatus(enabled.get(), 0, maxRecords);
        }
    }

    private void trimToLimit() {
        while (exchanges.size() > maxRecords) {
            String oldestId = exchanges.keySet().iterator().next();
            exchanges.remove(oldestId);
        }
    }

    private void logEnabledWarning() {
        log.warn("LLM full traffic capture enabled. Authentication headers are masked, "
                + "but request and response bodies may contain sensitive prompts and images. "
                + "The latest {} exchanges will be retained in memory.", maxRecords);
    }

    public static Map<String, List<String>> immutableHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) return Map.of();
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, values == null ? List.of() : List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }

    public record LlmDebugStatus(boolean enabled, int recordCount, int maxRecords) {
    }

    public record LlmDebugExchangeSummary(
            String id,
            Instant startedAt,
            String method,
            String url,
            Integer responseStatus,
            Long elapsedMs,
            int requestBodyLength,
            Integer responseBodyLength,
            boolean completed,
            String errorType,
            String errorMessage) {
    }

    public record LlmDebugExchange(
            String id,
            Instant startedAt,
            String method,
            String url,
            Map<String, List<String>> requestHeaders,
            int requestBodyLength,
            String requestBodyEncoding,
            String requestBody,
            Integer responseStatus,
            Map<String, List<String>> responseHeaders,
            Integer responseBodyLength,
            String responseBodyEncoding,
            String responseBody,
            Long elapsedMs,
            boolean completed,
            String errorType,
            String errorMessage) {

        public LlmDebugExchange {
            requestHeaders = immutableHeaders(requestHeaders);
            responseHeaders = immutableHeaders(responseHeaders);
        }

        public static LlmDebugExchange started(
                String id,
                Instant startedAt,
                String method,
                String url,
                Map<String, List<String>> requestHeaders,
                int requestBodyLength,
                String requestBodyEncoding,
                String requestBody) {
            return new LlmDebugExchange(
                    id, startedAt, method, url, requestHeaders,
                    requestBodyLength, requestBodyEncoding, requestBody,
                    null, Map.of(), null, null, null,
                    null, false, null, null);
        }

        LlmDebugExchange complete(
                int status,
                Map<String, List<String>> headers,
                int bodyLength,
                String bodyEncoding,
                String body,
                long elapsed) {
            return new LlmDebugExchange(
                    id, startedAt, method, url, requestHeaders,
                    requestBodyLength, requestBodyEncoding, requestBody,
                    status, headers, bodyLength, bodyEncoding, body,
                    elapsed, true, null, null);
        }

        LlmDebugExchange fail(String type, String message, long elapsed) {
            return new LlmDebugExchange(
                    id, startedAt, method, url, requestHeaders,
                    requestBodyLength, requestBodyEncoding, requestBody,
                    null, Map.of(), null, null, null,
                    elapsed, true, type, message);
        }

        LlmDebugExchangeSummary toSummary() {
            return new LlmDebugExchangeSummary(
                    id, startedAt, method, url, responseStatus, elapsedMs,
                    requestBodyLength, responseBodyLength, completed, errorType, errorMessage);
        }
    }
}
