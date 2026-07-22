package com.gs.ais.util;

import ar.com.hjg.pngj.IImageLine;
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineHelper;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import ar.com.hjg.pngj.chunks.PngChunkPLTE;
import ar.com.hjg.pngj.chunks.PngChunkTRNS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure-Java (no AWT) longest-edge PNG thumbnail writer for GraalVM native image.
 * Decodes PNG via PNGJ; non-PNG formats are skipped with a warning.
 */
public final class PureThumbnail {
    private static final Logger log = LoggerFactory.getLogger(PureThumbnail.class);

    private PureThumbnail() {}

    public static void writeLongestEdgePng(byte[] imageData, Path thumbPath, int maxEdge) {
        try {
            if (imageData == null || imageData.length == 0) {
                log.warn("Thumbnail skipped: empty image data");
                return;
            }
            if (!isPng(imageData)) {
                log.warn("Thumbnail skipped: only PNG is supported (no AWT decoder)");
                return;
            }

            DecodedPng decoded = decodePngArgb(imageData);
            int sw = decoded.width;
            int sh = decoded.height;
            if (sw <= 0 || sh <= 0) {
                log.warn("Thumbnail skipped: empty image");
                return;
            }

            int tw = maxEdge;
            int th = (int) ((double) sh / sw * maxEdge);
            if (th > maxEdge) {
                th = maxEdge;
                tw = Math.max(1, (int) ((double) sw / sh * maxEdge));
            }
            tw = Math.max(1, tw);
            th = Math.max(1, th);

            int[] dst = scaleBilinear(decoded.argb, sw, sh, tw, th);
            byte[] pngBytes = encodePngRgba(dst, tw, th);
            Files.createDirectories(thumbPath.getParent());
            Files.write(thumbPath, pngBytes);
            log.info("Thumbnail saved: {} ({}x{})", thumbPath, tw, th);
        } catch (Exception e) {
            log.warn("Failed to generate thumbnail {}: {}", thumbPath, e.getMessage());
        }
    }

    private static boolean isPng(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xff) == 0x89
                && data[1] == 0x50
                && data[2] == 0x4e
                && data[3] == 0x47
                && data[4] == 0x0d
                && data[5] == 0x0a
                && data[6] == 0x1a
                && data[7] == 0x0a;
    }

    private static DecodedPng decodePngArgb(byte[] imageData) {
        PngReader png = new PngReader(new ByteArrayInputStream(imageData));
        try {
            ImageInfo info = png.imgInfo;
            int w = info.cols;
            int h = info.rows;
            int[] argb = new int[w * h];
            PngChunkPLTE plte = info.indexed ? png.getMetadata().getPLTE() : null;
            PngChunkTRNS trns = png.getMetadata().getTRNS();
            int bitDepth = info.bitDepth;

            for (int y = 0; y < h; y++) {
                IImageLine line = png.readRow(y);
                if (!(line instanceof ImageLineInt ili)) {
                    throw new IllegalStateException("Unexpected PNG line type: " + line.getClass().getName());
                }
                int[] scan = ili.getScanline();
                int rowBase = y * w;

                if (info.indexed) {
                    int[] rgba = ImageLineHelper.palette2rgba(ili, plte, trns, null);
                    for (int x = 0; x < w; x++) {
                        int i = x * 4;
                        argb[rowBase + x] = pack(rgba[i + 3], rgba[i], rgba[i + 1], rgba[i + 2]);
                    }
                } else if (info.greyscale) {
                    for (int x = 0; x < w; x++) {
                        int i = x * info.channels;
                        int g = to8(scan[i], bitDepth);
                        int a = info.alpha ? to8(scan[i + 1], bitDepth) : 255;
                        argb[rowBase + x] = pack(a, g, g, g);
                    }
                } else {
                    // RGB or RGBA
                    for (int x = 0; x < w; x++) {
                        int i = x * info.channels;
                        int r = to8(scan[i], bitDepth);
                        int g = to8(scan[i + 1], bitDepth);
                        int b = to8(scan[i + 2], bitDepth);
                        int a = info.alpha ? to8(scan[i + 3], bitDepth) : 255;
                        argb[rowBase + x] = pack(a, r, g, b);
                    }
                }
            }
            png.end();
            return new DecodedPng(argb, w, h);
        } finally {
            png.close();
        }
    }

    /** Scale sample to 0–255 from PNG bit depth (1/2/4/8/16). */
    private static int to8(int sample, int bitDepth) {
        if (bitDepth >= 8) {
            if (bitDepth == 16) {
                return (sample >> 8) & 0xff;
            }
            return sample & 0xff;
        }
        int max = (1 << bitDepth) - 1;
        if (max <= 0) {
            return 0;
        }
        return (sample * 255) / max;
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

    private static int ch(int c, int shift) {
        return (c >>> shift) & 0xff;
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

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

    private record DecodedPng(int[] argb, int width, int height) {}
}
