package com.gs.ais.service;

import com.gs.ais.model.entity.LoginSecurityEvent;
import com.gs.ais.model.entity.LoginSecurityEventType;
import com.gs.ais.repository.LoginSecurityEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class LoginSecurityEventService {

    private static final Logger log = LoggerFactory.getLogger(LoginSecurityEventService.class);
    private static final int MAX_QUERY_LIMIT = 200;

    private final LoginSecurityEventRepository repository;

    public LoginSecurityEventService(LoginSecurityEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void recordIpLocked(String ipAddress,
                               String username,
                               int failureCount,
                               LocalDateTime lockedUntil) {
        LoginSecurityEvent event = newEvent(LoginSecurityEventType.IP_LOCKED,
                username, ipAddress, failureCount, lockedUntil, "IP 登录失败次数达到阈值，已锁定");
        repository.save(event);
        log.warn("Login IP locked: ip={}, username={}, failureCount={}, lockedUntil={}",
                event.getIpAddress(), event.getUsername(), failureCount, lockedUntil);
    }

    @Transactional
    public void recordBlockedAccount(String username, String ipAddress) {
        LoginSecurityEvent event = newEvent(LoginSecurityEventType.ACCOUNT_BLOCKED,
                username, ipAddress, null, null, "账号已禁用，拒绝登录");
        repository.save(event);
        log.warn("Blocked account login attempt: username={}, ip={}",
                event.getUsername(), event.getIpAddress());
    }

    @Transactional(readOnly = true)
    public List<LoginSecurityEvent> recent(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        return repository.findTop200ByOrderByOccurredAtDesc().stream()
                .limit(normalizedLimit)
                .toList();
    }

    private LoginSecurityEvent newEvent(LoginSecurityEventType eventType,
                                        String username,
                                        String ipAddress,
                                        Integer failureCount,
                                        LocalDateTime lockedUntil,
                                        String detail) {
        LoginSecurityEvent event = new LoginSecurityEvent();
        event.setEventType(eventType);
        event.setUsername(normalize(username));
        event.setIpAddress(normalize(ipAddress));
        event.setFailureCount(failureCount);
        event.setLockedUntil(lockedUntil);
        event.setOccurredAt(LocalDateTime.now());
        event.setDetail(detail);
        return event;
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
