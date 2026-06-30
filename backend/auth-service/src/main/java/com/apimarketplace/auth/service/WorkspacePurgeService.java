package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.repository.OrganizationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Hard-purges a soft-deleted workspace after its grace window. Separated from
 * {@link WorkspacePurgeScheduler} so Spring AOP proxies the {@code @Transactional}
 * correctly (no self-invocation bypass), mirroring the account-purge pair.
 *
 * <p><b>What it does:</b> deletes ALL operational org-scoped data (via the shared
 * {@link WorkspaceDataPurger}) + the workspace memberships, then <b>keeps the
 * {@code organization} row as a tombstone</b> ({@code purged_at} set, {@code deleted_at}
 * preserved). It NEVER touches the financial ledger / audit ({@code credit_ledger},
 * {@code usage_cycle}, {@code credit_reconciliation_log}, {@code organization_audit_event}) -
 * keeping the org row valid for owner-pays ledger references (ADR-009).
 *
 * <p><b>Guards:</b> refuses to purge a personal org, a non-deleted org, or an already-purged
 * org. (An active-runs guard is intentionally omitted: a run cannot stay active for the full
 * 30-day grace window, and the operational purge deletes {@code workflow_runs} anyway.)
 */
@Service
public class WorkspacePurgeService {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePurgeService.class);

    private final OrganizationRepository organizationRepository;
    private final WorkspaceDataPurger workspaceDataPurger;
    private final OrganizationAuditService auditService;

    @PersistenceContext
    private EntityManager em;

    public WorkspacePurgeService(OrganizationRepository organizationRepository,
                                 WorkspaceDataPurger workspaceDataPurger,
                                 OrganizationAuditService auditService) {
        this.organizationRepository = organizationRepository;
        this.workspaceDataPurger = workspaceDataPurger;
        this.auditService = auditService;
    }

    /**
     * Hard-purge one workspace. Returns true when the purge ran, false when it was skipped
     * (org missing / personal / not deleted / already purged). Idempotent.
     */
    @Transactional
    public boolean purgeWorkspace(UUID orgId) {
        Organization org = organizationRepository.findById(orgId).orElse(null);
        if (org == null) {
            log.warn("Workspace purge: org {} not found, skipping", orgId);
            return false;
        }
        if (org.isPersonal()) {
            log.warn("Workspace purge: refusing to purge personal workspace {}", orgId);
            return false;
        }
        if (!org.isDeleted()) {
            log.warn("Workspace purge: refusing to purge non-deleted workspace {}", orgId);
            return false;
        }
        if (org.isPurged()) {
            log.debug("Workspace purge: workspace {} already purged, skipping", orgId);
            return false;
        }

        String orgIdStr = orgId.toString();

        // 1. Operational data across every schema (NOT the financial ledger / audit).
        workspaceDataPurger.purgeOperationalData(orgIdStr);

        // 2. Memberships - the workspace is dead. Native delete to avoid touching the kept org
        //    row's ORM cascade. organization_member.organization_id is UUID; cast to text so the
        //    String param doesn't trip 'uuid = character varying'.
        em.createNativeQuery("DELETE FROM auth.organization_member WHERE organization_id::text = ?1")
                .setParameter(1, orgIdStr)
                .executeUpdate();

        // 3. Tombstone the org row: keep it (so credit_ledger references stay valid - ADR-009),
        //    stamp purged_at. deleted_at is preserved.
        org.setPurgedAt(LocalDateTime.now());
        organizationRepository.save(org);

        // 4. Audit - organization_audit_event is a RETAINED table, so this survives the purge.
        auditService.record(orgId, org.getDeletedBy(), OrganizationAuditEvent.Type.PURGED,
                Map.of("purgedAt", org.getPurgedAt().toString()));

        log.info("Workspace {} hard-purged (operational data deleted, financial ledger retained)", orgId);
        return true;
    }
}
