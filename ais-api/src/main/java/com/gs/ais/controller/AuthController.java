package com.gs.ais.controller;

import com.gs.ais.config.SecurityProperties;
import com.gs.ais.model.entity.SecuritySettings;
import com.gs.ais.dto.request.ChangePasswordRequest;
import com.gs.ais.dto.request.UserProfileRequest;
import com.gs.ais.dto.response.UserResponse;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.security.AccessTokenService;
import com.gs.ais.security.AuthException;
import com.gs.ais.security.AuthPrincipal;
import com.gs.ais.security.AuthRole;
import com.gs.ais.security.AuthContext;
import com.gs.ais.security.CaptchaService;
import com.gs.ais.security.LoginProtectionService;
import com.gs.ais.security.RsaPasswordCryptoService;
import com.gs.ais.service.LoginSecurityEventService;
import com.gs.ais.service.SecuritySettingsService;
import com.gs.ais.service.UserManagementService;
import com.gs.ais.util.ClientIpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AccessTokenService accessTokenService;
    private final SecurityProperties securityProperties;
    private final CaptchaService captchaService;
    private final LoginProtectionService loginProtectionService;
    private final SecuritySettingsService securitySettingsService;
    private final LoginSecurityEventService loginSecurityEventService;
    private final AppUserRepository appUserRepository;
    private final RsaPasswordCryptoService rsaPasswordCryptoService;
    private final UserManagementService userManagementService;

    public AuthController(AccessTokenService accessTokenService,
                          SecurityProperties securityProperties,
                          CaptchaService captchaService,
                          LoginProtectionService loginProtectionService,
                          LoginSecurityEventService loginSecurityEventService,
                          SecuritySettingsService securitySettingsService,
                          AppUserRepository appUserRepository,
                          RsaPasswordCryptoService rsaPasswordCryptoService,
                          UserManagementService userManagementService) {
        this.accessTokenService = accessTokenService;
        this.securityProperties = securityProperties;
        this.captchaService = captchaService;
        this.loginProtectionService = loginProtectionService;
        this.loginSecurityEventService = loginSecurityEventService;
        this.securitySettingsService = securitySettingsService;
        this.appUserRepository = appUserRepository;
        this.rsaPasswordCryptoService = rsaPasswordCryptoService;
        this.userManagementService = userManagementService;
    }

    @GetMapping("/captcha")
    public Map<String, Object> captcha() {
        SecuritySettings settings = securitySettingsService.getOrCreate();
        if (!settings.isCaptchaEnabled()) {
            return Map.of("enabled", false);
        }
        CaptchaService.CaptchaChallenge challenge = captchaService.createChallenge();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        body.put("captchaId", challenge.captchaId());
        body.put("imageBase64", challenge.imageBase64());
        body.put("expiresInSeconds", challenge.expiresInSeconds());
        return body;
    }

    /** Issues a short-lived RSA public key used to encrypt the client-side password digest. */
    @GetMapping("/password-key")
    public Map<String, Object> passwordKey() {
        RsaPasswordCryptoService.PublicKeyChallenge challenge = rsaPasswordCryptoService.createChallenge();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyId", challenge.keyId());
        body.put("publicKey", challenge.publicKey());
        body.put("algorithm", challenge.algorithm());
        body.put("expiresInSeconds", challenge.expiresInSeconds());
        return body;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest httpRequest) {
        if (!securityProperties.isEnabled()) {
            AuthPrincipal principal = new AuthPrincipal(AuthRole.ADMIN, "security-disabled");
            return ResponseEntity.ok(tokenResponse(principal, accessTokenService.issueToken(principal)));
        }

        String ip = ClientIpUtils.resolve(httpRequest);
        loginProtectionService.assertNotLocked(ip);

        SecuritySettings settings = securitySettingsService.getOrCreate();
        if (settings.isCaptchaEnabled()) {
            captchaService.validateAndConsume(request.captchaId(), request.captchaCode());
        }

        try {
            String passwordDigest = rsaPasswordCryptoService.decryptPasswordDigest(
                    request.keyId(), request.encryptedPassword());
            AuthPrincipal principal = accessTokenService.authenticateWithPasswordDigest(
                    request.username(), passwordDigest);
            loginProtectionService.recordSuccess(ip);
            String token = accessTokenService.issueToken(principal);
            return ResponseEntity.ok(tokenResponse(principal, token));
        } catch (AuthException ex) {
            if (ex.getStatus() == 401) {
                loginProtectionService.recordFailure(ip, request.username());
            } else if (ex.getStatus() == 403) {
                loginSecurityEventService.recordBlockedAccount(request.username(), ip);
            }
            throw ex;
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        if (!securityProperties.isEnabled()) {
            try {
                UserResponse user = userManagementService.currentUser(
                        new AuthPrincipal(AuthRole.ADMIN, "security-disabled"));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("authenticated", true);
                body.put("securityEnabled", false);
                body.put("id", user.id());
                body.put("username", user.username());
                body.put("subject", user.username());
                body.put("displayName", user.displayName());
                body.put("email", user.email());
                body.put("role", user.role().name());
                body.put("enabled", user.enabled());
                body.put("createdAt", user.createdAt());
                body.put("updatedAt", user.updatedAt());
                body.put("defaultChatProviderId", user.defaultChatProviderId());
                body.put("defaultImageProviderId", user.defaultImageProviderId());
                return ResponseEntity.ok(body);
            } catch (AuthException ignored) {
                return ResponseEntity.ok(Map.of(
                        "authenticated", true,
                        "role", AuthRole.ADMIN.name(),
                        "securityEnabled", false
                ));
            }
        }
        try {
            AuthPrincipal principal = accessTokenService.parseToken(extractBearer(request));
            UserResponse user = userManagementService.currentUser(principal);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("authenticated", true);
            body.put("securityEnabled", true);
            body.put("id", user.id());
            body.put("username", user.username());
            body.put("subject", user.username());
            body.put("displayName", user.displayName());
            body.put("email", user.email());
            body.put("role", user.role().name());
            body.put("enabled", user.enabled());
            body.put("createdAt", user.createdAt());
            body.put("updatedAt", user.updatedAt());
            body.put("defaultChatProviderId", user.defaultChatProviderId());
            body.put("defaultImageProviderId", user.defaultImageProviderId());
            return ResponseEntity.ok(body);
        } catch (AuthException ex) {
            return ResponseEntity.status(401).body(Map.of(
                    "authenticated", false,
                    "securityEnabled", true,
                    "message", ex.getMessage()
            ));
        }
    }

    @PutMapping("/me")
    public UserResponse updateMe(@Valid @RequestBody UserProfileRequest request) {
        return userManagementService.updateProfile(AuthContext.get(), request);
    }

    @PutMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userManagementService.changePassword(AuthContext.get(), request);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        SecuritySettings settings = securitySettingsService.getOrCreate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("securityEnabled", securityProperties.isEnabled());
        body.put("hasUsers", appUserRepository.count() > 0);
        body.put("captchaEnabled", settings.isCaptchaEnabled());
        body.put("passwordTransport", "RSA-OAEP-256");
        body.put("passwordStorage", "BCrypt(MD5(clientPassword))");
        body.put("initialAdminConfigured", securityProperties.hasInitialAdminPasswordMd5Configured());
        return body;
    }

    private Map<String, Object> tokenResponse(AuthPrincipal principal, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("role", principal.role().name());
        body.put("subject", principal.subject());
        body.put("expiresInSeconds", securityProperties.getSessionTtl().toSeconds());
        return body;
    }

    private static String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String keyId,
            @NotBlank String encryptedPassword,
            String captchaId,
            String captchaCode
    ) {
        public LoginRequest {
            if (StringUtils.hasText(username)) {
                username = username.trim();
            }
        }
    }
}
