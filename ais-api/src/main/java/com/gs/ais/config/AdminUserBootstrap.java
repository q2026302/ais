package com.gs.ais.config;

import com.gs.ais.model.entity.AppUser;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.security.AccessTokenService;
import com.gs.ais.security.AuthRole;
import com.gs.ais.service.SecuritySettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@Order(5)
public class AdminUserBootstrap implements CommandLineRunner {

    private static final String DEFAULT_ADMIN_PASSWORD_DIGEST = "21232f297a57a5a743894a0e4a801fc3";
    private static final Logger log = LoggerFactory.getLogger(AdminUserBootstrap.class);

    private final AppUserRepository appUserRepository;
    private final AccessTokenService accessTokenService;
    private final SecurityProperties securityProperties;
    private final SecuritySettingsService securitySettingsService;

    public AdminUserBootstrap(AppUserRepository appUserRepository,
                              AccessTokenService accessTokenService,
                              SecurityProperties securityProperties,
                              SecuritySettingsService securitySettingsService) {
        this.appUserRepository = appUserRepository;
        this.accessTokenService = accessTokenService;
        this.securityProperties = securityProperties;
        this.securitySettingsService = securitySettingsService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        securitySettingsService.getOrCreate();

        if (appUserRepository.count() > 0) {
            return;
        }
        String username = StringUtils.hasText(securityProperties.getInitialAdminUsername())
                ? securityProperties.getInitialAdminUsername().trim()
                : "admin";
        String passwordDigest = securityProperties.hasInitialAdminPasswordMd5Configured()
                ? securityProperties.getInitialAdminPasswordMd5()
                : DEFAULT_ADMIN_PASSWORD_DIGEST;
        if (!securityProperties.hasInitialAdminPasswordMd5Configured()) {
            String warning = "未配置 APP_INITIAL_ADMIN_PASSWORD_MD5，已使用默认管理员密码 admin，请登录后立即修改。";
            log.warn(warning);
            System.err.println("[安全警告] " + warning);
        }
        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setPasswordHash(accessTokenService.hashPasswordDigest(passwordDigest));
        admin.setRole(AuthRole.ADMIN);
        admin.setEnabled(true);
        appUserRepository.save(admin);
        log.info("Seeded initial admin user '{}'", username);
    }
}
