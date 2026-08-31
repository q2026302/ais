package com.gs.ais.security;

import com.gs.ais.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceUrlSignerTests {

    private ResourceUrlSigner signer;

    @BeforeEach
    void setUp() {
        SecurityProperties properties = new SecurityProperties();
        properties.setTokenSecret("unit-test-secret");
        signer = new ResourceUrlSigner(properties, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void signsAndVerifiesRoundTrip() {
        String signed = signer.signFor("/api/images/generated/a.png", "alice");
        assertNotEquals("/api/images/generated/a.png", signed);
        assertTrue(signed.contains("sig="));
        assertEquals("alice", signer.verify("/api/images/generated/a.png", extractSig(signed)));
    }

    @Test
    void rejectsSignatureBoundToADifferentPath() {
        String signed = signer.signFor("/api/images/a.png", "alice");
        assertNull(signer.verify("/api/images/b.png", extractSig(signed)));
    }

    @Test
    void rejectsTamperedSignature() {
        String signed = signer.signFor("/api/images/a.png", "alice");
        String sig = extractSig(signed);
        String tampered = sig.substring(0, sig.length() - 1) + (sig.endsWith("a") ? "b" : "a");
        assertNull(signer.verify("/api/images/a.png", tampered));
    }

    @Test
    void leavesPublicTestImagesUnsigned() {
        String url = "/api/images/test_1_20260831_00000000.png";
        assertEquals(url, signer.signFor(url, "alice"));
    }

    @Test
    void leavesUrlsWithoutSubjectUnsigned() {
        String url = "/api/images/generated/a.png";
        assertEquals(url, signer.signFor(url, null));
    }

    @Test
    void signsAttachmentUrlsToo() {
        String signed = signer.signFor("/api/attachments/doc.pdf", "alice");
        assertTrue(signed.contains("sig="));
        assertEquals("alice", signer.verify("/api/attachments/doc.pdf", extractSig(signed)));
    }

    private static String extractSig(String signed) {
        int idx = signed.indexOf("sig=");
        return signed.substring(idx + "sig=".length());
    }
}
