package com.gs.ais.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public final class ApiRequestUrlUtils {

    private static final Pattern SCHEME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

    private ApiRequestUrlUtils() {
    }

    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }

        String normalized = baseUrl.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }

        if (!SCHEME_PATTERN.matcher(normalized).matches()) {
            normalized = "https://" + normalized;
        }

        try {
            URI uri = new URI(normalized);
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                uri = new URI(
                        uri.getScheme(),
                        uri.getRawAuthority(),
                        "/v1",
                        uri.getRawQuery(),
                        uri.getRawFragment());
                normalized = uri.toString();
            }
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // Keep the user's input as-is when URI parsing fails; endpoint() will still join paths safely.
        }

        return trimTrailingSlashes(normalized);
    }

    /** Normalizes a provider host without forcing an OpenAI-style /v1 suffix. */
    public static String normalizeRootBaseUrl(String baseUrl) {
        if (baseUrl == null) return null;
        String normalized = baseUrl.trim();
        if (normalized.isEmpty()) return normalized;
        if (!SCHEME_PATTERN.matcher(normalized).matches()) normalized = "https://" + normalized;
        return trimTrailingSlashes(normalized);
    }

    /** Joins an endpoint to a provider root while preserving its configured path. */
    public static String rootEndpoint(String baseUrl, String path) {
        String normalizedBaseUrl = normalizeRootBaseUrl(baseUrl);
        if (normalizedBaseUrl == null || normalizedBaseUrl.isBlank()) {
            throw new IllegalArgumentException("API request address is required");
        }
        String normalizedPath = path == null ? "" : path.trim();
        while (normalizedPath.startsWith("/")) normalizedPath = normalizedPath.substring(1);
        return trimTrailingSlashes(normalizedBaseUrl) + "/" + normalizedPath;
    }

    public static String endpoint(String baseUrl, String path) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        if (normalizedBaseUrl == null || normalizedBaseUrl.isBlank()) {
            throw new IllegalArgumentException("API request address is required");
        }
        String normalizedPath = path == null ? "" : path.trim();
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        return trimTrailingSlashes(normalizedBaseUrl) + "/" + normalizedPath;
    }

    private static String trimTrailingSlashes(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
