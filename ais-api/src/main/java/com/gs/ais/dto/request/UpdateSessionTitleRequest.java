package com.gs.ais.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UpdateSessionTitleRequest {

    @NotBlank(message = "会话标题不能为空")
    @Size(max = 255, message = "会话标题不能超过 255 个字符")
    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
