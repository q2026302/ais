package com.gs.ais.util;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;

/**
 * Pure-Java (no AWT/ImageIO) slider-puzzle captcha renderer for GraalVM native image.
 *
 * <p>Renders a decorative background with a "gap" (notch) cut out at a secret position and a
 * matching puzzle piece as two RGBA PNGs, using the same PNGJ raster stack as {@link PureThumbnail}.
 * The correct X position is never embedded in the returned bytes; it is returned to the caller
 * (CaptchaService) and stored server-side only.
 *
 * <p>Piece geometry: a rounded square (core {@code pieceWidth x pieceHeight}) with a semicircular
 * tab of radius {@link #TAB_RADIUS} protruding from the top edge. The piece PNG canvas is
 * {@code pieceWidth x (pieceHeight + tabRadius)}; its row 0 is the tab's top, so the piece canvas
 * top maps to {@code notchY - tabRadius} in background coordinates (returned as {@link Render#pieceY()}).
 * The notch is carved with the exact same shape at the exact same coordinates, so the piece and the
 * notch are complementary and align flush when the piece is dropped onto the notch.
 *
 * <p>The frontend overlays a compact slider track over the bottom {@value #TRACK_RESERVE_PX}
 * background pixels, so the piece (core + tab) is kept in the upper region of the background to stay
 * clear of that track.
 */
public final class PureSliderCaptchaImage {

    public static final int BACKGROUND_WIDTH = 320;
    public static final int BACKGROUND_HEIGHT = 112;

    public static final int PIECE_WIDTH = 44;
    public static final int PIECE_HEIGHT = 44;
    public static final int TAB_RADIUS = 10;
    public static final int CORNER_RADIUS = 6;

    /** Acceptable horizontal error, in background pixels, between the reported and the correct X. */
    public static final int TOLERANCE_PX = 5;

    /** Bottom strip of the background (in background px) reserved for the frontend's overlaid slider track. */
    public static final int TRACK_RESERVE_PX = 38;

    private static final int PIECE_PNG_HEIGHT = PIECE_HEIGHT + TAB_RADIUS;

    private PureSliderCaptchaImage() {
    }

    public static Render render(SecureRandom random) {
        SecureRandom rng = random != null ? random : new SecureRandom();
        int[] background = renderBackground(rng);

        int notchX = 20 + rng.nextInt(BACKGROUND_WIDTH - PIECE_WIDTH - 40);
        // Keep the piece (core + tab) inside the upper region so it stays clear of the bottom track.
        int minNotchY = TAB_RADIUS + 6;
        int maxNotchY = BACKGROUND_HEIGHT - PIECE_HEIGHT - TRACK_RESERVE_PX;
        int notchY = minNotchY + rng.nextInt(Math.max(1, maxNotchY - minNotchY + 1));

        int[] piece = renderPiece(background, notchX, notchY, rng);
        carveNotch(background, notchX, notchY);

        return new Render(
                encodePngRgba(background, BACKGROUND_WIDTH, BACKGROUND_HEIGHT),
                encodePngRgba(piece, PIECE_WIDTH, PIECE_PNG_HEIGHT),
                notchX,
                notchY - TAB_RADIUS);
    }

    /** Piece bounding-box top in background coordinates; the frontend pins the piece to this Y. */
    public record Render(byte[] backgroundPng, byte[] piecePng, int notchX, int pieceY) {
        public int backgroundWidth() {
            return BACKGROUND_WIDTH;
        }

        public int backgroundHeight() {
            return BACKGROUND_HEIGHT;
        }

        public int pieceWidth() {
            return PIECE_WIDTH;
        }

        public int pieceHeight() {
            return PIECE_PNG_HEIGHT;
        }
    }

