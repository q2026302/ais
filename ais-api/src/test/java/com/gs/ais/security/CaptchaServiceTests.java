package com.gs.ais.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptchaServiceTests {

    @Test
    void createChallengeReturnsPngDataUrl() {
        CaptchaService service = new CaptchaService();
        CaptchaService.CaptchaChallenge challenge = service.createChallenge();
        assertTrue(challenge.imageBase64().startsWith("data:image/png;base64,"));
        assertTrue(challenge.captchaId().length() >= 16);
        assertTrue(challenge.expiresInSeconds() > 0);
    }

    @Test
    void validateAndConsumeAcceptsMatchingCode() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.CaptchaChallenge challenge = service.createChallenge();
        String code = readStoredCode(service, challenge.captchaId());
        assertDoesNotThrow(() -> service.validateAndConsume(challenge.captchaId(), code));
        // consumed once
        assertThrows(AuthException.class, () -> service.validateAndConsume(challenge.captchaId(), code));
    }

    @Test
    void validateAndConsumeRejectsWrongCode() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.CaptchaChallenge challenge = service.createChallenge();
        assertThrows(AuthException.class, () -> service.validateAndConsume(challenge.captchaId(), "!!!!"));
    }

    @SuppressWarnings("unchecked")
    private static String readStoredCode(CaptchaService service, String captchaId) throws Exception {
        Field storeField = CaptchaService.class.getDeclaredField("store");
        storeField.setAccessible(true);
        ConcurrentHashMap<String, ?> store = (ConcurrentHashMap<String, ?>) storeField.get(service);
        Object entry = store.get(captchaId);
        Field codeField = entry.getClass().getDeclaredField("code");
        codeField.setAccessible(true);
        return (String) codeField.get(entry);
    }
}
