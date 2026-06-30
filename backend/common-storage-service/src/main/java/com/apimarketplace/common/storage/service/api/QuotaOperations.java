package com.apimarketplace.common.storage.service.api;

import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.TenantStorageQuota;

/**
 * Interface definissant les operations de gestion des quotas (Interface Segregation Principle).
 *
 * <p>The tenant-* methods drive the personal (tenant_storage_quota) scope; the
 * organization-* methods drive the org (organization_storage_quota) scope. Callers
 * route on the presence of {@code X-Organization-ID} on the request.</p>
 */
public interface QuotaOperations {

    /**
     * Verifie si un tenant peut stocker des donnees supplementaires.
     */
    QuotaStatus checkQuota(String tenantId, long additionalBytes);

    /**
     * Met a jour l'usage d'un tenant.
     */
    void updateUsage(String tenantId);

    /**
     * Obtient le quota d'un tenant.
     */
    TenantStorageQuota getQuota(String tenantId);

    /**
     * Met a jour les limites d'un tenant.
     */
    void updateLimits(String tenantId, long maxBytes, double softLimitRatio);

    /**
     * Obtient l'usage actuel d'un tenant.
     */
    long getCurrentUsage(String tenantId);

    /**
     * Obtient le pourcentage d'usage d'un tenant.
     */
    double getUsagePercentage(String tenantId);

    /**
     * Verifie si la limite souple est atteinte.
     */
    boolean isSoftLimitReached(String tenantId);

    /**
     * Verifie si la limite dure est atteinte.
     */
    boolean isHardLimitReached(String tenantId);

    // ============================================================
    // Organization-scoped operations (strict isolation, PR18)
    // ============================================================

    /**
     * Org-scope quota check. Used when X-Organization-ID is present on the request.
     */
    QuotaStatus checkOrganizationQuota(String organizationId, long additionalBytes);

    /**
     * Org-scope quota row. Created on demand with FREE-plan defaults; the org
     * owner's plan allowance is applied via {@link #updateOrganizationLimits}.
     */
    OrganizationStorageQuota getOrganizationQuota(String organizationId);

    /**
     * Recompute org usage from storage table (rows where organization_id = orgId).
     */
    void updateOrganizationUsage(String organizationId);

    /**
     * Update org max_bytes from owner plan; sets soft_limit at the configured ratio.
     */
    void updateOrganizationLimits(String organizationId, long maxBytes, double softLimitRatio);

    /**
     * Current org usage (direct SUM on storage table, bypasses any rollup).
     */
    long getCurrentOrganizationUsage(String organizationId);

    /**
     * Convenience routing method - picks org-scope when {@code organizationId}
     * is non-null/non-blank, falls back to tenant-scope otherwise. This is what
     * controllers should call so the strict-isolation policy applies uniformly.
     */
    default QuotaStatus checkQuotaForScope(String tenantId, String organizationId,
                                            long additionalBytes) {
        return (organizationId != null && !organizationId.isBlank())
                ? checkOrganizationQuota(organizationId, additionalBytes)
                : checkQuota(tenantId, additionalBytes);
    }
}