    private static int[] renderBackground(SecureRandom rng) {
        int w = BACKGROUND_WIDTH;
        int h = BACKGROUND_HEIGHT;
        int[] px = new int[w * h];
        int top = randomPastel(rng);
        int bottom = randomPastel(rng);
        for (int y = 0; y < h; y++) {
            float t = (float) y / Math.max(1, h - 1);
            int row = lerpColor(top, bottom, t);
            for (int x = 0; x < w; x++) {
                px[y * w + x] = row;
            }
        }

        int blobs = 3 + rng.nextInt(4);
        for (int i = 0; i < blobs; i++) {
            double cx = rng.nextDouble() * w;
            double cy = rng.nextDouble() * h;
            double radius = 22 + rng.nextDouble() * 70;
            int color = withAlpha(randomPastel(rng), 36 + rng.nextInt(46));
            fillSoftCircle(px, w, h, cx, cy, radius, color);
        }

        // Fine grain so the background is not a smooth gradient (harder to synthesize a forged piece).
        for (int i = 0; i < w * h / 4; i++) {
            int x = rng.nextInt(w);
            int y = rng.nextInt(h);
            int idx = y * w + x;
            int n = rng.nextInt(16) - 8;
            px[idx] = pack(255,
                    clamp(ch(px[idx], 16) + n),
                    clamp(ch(px[idx], 8) + n),
                    clamp(ch(px[idx], 0) + n));
        }
        return px;
    }

