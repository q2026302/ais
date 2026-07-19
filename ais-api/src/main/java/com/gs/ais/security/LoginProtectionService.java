package com.gs.ais.security;

import com.gs.ais.model.entity.LoginIpLock;
import com.gs.ais.model.entity.SecuritySettings;
import com.gs.ais.repository.LoginIpLockRepository;
import com.gs.ais.service.LoginSecurityEventService;
import com.gs.ais.service.SecuritySettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class LoginProtectionService {

    private final LoginIpLockRepository lockRepository;
    private final SecuritySettingsService securitySettingsService;
    private final LoginSecurityEventService loginSecurityEventService;

    public LoginProtectionService(LoginIpLockRepository lockRepository,
                                  SecuritySettingsService securitySettingsService,
                                  LoginSecurityEventService loginSecurityEventService) {
        this.lockRepository = lockRepository;
        this.securitySettingsService = securitySettingsService;
        this.loginSecurityEventService = loginSecurityEventService;
    }

    @Transactional(readOnly = true)
    public void assertNotLocked(String ipAddress) {
        if (!StringUtils.hasText(ipAddress)) {
            return;
        }
        LoginIpLock lock = lockRepository.findByIpAddress(ipAddress).orElse(null);
        if (lock == null || lock.getLockedUntil() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (lock.getLockedUntil().isAfter(now)) {
            long minutes = Math.max(1, ChronoUnit.MINUTES.between(now, lock.getLockedUntil()));
            throw new AuthException(429, "来源 IP 登录失败次数过多，请 " + minutes + " 分钟后再试");
        }
    }

    @Transactional
    public void recordFailure(String ipAddress) {
        recordFailure(ipAddress, null);
    }

    @Transactional
    public void recordFailure(String ipAddress, String username) {
        if (!StringUtils.hasText(ipAddress)) {
            return;
        }
        SecuritySettings settings = securitySettingsService.getOrCreate();
        LocalDateTime now = LocalDateTime.now();
        LoginIpLock lock = lockRepository.findByIpAddress(ipAddress).orElseGet(() -> {
            LoginIpLock created = new LoginIpLock();
            created.setIpAddress(ipAddress);
            created.setFailureCount(0);
            return created;
        });

        if (lock.getLockedUntil() != null && lock.getLockedUntil().isAfter(now)) {
            lock.setLastFailureAt(now);
            lockRepository.save(lock);
            return;
        }

        boolean windowExpired = lock.getWindowStartedAt() == null
                || Duration.between(lock.getWindowStartedAt(), now).toMinutes() >= settings.getFailureWindowMinutes();
        if (windowExpired || lock.getFailureCount() <= 0) {
            lock.setWindowStartedAt(now);
            lock.setFailureCount(1);
        } else {
            lock.setFailureCount(lock.getFailureCount() + 1);
        }
        lock.setLastFailureAt(now);

        int lockTriggerFailureCount = 0;
        if (lock.getFailureCount() >= settings.getMaxFailures()) {
            lockTriggerFailureCount = lock.getFailureCount();
            lock.setLockedUntil(now.plusMinutes(settings.getLockDurationMinutes()));
            lock.setFailureCount(0);
            lock.setWindowStartedAt(null);
        } else {
            lock.setLockedUntil(null);
        }
        lockRepository.save(lock);
        if (lockTriggerFailureCount > 0) {
            loginSecurityEventService.recordIpLocked(
                    ipAddress, username, lockTriggerFailureCount, lock.getLockedUntil());
        }
    }

    @Transactional
    public void recordSuccess(String ipAddress) {
        if (!StringUtils.hasText(ipAddress)) {
            return;
        }
        lockRepository.findByIpAddress(ipAddress).ifPresent(lock -> {
            lock.setFailureCount(0);
            lock.setWindowStartedAt(null);
            lock.setLockedUntil(null);
            lock.setLastFailureAt(null);
            lockRepository.save(lock);
        });
    }
}
