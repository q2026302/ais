package com.gs.ais.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private boolean enabled = true;
    private Duration sessionTtl = Duration.ofHours(12);
    private String tokenSecret = "";
    private List<String> corsAllowedOrigins = new ArrayList<>(List.of("*"));

    /** Seeded only when the user table is empty. */
    private String initialAdminUsername = "admin";
    private String initialAdminPasswordMd5 = "";

    private int maxFailures = 5;
    private int failureWindowMinutes = 15;
    private int lockDurationMinutes = 30;
    private boolean captchaEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl != null ? sessionTtl : Duration.ofHours(12);
    }

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins != null ? corsAllowedOrigins : new ArrayList<>();
    }

    public String getInitialAdminUsername() {
        return initialAdminUsername;
    }

    public void setInitialAdminUsername(String initialAdminUsername) {
        this.initialAdminUsername = initialAdminUsername;
    }

    public String getInitialAdminPasswordMd5() {
        return initialAdminPasswordMd5;
    }

    public void setInitialAdminPasswordMd5(String initialAdminPasswordMd5) {
        this.initialAdminPasswordMd5 = initialAdminPasswordMd5;
    }

    public int getMaxFailures() {
        return maxFailures;
    }

    public void setMaxFailures(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    public int getFailureWindowMinutes() {
        return failureWindowMinutes;
    }

    public void setFailureWindowMinutes(int failureWindowMinutes) {
        this.failureWindowMinutes = failureWindowMinutes;
    }

    public int getLockDurationMinutes() {
        return lockDurationMinutes;
    }

    public void setLockDurationMinutes(int lockDurationMinutes) {
        this.lockDurationMinutes = lockDurationMinutes;
    }

    public boolean isCaptchaEnabled() {
        return captchaEnabled;
    }

    public void setCaptchaEnabled(boolean captchaEnabled) {
        this.captchaEnabled = captchaEnabled;
    }

    public String resolvedTokenSecret() {
        if (StringUtils.hasText(tokenSecret)) {
            return tokenSecret;
        }
        String seed = StringUtils.hasText(initialAdminPasswordMd5)
                ? initialAdminPasswordMd5
                : "ais-dev-secret";
        return "ais:" + seed;
    }

    public boolean hasInitialAdminPasswordMd5Configured() {
        return StringUtils.hasText(initialAdminPasswordMd5);
    }
}
