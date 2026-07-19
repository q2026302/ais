package com.gs.ais.repository;

import com.gs.ais.model.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    long countByRoleAndEnabledTrue(com.gs.ais.security.AuthRole role);
    Optional<AppUser> findFirstByRoleOrderByIdAsc(com.gs.ais.security.AuthRole role);
}
