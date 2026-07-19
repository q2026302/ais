package com.gs.ais.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "重新生成或再次发送时的临时模型选择")
public class RegenerateRequest {

    @Schema(description = "本次操作使用的对话供应商 ID；不传则使用会话默认配置", example = "1")
    private Long chatProviderId;

    @Schema(description = "本次操作使用的图像供应商 ID；不传则使用原消息或会话默认配置", example = "2")
    private Long imageProviderId;

    public Long getChatProviderId() { return chatProviderId; }
    public void setChatProviderId(Long chatProviderId) { this.chatProviderId = chatProviderId; }
    public Long getImageProviderId() { return imageProviderId; }
    public void setImageProviderId(Long imageProviderId) { this.imageProviderId = imageProviderId; }
}
