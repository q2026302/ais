package com.gs.ais.dto.response;

public class SystemModelSettingsResponse {
    private Long defaultChatModelId;
    private Long defaultImageModelId;

    public SystemModelSettingsResponse() {}

    public SystemModelSettingsResponse(Long defaultChatModelId, Long defaultImageModelId) {
        this.defaultChatModelId = defaultChatModelId;
        this.defaultImageModelId = defaultImageModelId;
    }

    public Long getDefaultChatModelId() { return defaultChatModelId; }
    public void setDefaultChatModelId(Long defaultChatModelId) { this.defaultChatModelId = defaultChatModelId; }
    public Long getDefaultImageModelId() { return defaultImageModelId; }
    public void setDefaultImageModelId(Long defaultImageModelId) { this.defaultImageModelId = defaultImageModelId; }
}
