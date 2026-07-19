package com.gs.ais.service;

import com.gs.ais.config.SecurityProperties;
import com.gs.ais.model.entity.SecuritySettings;
import com.gs.ais.repository.SecuritySettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SecuritySettingsService {

    private final SecuritySettingsRepository repository;
    private final SecurityProperties securityProperties;

    public SecuritySettingsService(SecuritySettingsRepository repository,
                                   SecurityProperties securityProperties) {
        this.repository = repository;
        this.securityProperties = securityProperties;
    }

    @Transactional(readOnly = true)
    public SecuritySettings getOrCreate() {
        return repository.findById(SecuritySettings.SINGLETON_ID).orElseGet(this::createDefaults);
    }

    public SecuritySettings update(int maxFailures,
                                   int failureWindowMinutes,
                                   int lockDurationMinutes,
                                   boolean captchaEnabled) {
        if (maxFailures < 1 || maxFailures > 100) {
            throw new IllegalArgumentException("maxFailures 需在 1-100 之间");
        }
        if (failureWindowMinutes < 1 || failureWindowMinutes > 24 * 60) {
            throw new IllegalArgumentException("failureWindowMinutes 需在 1-1440 之间");
        }
        if (lockDurationMinutes < 1 || lockDurationMinutes > 7 * 24 * 60) {
            throw new IllegalArgumentException("lockDurationMinutes 需在 1-10080 之间");
        }
        SecuritySettings settings = getOrCreate();
        settings.setMaxFailures(maxFailures);
        settings.setFailureWindowMinutes(failureWindowMinutes);
        settings.setLockDurationMinutes(lockDurationMinutes);
        settings.setCaptchaEnabled(captchaEnabled);
        return repository.save(settings);
    }

    private SecuritySettings createDefaults() {
        SecuritySettings settings = new SecuritySettings();
        settings.setId(SecuritySettings.SINGLETON_ID);
        settings.setMaxFailures(Math.max(1, securityProperties.getMaxFailures()));
        settings.setFailureWindowMinutes(Math.max(1, securityProperties.getFailureWindowMinutes()));
        settings.setLockDurationMinutes(Math.max(1, securityProperties.getLockDurationMinutes()));
        settings.setCaptchaEnabled(securityProperties.isCaptchaEnabled());
        return repository.save(settings);
    }
}
