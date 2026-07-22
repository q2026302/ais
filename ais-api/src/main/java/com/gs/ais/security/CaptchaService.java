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

    /**
     * Simplified stroke glyphs in a 0..10 x 0..14 design box (origin top-left).
     * Each value is an SVG path using only M/L commands.
     */
    private static final Map<Character, String> GLYPH_PATHS = Map.ofEntries(
            Map.entry('A', "M1,14 L5,1 L9,14 M2.5,9 L7.5,9"),
            Map.entry('B', "M2,1 L2,14 L7,14 L9,11 L7,8 L9,5 L7,1 Z M2,1 L7,1 M2,8 L7,8"),
            Map.entry('C', "M9,3 L7,1 L3,1 L1,3 L1,11 L3,13 L7,13 L9,11"),
            Map.entry('D', "M2,1 L2,14 L6,14 L9,11 L9,3 L6,1 Z M2,1 L6,1"),
            Map.entry('E', "M9,1 L2,1 L2,14 L9,14 M2,7.5 L7,7.5"),
            Map.entry('F', "M9,1 L2,1 L2,14 M2,7.5 L7,7.5"),
            Map.entry('G', "M9,3 L7,1 L3,1 L1,3 L1,11 L3,13 L7,13 L9,11 L9,8 L5,8"),
            Map.entry('H', "M2,1 L2,14 M8,1 L8,14 M2,7.5 L8,7.5"),
            Map.entry('J', "M8,1 L8,11 L6,13 L3,13 L1,11"),
            Map.entry('K', "M2,1 L2,14 M8,1 L2,8 L8,14"),
            Map.entry('L', "M2,1 L2,14 L9,14"),
            Map.entry('M', "M1,14 L1,1 L5,8 L9,1 L9,14"),
            Map.entry('N', "M2,14 L2,1 L8,14 L8,1"),
            Map.entry('P', "M2,14 L2,1 L7,1 L9,3 L9,6 L7,8 L2,8"),
            Map.entry('Q', "M5,1 L2,3 L2,11 L5,13 L8,11 L8,3 L5,1 M6,10 L9,14"),
            Map.entry('R', "M2,14 L2,1 L7,1 L9,3 L9,6 L7,8 L2,8 M5,8 L9,14"),
            Map.entry('S', "M9,3 L7,1 L3,1 L1,3 L1,5 L3,7 L7,7 L9,9 L9,11 L7,13 L3,13 L1,11"),
            Map.entry('T', "M1,1 L9,1 M5,1 L5,14"),
            Map.entry('U', "M2,1 L2,11 L4,13 L6,13 L8,11 L8,1"),
            Map.entry('V', "M1,1 L5,14 L9,1"),
            Map.entry('W', "M1,1 L2.5,14 L5,6 L7.5,14 L9,1"),
            Map.entry('X', "M1,1 L9,14 M9,1 L1,14"),
            Map.entry('Y', "M1,1 L5,8 L9,1 M5,8 L5,14"),
            Map.entry('Z', "M1,1 L9,1 L1,14 L9,14"),
            Map.entry('2', "M1,3 L3,1 L7,1 L9,3 L9,5 L1,13 L9,13"),
            Map.entry('3', "M1,2 L3,1 L7,1 L9,3 L7,7 L9,9 L9,11 L7,13 L3,13 L1,12 M3,7 L7,7"),
            Map.entry('4', "M7,1 L1,9 L9,9 M7,1 L7,14"),
            Map.entry('5', "M9,1 L2,1 L2,7 L7,7 L9,9 L9,11 L7,13 L3,13 L1,11"),
            Map.entry('6', "M8,2 L6,1 L3,1 L1,4 L1,11 L3,13 L7,13 L9,11 L9,9 L7,7 L3,7 L1,9"),
            Map.entry('7', "M1,1 L9,1 L4,14"),
            Map.entry('8', "M5,1 L2,3 L2,6 L5,7.5 L8,6 L8,3 L5,1 M5,7.5 L2,9 L2,12 L5,14 L8,12 L8,9 L5,7.5"),
            Map.entry('9', "M9,5 L7,7 L3,7 L1,5 L1,3 L3,1 L7,1 L9,3 L9,11 L7,13 L3,13 L1,11")
    );

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
     *
     * Characters are drawn as path strokes (not &lt;text&gt;) with random
     * scale/rotate/translate, plus decoy stroke paths for obfuscation.
     */
    private String renderSvgBase64(String code) {
        int width = 140;
        int height = 48;
        StringBuilder svg = new StringBuilder(4_096);
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
            char ch = code.charAt(i);
            String glyph = GLYPH_PATHS.get(ch);
            if (glyph == null) {
                continue;
            }
            double baseX = 12 + i * 30;
            double baseY = 6 + random.nextDouble() * 4;
            double scale = 1.7 + random.nextDouble() * 0.5;
            double angle = (random.nextDouble() - 0.5) * 28;
            double tx = baseX + random.nextDouble() * 4;
            double ty = baseY + random.nextDouble() * 3;
            appendGlyphPath(svg, glyph, tx, ty, scale, angle, randomColor(50, 60, 140), 1.8 + random.nextDouble() * 0.4);
        }

        // Decoy stroke paths that mimic character strokes
        for (int i = 0; i < 6; i++) {
            appendDecoyPath(svg, width, height);
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

    private void appendGlyphPath(StringBuilder svg, String glyph, double tx, double ty,
                                 double scale, double angle, String color, double strokeWidth) {
        // Glyph box is ~10x14; pivot around glyph center (5,7) after scale
        double cx = 5 * scale;
        double cy = 7 * scale;
        svg.append("<path d=\"").append(glyph)
                .append("\" fill=\"none\" stroke=\"").append(color)
                .append("\" stroke-width=\"").append(String.format(Locale.ROOT, "%.2f", strokeWidth))
                .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\".92\" transform=\"")
                .append("translate(").append(String.format(Locale.ROOT, "%.2f", tx))
                .append(' ').append(String.format(Locale.ROOT, "%.2f", ty))
                .append(") rotate(").append(String.format(Locale.ROOT, "%.2f", angle))
                .append(' ').append(String.format(Locale.ROOT, "%.2f", cx))
                .append(' ').append(String.format(Locale.ROOT, "%.2f", cy))
                .append(") scale(").append(String.format(Locale.ROOT, "%.2f", scale))
                .append(")\"/>");
    }

    private void appendDecoyPath(StringBuilder svg, int width, int height) {
        int segments = 2 + random.nextInt(3);
        StringBuilder d = new StringBuilder(64);
        double x = 8 + random.nextDouble() * (width - 16);
        double y = 6 + random.nextDouble() * (height - 12);
        d.append('M').append(String.format(Locale.ROOT, "%.1f", x))
                .append(',').append(String.format(Locale.ROOT, "%.1f", y));
        for (int i = 0; i < segments; i++) {
            x += (random.nextDouble() - 0.5) * 18;
            y += (random.nextDouble() - 0.5) * 14;
            x = Math.max(4, Math.min(width - 4, x));
            y = Math.max(4, Math.min(height - 4, y));
            d.append('L').append(String.format(Locale.ROOT, "%.1f", x))
                    .append(',').append(String.format(Locale.ROOT, "%.1f", y));
        }
        double angle = (random.nextDouble() - 0.5) * 40;
        double scale = 0.7 + random.nextDouble() * 0.6;
        svg.append("<path d=\"").append(d)
                .append("\" fill=\"none\" stroke=\"").append(randomColor(120, 130, 180))
                .append("\" stroke-width=\"").append(String.format(Locale.ROOT, "%.2f", 1.0 + random.nextDouble()))
                .append("\" stroke-linecap=\"round\" opacity=\".45\" transform=\"rotate(")
                .append(String.format(Locale.ROOT, "%.2f", angle))
                .append(' ').append(String.format(Locale.ROOT, "%.1f", x))
                .append(' ').append(String.format(Locale.ROOT, "%.1f", y))
                .append(") scale(").append(String.format(Locale.ROOT, "%.2f", scale))
                .append(")\"/>");
    }

    private String randomColor(int redBase, int greenBase, int blueBase) {
        int red = redBase + random.nextInt(redBase == 0 ? 255 : 80);
        int green = greenBase + random.nextInt(greenBase == 0 ? 255 : 70);
        int blue = blueBase + random.nextInt(blueBase == 0 ? 255 : 50);
        return String.format(Locale.ROOT, "#%02x%02x%02x", red, green, blue);
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
