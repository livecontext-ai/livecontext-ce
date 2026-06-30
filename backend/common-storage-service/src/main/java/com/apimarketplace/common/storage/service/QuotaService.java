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

        long totalUsage = breakdownService.getTotalUsage(tenantId);
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

    @Override
    public QuotaStatus checkOrganizationQuota(String organizationId, long additionalBytes) {
        // See checkQuota: the decision depends on additionalBytes, so it must
        // be recalculated for every attempted write.
        if (editionProvider.hasCeFreeUnlimitedLocalResources()) {
            return QuotaStatus.OK;
        }
        OrganizationStorageQuota quota = getOrCreateOrganizationQuota(organizationId);
        return quota.canStore(additionalBytes)
                ? QuotaStatus.OK
                : QuotaStatus.HARD_LIMIT_REACHED;
    }

    @Override
    @Cacheable(value = "orgQuota", key = "#organizationId")
    public OrganizationStorageQuota getOrganizationQuota(String organizationId) {
        return getOrCreateOrganizationQuota(organizationId);
    }

    @Override
    @CacheEvict(value = {"orgQuotaStatus", "orgQuota"}, key = "#organizationId")
    public void updateOrganizationUsage(String organizationId) {
        long totalUsage = storageRepository.calculateOrganizationUsage(organizationId);
        OrganizationStorageQuota quota = getOrCreateOrganizationQuota(organizationId);
        quota.setUsedBytes(totalUsage);
        quota.setUpdatedAt(Instant.now());
        orgQuotaRepository.save(quota);
        logger.debug("Org usage updated: org={} -> {} bytes", organizationId, totalUsage);
    }

    @Override
    @CacheEvict(value = {"orgQuotaStatus", "orgQuota"}, key = "#organizationId")
    public void updateOrganizationLimits(String organizationId, long maxBytes, double softLimitRatio) {
        logger.info("Updating org storage limits: org={} -> {} bytes (soft: {}%)",
                organizationId, maxBytes, softLimitRatio * 100);
        OrganizationStorageQuota quota = getOrCreateOrganizationQuota(organizationId);
        quota.setMaxBytes(maxBytes);
        quota.setSoftLimitBytes((long) (maxBytes * softLimitRatio));
        quota.setHardLimitBytes(maxBytes);
        quota.setUpdatedAt(Instant.now());
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
