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
     * Same, plus the account that owns the workspace.
     *
     * <p>The allowance is shared across every workspace one account owns, so the quota gate has
     * to know which rows belong together. Only auth-service knows that (it owns
     * {@code auth.organization.owner_id}) and it is already the only writer of these rows, so it
     * stamps the owner here rather than storage inferring it from a schema it may not read.
     *
     * <p>A null {@code accountId} leaves any existing attribution untouched instead of clearing
     * it: a caller that does not know the owner must not be able to silently demote a workspace
     * back to per-workspace enforcement. The 3-argument overload delegates here with null, which
     * is why existing callers keep working unchanged.
     */
    default void updateOrganizationLimits(String organizationId, long maxBytes,
                                          double softLimitRatio, String accountId) {
        updateOrganizationLimits(organizationId, maxBytes, softLimitRatio);
    }

    /**
     * The shared allowance a workspace draws on: what the owning account stores in total, and
     * the ceiling that total is measured against.
     *
     * <p>Returned as one value because the two must come from one resolution. Letting a caller
     * pair this total with a ceiling it read elsewhere (the workspace's own row, say) is how a
     * page ends up declaring an account full while the write gate accepts the upload.
     *
     * <p>Null when the workspace is unattributed: enforcement is then per-workspace and there
     * is no pool. Default null so alternative implementations and slim test doubles need no
     * change, and the caller falls back to the per-workspace reading that shipped before.
     */
    default AccountPool getAccountPool(String organizationId) {
        return null;
    }

    /** Convenience for callers that only need the total. */
    default Long getAccountUsedBytes(String organizationId) {
        AccountPool pool = getAccountPool(organizationId);
        return pool == null ? null : pool.usedBytes();
    }

    /**
     * An account's storage pool.
     *
     * @param accountId the owning account
     * @param usedBytes everything its workspaces store, counted once each
     * @param maxBytes  the ceiling the gate enforces that total against
     */
    record AccountPool(String accountId, long usedBytes, long maxBytes) {}

    /**
     * Current org usage (direct SUM on storage table, bypasses any rollup).
     */
    long getCurrentOrganizationUsage(String organizationId);

    /**
     * Convenience routing method - picks org-scope when {@code organizationId}
     * is non-null/non-blank, falls back to tenant-scope otherwise. This is what
     * controllers should call so the strict-isolation policy applies uniformly.
     *
     * <p><b>Only the org branch draws on the account's shared pool.</b> Tenant scope keeps its
     * own separate allowance and is not summed into it. That is a boundary, not an oversight:
     * the gateway supplies the workspace header on the user-facing paths, so in practice a
     * write lands in a workspace and is pooled. Production carried zero bytes outside a
     * workspace when the pool shipped (2026-09-19). A path that deliberately writes without a
     * workspace is therefore measured on its own, and anyone adding one should decide whether
     * it belongs in the pool.
     */
    default QuotaStatus checkQuotaForScope(String tenantId, String organizationId,
                                            long additionalBytes) {
        return (organizationId != null && !organizationId.isBlank())
                ? checkOrganizationQuota(organizationId, additionalBytes)
                : checkQuota(tenantId, additionalBytes);
    }
}
