package com.gs.ais.security;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReaderInt;
import com.gs.ais.util.PureSliderCaptchaImage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Base64;
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
        // Geometry contract: the piece canvas (top at pieceY) must fit inside the background,
        // so the frontend's `top = pieceY * scale` placement aligns with the carved notch.
        assertTrue(challenge.pieceY() + challenge.pieceHeight() <= challenge.backgroundHeight());
        assertEquals(5 * 60, challenge.expiresInSeconds());
    }

    @Test
    void servedBackgroundNotchPositionMatchesStoredCorrectX() throws Exception {
        // Black-box regression for the reported defect: independently locate the notch from the
        // served background PNG pixels (via the piece's pixel-exact opaque crop) and assert that the
        // drawn position agrees with the answer the server will accept (the stored correctX).
        for (int i = 0; i < 10; i++) {
            CaptchaService service = new CaptchaService();
            CaptchaService.SliderChallenge challenge = service.createChallenge("10.0.0." + (i + 1));
            int correctX = readCorrectX(service, challenge.captchaId());
            int[][] bg = decodeRgba(base64Png(challenge.backgroundImage()));
            int[][] piece = decodeRgba(base64Png(challenge.pieceImage()));
            int locatedX = locateNotchX(bg, piece, challenge.pieceY());
            assertTrue(Math.abs(locatedX - correctX) <= PureSliderCaptchaImage.TOLERANCE_PX,
                    "notch drawn at x=" + locatedX + " but server answer correctX=" + correctX);
        }
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
    void failedSubmissionStillConsumesChallenge() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        int correctX = readCorrectX(service, challenge.captchaId());
        int wrongX = correctX + 40;
        // First attempt with a wrong drop point fails, but must still consume the challenge.
        assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), wrongX, humanTrack(wrongX)));
        // A second attempt — even with the correct answer and a human-like track — must be rejected,
        // proving one-shot semantics (no binary-search / brute-force on the stored correctX).
        AuthException ex = assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), correctX, humanTrack(correctX)));
        assertTrue(ex.getMessage().contains("已失效"));
    }

    @Test
    void trajectoryRejectsOverflowingStepJump() throws Exception {
        CaptchaService service = new CaptchaService();
        CaptchaService.SliderChallenge challenge = service.createChallenge("1.2.3.4");
        int correctX = readCorrectX(service, challenge.captchaId());
        // A jump from Integer.MIN_VALUE to Integer.MAX_VALUE overflows the int difference; with the
        // long promotion in hasHumanLikeTrajectory it is still recognised as a teleport (> MAX_STEP_PX).
        List<CaptchaService.TrackPoint> track = List.of(
                new CaptchaService.TrackPoint(Integer.MIN_VALUE, 30, 0L),
                new CaptchaService.TrackPoint(Integer.MAX_VALUE, 35, 100L),
                new CaptchaService.TrackPoint(Integer.MIN_VALUE, 32, 200L));
        AuthException ex = assertThrows(AuthException.class, () -> service.validateAndConsume(
                challenge.captchaId(), correctX, track));
        assertTrue(ex.getMessage().contains("轨迹校验未通过"), ex.getMessage());
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

    /** Strips the {@code data:image/png;base64,} prefix and decodes the PNG payload. */
    private static byte[] base64Png(String dataUrl) {
        String encoded = dataUrl.substring(dataUrl.indexOf(',') + 1);
        return Base64.getDecoder().decode(encoded);
    }

    /**
     * Locates the notch's left edge (X) by scanning the background for the position where the
     * piece's fully-opaque pixels (an exact, untouched crop of the notch interior) align pixel-for-
     * pixel. Uses only the two served PNGs and the public geometry — no renderer internals.
     */
    private static int locateNotchX(int[][] bg, int[][] piece, int pieceY) {
        int pw = piece[0].length;
        int ph = piece.length;
        int bgW = bg[0].length;
        List<int[]> refs = new ArrayList<>();
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                if (alpha(piece[y][x]) == 255) {
                    refs.add(new int[]{x, y, piece[y][x]});
                }
            }
        }
        int bestX = 0;
        int bestScore = -1;
        for (int X = 0; X + pw <= bgW; X++) {
            int score = 0;
            for (int[] r : refs) {
                int by = pieceY + r[1];
                if (by >= 0 && by < bg.length && bg[by][X + r[0]] == r[2]) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestX = X;
            }
        }
        // Sanity: the winning alignment must be a substantial exact match, not a tie at zero.
        assertTrue(bestScore > 500, "notch localization produced a weak match (" + bestScore + ")");
        return bestX;
    }

    private static int[][] decodeRgba(byte[] png) throws Exception {
        PngReaderInt reader = new PngReaderInt(new ByteArrayInputStream(png));
        ImageInfo info = reader.imgInfo;
        int w = info.cols;
        int h = info.rows;
        int channels = info.channels;
        int[][] out = new int[h][w];
        for (int y = 0; y < h; y++) {
            ImageLineInt line = reader.readRowInt();
            int[] s = line.getScanline();
            for (int x = 0; x < w; x++) {
                int r = s[x * channels];
                int g = s[x * channels + 1];
                int b = s[x * channels + 2];
                int a = info.alpha ? s[x * channels + 3] : 255;
                out[y][x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        reader.end();
        return out;
    }

    private static int alpha(int c) {
        return (c >>> 24) & 0xff;
    }
}
