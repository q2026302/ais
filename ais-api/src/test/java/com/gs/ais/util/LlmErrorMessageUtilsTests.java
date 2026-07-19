package com.gs.ais.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmErrorMessageUtilsTests {

    @Test
    void extractsCommonNestedProviderErrorAndMetadata() {
        String message = LlmErrorMessageUtils.payloadError(Map.of(
                "error", Map.of(
                        "message", "The model is busy",
                        "code", "model_busy",
                        "type", "upstream_error")))
                .orElseThrow();

        assertTrue(message.contains("The model is busy"));
        assertTrue(message.contains("code: model_busy"));
        assertTrue(message.contains("type: upstream_error"));
    }

    @Test
    void extractsStringAndRootMessageResponseBodies() {
        assertEquals("temporary failure",
                LlmErrorMessageUtils.extractResponseBodyMessage("{\"error\":\"temporary failure\"}"));
        assertEquals("quota exceeded",
                LlmErrorMessageUtils.extractResponseBodyMessage("{\"message\":\"quota exceeded\"}"));
        assertEquals("gateway unavailable",
                LlmErrorMessageUtils.extractResponseBodyMessage("gateway unavailable"));
    }

    @Test
    void ignoresSuccessfulPayloadWithoutErrorField() {
        assertFalse(LlmErrorMessageUtils.payloadError(Map.of("data", Map.of("value", "ok"))).isPresent());
    }

    @Test
    void redactsTokensFromFallbackExceptionMessages() {
        String message = LlmErrorMessageUtils.describe(
                new RuntimeException("Authorization: Bearer secret-token api_key=sk-1234567890abcdef"));

        assertFalse(message.contains("secret-token"));
        assertFalse(message.contains("1234567890abcdef"));
    }
}
