package com.gs.ais.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.config.SecurityProperties;
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AccessTokenService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AccessTokenService(SecurityProperties properties,
                              ObjectMapper objectMapper,
                              AppUserRepository appUserRepository,
                              PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthPrincipal authenticateWithPasswordDigest(String username, String passwordDigest) {
        if (!StringUtils.hasText(username) || !PasswordDigests.isMd5Hex(passwordDigest)) {
            throw new AuthException(401, "用户名或密码不能为空");
        }
        AppUser user = appUserRepository.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new AuthException(401, "用户名或密码不正确"));
        if (!user.isEnabled()) {
            throw new AuthException(403, "账号已禁用");
        }
        if (!passwordEncoder.matches(passwordDigest, user.getPasswordHash())) {
            throw new AuthException(401, "用户名或密码不正确");
        }
        return new AuthPrincipal(user.getRole(), user.getUsername());
    }

    public String hashPasswordDigest(String passwordDigest) {
        if (!PasswordDigests.isMd5Hex(passwordDigest)) {
            throw new IllegalArgumentException("password digest must be a 32-character MD5 hex value");
        }
        return passwordEncoder.encode(passwordDigest.toLowerCase(java.util.Locale.ROOT));
    }

    public String issueToken(AuthPrincipal principal) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.getSessionTtl());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", principal.subject());
        payload.put("role", principal.role().name());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());
        try {
            String body = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            String signature = sign(body);
            return body + "." + signature;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to issue auth token", e);
        }
    }

    public AuthPrincipal parseToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new AuthException(401, "缺少访问凭证");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new AuthException(401, "访问凭证格式无效");
        }
        String body = parts[0];
        String signature = parts[1];
        if (!constantTimeEquals(signature, sign(body))) {
            throw new AuthException(401, "访问凭证签名无效");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(URL_DECODER.decode(body), Map.class);
            Object expValue = payload.get("exp");
            if (expValue instanceof Number number) {
                if (Instant.now().getEpochSecond() > number.longValue()) {
                    throw new AuthException(401, "访问凭证已过期，请重新登录");
                }
            } else {
                throw new AuthException(401, "访问凭证缺少过期时间");
            }
            String roleName = String.valueOf(payload.get("role"));
            AuthRole role = AuthRole.valueOf(roleName);
            String subject = String.valueOf(payload.getOrDefault("sub", "user"));
            return new AuthPrincipal(role, subject);
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(401, "访问凭证无法解析");
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.resolvedTokenSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign auth token", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