    private static int[] renderPiece(int[] background, int notchX, int notchY, SecureRandom rng) {
        int w = PIECE_WIDTH;
        int h = PIECE_PNG_HEIGHT;
        int[] piece = new int[w * h];

        // Soft drop shadow (offset down/right), visible on the transparent margins.
        int shadowOffsetX = 2;
        int shadowOffsetY = 3;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Piece-canvas row 0 is the tab's top, i.e. local shape y = -TAB_RADIUS.
                double cov = shapeCoverage(x + 0.5 - shadowOffsetX, y + 0.5 - TAB_RADIUS - shadowOffsetY);
                if (cov <= 0) {
                    continue;
                }
                int a = (int) Math.round(64 * cov);
                piece[y * w + x] = blendOver(piece[y * w + x], 18, 22, 46, a);
            }
        }

        // Copy the untouched background texture inside the piece shape.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double cov = shapeCoverage(x + 0.5, y + 0.5 - TAB_RADIUS);
                if (cov <= 0) {
                    continue;
                }
                int bgX = notchX + x;
                int bgY = (notchY - TAB_RADIUS) + y;
                if (bgX < 0 || bgX >= BACKGROUND_WIDTH || bgY < 0 || bgY >= BACKGROUND_HEIGHT) {
                    continue;
                }
                int src = background[bgY * BACKGROUND_WIDTH + bgX];
                int a = (int) Math.round(255 * cov);
                piece[y * w + x] = blendOver(piece[y * w + x], ch(src, 16), ch(src, 8), ch(src, 0), a);
            }
        }

        // Crisp light edge so the piece reads as a distinct tile.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double cov = shapeCoverage(x + 0.5, y + 0.5 - TAB_RADIUS);
                if (cov > 0 && cov < 1) {
                    int a = (int) Math.round(130 * (1 - cov));
                    piece[y * w + x] = blendOver(piece[y * w + x], 255, 255, 255, a);
                }
            }
        }
        return piece;
    }

    private static void carveNotch(int[] background, int notchX, int notchY) {
        int minX = Math.max(0, notchX - 1);
        int maxX = Math.min(BACKGROUND_WIDTH - 1, notchX + PIECE_WIDTH + 1);
        int minY = Math.max(0, notchY - TAB_RADIUS - 1);
        int maxY = Math.min(BACKGROUND_HEIGHT - 1, notchY + PIECE_HEIGHT + 1);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double cov = shapeCoverage(x + 0.5 - notchX, y + 0.5 - notchY);
                if (cov <= 0) {
                    continue;
                }
                int idx = y * BACKGROUND_WIDTH + x;
                int src = background[idx];
                if (cov >= 0.95) {
                    // Interior: keep the original texture untouched so the dropped piece completes
                    // the image seamlessly (piece content == background pixels at this exact spot).
                    continue;
                }
                // Edge band: dark outline so the notch target stays clearly visible on the background.
                int amount = (int) Math.round(220 * cov);
                background[idx] = blendOver(src, 52, 62, 98, amount);
            }
        }
    }

    /**
     * Coverage (0..1, anti-aliased) of the piece shape at local coordinates.
     * The core rounded square spans [0,pieceW]x[0,pieceH]; the tab is a semicircle centered on the
     * top edge (pieceW/2, 0) with radius {@link #TAB_RADIUS}, for ly &lt;= 0.
     */
    static double shapeCoverage(double lx, double ly) {
        double core = roundedRectCoverage(lx, ly, 0, 0, PIECE_WIDTH, PIECE_HEIGHT, CORNER_RADIUS);
        double tab = 0;
        if (ly <= 0) {
            tab = circleCoverage(lx, ly, PIECE_WIDTH / 2.0, 0, TAB_RADIUS);
        }
        double cov = Math.max(core, tab);
        return clamp01(cov);
    }

    private static double roundedRectCoverage(double px, double py, double x0, double y0,
                                              double w, double h, double r) {
        double cx = x0 + w / 2.0;
        double cy = y0 + h / 2.0;
        double hw = w / 2.0;
        double hh = h / 2.0;
        double qx = Math.abs(px - cx) - (hw - r);
        double qy = Math.abs(py - cy) - (hh - r);
        double ax = Math.max(qx, 0);
        double ay = Math.max(qy, 0);
        double outside = Math.hypot(ax, ay);
        double inside = Math.min(Math.max(qx, qy), 0);
        double dist = outside + inside - r;
        return clamp01(0.5 - dist);
    }

    private static double circleCoverage(double px, double py, double cx, double cy, double r) {
        double d = Math.hypot(px - cx, py - cy) - r;
        return clamp01(0.5 - d);
    }

    private static void fillSoftCircle(int[] px, int w, int h, double cx, double cy, double radius, int color) {
        int minX = Math.max(0, (int) Math.floor(cx - radius - 1));
        int maxX = Math.min(w - 1, (int) Math.ceil(cx + radius + 1));
        int minY = Math.max(0, (int) Math.floor(cy - radius - 1));
        int maxY = Math.min(h - 1, (int) Math.ceil(cy + radius + 1));
        int srcA = (color >>> 24) & 0xff;
        int srcR = (color >> 16) & 0xff;
        int srcG = (color >> 8) & 0xff;
        int srcB = color & 0xff;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double d = Math.hypot((x + 0.5) - cx, (y + 0.5) - cy);
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
                px[y * w + x] = blendOver(px[y * w + x], srcR, srcG, srcB, a);
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

    private static int randomPastel(SecureRandom rng) {
        double hue = rng.nextDouble() * 360.0;
        double saturation = 0.45 + rng.nextDouble() * 0.35;
        double value = 0.72 + rng.nextDouble() * 0.20;
        return hsvToRgb(hue, saturation, value);
    }

    private static int hsvToRgb(double hue, double saturation, double value) {
        double c = value * saturation;
        double hp = (hue / 60.0) % 6.0;
        double x = c * (1 - Math.abs(hp % 2 - 1));
        double m = value - c;
        double r = 0;
        double g = 0;
        double b = 0;
        if (hp < 1) {
            r = c;
            g = x;
        } else if (hp < 2) {
            r = x;
            g = c;
        } else if (hp < 3) {
            g = c;
            b = x;
        } else if (hp < 4) {
            g = x;
            b = c;
        } else if (hp < 5) {
            r = x;
            b = c;
        } else {
            r = c;
            b = x;
        }
        return pack(255,
                (int) Math.round((r + m) * 255),
                (int) Math.round((g + m) * 255),
                (int) Math.round((b + m) * 255));
    }

    private static int withAlpha(int argb, int alpha) {
        return pack(alpha, (argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff);
    }

    private static int lerpColor(int c0, int c1, float t) {
        return pack(255,
                Math.round(((c0 >> 16) & 0xff) * (1 - t) + ((c1 >> 16) & 0xff) * t),
                Math.round(((c0 >> 8) & 0xff) * (1 - t) + ((c1 >> 8) & 0xff) * t),
                Math.round((c0 & 0xff) * (1 - t) + (c1 & 0xff) * t));
    }

    private static int ch(int c, int shift) {
        return (c >>> shift) & 0xff;
    }

    private static int pack(int a, int r, int g, int b) {
        return ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
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
}
