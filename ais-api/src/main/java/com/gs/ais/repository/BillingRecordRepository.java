package com.gs.ais.repository;

import com.gs.ais.model.entity.BillingRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingRecordRepository extends JpaRepository<BillingRecord, Long> {
    Page<BillingRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Page<BillingRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<BillingRecord> findByUserIdOrderByCreatedAtDesc(Long userId);
}