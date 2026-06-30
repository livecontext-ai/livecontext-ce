package com.apimarketplace.orchestrator.services.storage;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.StorageUsageDto;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.QuotaService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.config.ConversationStorageClient;
import com.apimarketplace.publication.client.PublicationClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full reconciliation service for storage breakdown.
 * Local categories (STEP_OUTPUTS, FILES, EXECUTION_DATA, CONFIGURATION) use native SQL.
 * Remote categories (AGENTS, INTERFACES, CONVERSATIONS, DATATABLES, PUBLICATIONS)
 * are fetched via HTTP clients from their respective services.
 * Triggered daily at 3 AM or on-demand via REST API.
 */
@Service
public class StorageReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciliationService.class);

    /**
     * Per-tenant TTL throttle for {@link #refreshExecutionData(String)}. A hit within
     * the window short-circuits the SQL - important because the storage dashboard
     * fires GET /quota + GET /breakdown in parallel ({@code Promise.all}) on every
     * mount, which would otherwise double the {@code pg_column_size} aggregate over
     * {@code workflow_runs}. 30s is short enough that users don't perceive staleness
     * yet long enough to absorb dashboard polling and re-render churn.
     *
     * Per-replica (not Redis-backed) on purpose: the SQL is idempotent and
     * absolute-set, so two replicas racing only cost an extra query, never
     * incorrect state.
     */
    private static final Duration EXECUTION_DATA_REFRESH_TTL = Duration.ofSeconds(30);
    private final Cache<String, Boolean> executionDataRefreshThrottle = Caffeine.newBuilder()
            .expireAfterWrite(EXECUTION_DATA_REFRESH_TTL)
            .maximumSize(10_000)
            .build();

    /**
     * Per-org TTL throttle for {@link #refreshOrgBreakdown(String)}. Same shape as
     * the tenant throttle above but keyed by organizationId. 2026-05-21 fix -
     * before, the org breakdown read path returned a 24-hour-stale snapshot
     * because there was no inline refresh symmetric to the tenant path. The user
     * saw 4.8MB in the breakdown while the gauge (computed fresh) reported 435MB.
     */
    private final Cache<String, Boolean> orgBreakdownRefreshThrottle = Caffeine.newBuilder()
            .expireAfterWrite(EXECUTION_DATA_REFRESH_TTL)
            .maximumSize(10_000)
            .build();

    /** Local queries run via EntityManager (orchestrator-owned schemas only). */
    private static final Map<String, String> LOCAL_QUERIES = new LinkedHashMap<>();

    /** Org-scoped local queries - same categories filtered on workflows.organization_id. */
    private static final Map<String, String> LOCAL_QUERIES_BY_ORG = new LinkedHashMap<>();

    static {
        LOCAL_QUERIES.put("STEP_OUTPUTS", StorageReconciliationQueries.STEP_OUTPUTS);
        LOCAL_QUERIES.put("FILES", StorageReconciliationQueries.FILES);
        LOCAL_QUERIES.put("EXECUTION_DATA", StorageReconciliationQueries.EXECUTION_DATA);

        LOCAL_QUERIES_BY_ORG.put("STEP_OUTPUTS", StorageReconciliationQueries.STEP_OUTPUTS_BY_ORG);
        LOCAL_QUERIES_BY_ORG.put("FILES", StorageReconciliationQueries.FILES_BY_ORG);
        LOCAL_QUERIES_BY_ORG.put("EXECUTION_DATA", StorageReconciliationQueries.EXECUTION_DATA_BY_ORG);
    }

    private final EntityManager entityManager;
    private final StorageBreakdownService breakdownService;
    private final QuotaService quotaService;
    private final AgentClient agentClient;
    private final InterfaceClient interfaceClient;
    private final DataSourceClient dataSourceClient;
    private final PublicationClient publicationClient;
    private final ConversationStorageClient conversationStorageClient;

    public StorageReconciliationService(EntityManager entityManager,
                                         StorageBreakdownService breakdownService,
                                         QuotaService quotaService,
                                         AgentClient agentClient,
                                         InterfaceClient interfaceClient,
                                         DataSourceClient dataSourceClient,
                                         PublicationClient publicationClient,
                                         ConversationStorageClient conversationStorageClient) {
        this.entityManager = entityManager;
        this.breakdownService = breakdownService;
        this.quotaService = quotaService;
        this.agentClient = agentClient;
        this.interfaceClient = interfaceClient;
        this.dataSourceClient = dataSourceClient;
        this.publicationClient = publicationClient;
        this.conversationStorageClient = conversationStorageClient;
    }

    /**
     * Full reconciliation for a single tenant.
     * Runs local SQL for orchestrator-owned categories, then HTTP calls for remote categories.
     */
    @Transactional
    public void reconcileTenant(String tenantId) {
        log.info("[Reconciliation] Starting full reconciliation for tenant: {}", tenantId);
        long startTime = System.currentTimeMillis();

        // 1. Local categories via native SQL
        for (Map.Entry<String, String> entry : LOCAL_QUERIES.entrySet()) {
            runLocalQuery(tenantId, entry.getKey(), entry.getValue());
        }

        // 2. CONFIGURATION = local workflows + remote skills
        reconcileConfiguration(tenantId);

        // 3. Remote categories via HTTP clients
        reconcileAgents(tenantId);
        reconcileInterfaces(tenantId);
        reconcileConversations(tenantId);
        reconcileDatatables(tenantId);
        reconcilePublications(tenantId);

        // Update quota total from breakdown
        quotaService.updateUsage(tenantId);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Reconciliation] Completed for tenant: {} in {}ms", tenantId, elapsed);
    }

    /**
     * Full reconciliation for a single organization (Issue #149).
     *
     * <p>Re-aggregates org-owned categories and overwrites the corresponding
     * rows in {@code storage.org_storage_breakdown} via
     * {@link StorageBreakdownService#setOrgUsage}. STEP_OUTPUTS and FILES come
     * from {@code storage.storage} filtered by {@code organization_id} -
     * the same column the gauge sums via
     * {@code StorageRepository.calculateOrganizationUsage}, so the two views
     * agree. EXECUTION_DATA and CONFIGURATION come from orchestrator-owned
     * tables joined on {@code workflows.organization_id}.
     *
     * <p>Remote categories (AGENTS, INTERFACES, CONVERSATIONS, DATATABLES,
     * PUBLICATIONS) are NOT recomputed here - they have no organization_id
     * column in their owning service yet, and a cross-schema sum from
     * orchestrator would violate the inter-service boundary rule. Their
     * incrementally tracked rows in org_storage_breakdown stay as-is until
     * those services grow org awareness.
     */
    @Transactional
    public void reconcileOrganization(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            log.debug("[Reconciliation] reconcileOrganization called with blank orgId - skipping");
            return;
        }
        log.info("[Reconciliation] Starting org reconciliation for organization: {}", organizationId);
        long startTime = System.currentTimeMillis();

        // Local categories - same shape as tenant queries, filtered on workflows.organization_id.
        for (Map.Entry<String, String> entry : LOCAL_QUERIES_BY_ORG.entrySet()) {
            runLocalOrgQuery(organizationId, entry.getKey(), entry.getValue());
        }

        // CONFIGURATION = local workflows + plan versions joined on org_id.
        reconcileOrgConfiguration(organizationId);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Reconciliation] Org reconciliation completed for {}, in {}ms", organizationId, elapsed);
    }

    /**
     * Daily reconciliation for all tenants that have breakdown data.
     * Runs at 2 AM every day, before the 2:30 AM history snapshot, so the snapshot
     * captures reconciled (authoritative) values rather than 24h of incremental drift.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "storage_daily_reconciliation", lockAtMostFor = "PT25M", lockAtLeastFor = "PT1M")
    @Transactional
    public void dailyReconciliation() {
        log.info("[Reconciliation] Starting daily reconciliation");
        long startTime = System.currentTimeMillis();

        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> tenantIds = entityManager
                    .createNativeQuery("SELECT DISTINCT tenant_id FROM storage.tenant_storage_breakdown")
                    .getResultList();

            log.info("[Reconciliation] Found {} tenants to reconcile", tenantIds.size());

            for (String tenantId : tenantIds) {
                try {
                    reconcileTenant(tenantId);
                } catch (Exception e) {
                    log.warn("[Reconciliation] Failed for tenant {}: {}", tenantId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Reconciliation] Daily reconciliation failed: {}", e.getMessage(), e);
        }

        // Issue #149 - same pass for orgs that have breakdown data.
        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> orgIds = entityManager
                    .createNativeQuery("SELECT DISTINCT organization_id FROM storage.org_storage_breakdown")
                    .getResultList();

            log.info("[Reconciliation] Found {} organizations to reconcile", orgIds.size());

            for (String orgId : orgIds) {
                try {
                    reconcileOrganization(orgId);
                } catch (Exception e) {
                    log.warn("[Reconciliation] Failed for organization {}: {}", orgId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Reconciliation] Daily org reconciliation failed: {}", e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Reconciliation] Daily reconciliation completed in {}ms", elapsed);
    }

    /**
     * On-demand single-category refresh for EXECUTION_DATA.
     *
     * <p>EXECUTION_DATA drifts silently between daily reconciliation runs because
     * per-run create/delete hooks intentionally skip incremental tracking (see
     * comments in {@code WorkflowRunPersistenceService:102} and
     * {@code WorkflowManagementService:707} - "incremental tracking causes
     * negative drift because state_snapshot grows during execution"). The
     * result: the breakdown row can sit at 0 even when {@code workflow_runs}
     * hold non-trivial {@code state_snapshot} JSONB, while history snapshots
     * taken on prior days still show the real value - visible to users as the
     * 0-vs-500KB discrepancy between the breakdown card and the trends chart.
     *
     * <p>The query is a single aggregate over {@code workflow_runs} filtered by
     * {@code tenant_id}, supported by {@code idx_workflow_runs_tenant_created}.
     * Cost grows with row count and {@code pg_column_size} over the TOAST'd
     * JSONB columns; for large tenants this is not free, so a 30s per-tenant
     * throttle ({@link #EXECUTION_DATA_REFRESH_TTL}) absorbs dashboard polling
     * and the {@code Promise.all} 2× hit per page load.
     *
     * <p>Throttle hits are silent and very cheap (Caffeine in-memory map lookup);
     * throttle misses run the SQL and update {@code QuotaService} which in turn
     * evicts both the {@code quotaStatus} and {@code tenantQuota} caches so the
     * downstream {@code getQuota} read sees the fresh value (see
     * {@code QuotaService#updateUsage}).
     */
    @Transactional
    public void refreshExecutionData(String tenantId) {
        if (executionDataRefreshThrottle.getIfPresent(tenantId) != null) {
            return;
        }
        // Throttle entry is recorded ONLY on SQL success. A transient DB failure
        // (lock-wait timeout, brief connectivity loss) must not poison the 30s
        // window - the next caller should be free to retry. quotaService.updateUsage
        // is still called regardless so the quota total stays consistent with
        // whatever breakdown rows hold (defense-in-depth on cache eviction).
        boolean refreshed = runLocalQuery(tenantId, "EXECUTION_DATA", StorageReconciliationQueries.EXECUTION_DATA);
        quotaService.updateUsage(tenantId);
        if (refreshed) {
            executionDataRefreshThrottle.put(tenantId, Boolean.TRUE);
        }
    }

    /**
     * On-demand inline refresh of the 3 local categories (STEP_OUTPUTS, FILES,
     * EXECUTION_DATA) for an organization. Symmetric to
     * {@link #refreshExecutionData(String)} on the tenant path but covers ALL
     * three volatile local categories because the org breakdown read path is
     * called less frequently than the tenant one - fewer dashboard refreshes
     * means we can afford to recompute the full local set on each call (still
     * 30s-throttled).
     *
     * <p>2026-05-21 fix - pre-fix, the org breakdown endpoint read from
     * {@code org_storage_breakdown} which is only repopulated by the daily
     * 02:30 UTC reconciliation cron. Between cron runs, the org gauge (which
     * computes fresh from {@code storage.storage}) and the breakdown could
     * diverge by 100s of MB. Now an inline refresh runs before the read so
     * the displayed numbers match the gauge.
     *
     * <p>CONFIGURATION is intentionally NOT refreshed here - it changes
     * rarely (only when workflows + plan versions are added/removed) and the
     * join is heavier. Daily reconciliation keeps it in sync.
     *
     * @param organizationId the org to refresh; no-op when null or blank
     */
    @Transactional
    public void refreshOrgBreakdown(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return;
        }
        if (orgBreakdownRefreshThrottle.getIfPresent(organizationId) != null) {
            quotaService.updateOrganizationUsage(organizationId);
            return;
        }
        boolean anyRefreshed = false;
        for (Map.Entry<String, String> entry : LOCAL_QUERIES_BY_ORG.entrySet()) {
            try {
                Query query = entityManager.createNativeQuery(entry.getValue());
                query.setParameter("oid", organizationId);
                Object[] result = (Object[]) query.getSingleResult();
                long usedBytes = toBigInteger(result[0]).longValue();
                int itemCount = toBigInteger(result[1]).intValue();
                breakdownService.setOrgUsage(organizationId, entry.getKey(),
                        Math.max(0, usedBytes), Math.max(0, itemCount));
                anyRefreshed = true;
            } catch (Exception e) {
                log.warn("[Reconciliation] refreshOrgBreakdown failed for org={}, category={}: {}",
                        organizationId, entry.getKey(), e.getMessage());
            }
        }
        quotaService.updateOrganizationUsage(organizationId);
        if (anyRefreshed) {
            orgBreakdownRefreshThrottle.put(organizationId, Boolean.TRUE);
        }
    }

    // ========== Local queries ==========

    /**
     * @return true if the query ran and breakdown was written, false if any
     *         exception was caught. Daily reconciliation ignores this; the
     *         inline {@link #refreshExecutionData} path uses it to skip the
     *         throttle entry on failure (so a transient DB failure does not
     *         block retry for 30s).
     */
    private boolean runLocalQuery(String tenantId, String category, String sql) {
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter("tid", tenantId);
            Object[] result = (Object[]) query.getSingleResult();

            long usedBytes = toBigInteger(result[0]).longValue();
            int itemCount = toBigInteger(result[1]).intValue();

            breakdownService.setUsage(tenantId, category, Math.max(0, usedBytes), Math.max(0, itemCount));
            log.debug("[Reconciliation] tenant={}, category={}: {} bytes, {} items",
                    tenantId, category, usedBytes, itemCount);
            return true;
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for tenant={}, category={}: {}",
                    tenantId, category, e.getMessage());
            return false;
        }
    }

    private void runLocalOrgQuery(String organizationId, String category, String sql) {
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter("oid", organizationId);
            Object[] result = (Object[]) query.getSingleResult();

            long usedBytes = toBigInteger(result[0]).longValue();
            int itemCount = toBigInteger(result[1]).intValue();

            breakdownService.setOrgUsage(organizationId, category, Math.max(0, usedBytes), Math.max(0, itemCount));
            log.debug("[Reconciliation] org={}, category={}: {} bytes, {} items",
                    organizationId, category, usedBytes, itemCount);
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for org={}, category={}: {}",
                    organizationId, category, e.getMessage());
        }
    }

    // ========== Configuration (hybrid: local workflows + remote skills) ==========

    private void reconcileOrgConfiguration(String organizationId) {
        try {
            Query query = entityManager.createNativeQuery(StorageReconciliationQueries.CONFIGURATION_WORKFLOWS_BY_ORG);
            query.setParameter("oid", organizationId);
            Object[] result = (Object[]) query.getSingleResult();

            long workflowBytes = toBigInteger(result[0]).longValue();
            int workflowCount = toBigInteger(result[1]).intValue();

            // No skills bytes - agent-service has no org column yet (Issue #149 follow-up).
            breakdownService.setOrgUsage(organizationId, "CONFIGURATION",
                    Math.max(0, workflowBytes), Math.max(0, workflowCount));
            log.debug("[Reconciliation] org={}, CONFIGURATION: {} bytes, {} items",
                    organizationId, workflowBytes, workflowCount);
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for org={}, category=CONFIGURATION: {}",
                    organizationId, e.getMessage());
        }
    }

    private void reconcileConfiguration(String tenantId) {
        try {
            // Local: workflows + plan versions
            Query query = entityManager.createNativeQuery(StorageReconciliationQueries.CONFIGURATION_WORKFLOWS);
            query.setParameter("tid", tenantId);
            Object[] result = (Object[]) query.getSingleResult();

            long workflowBytes = toBigInteger(result[0]).longValue();
            int workflowCount = toBigInteger(result[1]).intValue();

            // Remote: skills from agent-service (included in AGENTS response as "SKILLS" key)
            long skillsBytes = 0;
            try {
                Map<String, Object> agentUsage = agentClient.getAgentStorageUsage(tenantId);
                Object skillsData = agentUsage.get("SKILLS");
                if (skillsData instanceof Map<?, ?> skillsMap) {
                    Object bytes = skillsMap.get("usedBytes");
                    if (bytes instanceof Number n) skillsBytes = n.longValue();
                }
            } catch (Exception e) {
                log.warn("[Reconciliation] Failed to get skills storage for tenant={}: {}", tenantId, e.getMessage());
            }

            long totalBytes = Math.max(0, workflowBytes) + Math.max(0, skillsBytes);
            breakdownService.setUsage(tenantId, "CONFIGURATION", totalBytes, Math.max(0, workflowCount));
            log.debug("[Reconciliation] tenant={}, CONFIGURATION: {} bytes (workflows={}, skills={}), {} items",
                    tenantId, totalBytes, workflowBytes, skillsBytes, workflowCount);
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for tenant={}, category=CONFIGURATION: {}",
                    tenantId, e.getMessage());
        }
    }

    // ========== Remote categories via HTTP ==========

    private void reconcileAgents(String tenantId) {
        try {
            Map<String, Object> usage = agentClient.getAgentStorageUsage(tenantId);
            Object agentsData = usage.get("AGENTS");
            if (agentsData instanceof Map<?, ?> agentsMap) {
                long bytes = agentsMap.get("usedBytes") instanceof Number n ? n.longValue() : 0;
                int count = agentsMap.get("itemCount") instanceof Number n ? n.intValue() : 0;
                breakdownService.setUsage(tenantId, "AGENTS", Math.max(0, bytes), Math.max(0, count));
                log.debug("[Reconciliation] tenant={}, AGENTS: {} bytes, {} items", tenantId, bytes, count);
            }
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for tenant={}, category=AGENTS: {}", tenantId, e.getMessage());
        }
    }

    private void reconcileInterfaces(String tenantId) {
        try {
            Map<String, Object> usage = interfaceClient.getInterfaceStorageUsage(tenantId);
            long bytes = usage.get("usedBytes") instanceof Number n ? n.longValue() : 0;
            int count = usage.get("itemCount") instanceof Number n ? n.intValue() : 0;
            breakdownService.setUsage(tenantId, "INTERFACES", Math.max(0, bytes), Math.max(0, count));
            log.debug("[Reconciliation] tenant={}, INTERFACES: {} bytes, {} items", tenantId, bytes, count);
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for tenant={}, category=INTERFACES: {}", tenantId, e.getMessage());
        }
    }

    private void reconcileConversations(String tenantId) {
        try {
            StorageUsageDto usage = conversationStorageClient.getStorageUsage(tenantId);
            // Defensive Math.max(0, ...): mirrors the 4 sibling reconcilers and ensures
            // the V184 CHECK (used_bytes >= 0, item_count >= 0) never trips if the
            // remote conversation-service ever returns a negative count under drift.
            breakdownService.setUsage(tenantId, "CONVERSATIONS",
                    Math.max(0L, usage.usedBytes()), Math.max(0, usage.itemCount()));
            log.debug("[Reconciliation] tenant={}, CONVERSATIONS: {} bytes, {} items",
                    tenantId, usage.usedBytes(), usage.itemCount());
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for tenant={}, category=CONVERSATIONS: {}", tenantId, e.getMessage());
        }
    }

    private void reconcileDatatables(String tenantId) {
        try {
            Map<String, Object> usage = dataSourceClient.getDataSourceStorageUsage(tenantId);
            long bytes = usage.get("usedBytes") instanceof Number n ? n.longValue() : 0;
            int count = usage.get("itemCount") instanceof Number n ? n.intValue() : 0;
            breakdownService.setUsage(tenantId, "DATATABLES", Math.max(0, bytes), Math.max(0, count));
            log.debug("[Reconciliation] tenant={}, DATATABLES: {} bytes, {} items", tenantId, bytes, count);
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for tenant={}, category=DATATABLES: {}", tenantId, e.getMessage());
        }
    }

    private void reconcilePublications(String tenantId) {
        try {
            Map<String, Object> usage = publicationClient.getPublicationStorageUsage(tenantId);
            long bytes = usage.get("usedBytes") instanceof Number n ? n.longValue() : 0;
            int count = usage.get("itemCount") instanceof Number n ? n.intValue() : 0;
            breakdownService.setUsage(tenantId, "PUBLICATIONS", Math.max(0, bytes), Math.max(0, count));
            log.debug("[Reconciliation] tenant={}, PUBLICATIONS: {} bytes, {} items", tenantId, bytes, count);
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for tenant={}, category=PUBLICATIONS: {}", tenantId, e.getMessage());
        }
    }

    // ========== Helpers ==========

    private BigInteger toBigInteger(Object value) {
        if (value == null) return BigInteger.ZERO;
        if (value instanceof BigInteger bi) return bi;
        if (value instanceof Number num) return BigInteger.valueOf(num.longValue());
        return BigInteger.ZERO;
    }
}
