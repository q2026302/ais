package com.gs.ais.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "生成/聊天请求参数")
public class GenerateRequest {

    @Schema(description = "用户输入的提示文本", example = "一只可爱的橘猫坐在窗台上晒太阳", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String prompt;

    @Schema(description = "附件 ID 列表（已上传文件的 ID）", example = "[1, 2, 3]")
    private List<Long> attachmentIds;

    @Schema(description = "对话供应商 ID（不传则使用会话默认配置）", example = "1")
    private Long chatProviderId;

    @Schema(description = "图像供应商 ID（不传则使用会话默认配置）", example = "2")
    private Long imageProviderId;

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public List<Long> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<Long> attachmentIds) { this.attachmentIds = attachmentIds; }
    public Long getChatProviderId() { return chatProviderId; }
    public void setChatProviderId(Long chatProviderId) { this.chatProviderId = chatProviderId; }
    public Long getImageProviderId() { return imageProviderId; }
    public void setImageProviderId(Long imageProviderId) { this.imageProviderId = imageProviderId; }
}
