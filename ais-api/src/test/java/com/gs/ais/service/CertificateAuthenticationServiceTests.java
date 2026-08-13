package com.gs.ais.service;

import com.gs.ais.model.entity.AppUser;
import com.gs.ais.model.entity.UserKey;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.UserKeyRepository;
import com.gs.ais.security.AuthException;
import com.gs.ais.security.AuthPrincipal;
import com.gs.ais.security.AuthRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CertificateAuthenticationServiceTests {

    private CertificateAuthenticationService service;
    private UserKeyRepository keyRepository;
    private AppUserRepository userRepository;
    private final Map<Long, UserKey> keys = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private AppUser user;

    @BeforeEach
    void setUp() {
        keyRepository = mock(UserKeyRepository.class);
        userRepository = mock(AppUserRepository.class);
        user = new AppUser();
        user.setId(1L);
        user.setUsername("tester");
        user.setRole(AuthRole.USER);
        user.setEnabled(true);
        when(userRepository.findByUsernameIgnoreCase("tester")).thenReturn(Optional.of(user));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(keyRepository.countByUserIdAndRevokedAtIsNull(1L)).thenAnswer(invocation ->
                keys.values().stream().filter(key -> key.getRevokedAt() == null).count());
        when(keyRepository.save(any(UserKey.class))).thenAnswer(invocation -> {
            UserKey key = invocation.getArgument(0);
            if (key.getId() == null) {
                key.setId(ids.incrementAndGet());
            }
            keys.put(key.getId(), key);
            return key;
        });
        when(keyRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenAnswer(invocation ->
                keys.values().stream().sorted(java.util.Comparator.comparing(UserKey::getCreatedAt).reversed()).toList());
        when(keyRepository.findByFingerprint(any())).thenAnswer(invocation -> keys.values().stream()
                .filter(key -> key.getFingerprint().equals(invocation.getArgument(0))).findFirst());
        when(keyRepository.findByIdAndUserId(any(), any())).thenAnswer(invocation -> keys.values().stream()
                .filter(key -> key.getId().equals(invocation.getArgument(0)) && key.getUserId().equals(invocation.getArgument(1)))
                .findFirst());
        service = new CertificateAuthenticationService(keyRepository, userRepository);
    }

    @Test
    void generatesVerifiesAndConsumesChallenge() throws Exception {
        CertificateAuthenticationService.GeneratedKey key = service.generate(principal(), "automation");
        assertTrue(key.publicKey().contains("BEGIN PUBLIC KEY"));
        assertTrue(key.privateKey().contains("BEGIN PRIVATE KEY"));
        assertEquals(64, key.fingerprint().length());
        verify(keyRepository).save(any(UserKey.class));

        CertificateAuthenticationService.ChallengeResponse challenge = service.createChallenge(key.fingerprint());
        AuthPrincipal authenticated = service.authenticate(key.fingerprint(), challenge.nonce(), sign(key.privateKey(), challenge.nonce()));
        assertEquals("tester", authenticated.subject());
        verify(keyRepository, times(2)).findByFingerprint(key.fingerprint());
        assertThrows(AuthException.class, () -> service.authenticate(key.fingerprint(), challenge.nonce(), sign(key.privateKey(), challenge.nonce())));
    }

    @Test
    void rejectsWrongSignatureAndRevokedKey() throws Exception {
        CertificateAuthenticationService.GeneratedKey key = service.generate(principal(), null);
        CertificateAuthenticationService.ChallengeResponse wrongSignatureChallenge = service.createChallenge(key.fingerprint());
        assertThrows(AuthException.class, () -> service.authenticate(key.fingerprint(), wrongSignatureChallenge.nonce(), Base64.getEncoder().encodeToString("wrong".getBytes(StandardCharsets.UTF_8))));

        service.revoke(principal(), key.keyId());
        assertThrows(AuthException.class, () -> service.createChallenge(key.fingerprint()));
    }

    @Test
    void limitsActiveKeysToFive() {
        for (int index = 0; index < 5; index++) {
            service.generate(principal(), "key-" + index);
        }
        AuthException exception = assertThrows(AuthException.class, () -> service.generate(principal(), "sixth"));
        assertEquals(409, exception.getStatus());
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(AuthRole.USER, "tester");
    }

    private static String sign(String privateKeyPem, String message) throws Exception {
        String encoded = privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
