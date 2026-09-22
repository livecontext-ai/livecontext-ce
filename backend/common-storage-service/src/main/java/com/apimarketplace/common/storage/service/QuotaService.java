package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.TenantStorageQuota;
import com.apimarketplace.common.storage.repository.OrganizationStorageQuotaRepository;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.repository.TenantStorageQuotaRepository;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.web.AppEditionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Service pour la gestion des quotas de stockage.
 * Respecte les principes SOLID:
 * - SRP: Responsable uniquement de la gestion des quotas
 * - OCP: Extensible via l'interface QuotaOperations
 * - DIP: Depend des abstractions (repositories)
 */
@Service
@Transactional
public class QuotaService implements QuotaOperations {

    private static final Logger logger = LoggerFactory.getLogger(QuotaService.class);

    // Default quota = FREE plan's included_storage_bytes (auth.plan seed in V4
    // = 104857600 bytes = 100 MB). Used only as a SAFETY FALLBACK when a
    // tenant lacks a quota row AND the auth-side plan→quota sync hasn't run
    // (e.g. very early in signup, or a tenant created outside the normal
    // provisioning path). Live tenants on STARTER/PRO/TEAM/ENTERPRISE get
    // their actual plan quota applied via SubscriptionService.onSubscriptionUpsert
    // (routed through PlanStorageQuotaSyncer). Conservative default avoids
    // silently giving non-FREE storage to mis-provisioned tenants.
    public static final long DEFAULT_MAX_BYTES = 104_857_600L; // 100 MB (FREE)
    public static final double DEFAULT_SOFT_LIMIT_RATIO = 0.8; // 80%

    private final StorageRepository storageRepository;
    private final TenantStorageQuotaRepository quotaRepository;
    private final OrganizationStorageQuotaRepository orgQuotaRepository;
    private final StorageBreakdownService breakdownService;
    private final AppEditionProvider editionProvider;

    public QuotaService(StorageRepository storageRepository,
                       TenantStorageQuotaRepository quotaRepository,
                       OrganizationStorageQuotaRepository orgQuotaRepository,
                       StorageBreakdownService breakdownService,
                       AppEditionProvider editionProvider) {
        this.storageRepository = storageRepository;
        this.quotaRepository = quotaRepository;
        this.orgQuotaRepository = orgQuotaRepository;
        this.breakdownService = breakdownService;
        this.editionProvider = editionProvider;
    }

    @Override
    public QuotaStatus checkQuota(String tenantId, long additionalBytes) {
        // Quota decisions are intentionally not cached: the result depends on
        // additionalBytes, not only tenantId. Caching by tenant would let a
        // small allowed write mask a later oversized write for the same tenant.
        // CE Free = unlimited storage. Self-Hosted Enterprise may reuse the
        // monolith topology, but it must honor its signed license/contract
        // quotas, so this bypass is intentionally narrower than "self-hosted".
        if (editionProvider.hasCeFreeUnlimitedLocalResources()) {
            return QuotaStatus.OK;
        }

        logger.debug("Verification quota pour tenant: {}, bytes: {}", tenantId, additionalBytes);

        TenantStorageQuota quota = getOrCreateQuota(tenantId);

        return quota.canStore(additionalBytes)
                ? QuotaStatus.OK
                : QuotaStatus.HARD_LIMIT_REACHED;
    }

    @Override
    @CacheEvict(value = {"quotaStatus", "tenantQuota"}, key = "#tenantId")
    public void updateUsage(String tenantId) {
        // BOTH caches must be evicted: quotaStatus (decisions made by checkQuota) and
        // tenantQuota (TenantStorageQuota returned by getQuota). Pre-fix this only evicted
        // quotaStatus, so getQuota kept returning the cached pre-update TenantStorageQuota
        // with a stale usedBytes - silently breaking recalculateUsage and any inline refresh
        // path. updateLimits below already evicts both; this brings updateUsage in line.
        logger.debug("Mise a jour usage pour tenant: {}", tenantId);

        // Clamped because this total feeds checkQuota, where a negative reads as room to spare
        // rather than as an error. The tenant breakdown rows are clamped at their upsert AND by a
        // V184 CHECK constraint, so this is belt and braces rather than a known hole. Unlike the
        // org gauge above, the tenant gauge has always been the sum of the categories; that is
        // pre-existing and not changed here.
        long totalUsage = Math.max(0L, breakdownService.getTotalUsage(tenantId));
        TenantStorageQuota quota = getOrCreateQuota(tenantId);

        quota.setUsedBytes(totalUsage);
        quota.setUpdatedAt(Instant.now());
        quotaRepository.save(quota);

        logger.debug("Usage mis a jour pour tenant: {} -> {} bytes (from breakdown)", tenantId, totalUsage);
    }

