package com.gs.ais.client;

import com.gs.ais.service.LlmDebugService;
import com.gs.ais.service.LlmDebugService.LlmDebugExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Logs complete HTTP request and response packets exchanged with model providers.
 * Authentication and cookie headers are deliberately masked, while bodies are kept
 * complete. Text bodies are logged as text; binary and multipart bodies are logged
 * as Base64 so no bytes are lost or written as control characters.
 */
final class LlmTrafficLoggingInterceptor implements ClientHttpRequestInterceptor {

    static final String LOGGER_NAME = "com.gs.ais.llm.traffic";

    private static final Logger trafficLog = LoggerFactory.getLogger(LOGGER_NAME);
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "x-goog-api-key",
            "api-key",
            "x-api-key",
            "cookie",
            "set-cookie"
    );
    private static final Pattern SENSITIVE_QUERY_PARAMETER = Pattern.compile(
            "(?i)([?&](?:api[_-]?key|key|token|access[_-]?token)=)[^&]*");

    private final LlmDebugService debugService;

    LlmTrafficLoggingInterceptor(LlmDebugService debugService) {
        this.debugService = debugService;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (!debugService.isEnabled()) {
            return execution.execute(request, body);
        }

        String requestId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        BodyDump requestBody = dumpBody(body, request.getHeaders().getContentType());
        String sanitizedUrl = sanitizeUri(request.getURI());
        Map<String, List<String>> requestHeaders = sanitizeHeaders(request.getHeaders());

        debugService.beginExchange(LlmDebugExchange.started(
                requestId,
                startedAt,
                request.getMethod().name(),
                sanitizedUrl,
                requestHeaders,
                body.length,
                requestBody.encoding(),
                requestBody.value()));

        trafficLog.info("""
                [LLM-HTTP][{}] >>> REQUEST
                method={}
                url={}
                headers={}
                bodyLength={}
                bodyEncoding={}
                body:
                {}""",
                requestId,
                request.getMethod(),
                sanitizedUrl,
                requestHeaders,
                body.length,
                requestBody.encoding(),
                requestBody.value());

        try {
            ClientHttpResponse response = execution.execute(request, body);
            byte[] responseBytes = StreamUtils.copyToByteArray(response.getBody());
            BodyDump responseBody = dumpBody(responseBytes, response.getHeaders().getContentType());
            long elapsedMs = elapsedMillis(startedNanos);
            int responseStatus = response.getStatusCode().value();
            Map<String, List<String>> responseHeaders = sanitizeHeaders(response.getHeaders());

            debugService.completeExchange(
                    requestId,
                    responseStatus,
                    responseHeaders,
                    responseBytes.length,
                    responseBody.encoding(),
                    responseBody.value(),
                    elapsedMs);

            trafficLog.info("""
                    [LLM-HTTP][{}] <<< RESPONSE
                    status={}
                    elapsedMs={}
                    headers={}
                    bodyLength={}
                    bodyEncoding={}
                    body:
                    {}""",
                    requestId,
                    responseStatus,
                    elapsedMs,
                    responseHeaders,
                    responseBytes.length,
                    responseBody.encoding(),
                    responseBody.value());
            return new ReplayableClientHttpResponse(response, responseBytes);
        } catch (IOException | RuntimeException e) {
            long elapsedMs = elapsedMillis(startedNanos);
            debugService.failExchange(requestId, e, elapsedMs);
            trafficLog.error("[LLM-HTTP][{}] !!! TRANSPORT ERROR after {}ms: {}: {}",
                    requestId, elapsedMs, e.getClass().getName(), e.getMessage(), e);
            throw e;
        }
    }

    static Map<String, List<String>> sanitizeHeaders(HttpHeaders headers) {
        Map<String, List<String>> sanitized = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                sanitized.put(name, List.of("***REDACTED***"));
            } else {
                sanitized.put(name, List.copyOf(values));
            }
        });
        return sanitized;
    }

    static String sanitizeUri(URI uri) {
        return SENSITIVE_QUERY_PARAMETER.matcher(uri.toString()).replaceAll("$1***REDACTED***");
    }

    static BodyDump dumpBody(byte[] body, MediaType contentType) {
        if (body == null || body.length == 0) {
            return new BodyDump("empty", "<empty>");
        }
        if (isTextContent(contentType)) {
            Charset charset = contentType != null && contentType.getCharset() != null
                    ? contentType.getCharset()
                    : StandardCharsets.UTF_8;
            return new BodyDump(charset.name(), new String(body, charset));
        }
        return new BodyDump("base64", Base64.getEncoder().encodeToString(body));
    }

    private static boolean isTextContent(MediaType contentType) {
        if (contentType == null) return true;
        String type = contentType.getType().toLowerCase(Locale.ROOT);
        String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
        return "text".equals(type)
                || subtype.equals("json")
                || subtype.endsWith("+json")
                || subtype.equals("xml")
                || subtype.endsWith("+xml")
                || subtype.equals("x-www-form-urlencoded");
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }


    private static final class ReplayableClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        private ReplayableClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    record BodyDump(String encoding, String value) {
    }
}
