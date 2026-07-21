package com.gs.ais.repository;

import com.gs.ais.model.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long>, JpaSpecificationExecutor<OperationLog> {
}
