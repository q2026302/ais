package com.gs.ais.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RsaPasswordCryptoServiceTests {

    private final RsaPasswordCryptoService service = new RsaPasswordCryptoService();

    @Test
    void decryptsClientProducedPasswordDigestWithIssuedPublicKey() throws Exception {
        RsaPasswordCryptoService.PublicKeyChallenge challenge = service.createChallenge();
        String encrypted = encryptWithPublicKey(challenge.publicKey(),
                "5ebe2294ecd0e0f08eab7690d2a6ee69");
        String digest = service.decryptPasswordDigest(challenge.keyId(), encrypted);
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", digest);
    }

    @Test
    void rejectsEncryptedPlaintextPassword() throws Exception {
        RsaPasswordCryptoService.PublicKeyChallenge challenge = service.createChallenge();
        String encrypted = encryptWithPublicKey(challenge.publicKey(), "s3cret-pass");

        AuthException ex = assertThrows(AuthException.class,
                () -> service.decryptPasswordDigest(challenge.keyId(), encrypted));
        assertEquals(400, ex.getStatus());
    }

    @Test
    void keyIsSingleUse() throws Exception {
        RsaPasswordCryptoService.PublicKeyChallenge challenge = service.createChallenge();
        String encrypted = encryptWithPublicKey(challenge.publicKey(),
                "00000000000000000000000000000000");
        service.decryptPasswordDigest(challenge.keyId(), encrypted);
        AuthException ex = assertThrows(AuthException.class,
                () -> service.decryptPasswordDigest(challenge.keyId(), encrypted));
        assertEquals(400, ex.getStatus());
    }

    private static String encryptWithPublicKey(String publicKeySpkiBase64, String password) throws Exception {
        PublicKey publicKey = RsaPasswordCryptoService.decodePublicKey(publicKeySpkiBase64);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaep);
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }
}
