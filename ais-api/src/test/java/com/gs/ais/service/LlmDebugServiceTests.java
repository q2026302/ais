package com.gs.ais.service;

import com.gs.ais.config.LlmConfigProperties;
import com.gs.ais.service.LlmDebugService.LlmDebugExchange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmDebugServiceTests {

    @Test
    void runtimeSwitchChangesImmediatelyAndKeepsExistingRecordsVisible() {
        LlmDebugService service = service(false, 5);
        assertFalse(service.isEnabled());

        service.setEnabled(true);
        assertTrue(service.isEnabled());
        service.beginExchange(started("request-1"));
        service.completeExchange("request-1", 200, Map.of("Content-Type", List.of("application/json")),
                2, "UTF-8", "{}", 15);

        service.setEnabled(false);

        assertFalse(service.isEnabled());
        assertEquals(1, service.getStatus().recordCount());
        assertEquals("{}", service.getExchange("request-1").orElseThrow().responseBody());
    }

    @Test
    void evictsOldestExchangeWhenConfiguredLimitIsReached() {
        LlmDebugService service = service(true, 2);

        service.beginExchange(started("request-1"));
        service.beginExchange(started("request-2"));
        service.beginExchange(started("request-3"));

        assertEquals(List.of("request-3", "request-2"),
                service.listExchanges(10).stream().map(LlmDebugService.LlmDebugExchangeSummary::id).toList());
        assertTrue(service.getExchange("request-1").isEmpty());
    }

    private LlmDebugService service(boolean enabled, int maxRecords) {
        LlmConfigProperties config = new LlmConfigProperties();
        config.getDebugLogging().setEnabled(enabled);
        config.getDebugLogging().setMaxRecords(maxRecords);
        return new LlmDebugService(config);
    }

    private LlmDebugExchange started(String id) {
        return LlmDebugExchange.started(
                id,
                Instant.parse("2026-07-14T00:00:00Z"),
                "POST",
                "https://provider.test/v1/chat/completions",
                Map.of("Authorization", List.of("***REDACTED***")),
                2,
                "UTF-8",
                "{}");
    }
}
