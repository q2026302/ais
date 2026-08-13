package com.gs.ais.repository;

import com.gs.ais.model.entity.UserKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserKeyRepository extends JpaRepository<UserKey, Long> {
    List<UserKey> findByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndRevokedAtIsNull(Long userId);
    Optional<UserKey> findByFingerprint(String fingerprint);
    Optional<UserKey> findByIdAndUserId(Long id, Long userId);
}
