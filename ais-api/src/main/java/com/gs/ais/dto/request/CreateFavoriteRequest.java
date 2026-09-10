package com.gs.ais.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "收藏一条生成图片消息")
public class CreateFavoriteRequest {

    @Schema(description = "要收藏的消息 ID（必须包含生成图片）", example = "42")
    private Long messageId;

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
}
