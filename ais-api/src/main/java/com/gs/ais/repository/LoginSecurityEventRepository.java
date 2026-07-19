package com.gs.ais.repository;

import com.gs.ais.model.entity.LoginSecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginSecurityEventRepository extends JpaRepository<LoginSecurityEvent, Long> {
    List<LoginSecurityEvent> findTop200ByOrderByOccurredAtDesc();
}
