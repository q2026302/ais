package com.gs.ais.repository;

import com.gs.ais.model.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    List<Session> findAllByOrderByUpdatedAtDesc();

    java.util.Optional<Session> findFirstByExternalChannelAndExternalChatId(String externalChannel, String externalChatId);
}
