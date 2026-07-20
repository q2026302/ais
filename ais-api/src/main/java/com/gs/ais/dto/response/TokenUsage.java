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
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cacheReadTokens;
    private Integer cacheWriteTokens;
    private Integer reasoningTokens;

    public TokenUsage() {}

    public TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.inputTokens = promptTokens;
        this.outputTokens = completionTokens;
    }

    public TokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens,
                      Integer cacheReadTokens, Integer cacheWriteTokens, Integer reasoningTokens) {
        this(inputTokens, outputTokens, totalTokens);
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.reasoningTokens = reasoningTokens;
    }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public Integer getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Integer cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    public Integer getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(Integer cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }
    public Integer getReasoningTokens() { return reasoningTokens; }
    public void setReasoningTokens(Integer reasoningTokens) { this.reasoningTokens = reasoningTokens; }
}
