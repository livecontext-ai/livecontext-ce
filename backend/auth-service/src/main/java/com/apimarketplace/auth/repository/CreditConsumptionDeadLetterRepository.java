package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CreditConsumptionDeadLetterEntity;
import com.apimarketplace.auth.domain.CreditConsumptionDeadLetterEntity.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for credit consumption dead-letter entries.
 */
@Repository
public interface CreditConsumptionDeadLetterRepository extends JpaRepository<CreditConsumptionDeadLetterEntity, UUID> {

    List<CreditConsumptionDeadLetterEntity> findByStatusInOrderByCreatedAtAsc(List<Status> statuses);

    List<CreditConsumptionDeadLetterEntity> findByTenantIdAndStatusIn(String tenantId, List<Status> statuses);

    long countByStatusIn(List<Status> statuses);

    long countByTenantIdAndStatusIn(String tenantId, List<Status> statuses);

    // ==========================================================
    // Org-scoped strict finders - entity is OrgScopedEntity and
    // every row carries a non-null organization_id post-V263. Use
    // these from any new code path that already has the active
    // workspace's orgId (e.g. UI dashboards, org-aware retry jobs).
    // Tenant-scoped variants above are retained for the per-user
    // credit-reconciliation path (CreditReconciliationService),
    // which iterates tenants regardless of org membership.
    // ==========================================================

    List<CreditConsumptionDeadLetterEntity> findByOrganizationIdAndStatusIn(String organizationId, List<Status> statuses);

    long countByOrganizationIdAndStatusIn(String organizationId, List<Status> statuses);
}
