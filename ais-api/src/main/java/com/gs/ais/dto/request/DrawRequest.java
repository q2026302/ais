package com.gs.ais.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "绘图请求参数")
public class DrawRequest {

    @Schema(description = "绘画提示词", example = "一只橘猫坐在窗台上晒太阳，油画风格")
    private String prompt;

    @Schema(description = "参考图附件 ID 列表", example = "[1, 2]")
    private List<Long> attachmentIds;

    @Schema(description = "图像供应商 ID（不传则使用会话默认配置）", example = "2")
    private Long imageProviderId;

    @Schema(description = "输出尺寸", example = "1024x1024")
    private String size;

    @Schema(description = "输出质量", example = "high")
    private String quality;

    @Schema(description = "输出格式", example = "png")
    private String format;

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public List<Long> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<Long> attachmentIds) { this.attachmentIds = attachmentIds; }
    public Long getImageProviderId() { return imageProviderId; }
    public void setImageProviderId(Long imageProviderId) { this.imageProviderId = imageProviderId; }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
}
