package com.gs.ais.dto.request;

public class SystemModelSettingsRequest {
    private Long defaultChatModelId;
    private Long defaultImageModelId;

    public Long getDefaultChatModelId() { return defaultChatModelId; }
    public void setDefaultChatModelId(Long defaultChatModelId) { this.defaultChatModelId = defaultChatModelId; }
    public Long getDefaultImageModelId() { return defaultImageModelId; }
    public void setDefaultImageModelId(Long defaultImageModelId) { this.defaultImageModelId = defaultImageModelId; }
}
