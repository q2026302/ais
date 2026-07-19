package com.gs.ais.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "生成操作响应结果")
public class GenerateResponse {

    @Schema(description = "消息 ID（生成成功时返回）")
    private Long messageId;

    @Schema(description = "优化后的提示词（供图像生成使用）", example = "A cute orange cat sitting on a windowsill, basking in the warm sunlight, soft shadows, photorealistic")
    private String optimizedPrompt;

    @Schema(description = "生成图像的 URL 地址", example = "/api/images/test_1_20260711120000_a1b2c3d4.png")
    private String imageUrl;

    @Schema(description = "Token 使用统计")
    private TokenUsage tokenUsage;

    public GenerateResponse() {}

    public GenerateResponse(Long messageId, String optimizedPrompt, String imageUrl) {
        this.messageId = messageId;
        this.optimizedPrompt = optimizedPrompt;
        this.imageUrl = imageUrl;
    }

    public GenerateResponse(Long messageId, String optimizedPrompt, String imageUrl, TokenUsage tokenUsage) {
        this.messageId = messageId;
        this.optimizedPrompt = optimizedPrompt;
        this.imageUrl = imageUrl;
        this.tokenUsage = tokenUsage;
    }

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public String getOptimizedPrompt() { return optimizedPrompt; }
    public void setOptimizedPrompt(String optimizedPrompt) { this.optimizedPrompt = optimizedPrompt; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public TokenUsage getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(TokenUsage tokenUsage) { this.tokenUsage = tokenUsage; }
}
