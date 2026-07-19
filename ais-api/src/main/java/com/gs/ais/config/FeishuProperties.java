package com.gs.ais.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    /**
     * The long-connection mode is the preferred transport because it does not
     * require a publicly reachable callback URL.
     */
    private String transport = "websocket";
    private boolean enabled;
    private String appId;
    private String appSecret;
    private String verificationToken;
    private String encryptKey;
    private String apiBaseUrl = "https://open.feishu.cn/open-apis";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }
    public String getEncryptKey() { return encryptKey; }
    public void setEncryptKey(String encryptKey) { this.encryptKey = encryptKey; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }

    public boolean isConfigured() {
        return enabled && isPresent(appId) && isPresent(appSecret);
    }

    public boolean isWebSocketMode() {
        return "websocket".equals(normalizedTransport());
    }

    public boolean isHttpMode() {
        return "http".equals(normalizedTransport());
    }

    public boolean hasSupportedTransport() {
        return isWebSocketMode() || isHttpMode();
    }

    /** Returns the normalized configured value, defaulting blank values to websocket. */
    public String normalizedTransport() {
        return transport == null || transport.isBlank()
                ? "websocket"
                : transport.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
