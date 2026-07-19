package com.gs.ais.dto.request;

import jakarta.validation.constraints.NotBlank;

public class ResetUserPasswordRequest {
    @NotBlank(message = "keyId is required")
    private String keyId;
    @NotBlank(message = "encryptedPassword is required")
    private String encryptedPassword;

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }
}
