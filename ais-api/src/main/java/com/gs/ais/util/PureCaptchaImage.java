package com.gs.ais.util;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java (no AWT) captcha PNG renderer for GraalVM native image.
 * Draws stroke glyphs into an ARGB buffer and encodes PNG via PNGJ (same stack as {@link PureThumbnail}).
 */
public final class PureCaptchaImage {

    /** Glyph paths in a 0..10 x 0..14 design box (origin top-left), using only M/L/Z. */
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

    private PureCaptchaImage() {
    }

    public static byte[] renderPng(String code, SecureRandom random) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("captcha code is empty");
        }
        SecureRandom rng = random != null ? random : new SecureRandom();
        int width = 160;
        int height = 56;
        int[] pixels = new int[width * height];

        fillBackground(pixels, width, height, rng);

        // Interference lines behind text
        for (int i = 0; i < 10; i++) {
            int color = randomArgb(rng, 140, 150, 190, 160 + rng.nextInt(60));
            double thickness = 1.0 + rng.nextDouble() * 1.4;
            drawLine(pixels, width, height,
                    rng.nextDouble() * width, rng.nextDouble() * height,
                    rng.nextDouble() * width, rng.nextDouble() * height,
                    color, thickness);
        }

        for (int i = 0; i < code.length(); i++) {
            char ch = Character.toUpperCase(code.charAt(i));
            String glyph = GLYPH_PATHS.get(ch);
            if (glyph == null) {
                continue;
            }
            double baseX = 10 + i * 36.0 + rng.nextDouble() * 5;
            double baseY = 5 + rng.nextDouble() * 5;
            double scale = 2.0 + rng.nextDouble() * 0.55;
            double angleDeg = (rng.nextDouble() - 0.5) * 32;
            double stroke = 2.0 + rng.nextDouble() * 0.9;
            int color = randomArgb(rng, 35, 45, 110, 230 + rng.nextInt(25));
            drawGlyph(pixels, width, height, glyph, baseX, baseY, scale, angleDeg, stroke, color);
        }

        // Decoy strokes that mimic partial characters
        for (int i = 0; i < 7; i++) {
            drawDecoy(pixels, width, height, rng);
        }

        // Speckle noise
        for (int i = 0; i < 90; i++) {
            int x = rng.nextInt(width);
            int y = rng.nextInt(height);
            int color = randomArgb(rng, 0, 0, 0, 90 + rng.nextInt(120));
            stamp(pixels, width, height, x + 0.5, y + 0.5, 0.55 + rng.nextDouble() * 0.7, color);
        }

        // Foreground interference arcs/lines
        for (int i = 0; i < 4; i++) {
            int color = randomArgb(rng, 90, 100, 160, 110 + rng.nextInt(70));
            drawLine(pixels, width, height,
                    rng.nextDouble() * width, rng.nextDouble() * height,
                    rng.nextDouble() * width, rng.nextDouble() * height,
                    color, 1.1 + rng.nextDouble());
        }

        pixels = waveDistort(pixels, width, height, rng);
        // Soft blur reduces clean edge features used by simple OCR pipelines.
        pixels = boxBlur3(pixels, width, height);
        return encodePngRgba(pixels, width, height);
    }

    private static void fillBackground(int[] pixels, int width, int height, SecureRandom rng) {
        int c0 = pack(255, 242 + rng.nextInt(10), 244 + rng.nextInt(8), 252);
        int c1 = pack(255, 228 + rng.nextInt(12), 232 + rng.nextInt(10), 248);
        for (int y = 0; y < height; y++) {
            float ty = (float) y / Math.max(1, height - 1);
            for (int x = 0; x < width; x++) {
                float tx = (float) x / Math.max(1, width - 1);
                float t = (tx * 0.55f + ty * 0.45f + (rng.nextFloat() - 0.5f) * 0.04f);
                t = Math.max(0f, Math.min(1f, t));
                pixels[y * width + x] = lerpColor(c0, c1, t);
                // Fine grain
                if (rng.nextInt(7) == 0) {
                    int n = 4 + rng.nextInt(10);
                    int base = pixels[y * width + x];
                    int deltaR = rng.nextBoolean() ? n : -n;
                    int deltaG = rng.nextBoolean() ? n : -n;
                    int deltaB = rng.nextBoolean() ? n : -n;
                    pixels[y * width + x] = pack(
                            255,
                            clamp(((base >> 16) & 0xff) + deltaR),
                            clamp(((base >> 8) & 0xff) + deltaG),
                            clamp((base & 0xff) + deltaB));
                }
            }
        }
    }

    private static void drawGlyph(int[] pixels, int w, int h, String path,
                                  double tx, double ty, double scale, double angleDeg,
                                  double strokeWidth, int color) {
        double pivotX = 5 * scale;
        double pivotY = 7 * scale;
        double rad = Math.toRadians(angleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        List<double[]> segments = parsePathSegments(path);
        for (double[] seg : segments) {
            // seg: x0,y0,x1,y1 in glyph space
            double[] p0 = transformPoint(seg[0], seg[1], scale, cos, sin, pivotX, pivotY, tx, ty);
            double[] p1 = transformPoint(seg[2], seg[3], scale, cos, sin, pivotX, pivotY, tx, ty);
            drawLine(pixels, w, h, p0[0], p0[1], p1[0], p1[1], color, strokeWidth);
        }
    }

    private static double[] transformPoint(double x, double y, double scale,
                                           double cos, double sin,
                                           double pivotX, double pivotY,
                                           double tx, double ty) {
        // scale around origin, then rotate around scaled pivot, then translate
        double sx = x * scale;
        double sy = y * scale;
        double dx = sx - pivotX;
        double dy = sy - pivotY;
        double rx = dx * cos - dy * sin + pivotX;
        double ry = dx * sin + dy * cos + pivotY;
        return new double[]{rx + tx, ry + ty};
    }

    /**
     * Parses M/L/Z paths into independent line segments (x0,y0,x1,y1).
     * Z closes to the most recent subpath start.
     */
    private static List<double[]> parsePathSegments(String path) {
        List<double[]> segments = new ArrayList<>();
        double cx = 0;
        double cy = 0;
        double startX = 0;
        double startY = 0;
        boolean hasPoint = false;
        int i = 0;
        int n = path.length();
        while (i < n) {
            char cmd = path.charAt(i);
            if (Character.isWhitespace(cmd) || cmd == ',') {
                i++;
                continue;
            }
            if (cmd == 'M' || cmd == 'L' || cmd == 'Z' || cmd == 'z') {
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    if (hasPoint) {
                        segments.add(new double[]{cx, cy, startX, startY});
                        cx = startX;
                        cy = startY;
                    }
                    continue;
                }
                double[] pt = readPoint(path, i);
                if (pt == null) {
                    break;
                }
                i = (int) pt[2];
                if (cmd == 'M' || !hasPoint) {
                    cx = pt[0];
                    cy = pt[1];
                    startX = cx;
                    startY = cy;
                    hasPoint = true;
                } else {
                    segments.add(new double[]{cx, cy, pt[0], pt[1]});
                    cx = pt[0];
                    cy = pt[1];
                }
            } else {
                // Unexpected char — skip
                i++;
            }
        }
        return segments;
    }

    /** Returns {x,y,nextIndex} or null. */
    private static double[] readPoint(String path, int from) {
        int i = from;
        int n = path.length();
        while (i < n && (Character.isWhitespace(path.charAt(i)) || path.charAt(i) == ',')) {
            i++;
        }
        int start = i;
        while (i < n) {
            char c = path.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+') {
                i++;
            } else {
                break;
            }
        }
        if (i == start) {
            return null;
        }
        double x = Double.parseDouble(path.substring(start, i));
        while (i < n && (Character.isWhitespace(path.charAt(i)) || path.charAt(i) == ',')) {
            i++;
        }
        start = i;
        while (i < n) {
            char c = path.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+') {
                i++;
            } else {
                break;
            }
        }
        if (i == start) {
            return null;
        }
        double y = Double.parseDouble(path.substring(start, i));
        return new double[]{x, y, i};
    }

    private static void drawDecoy(int[] pixels, int width, int height, SecureRandom rng) {
        double x = 6 + rng.nextDouble() * (width - 12);
        double y = 5 + rng.nextDouble() * (height - 10);
        int segments = 2 + rng.nextInt(3);
        int color = randomArgb(rng, 100, 110, 160, 90 + rng.nextInt(80));
        double thickness = 1.0 + rng.nextDouble() * 1.2;
        for (int i = 0; i < segments; i++) {
            double nx = x + (rng.nextDouble() - 0.5) * 22;
            double ny = y + (rng.nextDouble() - 0.5) * 16;
            nx = Math.max(2, Math.min(width - 2, nx));
            ny = Math.max(2, Math.min(height - 2, ny));
            drawLine(pixels, width, height, x, y, nx, ny, color, thickness);
            x = nx;
            y = ny;
        }
    }

    private static void drawLine(int[] pixels, int w, int h,
                                 double x0, double y0, double x1, double y1,
                                 int color, double thickness) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        double radius = Math.max(0.6, thickness * 0.55);
        if (len < 1e-4) {
            stamp(pixels, w, h, x0, y0, radius, color);
            return;
        }
        int steps = Math.max(2, (int) Math.ceil(len * 2.4));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            stamp(pixels, w, h, x0 + dx * t, y0 + dy * t, radius, color);
        }
    }

    /** Soft circular brush stamp with coverage-based anti-aliasing. */
    private static void stamp(int[] pixels, int w, int h, double cx, double cy, double radius, int color) {
        int minX = Math.max(0, (int) Math.floor(cx - radius - 1));
        int maxX = Math.min(w - 1, (int) Math.ceil(cx + radius + 1));
        int minY = Math.max(0, (int) Math.floor(cy - radius - 1));
        int maxY = Math.min(h - 1, (int) Math.ceil(cy + radius + 1));
        double r2 = radius * radius;
        int srcA = (color >>> 24) & 0xff;
        int srcR = (color >> 16) & 0xff;
        int srcG = (color >> 8) & 0xff;
        int srcB = color & 0xff;

        for (int y = minY; y <= maxY; y++) {
            double dy = (y + 0.5) - cy;
            for (int x = minX; x <= maxX; x++) {
                double dx = (x + 0.5) - cx;
                double d2 = dx * dx + dy * dy;
                if (d2 > (radius + 1) * (radius + 1)) {
                    continue;
                }
                double d = Math.sqrt(d2);
                double cover;
                if (d <= radius - 0.5) {
                    cover = 1.0;
                } else if (d >= radius + 0.5) {
                    cover = 0.0;
                } else {
                    cover = 1.0 - (d - (radius - 0.5));
                }
                if (cover <= 0) {
                    continue;
                }
                int a = (int) Math.round(srcA * cover);
                if (a <= 0) {
                    continue;
                }
                int idx = y * w + x;
                pixels[idx] = blendOver(pixels[idx], srcR, srcG, srcB, a);
            }
        }
    }

    private static int blendOver(int dst, int sr, int sg, int sb, int sa) {
        if (sa >= 255) {
            return pack(255, sr, sg, sb);
        }
        int da = (dst >>> 24) & 0xff;
        int dr = (dst >> 16) & 0xff;
        int dg = (dst >> 8) & 0xff;
        int db = dst & 0xff;
        int inv = 255 - sa;
        int outA = sa + (da * inv + 127) / 255;
        int outR = (sr * sa + dr * inv + 127) / 255;
        int outG = (sg * sa + dg * inv + 127) / 255;
        int outB = (sb * sa + db * inv + 127) / 255;
        return pack(outA, outR, outG, outB);
    }

    private static int[] waveDistort(int[] src, int w, int h, SecureRandom rng) {
        double ampX = 1.2 + rng.nextDouble() * 2.2;
        double ampY = 0.8 + rng.nextDouble() * 1.6;
        double freqX = (2 * Math.PI) / (16 + rng.nextDouble() * 18);
        double freqY = (2 * Math.PI) / (14 + rng.nextDouble() * 16);
        double phaseX = rng.nextDouble() * Math.PI * 2;
        double phaseY = rng.nextDouble() * Math.PI * 2;
        int[] dst = new int[w * h];
        // default background
        int bg = src[0];
        for (int i = 0; i < dst.length; i++) {
            dst[i] = bg;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = (int) Math.round(x + Math.sin(y * freqX + phaseX) * ampX);
                int sy = (int) Math.round(y + Math.sin(x * freqY + phaseY) * ampY);
                if (sx >= 0 && sx < w && sy >= 0 && sy < h) {
                    dst[y * w + x] = src[sy * w + sx];
                }
            }
        }
        return dst;
    }

    private static int[] boxBlur3(int[] src, int w, int h) {
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, r = 0, g = 0, b = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) {
                            continue;
                        }
                        int c = src[yy * w + xx];
                        a += (c >>> 24) & 0xff;
                        r += (c >> 16) & 0xff;
                        g += (c >> 8) & 0xff;
                        b += c & 0xff;
                        n++;
                    }
                }
                dst[y * w + x] = pack(a / n, r / n, g / n, b / n);
            }
        }
        return dst;
    }

    private static int randomArgb(SecureRandom rng, int redBase, int greenBase, int blueBase, int alpha) {
        int red = clamp(redBase + rng.nextInt(redBase == 0 ? 200 : 70));
        int green = clamp(greenBase + rng.nextInt(greenBase == 0 ? 200 : 60));
        int blue = clamp(blueBase + rng.nextInt(blueBase == 0 ? 200 : 50));
        return pack(clamp(alpha), red, green, blue);
    }

    private static int lerpColor(int c0, int c1, float t) {
        return pack(
                255,
                Math.round(((c0 >> 16) & 0xff) * (1 - t) + ((c1 >> 16) & 0xff) * t),
                Math.round(((c0 >> 8) & 0xff) * (1 - t) + ((c1 >> 8) & 0xff) * t),
                Math.round((c0 & 0xff) * (1 - t) + (c1 & 0xff) * t));
    }

    private static int pack(int a, int r, int g, int b) {
        return ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static byte[] encodePngRgba(int[] argb, int width, int height) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(width * height + 1024);
        ImageInfo imi = new ImageInfo(width, height, 8, true);
        PngWriter png = new PngWriter(baos, imi);
        ImageLineInt line = new ImageLineInt(imi);
        int[] scan = line.getScanline();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = argb[y * width + x];
                int i = x * 4;
                scan[i] = (c >> 16) & 0xff;
                scan[i + 1] = (c >> 8) & 0xff;
                scan[i + 2] = c & 0xff;
                scan[i + 3] = (c >>> 24) & 0xff;
            }
            png.writeRow(line);
        }
        png.end();
        return baos.toByteArray();
    }

    /** Visible for tests — confirms glyph coverage for alphabet used by CaptchaService. */
    static boolean hasGlyph(char ch) {
        return GLYPH_PATHS.containsKey(Character.toUpperCase(ch));
    }
}
