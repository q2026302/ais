package com.gs.ais.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.config.SecurityProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signs and verifies short-lived (TTL 1h) access credentials for image/attachment
 * URLs. Browsers cannot attach an {@code Authorization} header to {@code <img>}
 * requests, so a signed query parameter ({@code ?sig=...}) is used as a
 * header-less authorization channel.
 *
 * <p>The signature is never baked into persisted URL fields ({@code message.imageUrl},
 * {@code attachment.fileUrl}); it is generated at API-response serialization time
 * and bound to (1) the requesting user and (2) the exact resource path, so a token
 * cannot be replayed against a different resource or user.
 *
 * <p>The signing key is derived from {@code APP_TOKEN_SECRET} (via
 * {@link SecurityProperties#resolvedTokenSecret()}) using HMAC-SHA256 with the
 * domain-separation context {@code "ais:resource-url-signature:v1"} as the message,
 * so the resource-URL key is independent of the login-token key and no new required
 * configuration is introduced. Rotating {@code APP_TOKEN_SECRET} invalidates both.
 */
@Component
public class ResourceUrlSigner {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    static final long TTL_SECONDS = 3600L;
    static final String SIGNATURE_CONTEXT = "ais:resource-url-signature:v1";

    /**
     * Issue-time rounding granularity. {@code exp} is computed from a minute-aligned
     * bucket instead of "now + TTL", so the same user + path resolves to the same
     * signature (and therefore the same cache key) for every serialization within a
     * bucket. This keeps the URL stable over a scrolling session while still
     * expiring within {@link #TTL_SECONDS} (between TTL-bucket and TTL seconds of
     * remaining validity).
     */
    static final long SIGNATURE_BUCKET_SECONDS = 60L;

    private final ObjectMapper objectMapper;
    private final SecretKeySpec signingKey;

    /** Set at construction so the Jackson serializer can sign without a Spring lookup. */
    private static volatile ResourceUrlSigner INSTANCE;

    public ResourceUrlSigner(SecurityProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.signingKey = deriveKey(properties.resolvedTokenSecret());
        INSTANCE = this;
    }

    /** Signs {@code url} for the caller currently bound to {@link AuthContext}. */
    public String sign(String url) {
        AuthPrincipal principal = AuthContext.get();
        String subject = principal != null ? principal.subject() : null;
        return signFor(url, subject);
    }

    /**
     * Signs {@code url} for the given subject (username). Public test images and
     * non-resource URLs are returned unchanged.
     */
    public String signFor(String url, String subject) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String path = resourcePathOf(url);
        if (path == null || isPublicTestImage(path)) {
            return url;
        }
        if (!StringUtils.hasText(subject)) {
            return url;
        }

        long exp = expiryForNow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", subject);
        payload.put("exp", exp);
        payload.put("path", path);
        try {
            String body = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            String sig = URL_ENCODER.encodeToString(hmac(body.getBytes(StandardCharsets.UTF_8)));
            return url + (url.contains("?") ? "&" : "?") + "sig=" + body + "." + sig;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to sign resource URL", e);
        }
    }

    /**
     * Verifies a {@code sig} query value against the given context-relative request
     * {@code path}. Returns the bound subject (username) on success, or {@code null}
     * when the token is absent, malformed, expired, or bound to a different path.
     */
    public String verify(String path, String sig) {
        if (path == null || sig == null || sig.isBlank()) {
            return null;
        }
        String[] parts = sig.split("\\.", -1);
        if (parts.length != 2) {
            return null;
        }
        String body = parts[0];
        String provided = parts[1];
        String expected = URL_ENCODER.encodeToString(hmac(body.getBytes(StandardCharsets.UTF_8)));
        if (!constantTimeEquals(provided, expected)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(URL_DECODER.decode(body), Map.class);
            Object expValue = payload.get("exp");
            long exp = expValue instanceof Number number ? number.longValue() : 0L;
            if (exp == 0L || Instant.now().getEpochSecond() > exp) {
                return null;
            }
            Object pathValue = payload.get("path");
            if (pathValue == null || !path.equals(String.valueOf(pathValue))) {
                return null;
            }
            return String.valueOf(payload.getOrDefault("sub", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The expiry timestamp (unix seconds) used for a signature minted right now:
     * the next bucket boundary plus the TTL, so signatures issued inside one bucket
     * share an expiry and thus a stable URL.
     */
    static long expiryForNow() {
        long now = Instant.now().getEpochSecond();
        return (now / SIGNATURE_BUCKET_SECONDS) * SIGNATURE_BUCKET_SECONDS + TTL_SECONDS;
    }

    /**
     * Remaining validity (seconds) of a signed URL's {@code sig} query value,
     * floored at 0. Returns 0 when the signature is absent or unreadable, which
     * callers treat as "do not cache" (public test images, legacy unsigned URLs).
     *
     * <p><strong>This is a non-authoritative cache hint only.</strong> It reads the
     * (non-secret) {@code exp} field without verifying the HMAC, so it must never be
     * used to mint or accept a signature, and it must never drive a cache policy on
     * its own — a caller with an {@code Authorization} header can attach an arbitrary
     * {@code sig} whose decoded {@code exp} is forged. Authorization and cache TTL
     * decisions must use {@link #verify(String, String)} /
     * {@link #remainingValiditySecondsVerified(String, String)} instead.
     */
    public long remainingValiditySeconds(String sig) {
        if (sig == null || sig.isBlank()) {
            return 0L;
        }
        String[] parts = sig.split("\\.", -1);
        if (parts.length != 2) {
            return 0L;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(URL_DECODER.decode(parts[0]), Map.class);
            Object expValue = payload.get("exp");
            long exp = expValue instanceof Number number ? number.longValue() : 0L;
            if (exp <= 0L) {
                return 0L;
            }
            long remaining = exp - Instant.now().getEpochSecond();
            return remaining > 0L ? remaining : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Remaining validity (seconds) of a signed URL's {@code sig} query value that
     * has <em>first been verified</em> against the exact context-relative request
     * {@code path}: HMAC must match, the token must not be expired, and the payload
     * {@code path} must equal {@code path}. Only then is the payload {@code exp}
     * trusted to compute the remaining lifetime.
     *
     * <p>Returns 0 for an absent, malformed, forged, expired, or path-mismatched
     * signature. Callers use 0 to mean "do not cache" ({@code no-store}). This is
     * the only cache-TTL entry point that may feed a {@code max-age}; the unchecked
     * {@link #remainingValiditySeconds(String)} must not be used for that purpose.
     */
    public long remainingValiditySecondsVerified(String path, String sig) {
        if (verify(path, sig) == null) {
            return 0L;
        }
        // The signature passed HMAC + expiry + path verification, so exp is genuine.
        return remainingValiditySeconds(sig);
    }

    /** Static entry point used by {@link com.gs.ais.dto.response.SignedUrlSerializer}. */
    public static String signCurrent(String url) {
        ResourceUrlSigner signer = INSTANCE;
        return signer == null ? url : signer.sign(url);
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign resource URL", e);
        }
    }

    private static SecretKeySpec deriveKey(String tokenSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(SIGNATURE_CONTEXT.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive resource signing key", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the context-relative path portion of a resource URL, or null if not signable. */
    private static String resourcePathOf(String url) {
        int query = url.indexOf('?');
        String path = query >= 0 ? url.substring(0, query) : url;
        if (path.startsWith("/api/images/") || path.startsWith("/api/attachments/")) {
            return path;
        }
        return null;
    }

    /** Provider test images ({@code test_*.png}) are public and never signed. */
    public static boolean isPublicTestImage(String path) {
        if (path == null || !path.startsWith("/api/images/")) {
            return false;
        }
        String relative = path.substring("/api/images/".length());
        if (relative.isEmpty() || relative.contains("/")) {
            return false;
        }
        return relative.startsWith("test_") && relative.endsWith(".png");
    }
}
