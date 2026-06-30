package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CreditReconciliationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface CreditReconciliationLogRepository extends JpaRepository<CreditReconciliationLog, Long> {

    List<CreditReconciliationLog> findByCreatedAtAfterOrderByDriftDesc(Instant after);

    long countByCreatedAtAfter(Instant after);
}
