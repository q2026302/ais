package com.gs.ais.dto.request;

import jakarta.validation.constraints.NotNull;

public class UpdateUserDefaultsRequest {
    private Long defaultChatProviderId;
    private Long defaultImageProviderId;

    public Long getDefaultChatProviderId() { return defaultChatProviderId; }
    public void setDefaultChatProviderId(Long value) { this.defaultChatProviderId = value; }
    public Long getDefaultImageProviderId() { return defaultImageProviderId; }
    public void setDefaultImageProviderId(Long value) { this.defaultImageProviderId = value; }
}