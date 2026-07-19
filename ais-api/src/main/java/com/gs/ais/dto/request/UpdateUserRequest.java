package com.gs.ais.dto.request;

import com.gs.ais.security.AuthRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class UpdateUserRequest {
    @Size(max = 100, message = "displayName 长度不能超过 100 个字符")
    private String displayName;
    @Email(message = "email 格式不正确")
    @Size(max = 254, message = "email 长度不能超过 254 个字符")
    private String email;
    @NotNull(message = "role is required")
    private AuthRole role;
    private boolean enabled;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public AuthRole getRole() { return role; }
    public void setRole(AuthRole role) { this.role = role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
