package com.gs.ais.security;

import com.gs.ais.util.PureCaptchaImage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CaptchaService {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 4;
    private static final long TTL_SECONDS = 5 * 60;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, CaptchaEntry> store = new ConcurrentHashMap<>();

    public CaptchaChallenge createChallenge() {
        purgeExpired();
        String id = UUID.randomUUID().toString().replace("-", "");
        String code = randomCode();
        store.put(id, new CaptchaEntry(code.toLowerCase(), Instant.now().plusSeconds(TTL_SECONDS)));
        return new CaptchaChallenge(id, "data:image/png;base64," + renderPngBase64(code), (int) TTL_SECONDS);
    }

    public void validateAndConsume(String captchaId, String captchaCode) {
        purgeExpired();
        if (!StringUtils.hasText(captchaId) || !StringUtils.hasText(captchaCode)) {
            throw new AuthException(400, "请填写验证码");
        }
        CaptchaEntry entry = store.remove(captchaId.trim());
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            throw new AuthException(400, "验证码已失效，请刷新后重试");
        }
        if (!entry.code().equalsIgnoreCase(captchaCode.trim())) {
            throw new AuthException(400, "验证码不正确");
        }
    }

    private String randomCode() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }

    /**
     * Renders a PNG captcha without java.awt/ImageIO.
     *
     * <p>Native Image builds do not include AWT's GraphicsEnvironment by default;
     * using AWT here previously terminated the native process (502 via reverse proxy).
     * Raster drawing + PNGJ encoding matches the approach used by {@link com.gs.ais.util.PureThumbnail}.
     *
     * <p>PNG bitmaps are harder for bots to parse than the previous SVG path output.
     */
    private String renderPngBase64(String code) {
        byte[] png = PureCaptchaImage.renderPng(code, random);
        return Base64.getEncoder().encodeToString(png);
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
    }

    private record CaptchaEntry(String code, Instant expiresAt) {
    }

    public record CaptchaChallenge(String captchaId, String imageBase64, int expiresInSeconds) {
    }
}
