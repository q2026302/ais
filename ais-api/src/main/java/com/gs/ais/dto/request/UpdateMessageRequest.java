package com.gs.ais.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "更新消息内容请求")
public class UpdateMessageRequest {

    @NotBlank
    @Schema(description = "更新后的消息内容", requiredMode = Schema.RequiredMode.REQUIRED, example = "修改后的提示词", minLength = 1)
    private String content;

    @Schema(hidden = true)
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
