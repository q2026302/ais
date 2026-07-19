package com.gs.ais.security;

import com.gs.ais.model.entity.LoginIpLock;
import com.gs.ais.model.entity.SecuritySettings;
import com.gs.ais.repository.LoginIpLockRepository;
import com.gs.ais.service.LoginSecurityEventService;
import com.gs.ais.service.SecuritySettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginProtectionServiceTests {

    private LoginIpLockRepository lockRepository;
    private LoginSecurityEventService loginSecurityEventService;
    private LoginProtectionService service;
    private final AtomicReference<LoginIpLock> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        lockRepository = mock(LoginIpLockRepository.class);
        SecuritySettingsService settingsService = mock(SecuritySettingsService.class);
        SecuritySettings settings = new SecuritySettings();
        settings.setMaxFailures(3);
        settings.setFailureWindowMinutes(15);
        settings.setLockDurationMinutes(30);
        settings.setCaptchaEnabled(true);
        when(settingsService.getOrCreate()).thenReturn(settings);
        loginSecurityEventService = mock(LoginSecurityEventService.class);

        when(lockRepository.findByIpAddress("1.2.3.4")).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(lockRepository.save(any(LoginIpLock.class))).thenAnswer(inv -> {
            LoginIpLock lock = inv.getArgument(0);
            stored.set(lock);
            return lock;
        });

        service = new LoginProtectionService(lockRepository, settingsService, loginSecurityEventService);
    }

    @Test
    void locksIpAfterMaxFailures() {
        service.recordFailure("1.2.3.4");
        service.recordFailure("1.2.3.4");
        assertNull(stored.get().getLockedUntil());

        service.recordFailure("1.2.3.4");
        assertNotNull(stored.get().getLockedUntil());
        assertTrue(stored.get().getLockedUntil().isAfter(LocalDateTime.now()));
        verify(loginSecurityEventService).recordIpLocked(
                eq("1.2.3.4"), eq(null), eq(3), any(LocalDateTime.class));

        AuthException ex = assertThrows(AuthException.class, () -> service.assertNotLocked("1.2.3.4"));
        assertEquals(429, ex.getStatus());
    }

    @Test
    void successClearsLockState() {
        service.recordFailure("1.2.3.4");
        service.recordFailure("1.2.3.4");
        service.recordSuccess("1.2.3.4");
        assertEquals(0, stored.get().getFailureCount());
        assertNull(stored.get().getLockedUntil());
        service.assertNotLocked("1.2.3.4");
    }
}
