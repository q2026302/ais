package com.gs.ais.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "连接测试结果响应")
public class TestConnectionResponse {

    @Schema(description = "是否连接成功", example = "true")
    private boolean success;

    @Schema(description = "测试结果消息", example = "Connection successful")
    private String message;

    @Schema(description = "响应时间（毫秒）", example = "350")
    private Long responseTimeMs;

    public TestConnectionResponse() {
    }

    public TestConnectionResponse(boolean success, String message, Long responseTimeMs) {
        this.success = success;
        this.message = message;
        this.responseTimeMs = responseTimeMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(Long responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }
}
