package com.gs.ais.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Token 使用统计")
public class TokenUsage {

    @Schema(description = "提示词消耗的 Token 数", example = "150")
    private Integer promptTokens;

    @Schema(description = "补全消耗的 Token 数", example = "200")
    private Integer completionTokens;

    @Schema(description = "总 Token 消耗数", example = "350")
    private Integer totalTokens;

    public TokenUsage() {}

    public TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
}
