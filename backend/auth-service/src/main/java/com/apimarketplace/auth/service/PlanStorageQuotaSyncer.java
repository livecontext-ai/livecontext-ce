package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.common.storage.service.QuotaService;
import com.apimarketplace.storage.client.StorageClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Single entry-point for keeping {@code storage.tenant_storage_quota.max_bytes}
 * in sync with {@code auth.plan.included_storage_bytes}.
 *
 * <p>This class exists because several distinct sites in auth-service mutate a
 * {@code Subscription.plan} reference but historically NONE propagated the
 * new plan's storage allowance to the storage subsystem:
 * <ul>
 *   <li>{@link SubscriptionService#onSubscriptionUpsert} - Stripe webhook.</li>
 *   <li>{@link StripeBillingService} plan-swap path (~line 537) - explicit Stripe-API upgrade.</li>
 *   <li>{@link StripeBillingService} fallback FREE-creation (~line 1096) - internal grant.</li>
 *   <li>{@link com.apimarketplace.auth.web.BillingController} FREE-recreation (~line 771).</li>
 *   <li>{@link UserResolutionService} email-verified signup path (~line 337).</li>
 * </ul>
 * Routing them all through {@link #syncAfterCommit(Long, Plan)} eliminates the
 * "we fixed three sites but the fourth still drifts" failure mode that previous
 * iterations of this fix were vulnerable to.
 *
 * <h2>Why post-commit</h2>
 * The auth tx has already mutated billing-critical state by the time we want to
 * touch the storage schema. If {@link QuotaService#updateLimits(String, long, double)}
 * fails (storage DB down, schema disabled, cache busy), letting the exception
 * bubble would taint the auth tx via Spring's {@code markRollbackOnly}-on-
 * runtime-exception semantics - the inline try/catch suppresses the symptom but
 * not the {@code UnexpectedRollbackException} that follows. Deferring the
 * storage write to {@code afterCommit} fully decouples lifecycles: the auth
 * side is persisted, the quota update is best-effort, and the V198 migration
 * (or the next webhook delivery) reconciles any drift asynchronously.
 *
 * <h2>Why primitives in the Runnable</h2>
 * The {@link Plan} entity is Hibernate-managed; by the time {@code afterCommit}
 * fires, the JPA session is closed and any lazy-loaded field would throw
 * {@code LazyInitializationException}. We snapshot {@code planCode} (String)
 * and {@code maxBytes} (primitive long) INSIDE the active session so the
 * post-commit hook never touches the entity again.
 */
@Service
public class PlanStorageQuotaSyncer {

    private static final Logger log = LoggerFactory.getLogger(PlanStorageQuotaSyncer.class);

    /**
     * Nullable on purpose: {@code common-storage-service} is an optional
     * module (absent in CE / monolith-without-storage profiles). When the
     * bean is missing, every call becomes a no-op + a one-shot startup
     * warning, rather than a hard wiring failure that takes down auth.
     */
    private final QuotaService quotaService;
    private final OrganizationRepository organizationRepository;

    /**
     * Microservice path (cloud): when wired, plan→quota limit updates are routed through
     * storage-service - the service that OWNS the {@code storage} schema and its read caches.
     * storage-service performs the write in its own request transaction (commits) and evicts its
     * own cache. Null in monolith (CE), where the in-process {@link QuotaService} path is used.
     * See {@code StorageClientConfig} (gated on {@code deployment.mode=microservice}).
     */
    @Autowired(required = false)
    private StorageClient storageClient;

    /**
     * In-process (monolith) path only: forces each {@link QuotaService} write into its OWN
     * committed transaction. Without it, a write performed inside a {@code afterCommit} hook joins
     * the already-committed caller transaction and is SILENTLY LOST (no exception). Null in slim
     * tests / when no transaction manager is wired - then the write runs directly, which is correct
     * for the non-tx inline path (a fresh @Transactional tx opens normally there).
     */
    private TransactionTemplate requiresNewTx;

    public PlanStorageQuotaSyncer(@Autowired(required = false) QuotaService quotaService,
                                  OrganizationRepository organizationRepository) {
        this.quotaService = quotaService;
        this.organizationRepository = organizationRepository;
    }

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager != null) {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tt;
        }
    }

    /**
     * Sync the tenant's storage quota to the plan's allowance. Deferred to
     * AFTER the current transaction commits when one is active; runs inline
     * otherwise (admin scripts, test paths, non-transactional callers).
     *
     * <p>Silently skips when:
     * <ul>
     *   <li>{@link QuotaService} bean is not wired (CE / module absent).</li>
     *   <li>{@code userId} is {@code null} (defensive guard).</li>
     *   <li>{@code plan} is {@code null} (defensive guard).</li>
     *   <li>{@code plan.includedStorageBytes} is {@code null} (plan has no
     *       opinion on storage - don't override).</li>
     *   <li>{@code plan.includedStorageBytes <= 0} (e.g. CREDIT_PACK seeds 0;
     *       should never be the active subscription plan, but guard anyway).</li>
     * </ul>
     */
    public void syncAfterCommit(Long userId, Plan plan) {
        if (!isSyncRequired(userId, plan)) return;

        // Snapshot inside the active JPA session - see class javadoc.
        final String planCode = plan.getCode();
        final long maxBytes = plan.getIncludedStorageBytes(); // guarded non-null by isSyncRequired
        final Runnable sync = () -> doSync(userId, planCode, maxBytes);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { sync.run(); }
            });
        } else {
            // Non-tx context - run inline. Loses the "isolated from outer tx"
            // guarantee but callers in non-tx contexts (e.g. admin scripts)
            // accept that trade-off explicitly.
            sync.run();
        }
    }

    private boolean isSyncRequired(Long userId, Plan plan) {
        if (quotaService == null && storageClient == null) {
            log.debug("No storage quota write path wired (neither StorageClient nor QuotaService) - "
                    + "skipping storage quota sync for user {}", userId);
            return false;
        }
        if (userId == null) {
            log.warn("Cannot sync storage quota: userId is null (plan {})",
                    plan != null ? plan.getCode() : "<null>");
            return false;
        }
        if (plan == null) {
            log.warn("Cannot sync storage quota for user {}: plan is null", userId);
            return false;
        }
        Long maxBytes = plan.getIncludedStorageBytes();
        if (maxBytes == null || maxBytes <= 0) {
            log.debug("Plan {} has null/zero included_storage_bytes - skipping quota sync for user {}",
                    plan.getCode(), userId);
            return false;
        }
        return true;
    }

    private void doSync(Long userId, String planCode, long maxBytes) {
        final double ratio = QuotaService.DEFAULT_SOFT_LIMIT_RATIO;

        // Tenant-quota sync.
        try {
            writeTenantLimit(String.valueOf(userId), maxBytes, ratio);
            log.info("Storage quota (tenant) synced for user {}: plan={}, maxBytes={}, via={}",
                    userId, planCode, maxBytes, writePath());
        } catch (Exception e) {
            log.warn("Tenant storage quota sync failed for user {} (plan {}): {}",
                    userId, planCode, e.getMessage());
        }

        // Org-quota sync (Bug fix 2026-05-14): the legacy syncer only updated
        // tenant_storage_quota, leaving organization_storage_quota seeded at FREE
        // default (100 MB) from V205's lazy first-write. UI reads org quota in
        // org-scope, so users upgrading to TEAM/PRO/etc never saw the new
        // allowance - visible as "Storage 100 MB" stuck on every personal-org
        // workspace. Apply same maxBytes + soft ratio to ALL orgs owned by the
        // user (personal-org for every user, plus any team org they own).
        //
        // Per-iteration try/catch (audit 2026-05-14 nit): a transient failure on
        // org[0] (row-level lock contention, intermittent network) must NOT
        // skip orgs [1..N]. The outer try/catch only covers the repository
        // lookup; each org write gets its own isolated catch.
        List<Organization> ownedOrgs;
        try {
            ownedOrgs = organizationRepository.findByOwnerId(userId);
        } catch (Exception e) {
            log.warn("Org repo lookup failed for user {} (plan {}): {}",
                    userId, planCode, e.getMessage());
            return;
        }
        for (Organization org : ownedOrgs) {
            if (org.getId() == null) continue;
            String orgId = org.getId().toString();
            try {
                writeOrgLimit(orgId, maxBytes, ratio);
                log.info("Storage quota (org {}) synced for user {}: plan={}, maxBytes={}, via={}",
                        orgId, userId, planCode, maxBytes, writePath());
            } catch (Exception e) {
                log.warn("Org storage quota sync failed for user {} org {} (plan {}): {} - continuing with remaining orgs",
                        userId, orgId, planCode, e.getMessage());
            }
        }
    }

    private String writePath() {
        return storageClient != null ? "storage-service(http)" : "in-process";
    }

    /**
     * Microservice: POST to storage-service (which writes in its own committed tx + evicts its own
     * cache). Monolith: in-process {@link QuotaService} wrapped in {@link #writeInNewTx} so the
     * afterCommit write actually commits. Throws on a non-ack so the caller logs + continues.
     */
    private void writeTenantLimit(String tenantId, long maxBytes, double ratio) {
        if (storageClient != null) {
            if (!storageClient.updateTenantStorageLimits(tenantId, maxBytes, ratio)) {
                throw new IllegalStateException("storage-service did not acknowledge tenant limit update");
            }
        } else {
            writeInNewTx(() -> quotaService.updateLimits(tenantId, maxBytes, ratio));
        }
    }

    private void writeOrgLimit(String organizationId, long maxBytes, double ratio) {
        if (storageClient != null) {
            if (!storageClient.updateOrganizationStorageLimits(organizationId, maxBytes, ratio)) {
                throw new IllegalStateException("storage-service did not acknowledge org limit update");
            }
        } else {
            writeInNewTx(() -> quotaService.updateOrganizationLimits(organizationId, maxBytes, ratio));
        }
    }

    /**
     * Run an in-process quota write in a brand-new committed transaction when a transaction manager
     * is wired (production monolith). Falls back to a direct call when none is wired (slim tests,
     * or the non-tx inline path where a fresh @Transactional tx opens normally).
     */
    private void writeInNewTx(Runnable write) {
        if (requiresNewTx != null) {
            requiresNewTx.executeWithoutResult(status -> write.run());
        } else {
            write.run();
        }
    }

    /**
     * Startup probe - surfaces missing wiring at boot, not days later when
     * someone notices every tenant is stuck on the library safety fallback.
     */
    @PostConstruct
    void logQuotaServiceWiring() {
        if (quotaService == null) {
            log.warn("QuotaService bean is NOT wired into PlanStorageQuotaSyncer - " +
                    "storage quota sync will no-op for every plan change. Check that " +
                    "common-storage-service module is on the classpath and JPA scans " +
                    "include storage.tenant_storage_quota.");
        } else {
            log.info("PlanStorageQuotaSyncer ready - storage quota will sync to plan allowance.");
        }
    }
}
