package com.gs.ais.security;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
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
        return new CaptchaChallenge(id, "data:image/svg+xml;base64," + renderSvgBase64(code), (int) TTL_SECONDS);
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
     * Generates a browser-rendered SVG instead of using java.awt/ImageIO.
     *
     * Native Image builds do not include AWT's GraphicsEnvironment by default;
     * using it here caused the captcha request to terminate the native process,
     * which surfaced as a 502/Bad Gateway through a reverse proxy.
     */
    private String renderSvgBase64(String code) {
        int width = 140;
        int height = 48;
        StringBuilder svg = new StringBuilder(2_048);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(width)
                .append("\" height=\"")
                .append(height)
                .append("\" viewBox=\"0 0 ")
                .append(width)
                .append(' ')
                .append(height)
                .append("\">");
        svg.append("<rect width=\"100%\" height=\"100%\" rx=\"6\" fill=\"#f5f7ff\"/>");

        for (int i = 0; i < 8; i++) {
            svg.append("<line x1=\"").append(random.nextInt(width))
                    .append("\" y1=\"").append(random.nextInt(height))
                    .append("\" x2=\"").append(random.nextInt(width))
                    .append("\" y2=\"").append(random.nextInt(height))
                    .append("\" stroke=\"").append(randomColor(150, 160, 200))
                    .append("\" stroke-width=\"1.2\" opacity=\".8\"/>");
        }

        for (int i = 0; i < code.length(); i++) {
            int x = 18 + i * 28;
            int y = 35 + random.nextInt(5);
            double angle = (random.nextDouble() - 0.5) * 28;
            svg.append("<text x=\"").append(x)
                    .append("\" y=\"").append(y)
                    .append("\" transform=\"rotate(").append(String.format(Locale.ROOT, "%.2f", angle))
                    .append(' ').append(x).append(' ').append(y)
                    .append(" )\" fill=\"").append(randomColor(50, 60, 140))
                    .append("\" font-family=\"Arial,sans-serif\" font-size=\"28\" font-weight=\"700\">")
                    .append(xmlEscape(code.charAt(i)))
                    .append("</text>");
        }

        for (int i = 0; i < 40; i++) {
            svg.append("<circle cx=\"").append(random.nextInt(width))
                    .append("\" cy=\"").append(random.nextInt(height))
                    .append("\" r=\".7\" fill=\"").append(randomColor(0, 0, 0))
                    .append("\" opacity=\".65\"/>");
        }
        svg.append("</svg>");
        return Base64.getEncoder().encodeToString(svg.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String randomColor(int redBase, int greenBase, int blueBase) {
        int red = redBase + random.nextInt(redBase == 0 ? 255 : 80);
        int green = greenBase + random.nextInt(greenBase == 0 ? 255 : 70);
        int blue = blueBase + random.nextInt(blueBase == 0 ? 255 : 50);
        return String.format(Locale.ROOT, "#%02x%02x%02x", red, green, blue);
    }

    private static String xmlEscape(char value) {
        return switch (value) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '\'' -> "&apos;";
            case '"' -> "&quot;";
            default -> String.valueOf(value);
        };
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
