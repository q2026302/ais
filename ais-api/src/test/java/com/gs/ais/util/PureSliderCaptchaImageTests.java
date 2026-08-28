package com.gs.ais.util;

import org.junit.jupiter.api.Test;

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
        // Center of the core rounded square.
        double inside = PureSliderCaptchaImage.shapeCoverage(PureSliderCaptchaImage.PIECE_WIDTH / 2.0,
                PureSliderCaptchaImage.TAB_RADIUS + PureSliderCaptchaImage.PIECE_HEIGHT / 2.0);
        assertTrue(inside > 0.5, "core center should be covered, got " + inside);

        // Tab center should also be covered.
        double tab = PureSliderCaptchaImage.shapeCoverage(PureSliderCaptchaImage.PIECE_WIDTH / 2.0,
                PureSliderCaptchaImage.TAB_RADIUS / 2.0);
        assertTrue(tab > 0.5, "tab center should be covered, got " + tab);

        // Far outside the shape.
        double outside = PureSliderCaptchaImage.shapeCoverage(-50, -50);
        assertEquals(0.0, outside, 1e-9);
    }

    private static void assertPng(byte[] png) {
        assertTrue(png.length > 200, "PNG should not be empty");
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }
}
