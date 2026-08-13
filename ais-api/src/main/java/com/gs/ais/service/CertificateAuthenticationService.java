package com.gs.ais.service;

import com.gs.ais.model.entity.AppUser;
import com.gs.ais.model.entity.UserKey;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.UserKeyRepository;
import com.gs.ais.security.AuthException;
import com.gs.ais.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CertificateAuthenticationService {

    private static final int MAX_ACTIVE_KEYS_PER_USER = 5;
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

    private final UserKeyRepository userKeyRepository;
    private final AppUserRepository appUserRepository;
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    public CertificateAuthenticationService(UserKeyRepository userKeyRepository,
                                            AppUserRepository appUserRepository) {
        this.userKeyRepository = userKeyRepository;
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public GeneratedKey generate(AuthPrincipal principal, String requestedName) {
        AppUser user = findEnabledUser(principal);
        if (userKeyRepository.countByUserIdAndRevokedAtIsNull(user.getId()) >= MAX_ACTIVE_KEYS_PER_USER) {
            throw new AuthException(409, "每个用户最多只能保留 5 个有效证书");
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = generator.generateKeyPair();
            byte[] encodedPublicKey = keyPair.getPublic().getEncoded();
            UserKey userKey = new UserKey();
            userKey.setUserId(user.getId());
            userKey.setName(normalizeName(requestedName));
            userKey.setPublicKey(BASE64_ENCODER.encodeToString(encodedPublicKey));
            userKey.setFingerprint(fingerprint(encodedPublicKey));
            userKey.setCreatedAt(LocalDateTime.now());
            userKey = userKeyRepository.save(userKey);
            return new GeneratedKey(userKey, pem("PUBLIC KEY", encodedPublicKey), pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成 Ed25519 证书", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<KeySummary> list(AuthPrincipal principal) {
        AppUser user = findEnabledUser(principal);
        return userKeyRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(KeySummary::from)
                .toList();
    }

    @Transactional
    public void revoke(AuthPrincipal principal, Long keyId) {
        AppUser user = findEnabledUser(principal);
        UserKey key = userKeyRepository.findByIdAndUserId(keyId, user.getId())
                .orElseThrow(() -> new AuthException(404, "证书不存在"));
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(LocalDateTime.now());
            userKeyRepository.save(key);
        }
    }

    public ChallengeResponse createChallenge(String fingerprint) {
        UserKey key = findActiveKey(fingerprint);
        String nonce = UUID.randomUUID() + "." + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(CHALLENGE_TTL);
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        challenges.put(nonce, new Challenge(key.getFingerprint(), expiresAt));
        return new ChallengeResponse(nonce, expiresAt);
    }

    @Transactional(readOnly = true)
    public AuthPrincipal authenticate(String fingerprint, String nonce, String encodedSignature) {
        String normalizedFingerprint = requireFingerprint(fingerprint);
        Challenge challenge = challenges.remove(nonce);
        if (challenge == null || challenge.expiresAt().isBefore(Instant.now())
                || !MessageDigest.isEqual(challenge.fingerprint().getBytes(StandardCharsets.US_ASCII),
                normalizedFingerprint.getBytes(StandardCharsets.US_ASCII))) {
            throw new AuthException(401, "证书挑战无效或已过期");
        }

        UserKey key = findActiveKey(normalizedFingerprint);
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(BASE64_DECODER.decode(key.getPublicKey())));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(nonce.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(BASE64_DECODER.decode(encodedSignature))) {
                throw new AuthException(401, "证书签名验证失败");
            }
            AppUser user = appUserRepository.findById(key.getUserId())
                    .orElseThrow(() -> new AuthException(401, "用户不存在或证书已失效"));
            if (!user.isEnabled()) {
                throw new AuthException(403, "账号已禁用");
            }
            return new AuthPrincipal(user.getRole(), user.getUsername());
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AuthException(401, "证书签名验证失败");
        }
    }

    private AppUser findEnabledUser(AuthPrincipal principal) {
        return appUserRepository.findByUsernameIgnoreCase(principal.subject())
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new AuthException(401, "用户不存在或访问凭证已失效"));
    }

    private UserKey findActiveKey(String fingerprint) {
        UserKey key = userKeyRepository.findByFingerprint(requireFingerprint(fingerprint))
                .orElseThrow(() -> new AuthException(401, "证书不存在或已吊销"));
        if (key.getRevokedAt() != null) {
            throw new AuthException(401, "证书不存在或已吊销");
        }
        return key;
    }

    private static String requireFingerprint(String fingerprint) {
        if (!StringUtils.hasText(fingerprint) || !fingerprint.matches("[0-9a-fA-F]{64}")) {
            throw new AuthException(401, "证书指纹无效");
        }
        return fingerprint.toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String normalized = name.trim();
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100);
    }

    private static String fingerprint(byte[] encodedPublicKey) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encodedPublicKey));
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + BASE64_ENCODER.encodeToString(encoded).replaceAll("(.{64})", "$1\n")
                + "\n-----END " + type + "-----\n";
    }

    public record GeneratedKey(Long keyId, String fingerprint, String publicKey, String privateKey,
                               String name, LocalDateTime createdAt) {
        private GeneratedKey(UserKey key, String publicKey, String privateKey) {
            this(key.getId(), key.getFingerprint(), publicKey, privateKey, key.getName(), key.getCreatedAt());
        }
    }

    public record KeySummary(Long keyId, String fingerprint, String name, LocalDateTime createdAt,
                             LocalDateTime revokedAt) {
        private static KeySummary from(UserKey key) {
            return new KeySummary(key.getId(), key.getFingerprint(), key.getName(), key.getCreatedAt(), key.getRevokedAt());
        }
    }

    public record ChallengeResponse(String nonce, Instant expiresAt) { }

    private record Challenge(String fingerprint, Instant expiresAt) { }
}
