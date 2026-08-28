package com.gs.ais.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptchaServiceTests {

    @Test
    void createChallengeReturnsSliderPuzzleDataUrls() {
        CaptchaService service = new CaptchaService();
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        assertTrue(challenge.captchaId().length() >= 16);
        assertTrue(challenge.backgroundImage().startsWith("data:image/png;base64,"));
        assertTrue(challenge.pieceImage().startsWith("data:image/png;base64,"));
        assertTrue(challenge.backgroundWidth() > 0);
        assertTrue(challenge.backgroundHeight() > 0);
        assertTrue(challenge.pieceWidth() > 0);
        assertTrue(challenge.pieceHeight() > 0);
        assertTrue(challenge.pieceY() >= 0);
        assertEquals(5 * 60, challenge.expiresInSeconds());
    }

    @Test
    void validateAndConsumeAcceptsHumanLikeTrackAtCorrectPosition() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        int correctX = readCorrectX(service, challenge.captchaId());
        assertDoesNotThrow(() -> service.validateAndConsume(
                challenge.captchaId(), correctX, humanTrack(correctX)));
        // one-shot: second submission is rejected
        assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), correctX, humanTrack(correctX)));
    }

    @Test
    void validateAndConsumeRejectsWrongPosition() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        int correctX = readCorrectX(service, challenge.captchaId());
        int wrongX = correctX + 40;
        assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), wrongX, humanTrack(wrongX)));
    }

    @Test
    void validateAndConsumeRejectsMissingTrack() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        int correctX = readCorrectX(service, challenge.captchaId());
        assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), correctX, null));
        assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), correctX, List.of()));
    }

    @Test
    void validateAndConsumeRejectsSingleSampleTrack() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        int correctX = readCorrectX(service, challenge.captchaId());
        assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), correctX,
                List.of(new CaptchaService.TrackPoint(correctX, 20, 0))));
    }

    @Test
    void validateAndConsumeRejectsPerfectlyLinearTrack() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        int correctX = readCorrectX(service, challenge.captchaId());
        // Legit drop point but a machine-like constant-velocity straight line with zero y jitter.
        assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), correctX, linearTrack(correctX)));
    }

    @Test
    void validateAndConsumeRejectsIntegerMinValueDropPosition() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        int correctX = readCorrectX(service, challenge.captchaId());
        // reportedX = Integer.MIN_VALUE + correctX makes (reportedX - correctX) overflow to
        // Integer.MIN_VALUE; Math.abs(int) returns a negative value and would bypass the tolerance
        // check without the long promotion in validateAndConsume.
        int reportedX = Integer.MIN_VALUE + correctX;
        List<CaptchaService.TrackPoint> track = List.of(
                new CaptchaService.TrackPoint(reportedX - 2, 30, 0L),
                new CaptchaService.TrackPoint(reportedX - 1, 33, 100L),
                new CaptchaService.TrackPoint(reportedX, 30, 200L));
        AuthException ex = assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), reportedX, track));
        assertTrue(ex.getMessage().contains("位置不正确"));
    }

    @Test
    void createChallengeThrottlesAcquisitionPerIp() {
        CaptchaService service = new CaptchaService();
        String ip = "10.0.0.9";
        for (int i = 0; i < 20; i++) {
            service.createChallenge(ip);
        }
        AuthException ex = assertThrows(AuthException.class, () -> service.createChallenge(ip));
        assertEquals(429, ex.getStatus());
    }

    @Test
    void validateAndConsumeRejectsExpiredChallenge() throws Exception {
        CaptchaService service = new CaptchaService(-1);
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        int correctX = readCorrectX(service, challenge.captchaId());
        AuthException ex = assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), correctX, humanTrack(correctX)));
        assertTrue(ex.getMessage().contains("已失效"));
    }

    private static List<CaptchaService.TrackPoint> humanTrack(int targetX) {
        List<CaptchaService.TrackPoint> track = new ArrayList<>();
        int samples = 30;
        for (int i = 0; i < samples; i++) {
            double progress = (double) i / (samples - 1);
            double eased = progress * progress * (3 - 2 * progress); // ease-in-out
            int x = (int) Math.round(eased * targetX);
            if (i > 0 && i < samples - 1) {
                x += (i % 2 == 0 ? 1 : -1); // small human jitter
            }
            int y = 30 + ((i * 7) % 5); // y jitter 0..4
            track.add(new CaptchaService.TrackPoint(x, y, i * 20L));
        }
        return track;
    }

    private static List<CaptchaService.TrackPoint> linearTrack(int targetX) {
        List<CaptchaService.TrackPoint> track = new ArrayList<>();
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            int x = (int) Math.round((double) targetX * i / (samples - 1));
            track.add(new CaptchaService.TrackPoint(x, 30, i * 20L));
        }
        return track;
    }

    @SuppressWarnings("unchecked")
    private static int readCorrectX(CaptchaService service, String captchaId) throws Exception {
        Field storeField = CaptchaService.class.getDeclaredField("store");
        storeField.setAccessible(true);
        ConcurrentHashMap<String, ?> store = (ConcurrentHashMap<String, ?>) storeField.get(service);
        Object entry = store.get(captchaId);
        Field correctXField = entry.getClass().getDeclaredField("correctX");
        correctXField.setAccessible(true);
        return (Integer) correctXField.get(entry);
    }
}