    @Override
    @Cacheable(value = "tenantQuota", key = "#tenantId")
    public TenantStorageQuota getQuota(String tenantId) {
        logger.debug("Recuperation quota pour tenant: {}", tenantId);
        return getOrCreateQuota(tenantId);
    }

    @Override
    @CacheEvict(value = {"quotaStatus", "tenantQuota"}, key = "#tenantId")
    public void updateLimits(String tenantId, long maxBytes, double softLimitRatio) {
        logger.info("Mise a jour limites pour tenant: {} -> {} bytes (soft: {}%)",
            tenantId, maxBytes, softLimitRatio * 100);

        TenantStorageQuota quota = getOrCreateQuota(tenantId);
        quota.setMaxBytes(maxBytes);
        quota.setSoftLimitBytes((long) (maxBytes * softLimitRatio));
        quota.setHardLimitBytes(maxBytes);
        quota.setUpdatedAt(Instant.now());

        quotaRepository.save(quota);
        logger.info("Limites mises a jour pour tenant: {}", tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getCurrentUsage(String tenantId) {
        return storageRepository.calculateTenantUsage(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public double getUsagePercentage(String tenantId) {
        TenantStorageQuota quota = getQuota(tenantId);
        return quota.getUsagePercentage();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSoftLimitReached(String tenantId) {
        TenantStorageQuota quota = getQuota(tenantId);
        return quota.isSoftLimitReached();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isHardLimitReached(String tenantId) {
        TenantStorageQuota quota = getQuota(tenantId);
        return quota.isHardLimitReached();
    }

    // ========== Methodes privees ==========

    private TenantStorageQuota getOrCreateQuota(String tenantId) {
        return quotaRepository.findByTenantId(tenantId)
            .orElseGet(() -> createDefaultQuota(tenantId));
    }

    private TenantStorageQuota createDefaultQuota(String tenantId) {
        logger.info("Creation quota par defaut pour tenant: {}", tenantId);

        TenantStorageQuota quota = new TenantStorageQuota(tenantId, DEFAULT_MAX_BYTES);
        quota.setSoftLimitBytes((long) (DEFAULT_MAX_BYTES * DEFAULT_SOFT_LIMIT_RATIO));

        return quotaRepository.save(quota);
    }

    // ============================================================
    // Organization-scoped operations (PR18)
    //
    // These mirror the tenant-* methods but key on organization_id. Routing
    // between them is decided by the caller (controllers branch on the presence
    // of X-Organization-ID via the QuotaOperations.checkQuotaForScope default
    // method). Strict isolation: a member in org workspace consumes the org
    // quota; outside the org workspace they consume their personal quota.
    //
    // At v1, max_bytes for a new org quota row is seeded with FREE defaults.
    // Auto-sync from the org owner's subscription plan is wired by a follow-up
    // PR (PlanStorageQuotaSyncer.syncOrgAfterCommit). Until then the org OWNER
    // must call updateOrganizationLimits() explicitly to upgrade the cap.
    // ============================================================

    @Override
    public QuotaStatus checkQuotaForScope(String tenantId, String organizationId, long additionalBytes) {
        return (organizationId != null && !organizationId.isBlank())
                ? checkOrganizationQuota(organizationId, additionalBytes)
                : checkQuota(tenantId, additionalBytes);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The allowance is the ACCOUNT's, not the workspace's. A plan that includes 100 GB grants
     * 100 GB to the customer; before this, each workspace was measured against that figure on its
     * own, so the same plan handed 100 GB to every workspace the customer created (a TEAM account
     * could reach 1 TB, and the plans with no workspace cap were unbounded). Consumption is now
     * summed across every workspace the account owns, which also means the account is blocked in
     * ALL of its workspaces once the pool is full, not only in the one that filled it.
     *
     * <p><b>Falls back to the old per-workspace check when the row has no {@code accountId}.</b>
     * That is deliberate and is what makes the change safe to roll out: an unattributed row (one
     * created between the migration and auth-service learning to stamp it, or a workspace whose
     * owner could not be resolved) keeps behaving exactly as it did rather than failing closed
     * and refusing writes it should allow. The failure mode of a missing attribution is "too
     * generous", never "wrongly blocked".
     */
    @Override
    public QuotaStatus checkOrganizationQuota(String organizationId, long additionalBytes) {
        // See checkQuota: the decision depends on additionalBytes, so it must
        // be recalculated for every attempted write.
        if (editionProvider.hasCeFreeUnlimitedLocalResources()) {
            return QuotaStatus.OK;
        }
        OrganizationStorageQuota quota = getOrCreateOrganizationQuota(organizationId);
        return accountCanStore(quota, additionalBytes)
                ? QuotaStatus.OK
                : QuotaStatus.HARD_LIMIT_REACHED;
    }

    /**
     * The shared pool a workspace draws on, or null when it is not attributed to an account
     * (enforcement is then per-workspace and there is no pool).
     *
     * <p><b>One resolution, used by everything.</b> The write gate and every surface that
     * displays or judges the quota read the pool from here, so they cannot disagree. An earlier
     * round of this change computed the ceiling one way in the gate and another way in the
     * response, which produced a page showing a red "full" bar over uploads the server was
     * happily accepting.
     *
     * <p>Reads without creating. The getOrCreate variant would attempt an INSERT inside this
     * read-only transaction; a workspace with no row yet also has nothing stored and no
     * attribution, so the honest answer is "no pool", not a freshly minted row.
     */
    @Override
    @Transactional(readOnly = true)
    public AccountPool getAccountPool(String organizationId) {
        return orgQuotaRepository.findByOrganizationId(organizationId)
                .map(row -> resolvePool(row))
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Long getAccountUsedBytes(String organizationId) {
        AccountPool pool = getAccountPool(organizationId);
        return pool == null ? null : pool.usedBytes();
    }

    /** The pool for a row already in hand, or null when that row is unattributed. */
    private AccountPool resolvePool(OrganizationStorageQuota row) {
        String accountId = row.getAccountId();
        if (accountId == null || accountId.isBlank()) {
            return null;
        }
        long ownCeiling = row.getHardLimitBytes() != null ? row.getHardLimitBytes() : 0L;
        // Freshest row wins, falling back to this one. See the repository javadoc: recency is
        // what survives BOTH a row lagging low (a stale FREE default would refuse a paying
        // account) and a row lagging high (a half-applied downgrade would stay non-binding).
        long ceiling = orgQuotaRepository.currentCeilingForAccount(accountId).orElse(ownCeiling);
        if (ceiling <= 0) {
            ceiling = ownCeiling;
        }
        long used = Math.max(0L, orgQuotaRepository.sumUsedBytesForAccount(accountId));
        return new AccountPool(accountId, used, ceiling);
    }

    /** Whether the account as a whole can take {@code additionalBytes} more. */
    private boolean accountCanStore(OrganizationStorageQuota quota, long additionalBytes) {
        AccountPool pool = resolvePool(quota);
        if (pool == null) {
            return quota.canStore(additionalBytes);
        }
        long additional = Math.max(0L, additionalBytes);
        return pool.usedBytes() <= pool.maxBytes() && additional <= pool.maxBytes() - pool.usedBytes();
    }

    @Override
    @Cacheable(value = "orgQuota", key = "#organizationId")
    public OrganizationStorageQuota getOrganizationQuota(String organizationId) {
        return getOrCreateOrganizationQuota(organizationId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads a fresh {@code SUM(size_bytes)} over {@code storage.storage}, NOT the sum of the
     * breakdown categories. Making it the breakdown sum is tempting, because the storage page
     * draws the gauge above a bar built from those categories and the two should add up. It was
     * tried and reverted, for a reason worth keeping written down: this value is also the write
     * gate ({@code checkOrganizationQuota} reads it with no refresh of any kind), and the
     * breakdown is a LEDGER, which can be incomplete in ways a direct sum cannot. An organization
     * that has never been reconciled has no rows for most of its categories, so the first save
     * after that would write one file's size over a correct total and hand the workspace its
     * whole cap. Measured on production 2026-09-18: 31 organizations held ACTIVE storage rows
     * with no breakdown row at all. A gauge that protects revenue should not depend on a
     * bookkeeping table being healthy.
     *
     * <p>What that costs, measured on the same day: the gauge leaves out the categories that do
     * not live in {@code storage.storage} (execution data and workflow configuration), which on
     * the largest production workspace is 16 MB against 21 GB, so the bar can total a hair more
     * than the number above it. That is the honest residual. The 16 GB the categories used to
     * omit was a different thing entirely, and it is fixed in the classification, not here.
     */
    @Override
    @CacheEvict(value = {"orgQuotaStatus", "orgQuota"}, key = "#organizationId")
    public void updateOrganizationUsage(String organizationId) {
        long totalUsage = Math.max(0L, storageRepository.calculateOrganizationUsage(organizationId));
        OrganizationStorageQuota quota = getOrCreateOrganizationQuota(organizationId);
        quota.setUsedBytes(totalUsage);
        quota.setUpdatedAt(Instant.now());
        orgQuotaRepository.save(quota);
        logger.debug("Org usage updated: org={} -> {} bytes", organizationId, totalUsage);
    }

    @Override
    public void updateOrganizationLimits(String organizationId, long maxBytes, double softLimitRatio) {
        updateOrganizationLimits(organizationId, maxBytes, softLimitRatio, null);
    }

    @Override
    @CacheEvict(value = {"orgQuotaStatus", "orgQuota"}, key = "#organizationId")
    public void updateOrganizationLimits(String organizationId, long maxBytes, double softLimitRatio,
                                         String accountId) {
        logger.info("Updating org storage limits: org={} -> {} bytes (soft: {}%), account={}",
                organizationId, maxBytes, softLimitRatio * 100, accountId);
        OrganizationStorageQuota quota = getOrCreateOrganizationQuota(organizationId);
        quota.setMaxBytes(maxBytes);
        quota.setSoftLimitBytes((long) (maxBytes * softLimitRatio));
        quota.setHardLimitBytes(maxBytes);
        // Null leaves the existing attribution alone. Clearing it would silently drop the
        // workspace out of its account's shared pool and back to its own private allowance,
        // which is the exact over-granting this is meant to end.
        if (accountId != null && !accountId.isBlank()) {
            quota.setAccountId(accountId);
        }
        Instant now = Instant.now();
        // Stamped ONLY here, where the allowance actually changes. The account's shared ceiling
        // is read from whichever of its workspaces has the freshest allowance, and updatedAt
        // cannot serve: every upload bumps it.
        quota.setLimitsUpdatedAt(now);
        quota.setUpdatedAt(now);
        orgQuotaRepository.save(quota);
    }

    @Override
    @Transactional(readOnly = true)
    public long getCurrentOrganizationUsage(String organizationId) {
        return storageRepository.calculateOrganizationUsage(organizationId);
    }

    private OrganizationStorageQuota getOrCreateOrganizationQuota(String organizationId) {
        return orgQuotaRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> createDefaultOrganizationQuota(organizationId));
    }

    private OrganizationStorageQuota createDefaultOrganizationQuota(String organizationId) {
        logger.info("Creating default org storage quota: org={} (FREE default until owner-plan sync)",
                organizationId);
        OrganizationStorageQuota quota = new OrganizationStorageQuota(organizationId, DEFAULT_MAX_BYTES);
        quota.setSoftLimitBytes((long) (DEFAULT_MAX_BYTES * DEFAULT_SOFT_LIMIT_RATIO));
        return orgQuotaRepository.save(quota);
    }
}
