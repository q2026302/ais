package com.gs.ais.config;

import com.gs.ais.model.entity.AppUser;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.security.AccessTokenService;
import com.gs.ais.security.AuthRole;
import com.gs.ais.service.SecuritySettingsService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserBootstrapTests {

    private static final String DEFAULT_ADMIN_PASSWORD_DIGEST = "21232f297a57a5a743894a0e4a801fc3";

    @Test
    void createsAdminWithDefaultPasswordDigestWhenNoDigestIsConfigured() {
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        AccessTokenService accessTokenService = mock(AccessTokenService.class);
        SecurityProperties securityProperties = new SecurityProperties();
        SecuritySettingsService securitySettingsService = mock(SecuritySettingsService.class);
        when(appUserRepository.count()).thenReturn(0L);
        when(accessTokenService.hashPasswordDigest(DEFAULT_ADMIN_PASSWORD_DIGEST)).thenReturn("bcrypt-hash");

        new AdminUserBootstrap(appUserRepository, accessTokenService, securityProperties, securitySettingsService)
                .run();

        verify(accessTokenService).hashPasswordDigest(DEFAULT_ADMIN_PASSWORD_DIGEST);
        var savedAdmin = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(savedAdmin.capture());
        assertEquals("admin", savedAdmin.getValue().getUsername());
        assertEquals("bcrypt-hash", savedAdmin.getValue().getPasswordHash());
        assertEquals(AuthRole.ADMIN, savedAdmin.getValue().getRole());
        assertEquals(true, savedAdmin.getValue().isEnabled());
    }
}
