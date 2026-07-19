package com.gs.ais.repository;

import com.gs.ais.model.entity.SystemModelSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemModelSettingsRepository extends JpaRepository<SystemModelSettings, Long> {
}
