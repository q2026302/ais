package com.gs.ais.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Extracts a readable and safe error description from model-provider failures.
 * Provider response bodies are preferred over Spring's generic HTTP exception text.
 */
public final class LlmErrorMessageUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_ERROR_LENGTH = 8_192;
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;\\\"']+");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(\\\"?(?:authorization|api[_-]?key|access[_-]?token)\\\"?\\s*[:=]\\s*\\\"?)([^\\\"'\\s,}]+)");

    private LlmErrorMessageUtils() {
    }

    /**
     * Returns the most useful error shown to users and persisted in conversation history.
     */
    public static String describe(Throwable error) {
        if (error == null) return "模型请求失败，未提供错误详情。";

        RestClientResponseException responseError = findResponseError(error);
        if (responseError != null) {
            String detail = extractResponseBodyMessage(responseError.getResponseBodyAsString());
            if (!hasText(detail)) detail = responseError.getStatusText();
            if (!hasText(detail)) detail = responseError.getMessage();
            return sanitizeAndLimit("HTTP " + responseError.getStatusCode().value()
                    + (hasText(detail) ? ": " + detail : ""));
        }

        Throwable current = error;
        String detail = null;
        while (current != null) {
            if (hasText(current.getMessage())) detail = current.getMessage().trim();
            current = current.getCause();
        }
        return sanitizeAndLimit(hasText(detail) ? detail : error.getClass().getSimpleName());
    }

    /**
     * Detects error payloads returned with a successful HTTP status, for example
     * {"error":{"message":"..."}}.
     */
    public static Optional<String> payloadError(Object payload) {
        if (!(payload instanceof Map<?, ?> map) || !map.containsKey("error") || map.get("error") == null) {
            return Optional.empty();
        }
        String detail = describeErrorValue(map.get("error"));
        return hasText(detail) ? Optional.of(sanitizeAndLimit(detail)) : Optional.empty();
    }

    static String extractResponseBodyMessage(String body) {
        if (!hasText(body)) return null;
        String trimmed = body.trim();
        try {
            Object payload = OBJECT_MAPPER.readValue(trimmed, Object.class);
            Optional<String> providerError = payloadError(payload);
            if (providerError.isPresent()) return providerError.get();
            if (payload instanceof Map<?, ?> map) {
                String message = firstText(map, "message", "detail", "error_description");
                if (hasText(message)) return message;
            }
            if (payload instanceof String text && hasText(text)) return text;
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private static RestClientResponseException findResponseError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RestClientResponseException responseError) return responseError;
            current = current.getCause();
        }
        return null;
    }

    private static String describeErrorValue(Object value) {
        if (value instanceof String text) return text.trim();
        if (!(value instanceof Map<?, ?> map)) return String.valueOf(value);

        String message = firstText(map, "message", "detail", "error_description", "reason");
        Object nestedError = map.get("error");
        if (!hasText(message) && nestedError != null && nestedError != value) {
            message = describeErrorValue(nestedError);
        }

        List<String> metadata = new ArrayList<>();
        appendMetadata(metadata, map, "code");
        appendMetadata(metadata, map, "status");
        appendMetadata(metadata, map, "type");

        if (hasText(message)) {
            return metadata.isEmpty() ? message : message + " (" + String.join(", ", metadata) + ")";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (Exception ignored) {
            return String.valueOf(map);
        }
    }

    private static void appendMetadata(List<String> metadata, Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value != null && hasText(String.valueOf(value))) {
            metadata.add(key + ": " + value);
        }
    }

    private static String firstText(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String text && hasText(text)) return text.trim();
        }
        return null;
    }

    private static String sanitizeAndLimit(String value) {
        String sanitized = value == null ? "" : value.trim();
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer ***");
        sanitized = OPENAI_STYLE_KEY.matcher(sanitized).replaceAll("sk-***");
        sanitized = SENSITIVE_FIELD.matcher(sanitized).replaceAll("$1***");
        if (sanitized.length() <= MAX_ERROR_LENGTH) return sanitized;
        return sanitized.substring(0, MAX_ERROR_LENGTH) + "\n…（错误信息已截断）";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
