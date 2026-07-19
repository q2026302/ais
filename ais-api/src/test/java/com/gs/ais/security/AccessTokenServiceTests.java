package com.gs.ais.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.config.SecurityProperties;
import com.gs.ais.model.entity.AppUser;
import com.gs.ais.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessTokenServiceTests {

    private static final String USER_PASSWORD_MD5 = "d9f6e2c6f3f5f8e7e5d2c4b7a8c9d0e1";
    private static final String ADMIN_PASSWORD_MD5 = "e3c8b7a6d5f4e3c2b1a09876543210ff";

    private AccessTokenService service;
    private AppUserRepository appUserRepository;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        SecurityProperties properties = new SecurityProperties();
        properties.setEnabled(true);
        properties.setTokenSecret("unit-test-secret");
        properties.setSessionTtl(Duration.ofHours(1));
        appUserRepository = mock(AppUserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        service = new AccessTokenService(properties, new ObjectMapper(), appUserRepository, passwordEncoder);
    }

    @Test
    void authenticatesUserAndAdminPasswordsUsingMd5ThenBcrypt() {
        AppUser user = user("alice", USER_PASSWORD_MD5, AuthRole.USER);
        AppUser admin = user("admin", ADMIN_PASSWORD_MD5, AuthRole.ADMIN);
        when(appUserRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(appUserRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin));

        AuthPrincipal userPrincipal = service.authenticateWithPasswordDigest("alice", USER_PASSWORD_MD5);
        AuthPrincipal adminPrincipal = service.authenticateWithPasswordDigest("admin", ADMIN_PASSWORD_MD5);
        assertEquals(AuthRole.USER, userPrincipal.role());
        assertEquals("alice", userPrincipal.subject());
        assertEquals(AuthRole.ADMIN, adminPrincipal.role());
        assertTrue(passwordEncoder.matches(USER_PASSWORD_MD5, user.getPasswordHash()));
    }

    @Test
    void authenticatesUsingAlreadyDigestedPassword() {
        AppUser user = user("alice", USER_PASSWORD_MD5, AuthRole.USER);
        when(appUserRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));

        AuthPrincipal principal = service.authenticateWithPasswordDigest("alice", USER_PASSWORD_MD5);

        assertEquals(AuthRole.USER, principal.role());
        assertEquals("alice", principal.subject());
    }

    @Test
    void acceptsOnlyMd5HexPasswordMaterial() {
        assertTrue(PasswordDigests.isMd5Hex(USER_PASSWORD_MD5));
        assertTrue(!PasswordDigests.isMd5Hex("plaintext-password"));
    }

    @Test
    void rejectsInvalidPassword() {
        AppUser user = user("alice", USER_PASSWORD_MD5, AuthRole.USER);
        when(appUserRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        AuthException ex = assertThrows(AuthException.class,
                () -> service.authenticateWithPasswordDigest("alice", "00000000000000000000000000000000"));
        assertEquals(401, ex.getStatus());
    }

    @Test
    void issuesAndParsesToken() {
        AuthPrincipal principal = new AuthPrincipal(AuthRole.ADMIN, "admin");
        String token = service.issueToken(principal);
        AuthPrincipal parsed = service.parseToken(token);
        assertEquals(AuthRole.ADMIN, parsed.role());
        assertEquals("admin", parsed.subject());
        assertTrue(token.contains("."));
    }

    @Test
    void rejectsTamperedToken() {
        String token = service.issueToken(new AuthPrincipal(AuthRole.USER, "user"));
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
        AuthException ex = assertThrows(AuthException.class, () -> service.parseToken(tampered));
        assertEquals(401, ex.getStatus());
    }

    private AppUser user(String username, String passwordDigest, AuthRole role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(service.hashPasswordDigest(passwordDigest));
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }
}
