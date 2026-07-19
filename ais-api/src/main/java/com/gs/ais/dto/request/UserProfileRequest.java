package com.gs.ais.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public class UserProfileRequest {
    @Size(max = 100, message = "displayName 长度不能超过 100 个字符")
    private String displayName;

    @Email(message = "email 格式不正确")
    @Size(max = 254, message = "email 长度不能超过 254 个字符")
    private String email;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
