package com.gs.ais.util;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;
import org.apache.commons.imaging.Imaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PureThumbnail {
    private static final Logger log = LoggerFactory.getLogger(PureThumbnail.class);
    private PureThumbnail() {}

    public static void writeLongestEdgePng(byte[] imageData, Path thumbPath, int maxEdge) {
        try {
            if (isWebP(imageData)) {
                log.warn("Thumbnail skipped: WebP not supported");
                return;
            }
            BufferedImage bi = Imaging.getBufferedImage(imageData);
            if (bi == null) { log.warn("Thumbnail skipped: null image"); return; }
            int sw = bi.getWidth(), sh = bi.getHeight();
            if (sw <= 0 || sh <= 0) { log.warn("Thumbnail skipped: empty image"); return; }

            int tw = maxEdge, th = (int) ((double) sh / sw * maxEdge);
            if (th > maxEdge) { th = maxEdge; tw = Math.max(1, (int) ((double) sw / sh * maxEdge)); }
            tw = Math.max(1, tw); th = Math.max(1, th);

            int[] src = bi.getRGB(0, 0, sw, sh, null, 0, sw);
            int[] dst = scaleBilinear(src, sw, sh, tw, th);
            byte[] pngBytes = encodePngRgba(dst, tw, th);
            Files.createDirectories(thumbPath.getParent());
            Files.write(thumbPath, pngBytes);
            log.info("Thumbnail saved: {} ({}x{})", thumbPath, tw, th);
        } catch (Exception e) {
            log.warn("Failed to generate thumbnail {}: {}", thumbPath, e.getMessage());
        }
    }

    private static boolean isWebP(byte[] data) {
        return data != null && data.length >= 12
            && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private static int[] scaleBilinear(int[] src, int sw, int sh, int dw, int dh) {
        int[] dst = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            float sy = (y + 0.5f) * sh / dh - 0.5f;
            int y0 = Math.max(0, (int) Math.floor(sy));
            int y1 = Math.min(sh - 1, y0 + 1);
            float fy = sy - y0;
            for (int x = 0; x < dw; x++) {
                float sx = (x + 0.5f) * sw / dw - 0.5f;
                int x0 = Math.max(0, (int) Math.floor(sx));
                int x1 = Math.min(sw - 1, x0 + 1);
                float fx = sx - x0;
                dst[y * dw + x] = blend4(src[y0 * sw + x0], src[y0 * sw + x1],
                    src[y1 * sw + x0], src[y1 * sw + x1], fx, fy);
            }
        }
        return dst;
    }

    private static int blend4(int c00, int c10, int c01, int c11, float fx, float fy) {
        return pack(
            lerp(lerp(ch(c00, 24), ch(c10, 24), fx), lerp(ch(c01, 24), ch(c11, 24), fx), fy),
            lerp(lerp(ch(c00, 16), ch(c10, 16), fx), lerp(ch(c01, 16), ch(c11, 16), fx), fy),
            lerp(lerp(ch(c00, 8), ch(c10, 8), fx), lerp(ch(c01, 8), ch(c11, 8), fx), fy),
            lerp(lerp(ch(c00, 0), ch(c10, 0), fx), lerp(ch(c01, 0), ch(c11, 0), fx), fy));
    }

    private static int ch(int c, int shift) { return (c >>> shift) & 0xff; }
    private static int lerp(int a, int b, float t) { return Math.round(a + (b - a) * t); }
    private static int pack(int a, int r, int g, int b) {
        return ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
    }

    private static byte[] encodePngRgba(int[] argb, int w, int h) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageInfo imi = new ImageInfo(w, h, 8, true);
        PngWriter png = new PngWriter(baos, imi);
        ImageLineInt line = new ImageLineInt(imi);
        int[] scan = line.getScanline();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                int i = x * 4;
                scan[i] = (c >> 16) & 0xff; scan[i + 1] = (c >> 8) & 0xff;
                scan[i + 2] = c & 0xff; scan[i + 3] = (c >>> 24) & 0xff;
            }
            png.writeRow(line);
        }
        png.end();
        return baos.toByteArray();
    }
}
