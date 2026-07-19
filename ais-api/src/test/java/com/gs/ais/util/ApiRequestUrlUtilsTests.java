package com.gs.ais.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiRequestUrlUtilsTests {

    @Test
    void normalizesDomainOnlyAddressToHttpsV1() {
        assertEquals("https://api.example.com/v1", ApiRequestUrlUtils.normalizeBaseUrl("api.example.com"));
        assertEquals("https://api.example.com/v1", ApiRequestUrlUtils.normalizeBaseUrl("https://api.example.com"));
        assertEquals("http://localhost:8080/v1", ApiRequestUrlUtils.normalizeBaseUrl("http://localhost:8080/"));
    }

    @Test
    void preservesProviderRootForNonOpenAiEndpoints() {
        assertEquals("https://grsai.dakka.com.cn",
                ApiRequestUrlUtils.normalizeRootBaseUrl("grsai.dakka.com.cn/"));
        assertEquals("https://grsai.dakka.com.cn/v1/api/generate",
                ApiRequestUrlUtils.rootEndpoint("https://grsai.dakka.com.cn", "/v1/api/generate"));
    }

    @Test
    void keepsExistingPathAndJoinsEndpointCleanly() {
        assertEquals("https://api.example.com/openai", ApiRequestUrlUtils.normalizeBaseUrl("api.example.com/openai/"));
        assertEquals("https://api.example.com/v1/models", ApiRequestUrlUtils.endpoint("api.example.com", "/models"));
    }
}
