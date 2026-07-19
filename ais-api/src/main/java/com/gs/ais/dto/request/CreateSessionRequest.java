package com.gs.ais.dto.request;

import jakarta.validation.constraints.Size;

/** Optional initial settings for a newly created conversation. */
public class CreateSessionRequest {

    @Size(max = 255, message = "会话标题不能超过 255 个字符")
    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
