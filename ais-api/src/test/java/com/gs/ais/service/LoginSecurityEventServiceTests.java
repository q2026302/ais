package com.gs.ais.service;

import com.gs.ais.model.entity.LoginSecurityEvent;
import com.gs.ais.model.entity.LoginSecurityEventType;
import com.gs.ais.repository.LoginSecurityEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LoginSecurityEventServiceTests {

    private LoginSecurityEventRepository repository;
    private LoginSecurityEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(LoginSecurityEventRepository.class);
        service = new LoginSecurityEventService(repository);
    }

    @Test
    void recordsBlockedAccountLoginAttemptWithoutPasswordMaterial() {
        service.recordBlockedAccount(" locked-user ", " 1.2.3.4 ");

        var captor = org.mockito.ArgumentCaptor.forClass(LoginSecurityEvent.class);
        verify(repository).save(captor.capture());
        LoginSecurityEvent event = captor.getValue();
        assertEquals(LoginSecurityEventType.ACCOUNT_BLOCKED, event.getEventType());
        assertEquals("locked-user", event.getUsername());
        assertEquals("1.2.3.4", event.getIpAddress());
        assertEquals("账号已禁用，拒绝登录", event.getDetail());
    }

    @Test
    void recordsIpLockDetails() {
        LocalDateTime lockedUntil = LocalDateTime.now().plusMinutes(30);

        service.recordIpLocked("1.2.3.4", "alice", 5, lockedUntil);

        var captor = org.mockito.ArgumentCaptor.forClass(LoginSecurityEvent.class);
        verify(repository).save(captor.capture());
        LoginSecurityEvent event = captor.getValue();
        assertEquals(LoginSecurityEventType.IP_LOCKED, event.getEventType());
        assertEquals("alice", event.getUsername());
        assertEquals("1.2.3.4", event.getIpAddress());
        assertEquals(5, event.getFailureCount());
        assertEquals(lockedUntil, event.getLockedUntil());
    }
}
