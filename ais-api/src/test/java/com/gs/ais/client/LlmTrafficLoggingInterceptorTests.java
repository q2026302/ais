package com.gs.ais.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmTrafficLoggingInterceptorTests {

    @Test
    void masksAuthenticationHeadersButKeepsDebuggableHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("secret-token");
        headers.set("x-goog-api-key", "secret-google-key");
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, List<String>> result = LlmTrafficLoggingInterceptor.sanitizeHeaders(headers);

        assertEquals(List.of("***REDACTED***"), result.get(HttpHeaders.AUTHORIZATION));
        assertEquals(List.of("***REDACTED***"), result.get("x-goog-api-key"));
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), result.get(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void masksApiKeysInQueryString() {
        URI uri = URI.create("https://provider.test/models/demo:generateContent?key=secret&alt=json");

        assertEquals(
                "https://provider.test/models/demo:generateContent?key=***REDACTED***&alt=json",
                LlmTrafficLoggingInterceptor.sanitizeUri(uri));
    }

    @Test
    void keepsCompleteJsonBodyAsText() {
        byte[] body = "{\"choices\":[],\"message\":\"完整响应\"}".getBytes(StandardCharsets.UTF_8);

        LlmTrafficLoggingInterceptor.BodyDump dump =
                LlmTrafficLoggingInterceptor.dumpBody(body, MediaType.APPLICATION_JSON);

        assertEquals("UTF-8", dump.encoding());
        assertEquals(new String(body, StandardCharsets.UTF_8), dump.value());
    }

    @Test
    void encodesMultipartOrBinaryBodyAsBase64WithoutLosingBytes() {
        byte[] body = new byte[]{0, 1, 2, 3, -1};

        LlmTrafficLoggingInterceptor.BodyDump dump =
                LlmTrafficLoggingInterceptor.dumpBody(body, MediaType.MULTIPART_FORM_DATA);

        assertEquals("base64", dump.encoding());
        assertEquals(Base64.getEncoder().encodeToString(body), dump.value());
    }
}
