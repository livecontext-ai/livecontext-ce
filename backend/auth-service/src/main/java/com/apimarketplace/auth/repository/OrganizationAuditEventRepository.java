package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrganizationAuditEventRepository extends JpaRepository<OrganizationAuditEvent, Long> {

    /**
     * Paginated audit log for an org, newest first. Backed by
     * {@code idx_audit_event_org_created} (V195 partial index).
     */
    Page<OrganizationAuditEvent> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);

    /**
     * Same as above but filtered by event_type - backed by
     * {@code idx_audit_event_type_created}. Used by the GET endpoint's
     * optional {@code ?category=ORG_MEMBER_INVITED} filter.
     */
    Page<OrganizationAuditEvent> findByOrgIdAndEventTypeOrderByCreatedAtDesc(
            UUID orgId, String eventType, Pageable pageable);
}
