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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Full reconciliation service for storage breakdown.
 * Local categories (STEP_OUTPUTS, FILES, EXECUTION_DATA, CONFIGURATION) use native SQL.
 * Remote categories (AGENTS, INTERFACES, CONVERSATIONS, DATATABLES, PUBLICATIONS)
 * are fetched via HTTP clients from their respective services.
 * Triggered daily at 2 AM (see the cron on {@link #dailyReconciliation()}) or on demand.
 */
@Service
public class StorageReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciliationService.class);

    /**
     * Per-tenant TTL throttle for {@link #refreshTenantBreakdown(String)}. A hit within
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
    private static final Duration LOCAL_BREAKDOWN_REFRESH_TTL = Duration.ofSeconds(30);
    private final Cache<String, Boolean> tenantBreakdownRefreshThrottle = Caffeine.newBuilder()
            .expireAfterWrite(LOCAL_BREAKDOWN_REFRESH_TTL)
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
            .expireAfterWrite(LOCAL_BREAKDOWN_REFRESH_TTL)
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
     * {@link StorageBreakdownService#setOrgUsage}, then refreshes the org gauge
     * from those rows, exactly as {@link #reconcileTenant(String)} ends by
     * refreshing the tenant one. STEP_OUTPUTS and FILES come from
     * {@code storage.storage} filtered by {@code organization_id}, classified by
     * {@code StorageRowCategories} so the two categories partition the rows and
     * add up to what the workspace actually holds. EXECUTION_DATA and
     * CONFIGURATION come from orchestrator-owned tables joined on
     * {@code workflows.organization_id}.
     *
     * <p>Remote categories (AGENTS, INTERFACES, CONVERSATIONS, DATATABLES, PUBLICATIONS) do not
     * exist at org scope AT ALL, and saying they "stay as-is" was wrong: every producer of those
     * rows calls the tenant-only 3-argument overload of the tracker, so no org row is ever written
     * for them. A workspace's categories are therefore the four local ones, and its gauge
     * under-reports by whatever its agents, interfaces, conversations, tables and publications
     * hold (about 200 MB on the largest production workspace, against 21 GB of files). Closing
     * that needs an org-scoped usage query in each of the five owning services; it is not a
     * reconciliation change.
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

        // Refresh the gauge. Without this the nightly pass left the org gauge on yesterday's total
        // until somebody opened the page, so the quota gate that decides whether a WRITE is
        // allowed read a stale number in between. The tenant path has always ended this way.
        //
        // Guarded, unlike the tenant equivalent, because this one can INSERT: an org with no
        // quota row yet gets one created here, and dailyReconciliation calls this method through
        // `this.`, so self-invocation puts every scope of the night in ONE transaction. A primary
        // key collision with a concurrent page view would otherwise abort that transaction, and
        // every later scope would fail on "current transaction is aborted" while the pass still
        // logged "completed". The nightly enumeration now covers scopes that have never had a
        // quota row, so that collision became reachable with this change.
        try {
            quotaService.updateOrganizationUsage(organizationId);
        } catch (Exception e) {
            log.warn("[Reconciliation] Gauge refresh failed for org={}: {}", organizationId, e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Reconciliation] Org reconciliation completed for {}, in {}ms", organizationId, elapsed);
    }

    /**
     * Daily reconciliation for every tenant and organization that holds anything, whether or not
     * it has been reconciled before. Runs at 2 AM, ahead of the 2:30 AM history snapshot, so the
     * snapshot captures reconciled values rather than 24h of incremental drift.
     *
     * <p>Failures are per scope and logged, never rethrown. Note the shape this sits in: the
     * method is {@code @Transactional} and calls the per-scope methods through {@code this}, so
     * self-invocation puts the whole pass in ONE transaction. A statement that errors therefore
     * aborts it, every later statement fails on "current transaction is aborted", each one is
     * caught and logged at WARN, and the method still logs "completed". Splitting that is a
     * separate change; until then, anything added inside this loop that can throw needs its own
     * guard.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "storage_daily_reconciliation", lockAtMostFor = "PT25M", lockAtLeastFor = "PT1M")
    @Transactional
    public void dailyReconciliation() {
        // The scope lists are a UNION of "has a breakdown row" and "holds storage rows". Taking
        // them from the breakdown table alone meant a scope that had never been reconciled could
        // never BE reconciled, because the enumeration only ever found scopes it had already
        // visited. Measured on production 2026-09-18: 31 organizations and 30 tenants held ACTIVE
        // rows with no breakdown row at all, so none of their categories was ever computed.
        //
        // What this still does NOT cover: a scope holding only non-storage resources (workflows,
        // conversations, agents, tables, interfaces, publications) and no storage row at all. Most
        // of those tables belong to other services, so orchestrator cannot query them without
        // breaking the schema boundary, and the one it CAN reach, orchestrator.workflows, adds
        // exactly zero scopes today (measured: 84 tenants and 86 organizations with or without
        // that arm), so it is left out rather than shipped as code that selects nothing.
        //
        // Cost of the wider list: 54 tenants to 84, each costing 5 HTTP calls to sibling services.
        // The pass holds a 25-minute ShedLock, and 30 extra scopes do not approach it.
        log.info("[Reconciliation] Starting daily reconciliation");
        long startTime = System.currentTimeMillis();

        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> tenantIds = entityManager
                    .createNativeQuery(StorageReconciliationQueries.TENANTS_TO_RECONCILE)
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

        // Issue #149 - same pass for organizations, enumerated the same way.
        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> orgIds = entityManager
                    .createNativeQuery(StorageReconciliationQueries.ORGS_TO_RECONCILE)
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
     * On-demand inline refresh of the 3 local categories (STEP_OUTPUTS, FILES,
     * EXECUTION_DATA) for a tenant, before the dashboard reads them.
     *
     * <p>EXECUTION_DATA drifts silently between daily reconciliation runs because
     * per-run create/delete hooks intentionally skip incremental tracking (see
     * comments in {@code WorkflowRunPersistenceService:102} and
     * {@code WorkflowManagementService:707} - "incremental tracking causes
     * negative drift because state_snapshot grows during execution"). The
     * result: the breakdown row can sit at 0 even when {@code workflow_runs}
     * hold non-trivial {@code state_snapshot} JSONB, while history snapshots
     * taken on prior days still show the real value.
     *
     * <p>2026-09-18 - STEP_OUTPUTS and FILES were added here so that the tenant
     * scope refreshes exactly what {@link #refreshOrgBreakdown(String)} already
     * refreshed. They used to wait for the 02:00 cron, so the SAME page showed
     * numbers up to a day apart depending on which workspace it was scoped to,
     * and a corrected classification would have taken a day to appear. Whether a
     * figure is current must not depend on the scope it is read in.
     *
     * <p>The queries are single aggregates over {@code storage.storage} and
     * {@code workflow_runs} filtered by {@code tenant_id}. Cost grows with row
     * count and {@code pg_column_size} over the TOAST'd JSONB columns; for large
     * tenants this is not free, so a 30s per-tenant throttle
     * ({@link #LOCAL_BREAKDOWN_REFRESH_TTL}) absorbs dashboard polling and the
     * {@code Promise.all} 2x hit per page load. Measured on the largest
     * production tenant (168k rows, 21 GB): 80 ms for the FILES aggregate.
     *
     * <p>Throttle hits return immediately: a Caffeine lookup and nothing else, which is what this
     * path has always done. The tenant gauge is the sum of these rows, and every writer that
     * changes them refreshes it on the way out ({@code StorageService.trackUsageBestEffort}), so
     * there is nothing for a throttled read to correct. The org path differs on purpose: its
     * gauge is an independent {@code SUM(size_bytes)} that moves without the categories moving,
     * so it refreshes even on a throttle hit.
     *
     * <p>Throttle misses run the SQL and update {@code QuotaService}, which evicts both the
     * {@code quotaStatus} and {@code tenantQuota} caches so the downstream {@code getQuota} read
     * sees the fresh value (see {@code QuotaService#updateUsage}).
     */
    @Transactional
    public void refreshTenantBreakdown(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        if (tenantBreakdownRefreshThrottle.getIfPresent(tenantId) != null) {
            return;
        }
        // The throttle entry is recorded if at least one query succeeded, so a total DB failure
        // (lock-wait timeout, brief connectivity loss) leaves the window open for the next caller
        // to retry. A PARTIAL failure does arm it, and that is deliberate rather than an
        // oversight: requiring all three would mean a single permanently failing category makes
        // every page load re-run the other two forever. The cost is that the failed category can
        // be up to 30s staler than its siblings. quotaService.updateUsage runs regardless so the
        // quota total stays consistent with whatever the breakdown rows hold.
        boolean anyRefreshed = false;
        for (Map.Entry<String, String> entry : LOCAL_QUERIES.entrySet()) {
            if (runLocalQuery(tenantId, entry.getKey(), entry.getValue())) {
                anyRefreshed = true;
            }
        }
        quotaService.updateUsage(tenantId);
        if (anyRefreshed) {
            tenantBreakdownRefreshThrottle.put(tenantId, Boolean.TRUE);
        }
    }

    /**
     * On-demand inline refresh of the 3 local categories (STEP_OUTPUTS, FILES,
     * EXECUTION_DATA) for an organization. The exact mirror of
     * {@link #refreshTenantBreakdown(String)}: same categories, same 30s
     * throttle, so the two scopes are equally fresh.
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
            // The gauge is a direct SUM over storage.storage, so it moves whenever a file is
            // written even though the categories are throttled. Refreshing it here is what keeps
            // the number above the bar current inside the 30s window. (Removing this call was
            // tried while the gauge was derived from the categories; both changes are reverted.)
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
     *         inline {@link #refreshTenantBreakdown} path uses it to skip the
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

            // CONFIGURATION is an absolute sum. Both remote measurements must be known;
            // an omitted key can mean an older service OR a query failure, never proven zero.
            // During a rolling upgrade, keep the last complete total until both keys arrive.
            // Do not change the wire contract: this also protects calls to older producers
            // that already omit failed categories.
            Map<String, Object> agentUsage = agentClient.getAgentStorageUsage(tenantId);
            OptionalLong skills = readUsedBytes(agentUsage == null ? null : agentUsage.get("SKILLS"));
            OptionalLong memories = readUsedBytes(agentUsage == null ? null : agentUsage.get("MEMORIES"));
            if (skills.isEmpty() || memories.isEmpty()) {
                log.warn("[Reconciliation] Incomplete skills/memory measurement for tenant={}; "
                        + "keeping the stored CONFIGURATION value (skillsMeasured={}, memoriesMeasured={})",
                        tenantId, skills.isPresent(), memories.isPresent());
                return;
            }
            long skillsBytes = skills.getAsLong();
            long memoryBytes = memories.getAsLong();
            long totalBytes = Math.addExact(Math.addExact(Math.max(0, workflowBytes), skillsBytes), memoryBytes);
            breakdownService.setUsage(tenantId, "CONFIGURATION", totalBytes, Math.max(0, workflowCount));
            log.debug("[Reconciliation] tenant={}, CONFIGURATION: {} bytes (workflows={}, skills={}, memories={}), {} items",
                    tenantId, totalBytes, workflowBytes, skillsBytes, memoryBytes, workflowCount);
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed for tenant={}, category=CONFIGURATION: {}",
                    tenantId, e.getMessage());
        }
    }

    // ========== Remote categories via HTTP ==========

    /** An absent or invalid measurement must remain distinct from an explicitly measured zero. */
    private static OptionalLong readUsedBytes(Object categoryEntry) {
        if (categoryEntry instanceof Map<?, ?> map && map.get("usedBytes") instanceof Number n) {
            try {
                long bytes = new BigDecimal(n.toString()).longValueExact();
                return bytes >= 0 ? OptionalLong.of(bytes) : OptionalLong.empty();
            } catch (ArithmeticException | NumberFormatException e) {
                // Fractional, non-finite or overflowing byte counts are not measurements.
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
    }

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

    /**
     * True only when the remote service actually reported a figure.
     *
     * <p>The map-based clients (interface, datasource, publication) degrade to an EMPTY
     * map rather than throwing, so "the service is unreachable" and "this tenant stores
     * nothing" arrive here identically - and {@link StorageBreakdownService#setUsage} is
     * an ABSOLUTE set, so writing the empty case erases the last good figure for that
     * tenant. Skip instead: a stale number is strictly better than a wrong zero, and the
     * next nightly run repairs it. {@code reconcileConversations} makes the same
     * distinction through an Optional, since its client is typed.
     *
     * <p>{@code reconcileAgents} already skipped on a shape it did not recognise, but
     * silently; the four categories here log the skip, so a permanently stale figure
     * leaves a trace instead of none. AGENTS is the remaining category whose unmeasured
     * night says nothing at all.
     */
    private static boolean hasMeasurement(Map<String, Object> usage) {
        // Both keys, and both numeric. A non-empty check would accept a partial payload
        // such as {"itemCount": 3} and write usedBytes as 0 - the wrong-zero this exists
        // to prevent, arriving through a slightly different door.
        return usage != null
                && usage.get("usedBytes") instanceof Number
                && usage.get("itemCount") instanceof Number;
    }

    private void reconcileInterfaces(String tenantId) {
        try {
            Map<String, Object> usage = interfaceClient.getInterfaceStorageUsage(tenantId);
            if (!hasMeasurement(usage)) {
                log.warn("[Reconciliation] No INTERFACES measurement for tenant={}; keeping the stored value",
                        tenantId);
                return;
            }
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
            Optional<StorageUsageDto> measured = conversationStorageClient.getStorageUsage(tenantId);
            if (measured.isEmpty()) {
                log.warn("[Reconciliation] No CONVERSATIONS measurement for tenant={}; keeping the stored value",
                        tenantId);
                return;
            }
            StorageUsageDto usage = measured.get();
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
            if (!hasMeasurement(usage)) {
                log.warn("[Reconciliation] No DATATABLES measurement for tenant={}; keeping the stored value",
                        tenantId);
                return;
            }
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
            if (!hasMeasurement(usage)) {
                log.warn("[Reconciliation] No PUBLICATIONS measurement for tenant={}; keeping the stored value",
                        tenantId);
                return;
            }
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
