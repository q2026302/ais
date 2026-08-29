package com.gs.ais.util;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReaderInt;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureSliderCaptchaImageTests {

    @Test
    void renderProducesValidPngBytes() {
        SecureRandom random = new SecureRandom();
        random.setSeed(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        PureSliderCaptchaImage.Render render = PureSliderCaptchaImage.render(random);
        assertPng(render.backgroundPng());
        assertPng(render.piecePng());
        assertEquals(PureSliderCaptchaImage.BACKGROUND_WIDTH, render.backgroundWidth());
        assertEquals(PureSliderCaptchaImage.BACKGROUND_HEIGHT, render.backgroundHeight());
        assertEquals(PureSliderCaptchaImage.PIECE_WIDTH, render.pieceWidth());
    }

    @Test
    void pieceAndNotchStayInsideBackgroundBounds() {
        SecureRandom random = new SecureRandom();
        random.setSeed(new byte[]{9, 8, 7, 6, 5, 4, 3, 2});
        for (int i = 0; i < 100; i++) {
            PureSliderCaptchaImage.Render render = PureSliderCaptchaImage.render(random);
            assertTrue(render.pieceY() >= 0, "pieceY must be >= 0");
            assertTrue(render.pieceY() + render.pieceHeight() <= render.backgroundHeight(),
                    "piece must fit vertically");
            assertTrue(render.notchX() >= 0, "notchX must be >= 0");
            assertTrue(render.notchX() + PureSliderCaptchaImage.PIECE_WIDTH <= render.backgroundWidth(),
                    "notch must fit horizontally");
        }
    }

    @Test
    void differentSeedsProduceDifferentImages() {
        SecureRandom aRandom = new SecureRandom();
        aRandom.setSeed(new byte[]{1, 1, 1, 1, 1, 2, 3, 4});
        SecureRandom bRandom = new SecureRandom();
        bRandom.setSeed(new byte[]{9, 9, 9, 9, 5, 6, 7, 8});
        byte[] a = PureSliderCaptchaImage.render(aRandom).backgroundPng();
        byte[] b = PureSliderCaptchaImage.render(bRandom).backgroundPng();
        assertTrue(a.length != b.length || !Arrays.equals(a, b));
    }

    @Test
    void shapeCoverageIsPositiveInsideAndZeroOutside() {
        // Center of the core rounded square (core spans ly in [0, PIECE_HEIGHT]).
        double inside = PureSliderCaptchaImage.shapeCoverage(
                PureSliderCaptchaImage.PIECE_WIDTH / 2.0,
                PureSliderCaptchaImage.PIECE_HEIGHT / 2.0);
        assertTrue(inside > 0.5, "core center should be covered, got " + inside);

        // Tab center: the semicircular bump sits above the core, i.e. at negative ly.
        double tab = PureSliderCaptchaImage.shapeCoverage(
                PureSliderCaptchaImage.PIECE_WIDTH / 2.0,
                -PureSliderCaptchaImage.TAB_RADIUS / 2.0);
        assertTrue(tab > 0.5, "tab center should be covered, got " + tab);

        // Far outside the shape.
        double outside = PureSliderCaptchaImage.shapeCoverage(-50, -50);
        assertEquals(0.0, outside, 1e-9);
    }

    @Test
    void pieceCarriesTopTabAndMatchesDarkenedNotch() throws Exception {
        PureSliderCaptchaImage.Render render = PureSliderCaptchaImage.render(new SecureRandom());
        int[][] bg = decodeRgba(render.backgroundPng());
        int[][] piece = decodeRgba(render.piecePng());
        int pw = render.pieceWidth();
        int ph = render.pieceHeight();

        // (1) The piece has a semicircular tab on top: the tab tip (row 0) is a narrow bump,
        // the core is full width, and the bottom row still has content (no transparent margin).
        int coreWidth = countOpaqueInRow(piece, PureSliderCaptchaImage.TAB_RADIUS + 10);
        assertEquals(pw, coreWidth, "core row should be full width");
        int tabTip = countOpaqueInRow(piece, 0);
        assertTrue(tabTip > 0 && tabTip < coreWidth, "tab tip should be a narrow bump, got " + tabTip);
        assertTrue(countOpaqueInRow(piece, ph - 1) > 0, "bottom row should have content");

        // (2) Every fully-opaque piece pixel outside the boundary stroke band is the *undarkened*
        // texture at (notchX + x, pieceY + y): the carved background there is exactly the interior
        // darkening of that pixel. This proves the piece interior is still a pixel-exact crop of the
        // notch region, only with the gap visually darkened and the piece boundary stroked on top.
        int opaque = 0;
        int stroked = 0;
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int p = piece[y][x];
                if (alpha(p) < 255) {
                    continue;
                }
                opaque++;
                double lx = x + 0.5;
                double ly = y + 0.5 - PureSliderCaptchaImage.TAB_RADIUS;
                if (Math.abs(PureSliderCaptchaImage.shapeSignedDistance(lx, ly))
                        <= PureSliderCaptchaImage.PIECE_STROKE_HALF_WIDTH) {
                    // Boundary stroke overlays the crop; the seamless-fit contract is asserted on the
                    // interior only (the stroke is intentional decoration at the very edge).
                    stroked++;
                    continue;
                }
                int bx = render.notchX() + x;
                int by = render.pieceY() + y;
                assertTrue(bx >= 0 && bx < bg[0].length && by >= 0 && by < bg.length,
                        "piece crop out of background bounds at " + x + "," + y);
                int expected = PureSliderCaptchaImage.darkenNotchInterior(p);
                int b = bg[by][bx];
                assertEquals(red(expected), red(b), "R mismatch at " + x + "," + y);
                assertEquals(green(expected), green(b), "G mismatch at " + x + "," + y);
                assertEquals(blue(expected), blue(b), "B mismatch at " + x + "," + y);
            }
        }
        assertTrue(opaque > 1000, "piece should have a substantial opaque area, got " + opaque);
        assertTrue(stroked > 0, "piece boundary should carry a stroke band, got " + stroked);
    }

    @Test
    void notchInteriorIsDarkenedAndOutlined() throws Exception {
        SecureRandom random = new SecureRandom();
        random.setSeed(new byte[]{5, 4, 3, 2, 1, 6, 7, 8});
        PureSliderCaptchaImage.Render render = PureSliderCaptchaImage.render(random);
        int[][] bg = decodeRgba(render.backgroundPng());
        int notchX = render.notchX();
        // The notch core top (notchY) is pieceY + TAB_RADIUS (see the piece-canvas coordinate mapping).
        int notchY = render.pieceY() + PureSliderCaptchaImage.TAB_RADIUS;

        double interiorLum = 0;
        int interiorN = 0;
        double edgeLum = 0;
        int edgeN = 0;
        int minY = Math.max(0, notchY - PureSliderCaptchaImage.TAB_RADIUS - 2);
        int maxY = Math.min(bg.length - 1, notchY + PureSliderCaptchaImage.PIECE_HEIGHT + 2);
        int minX = Math.max(0, notchX - 2);
        int maxX = Math.min(bg[0].length - 1, notchX + PureSliderCaptchaImage.PIECE_WIDTH + 2);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double cov = PureSliderCaptchaImage.shapeCoverage(x + 0.5 - notchX, y + 0.5 - notchY);
                if (cov <= 0) {
                    continue;
                }
                double lum = lum(bg[y][x]);
                if (cov >= 0.95) {
                    interiorLum += lum;
                    interiorN++;
                } else {
                    edgeLum += lum;
                    edgeN++;
                }
            }
        }
        // Surroundings: untouched strips immediately flanking the notch core (same vertical band).
        double outsideLum = 0;
        int outsideN = 0;
        int stripY0 = Math.max(0, notchY);
        int stripY1 = Math.min(bg.length - 1, notchY + PureSliderCaptchaImage.PIECE_HEIGHT);
        for (int y = stripY0; y <= stripY1; y++) {
            for (int x = Math.max(0, notchX - 8); x < notchX; x++) {
                outsideLum += lum(bg[y][x]);
                outsideN++;
            }
            int rightFrom = notchX + PureSliderCaptchaImage.PIECE_WIDTH + 1;
            int rightTo = Math.min(bg[0].length - 1, notchX + PureSliderCaptchaImage.PIECE_WIDTH + 8);
            for (int x = rightFrom; x <= rightTo; x++) {
                outsideLum += lum(bg[y][x]);
                outsideN++;
            }
        }
        assertTrue(interiorN > 0 && edgeN > 0 && outsideN > 0,
                "notch region should have interior, edge and outside pixels");
        double interiorAvg = interiorLum / interiorN;
        double edgeAvg = edgeLum / edgeN;
        double outsideAvg = outsideLum / outsideN;
        // The gap interior must be clearly darker than the untouched surroundings (visible target).
        assertTrue(outsideAvg > interiorAvg + 20,
                "notch interior should be clearly darker than surroundings: interior="
                        + interiorAvg + " outside=" + outsideAvg);
        // The outline ring must be darker still than the interior (crisp contour).
        assertTrue(interiorAvg > edgeAvg + 10,
                "notch outline should be darker than the interior: interior="
                        + interiorAvg + " edge=" + edgeAvg);
    }

    @Test
    void pieceBoundaryIsVisiblyDarkerThanInterior() throws Exception {
        SecureRandom random = new SecureRandom();
        random.setSeed(new byte[]{7, 7, 7, 7, 8, 8, 8, 8});
        PureSliderCaptchaImage.Render render = PureSliderCaptchaImage.render(random);
        int[][] piece = decodeRgba(render.piecePng());
        int pw = render.pieceWidth();

        // Sample the *straight-edge* mid-spans, not the rounded corners/tab: the left/right edges at
        // the core's middle row, and the top/bottom edges at horizontal positions clear of the top
        // tab and the corner radii. Each stroke sample averages the outer 2 px band; the interior
        // reference is the same row/column band a few px inside, so the background gradient and grain
        // cancel out and the measured darkening is attributable to the stroke alone.
        int coreTop = PureSliderCaptchaImage.TAB_RADIUS;
        int coreBottom = coreTop + PureSliderCaptchaImage.PIECE_HEIGHT; // exclusive
        int midY = coreTop + PureSliderCaptchaImage.PIECE_HEIGHT / 2;

        double leftEdge = avgLum(piece, 0, 1, midY, midY);
        double leftInterior = avgLum(piece, 6, 37, midY, midY);

        double rightEdge = avgLum(piece, pw - 2, pw - 1, midY, midY);
        double rightInterior = avgLum(piece, 6, 37, midY, midY);

        int topX = 8; // straight top edge, left of the tab (spans x 12..32) and right of the corner radius
        double topEdge = avgLum(piece, topX, topX, coreTop, coreTop + 1);
        double topInterior = avgLum(piece, topX, topX, coreTop + 3, coreTop + 6);

        int bottomX = 22; // horizontal center, clear of the corner radii
        double bottomEdge = avgLum(piece, bottomX, bottomX, coreBottom - 2, coreBottom - 1);
        double bottomInterior = avgLum(piece, bottomX, bottomX, coreBottom - 6, coreBottom - 3);

        assertTrue(leftInterior - leftEdge > 25,
                "left straight edge should be clearly darker than interior: edge=" + leftEdge
                        + " interior=" + leftInterior);
        assertTrue(rightInterior - rightEdge > 25,
                "right straight edge should be clearly darker than interior: edge=" + rightEdge
                        + " interior=" + rightInterior);
        assertTrue(topInterior - topEdge > 25,
                "top straight edge should be clearly darker than interior: edge=" + topEdge
                        + " interior=" + topInterior);
        assertTrue(bottomInterior - bottomEdge > 25,
                "bottom straight edge should be clearly darker than interior: edge=" + bottomEdge
                        + " interior=" + bottomInterior);
    }

    private static double avgLum(int[][] img, int x0, int x1, int y0, int y1) {
        double sum = 0;
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                sum += lum(img[y][x]);
                n++;
            }
        }
        return sum / n;
    }

    private static int countOpaqueInRow(int[][] img, int y) {
        int count = 0;
        for (int x = 0; x < img[y].length; x++) {
            if (alpha(img[y][x]) >= 128) {
                count++;
            }
        }
        return count;
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

    private static int lum(int c) {
        return (red(c) + green(c) + blue(c)) / 3;
    }

    private static int red(int c) {
        return (c >> 16) & 0xff;
    }

    private static int green(int c) {
        return (c >> 8) & 0xff;
    }

    private static int blue(int c) {
        return c & 0xff;
    }

    private static void assertPng(byte[] png) {
        assertTrue(png.length > 200, "PNG should not be empty");
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }
}
