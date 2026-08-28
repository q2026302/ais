package com.gs.ais.security;

import com.gs.ais.util.PureSliderCaptchaImage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues one-shot slider-puzzle challenges and validates the reported drop position together with
 * the drag trajectory. Challenges are single-use and expire after {@link #TTL_SECONDS}.
 */
@Service
public class CaptchaService {

    private static final long TTL_SECONDS = 5 * 60;

    private final long ttlSeconds;

    public CaptchaService() {
        this(TTL_SECONDS);
    }

    /** Visible for tests so expiry behaviour can be exercised without waiting 5 minutes. */
    CaptchaService(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /** Per-IP acquisition cap for the challenge endpoint (anti bulk-pull). */
    private static final int ACQUIRE_LIMIT_PER_MINUTE = 20;
    private static final long ACQUIRE_WINDOW_MS = 60_000;

    private static final int MIN_TRACK_SAMPLES = 3;
    private static final int MAX_TRACK_SAMPLES = 500;
    private static final long MIN_TRACK_DURATION_MS = 150;
    private static final long MAX_TRACK_DURATION_MS = 30_000;
    /** Any single-sample jump larger than this is treated as a teleport (machine behaviour). */
    private static final int MAX_STEP_PX = 120;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, CaptchaEntry> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArrayDeque<Long>> acquireWindows = new ConcurrentHashMap<>();

    public SliderChallenge createChallenge(String ipAddress) {
        purgeExpired();
        assertAcquireAllowed(ipAddress);
        recordAcquire(ipAddress);

        PureSliderCaptchaImage.Render render = PureSliderCaptchaImage.render(random);
        String id = UUID.randomUUID().toString().replace("-", "");
        store.put(id, new CaptchaEntry(render.notchX(), Instant.now().plusSeconds(ttlSeconds)));

        return new SliderChallenge(
                id,
                "data:image/png;base64," + Base64.getEncoder().encodeToString(render.backgroundPng()),
                "data:image/png;base64," + Base64.getEncoder().encodeToString(render.piecePng()),
                render.backgroundWidth(),
                render.backgroundHeight(),
                render.pieceWidth(),
                render.pieceHeight(),
                render.pieceY(),
                (int) ttlSeconds);
    }

    /**
     * Validates and consumes a challenge. The challenge is removed before any check so that a single
     * submission — successful or not — is always consumed (one-shot semantics).
     */
    public void validateAndConsume(String captchaId, Integer x, List<TrackPoint> track) {
        purgeExpired();
        if (!StringUtils.hasText(captchaId)) {
            throw new AuthException(400, "验证码已失效，请刷新后重试");
        }
        CaptchaEntry entry = store.remove(captchaId.trim());
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            throw new AuthException(400, "验证码已失效，请刷新后重试");
        }
        if (x == null) {
            throw new AuthException(400, "缺少滑块落点，请重试");
        }
        if (!hasHumanLikeTrajectory(track)) {
            throw new AuthException(400, "拖动轨迹校验未通过，请重试");
        }
        int reportedX = x;
        // Promote to long before subtracting so Integer.MIN_VALUE-style inputs cannot overflow the
        // int difference to a negative value and bypass the tolerance check (fail-open).
        if (Math.abs((long) reportedX - entry.correctX()) > PureSliderCaptchaImage.TOLERANCE_PX) {
            throw new AuthException(400, "滑块位置不正确，请重试");
        }
        // The reported drop point must agree with where the trajectory actually ended.
        int lastX = track.get(track.size() - 1).x();
        if (Math.abs((long) reportedX - lastX) > PureSliderCaptchaImage.TOLERANCE_PX) {
            throw new AuthException(400, "拖动轨迹数据异常，请重试");
        }
    }

    private boolean hasHumanLikeTrajectory(List<TrackPoint> track) {
        if (track == null || track.size() < MIN_TRACK_SAMPLES || track.size() > MAX_TRACK_SAMPLES) {
            return false;
        }
        long firstT = track.get(0).t();
        long lastT = track.get(track.size() - 1).t();
        if (firstT < 0 || lastT < firstT) {
            return false;
        }
        long duration = lastT - firstT;
        if (duration < MIN_TRACK_DURATION_MS || duration > MAX_TRACK_DURATION_MS) {
            return false;
        }

        int x0 = track.get(0).x();
        int maxDisplacement = 0;
        int prevX = x0;
        long prevT = firstT;
        for (int i = 0; i < track.size(); i++) {
            TrackPoint p = track.get(i);
            if (p.t() < prevT) {
                return false; // non-monotonic timestamps
            }
            prevT = p.t();
            maxDisplacement = Math.max(maxDisplacement, Math.abs(p.x() - x0));
            if (i > 0 && Math.abs(p.x() - prevX) > MAX_STEP_PX) {
                return false; // teleport
            }
            prevX = p.x();
        }
        if (maxDisplacement == 0) {
            return false; // no movement at all
        }

        // A script that only forges the drop point typically submits a perfectly uniform straight
        // line with a constant y. Reject that shape while allowing natural human jitter.
        return !isPerfectlyLinear(track);
    }

    private boolean isPerfectlyLinear(List<TrackPoint> track) {
        int n = track.size();
        if (n < 3) {
            return false;
        }
        double sumT = 0;
        double sumX = 0;
        for (TrackPoint p : track) {
            sumT += p.t();
            sumX += p.x();
        }
        double meanT = sumT / n;
        double meanX = sumX / n;
        double num = 0;
        double den = 0;
        for (TrackPoint p : track) {
            double dt = p.t() - meanT;
            num += dt * (p.x() - meanX);
            den += dt * dt;
        }
        if (den == 0) {
            return true;
        }
        double slope = num / den;
        double intercept = meanX - slope * meanT;
        double maxResidual = 0;
        for (TrackPoint p : track) {
            double predicted = slope * p.t() + intercept;
            maxResidual = Math.max(maxResidual, Math.abs(p.x() - predicted));
        }

        double sumY = 0;
        for (TrackPoint p : track) {
            sumY += p.y();
        }
        double meanY = sumY / n;
        double yVariance = 0;
        for (TrackPoint p : track) {
            double dy = p.y() - meanY;
            yVariance += dy * dy;
        }
        double yStd = Math.sqrt(yVariance / n);

        return maxResidual < 0.9 && yStd < 1.0;
    }

    private void assertAcquireAllowed(String ipAddress) {
        if (!StringUtils.hasText(ipAddress)) {
            return;
        }
        ArrayDeque<Long> window = acquireWindows.computeIfAbsent(ipAddress, k -> new ArrayDeque<>());
        synchronized (window) {
            long now = System.currentTimeMillis();
            while (!window.isEmpty() && now - window.peekFirst() > ACQUIRE_WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= ACQUIRE_LIMIT_PER_MINUTE) {
                throw new AuthException(429, "验证码请求过于频繁，请稍后再试");
            }
        }
    }

    private void recordAcquire(String ipAddress) {
        if (!StringUtils.hasText(ipAddress)) {
            return;
        }
        ArrayDeque<Long> window = acquireWindows.computeIfAbsent(ipAddress, k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(System.currentTimeMillis());
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, CaptchaEntry>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CaptchaEntry> entry = it.next();
            if (entry.getValue().expiresAt().isBefore(now)) {
                it.remove();
            }
        }

        // Bound the acquisition window map: drop IPs idle for a long time.
        if (acquireWindows.size() > 10_000) {
            long cutoff = System.currentTimeMillis() - 10 * 60_000;
            Iterator<Map.Entry<String, ArrayDeque<Long>>> winIt = acquireWindows.entrySet().iterator();
            while (winIt.hasNext()) {
                Map.Entry<String, ArrayDeque<Long>> entry = winIt.next();
                ArrayDeque<Long> window = entry.getValue();
                synchronized (window) {
                    while (!window.isEmpty() && window.peekLast() < cutoff) {
                        window.pollFirst();
                    }
                    if (window.isEmpty()) {
                        winIt.remove();
                    }
                }
            }
        }
    }

    private record CaptchaEntry(int correctX, Instant expiresAt) {
    }

    public record TrackPoint(int x, int y, long t) {
    }

    public record SliderChallenge(
            String captchaId,
            String backgroundImage,
            String pieceImage,
            int backgroundWidth,
            int backgroundHeight,
            int pieceWidth,
            int pieceHeight,
            int pieceY,
            int expiresInSeconds) {
    }
}
