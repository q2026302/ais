package com.gs.ais.util;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureCaptchaImageTests {

    @Test
    void renderPngProducesValidPngBytes() {
        SecureRandom random = new SecureRandom();
        random.setSeed(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        byte[] png = PureCaptchaImage.renderPng("A2B3", random);
        assertTrue(png.length > 200, "PNG should not be empty");
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }

    @Test
    void alphabetGlyphsAreCovered() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int i = 0; i < alphabet.length(); i++) {
            assertTrue(PureCaptchaImage.hasGlyph(alphabet.charAt(i)),
                    "missing glyph for " + alphabet.charAt(i));
        }
    }

    @Test
    void differentSeedsProduceDifferentImages() {
        SecureRandom aRandom = new SecureRandom();
        aRandom.setSeed(new byte[]{9, 9, 9, 9, 1, 2, 3, 4});
        SecureRandom bRandom = new SecureRandom();
        bRandom.setSeed(new byte[]{1, 1, 1, 1, 5, 6, 7, 8});
        byte[] a = PureCaptchaImage.renderPng("TEST", aRandom);
        byte[] b = PureCaptchaImage.renderPng("TEST", bRandom);
        assertTrue(a.length != b.length || !Arrays.equals(a, b));
    }
}
