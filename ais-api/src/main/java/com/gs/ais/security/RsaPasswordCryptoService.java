package com.gs.ais.security;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary RSA key material for transporting client-produced password digests over plain HTTP.
 * One key challenge is issued per login attempt and consumed on decrypt.
 */
@Service
public class RsaPasswordCryptoService {

    private static final long TTL_SECONDS = 5 * 60;
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT);

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, KeyEntry> keys = new ConcurrentHashMap<>();

    public PublicKeyChallenge createChallenge() {
        purgeExpired();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, secureRandom);
            KeyPair keyPair = generator.generateKeyPair();
            String keyId = UUID.randomUUID().toString().replace("-", "");
            keys.put(keyId, new KeyEntry(keyPair.getPrivate(), Instant.now().plusSeconds(TTL_SECONDS)));
            String publicKeySpkiBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            return new PublicKeyChallenge(keyId, publicKeySpkiBase64, "RSA-OAEP-256", (int) TTL_SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create RSA key challenge", e);
        }
    }

    public String decryptPasswordDigest(String keyId, String encryptedPasswordBase64) {
        byte[] plain = decryptPasswordBytes(keyId, encryptedPasswordBase64);
        try {
            String passwordDigest = new String(plain, StandardCharsets.UTF_8);
            if (!PasswordDigests.isMd5Hex(passwordDigest)) {
                throw new AuthException(400, "密码摘要格式无效，请刷新后重试");
            }
            return passwordDigest.toLowerCase(java.util.Locale.ROOT);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private byte[] decryptPasswordBytes(String keyId, String encryptedPasswordBase64) {
        purgeExpired();
        if (!StringUtils.hasText(keyId) || !StringUtils.hasText(encryptedPasswordBase64)) {
            throw new AuthException(400, "缺少加密密码或密钥标识");
        }
        KeyEntry entry = keys.remove(keyId.trim());
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            throw new AuthException(400, "登录密钥已失效，请刷新后重试");
        }
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(encryptedPasswordBase64.trim());
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.DECRYPT_MODE, entry.privateKey(), OAEP_SHA256);
            return cipher.doFinal(cipherBytes);
        } catch (AuthException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new AuthException(400, "加密密码格式无效");
        } catch (Exception ex) {
            throw new AuthException(400, "密码解密失败，请刷新密钥后重试");
        }
    }

    /** For tests / diagnostics: encode a public key the same way the API returns it. */
    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public static PublicKey decodePublicKey(String spkiBase64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(spkiBase64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid public key", e);
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, KeyEntry>> it = keys.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, KeyEntry> entry = it.next();
            if (entry.getValue().expiresAt().isBefore(now)) {
                it.remove();
            }
        }
    }

    private record KeyEntry(PrivateKey privateKey, Instant expiresAt) {
    }

    public record PublicKeyChallenge(
            String keyId,
            String publicKey,
            String algorithm,
            int expiresInSeconds
    ) {
    }
}
