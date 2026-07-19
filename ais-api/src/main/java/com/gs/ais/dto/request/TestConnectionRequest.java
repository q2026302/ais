package com.gs.ais.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "测试连接请求参数")
public class TestConnectionRequest {

    @Schema(description = "供应商标识；用于选择供应商专用的连接测试方式", example = "grsai")
    private String providerKey;

    @Schema(description = "API 请求地址；仅填写域名时会自动补全为 https://域名/v1", example = "https://api.openai.com/v1", requiredMode = Schema.RequiredMode.REQUIRED)
    private String baseUrl;

    @Schema(description = "API 密钥", example = "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
    private String apiKey;

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String providerKey) {
        this.providerKey = providerKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
