package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeLinkAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link CeLinkAudit}. **Insert-only by design** - the DB enforces
 * via BEFORE UPDATE/DELETE/TRUNCATE triggers, so any call to {@code delete()} or
 * any modification of a fetched entity fails at flush.
 */
@Repository
public interface CeLinkAuditRepository extends JpaRepository<CeLinkAudit, Long> {

    /**
     * Latest N audit rows for an install - used by /settings/cloud-account UI
     * ({@code recent activity} list, doc §5/§6) and incident-response forensics.
     */
    List<CeLinkAudit> findTop10ByInstallIdOrderByCreatedAtDesc(UUID installId);
}
