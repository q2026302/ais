package com.gs.ais.dto.request;

import jakarta.validation.constraints.NotBlank;

public class ChangePasswordRequest {
    @NotBlank(message = "currentKeyId is required")
    private String currentKeyId;
    @NotBlank(message = "currentEncryptedPassword is required")
    private String currentEncryptedPassword;
    @NotBlank(message = "newKeyId is required")
    private String newKeyId;
    @NotBlank(message = "newEncryptedPassword is required")
    private String newEncryptedPassword;

    public String getCurrentKeyId() { return currentKeyId; }
    public void setCurrentKeyId(String currentKeyId) { this.currentKeyId = currentKeyId; }
    public String getCurrentEncryptedPassword() { return currentEncryptedPassword; }
    public void setCurrentEncryptedPassword(String currentEncryptedPassword) { this.currentEncryptedPassword = currentEncryptedPassword; }
    public String getNewKeyId() { return newKeyId; }
    public void setNewKeyId(String newKeyId) { this.newKeyId = newKeyId; }
    public String getNewEncryptedPassword() { return newEncryptedPassword; }
    public void setNewEncryptedPassword(String newEncryptedPassword) { this.newEncryptedPassword = newEncryptedPassword; }
}
