package com.gs.ais.config;

import com.gs.ais.model.entity.Session;
import com.gs.ais.repository.AppUserRepository;
import com.gs.ais.repository.SessionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class SessionMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionMigrationRunner.class);

    private final SessionRepository sessionRepository;
    private final AppUserRepository appUserRepository;

    public SessionMigrationRunner(SessionRepository sessionRepository,
                                  AppUserRepository appUserRepository) {
        this.sessionRepository = sessionRepository;
        this.appUserRepository = appUserRepository;
    }

    @PostConstruct
    @Transactional
    public void migrateSessionsWithoutUserId() {
        List<Session> sessionsWithoutUser = sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .filter(s -> s.getUserId() == null)
                .toList();

        if (sessionsWithoutUser.isEmpty()) {
            return;
        }

        // Find the first admin user to assign as owner
        Long adminUserId = appUserRepository.findFirstByRoleOrderByIdAsc(com.gs.ais.security.AuthRole.ADMIN)
                .map(admin -> admin.getId())
                .orElse(1L);

        log.info("Migrating {} sessions without userId to admin user {}", sessionsWithoutUser.size(), adminUserId);
        for (Session session : sessionsWithoutUser) {
            session.setUserId(adminUserId);
            sessionRepository.save(session);
        }
        log.info("Session migration complete");
    }
}