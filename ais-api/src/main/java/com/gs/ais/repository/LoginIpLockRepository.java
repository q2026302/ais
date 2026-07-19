package com.gs.ais.repository;

import com.gs.ais.model.entity.LoginIpLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoginIpLockRepository extends JpaRepository<LoginIpLock, Long> {
    Optional<LoginIpLock> findByIpAddress(String ipAddress);
}
