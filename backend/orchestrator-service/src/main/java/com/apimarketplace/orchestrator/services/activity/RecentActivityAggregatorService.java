package com.apimarketplace.orchestrator.services.activity;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.auth.UserSummaryDto;
import com.apimarketplace.common.recentactivity.RecentActivityItemDto;
import com.apimarketplace.common.recentactivity.RecentActivityResponseDto;
import com.apimarketplace.common.recentactivity.RecentActivityScopeResultDto;
import com.apimarketplace.common.recentactivity.ResourceKind;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.config.RecentActivityExecutorConfig;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Aggregates the {@code /app/activity} (3rd bell tab) feed by fanning out
 * to 4 parallel sources:
 *
 * <ol>
 *   <li>Workflows + Applications - orchestrator own-DB query
 *       ({@code WorkflowEntity.WorkflowType} discriminates the row's
 *       {@link ResourceKind}).</li>
 *   <li>Interfaces - {@code InterfaceClient.getRecentActivity}.</li>
 *   <li>Agents + Skills - {@code AgentClient.getRecentAgentResources}
 *       (union response, ONE RPC for both kinds).</li>
 *   <li>Tables (data_sources) - {@code DataSourceClient.getRecentTables}.</li>
 * </ol>
 *
 * <p>Pipeline (cache-miss path):
 * <ol>
 *   <li>Redis cache lookup ({@code recent-activity:{tenantId}:{orgId|none}}).
 *       Hit rate stays high given low-cardinality keys; the {@code CACHE_TTL}
 *       constant below is the source of truth for the actual window.</li>
 *   <li>Cache miss → single-flight lock via {@code SET NX PX 5s}. Winner
 *       computes; losers poll the value for up to 3 s in 100 ms ticks then
 *       fall back to direct fan-out on winner-crash.</li>
 *   <li>4 parallel branches on {@link RecentActivityExecutorConfig#EXECUTOR_BEAN_NAME}
 *       - each wrapped with {@code .orTimeout(3 s).exceptionally(empty)} for
 *       graceful partial-degradation (one slow downstream returns empty, the
 *       other 3 still contribute).</li>
 *   <li>Merge + sort by {@code lastEditedAt DESC} + cap at top-{@value #LIMIT}.</li>
 *   <li>{@code authClient.batchResolveUsers} enriches with displayName +
 *       (eventually) avatarUrl. ONE RPC, NOT N+1.</li>
 *   <li>Compute {@code peerScopeCount} (sum of branch peer counts + own-DB
 *       workflow peer count). When personal scope, peer = 0 (no
 *       cross-org/personal aggregation, mirrors Triggers tab precedent per
 *       auditor C v6 deferred decision).</li>
 *   <li>SETEX cache ({@link #CACHE_TTL}) + DEL lock + return.</li>
 * </ol>
 *
 * <p><b>Freshness mechanism per kind</b> - what bumps each row's
 * {@code updated_at} (the column every branch reads back as
 * {@code RecentActivityItemDto.lastEditedAt}):
 *
 * <table border="1" summary="Freshness mechanisms per ResourceKind">
 *   <tr><th>Kind</th><th>Bumped by</th><th>Reference</th></tr>
 *   <tr><td>WORKFLOW / APPLICATION</td><td>Hibernate {@code @PreUpdate} on every
 *       dirty save + explicit {@code setUpdatedAt(now)} in
 *       {@code WorkflowRunPersistenceService} on every run lifecycle transition
 *       (start, completion).</td>
 *       <td>{@code WorkflowEntity.bumpUpdatedAt} / WRPS:170-178,512-518</td></tr>
 *   <tr><td>TABLE</td><td>Statement-level DB trigger on {@code data_source_items}
 *       (INSERT / UPDATE / DELETE) bumps the parent {@code data_sources.updated_at}.
 *       Catches all 11 write call sites at the DB level.</td>
 *       <td>{@code V249__datasource_items_bump_parent_updated_at.sql}</td></tr>
 *   <tr><td>AGENT</td><td>JPQL SET clause in
 *       {@code AgentExecutionRepository.incrementCounters} bumps {@code agents.updated_at}
 *       alongside {@code lastExecutionAt} on every terminal status (COMPLETED /
 *       FAILED / CANCELLED).</td>
 *       <td>{@code AgentExecutionRepository:127-148}</td></tr>
 *   <tr><td>INTERFACE</td><td>Async best-effort touch from orchestrator on user
 *       action fire ({@code InterfaceActionController.fireAction} +
 *       {@code PublicApplicationService.fireAppAction}) via
 *       {@code InterfaceClient.touchUpdatedAt} on the default {@code ForkJoinPool}
 *       (NOT {@code recentActivityExecutor} - its {@code CallerRunsPolicy} would
 *       inline-block the user POST under saturation). Failures swallow + WARN log
 *       (cosmetic timestamp gap only).</td>
 *       <td>{@code POST /api/internal/interfaces/{id}/touch}</td></tr>
 *   <tr><td>SKILL</td><td><b>Config-edit only</b>. Skills are system-prompt
 *       content with no per-use runtime event (cache TTL 5 min + tool-call dispatch
 *       are not user-meaningful "activity" signals). Bumped by {@code @PreUpdate}
 *       on {@code SkillEntity} on schema/config edits exclusively. Documented
 *       divergence - see also {@link ResourceKind#SKILL}.</td>
 *       <td>{@code SkillEntity.@PreUpdate}</td></tr>
 * </table>
 *
 * <p><b>WS invalidation</b>: intentionally <i>not</i> implemented in v3.3.1.
 * The {@link #CACHE_TTL} constant + frontend {@code staleTime + refetchOnWindowFocus}
 * give acceptable freshness without instrumenting every CRUD path in 4 services.
 * A follow-up (v3.3.2) may add a Redis pub/sub listener tying
 * {@code activity.invalidate} events to {@code DEL recent-activity:*} when
 * upstream UPDATE paths start emitting them. Documented per auditor C v6
 * "WS reuse" suggestion.
 *
 * <p><b>Single-flight loser</b>: uses value-polling (30 × 100 ms) rather
 * than Redis pub/sub. The {@code RedisMessageListenerContainer} that the
 * pub/sub approach requires is not wired in this service - adding it for
 * one cache key would be over-engineering. Value-polling has the same 3 s
 * budget auditor B v6 accepted, with simpler ops semantics.
 */
@Service
public class RecentActivityAggregatorService {

    private static final Logger log = LoggerFactory.getLogger(RecentActivityAggregatorService.class);

    /** Items per response. Bounds the cross-scope sort + serialization cost. */
    static final int LIMIT = 50;
    /** Per-branch upper bound - drops to {@link CompletableFuture#exceptionally} on overrun. */
    static final long BRANCH_TIMEOUT_SECONDS = 3;
    /**
     * Redis cache TTL. Reduced from 30s to 5s as part of the V249 fix bundle
     * (datasource items bump parent updated_at). The DB trigger makes the
     * source-of-truth fresh on write, but the user-perceived freshness gap
     * is still bounded by this TTL. A median save→refresh round-trip is
     * &lt;10s; 30s left the user perception bug intact, 5s closes it without
     * needing a WS/NOTIFY invalidation path. Frontend react-query staleTime
     * should be aligned to 5s in the same release.
     */
    static final Duration CACHE_TTL = Duration.ofSeconds(5);
    /** Single-flight lock TTL - must comfortably exceed a happy-path full fan-out. */
    static final Duration LOCK_TTL = Duration.ofSeconds(5);
    /** Loser poll cadence × max polls = 4 s wait budget for the winner.
     *  Auditor v3.3 chunk-4 C3 raised from 3 s → 4 s after noting winner p99
     *  can hit 1-2 s under load + serialization, leaving the original 3 s
     *  budget with insufficient slack. */
    static final long LOSER_POLL_INTERVAL_MS = 100;
    static final int LOSER_MAX_POLLS = 40;

    // Distinct prefixes for cache vs lock keys - auditor v3.3 chunk-4 C1.
    // Any future invalidator that SCAN/KEYS sweeps "recent-activity:cache:*"
    // can DEL caches without touching locks (and vice-versa). Without the
    // discriminator, a SCAN MATCH recent-activity:* would clobber both.
    private static final String CACHE_KEY_PREFIX = "recent-activity:cache:";
    private static final String LOCK_KEY_PREFIX = "recent-activity:lock:";
    private static final String PERSONAL_PEER_LABEL = "Personal";

    private final WorkflowRepository workflowRepository;
    private final InterfaceClient interfaceClient;
    private final AgentClient agentClient;
    private final DataSourceClient dataSourceClient;
    private final AuthClient authClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final MeterRegistry meterRegistry;

    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter singleFlightLoserTimeouts;
    private final Counter branchFailures;
    private final Timer fanOutLatency;

    public RecentActivityAggregatorService(
            WorkflowRepository workflowRepository,
            InterfaceClient interfaceClient,
            AgentClient agentClient,
            DataSourceClient dataSourceClient,
            AuthClient authClient,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Qualifier(RecentActivityExecutorConfig.EXECUTOR_BEAN_NAME) ExecutorService executor,
            MeterRegistry meterRegistry) {
        this.workflowRepository = workflowRepository;
        this.interfaceClient = interfaceClient;
        this.agentClient = agentClient;
        this.dataSourceClient = dataSourceClient;
        this.authClient = authClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.cacheHits = Counter.builder("recent_activity_cache.hits")
                .description("Cache hit count on /api/activities/recent").register(meterRegistry);
        this.cacheMisses = Counter.builder("recent_activity_cache.misses")
                .description("Cache miss count on /api/activities/recent").register(meterRegistry);
        this.singleFlightLoserTimeouts = Counter.builder("recent_activity_singleflight.loser_timeouts")
                .description("Loser-path timeouts waiting on winner's SETEX (3 s budget)")
                .register(meterRegistry);
        this.branchFailures = Counter.builder("recent_activity_fanout.branch_failures")
                .description("Fan-out branch timeouts / exceptions degraded to empty result")
                .register(meterRegistry);
        this.fanOutLatency = Timer.builder("recent_activity_fanout.latency")
                .description("Total cache-miss-path fan-out latency").register(meterRegistry);
    }

    /**
     * Main entry. Returns the top-{@value #LIMIT} most-recently-edited
     * resources in the user's active workspace + the peer-scope hint.
     */
    public RecentActivityResponseDto getRecentActivity(String tenantId, String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return empty();
        }
        // Measure user-perceived latency once per request - auditor v3.3
        // chunk-4 C2: if the user hits a transient Redis failure path that
        // ends up doing fan-out twice, this still records ONCE (rather than
        // letting computeFanOut double-count).
        long requestStartNanos = System.nanoTime();
        try {
            return getRecentActivityInner(tenantId, orgId);
        } finally {
            fanOutLatency.record(Duration.ofNanos(System.nanoTime() - requestStartNanos));
        }
    }

    private RecentActivityResponseDto getRecentActivityInner(String tenantId, String orgId) {
        String key = cacheKey(tenantId, orgId);

        // 1) Cache lookup - happy path.
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                cacheHits.increment();
                return deserializeOrEmpty(cached);
            }
        } catch (Exception e) {
            // Redis transient failure - degrade to direct fan-out, no cache.
            log.warn("Redis GET failed for {}: {} - falling back to direct fan-out", key, e.getMessage());
            return computeFanOut(tenantId, orgId);
        }
        cacheMisses.increment();

        // 2) Try to be the single-flight winner.
        String lockKey = LOCK_KEY_PREFIX + scopeSuffix(tenantId, orgId);
        Boolean acquired = null;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        } catch (Exception e) {
            // Redis SET NX failure - degrade to direct fan-out without
            // cache write (next caller hits the same cache miss).
            log.warn("Redis SET NX failed for {}: {}", lockKey, e.getMessage());
            return computeFanOut(tenantId, orgId);
        }

        if (Boolean.TRUE.equals(acquired)) {
            try {
                RecentActivityResponseDto result = computeFanOut(tenantId, orgId);
                try {
                    redisTemplate.opsForValue().set(key, serializeOrNull(result), CACHE_TTL);
                } catch (Exception e) {
                    log.warn("Redis SETEX failed for {}: {} - caller still gets fresh result, next caller will refetch",
                            key, e.getMessage());
                }
                return result;
            } finally {
                try {
                    redisTemplate.delete(lockKey);
                } catch (Exception ignored) {
                    // Lock will expire via LOCK_TTL anyway - losers' polling
                    // budget (3 s) covers the gap.
                }
            }
        }

        // 3) Loser path - poll the value for up to 3 s.
        for (int i = 0; i < LOSER_MAX_POLLS; i++) {
            try {
                Thread.sleep(LOSER_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                String cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    return deserializeOrEmpty(cached);
                }
            } catch (Exception e) {
                log.warn("Redis poll GET failed for {}: {}", key, e.getMessage());
                break;
            }
        }

        // 4) Winner crashed or genuinely slow - fall back to direct fan-out.
        singleFlightLoserTimeouts.increment();
        log.warn("Single-flight timeout for key={} - falling back to direct fan-out", key);
        return computeFanOut(tenantId, orgId);
    }

    // ---- Fan-out core ---------------------------------------------------

    private RecentActivityResponseDto computeFanOut(String tenantId, String orgId) {
        // C4 fix - workflow branch now also returns its peer-count so the
        // count query runs INSIDE the executor (parallel with the other
        // branches) instead of serially on the calling thread post-join.
        CompletableFuture<RecentActivityScopeResultDto> wfFuture = CompletableFuture
                .supplyAsync(() -> fetchWorkflowsAndApplications(tenantId, orgId), executor)
                .orTimeout(BRANCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    recordBranchFailure("workflows", ex);
                    return emptyScope();
                });

        CompletableFuture<RecentActivityScopeResultDto> ifFuture = CompletableFuture
                .supplyAsync(() -> interfaceClient.getRecentActivity(tenantId, orgId), executor)
                .orTimeout(BRANCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    recordBranchFailure("interfaces", ex);
                    return emptyScope();
                });

        CompletableFuture<RecentActivityScopeResultDto> agFuture = CompletableFuture
                .supplyAsync(() -> agentClient.getRecentAgentResources(tenantId, orgId), executor)
                .orTimeout(BRANCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    recordBranchFailure("agents", ex);
                    return emptyScope();
                });

        CompletableFuture<RecentActivityScopeResultDto> dsFuture = CompletableFuture
                .supplyAsync(() -> dataSourceClient.getRecentTables(tenantId, orgId), executor)
                .orTimeout(BRANCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    recordBranchFailure("tables", ex);
                    return emptyScope();
                });

        // Wait for all four. Each .join() rethrows nothing because we
        // already mapped exceptions to empty via .exceptionally above.
        RecentActivityScopeResultDto wfResult = wfFuture.join();
        RecentActivityScopeResultDto ifResult = ifFuture.join();
        RecentActivityScopeResultDto agResult = agFuture.join();
        RecentActivityScopeResultDto dsResult = dsFuture.join();

        // Merge → sort by lastEditedAt DESC → cap.
        List<RecentActivityItemDto> all = new ArrayList<>(
                wfResult.items().size() + ifResult.items().size()
                        + agResult.items().size() + dsResult.items().size());
        all.addAll(wfResult.items());
        all.addAll(ifResult.items());
        all.addAll(agResult.items());
        all.addAll(dsResult.items());
        all.sort((a, b) -> {
            Instant ai = a.lastEditedAt();
            Instant bi = b.lastEditedAt();
            if (ai == null && bi == null) return 0;
            if (ai == null) return 1;
            if (bi == null) return -1;
            return bi.compareTo(ai);
        });
        if (all.size() > LIMIT) {
            all = new ArrayList<>(all.subList(0, LIMIT));
        }

        // Resolve actors in ONE RPC (cache-aware in AuthClient).
        Set<String> actorIds = all.stream()
                .map(RecentActivityItemDto::actorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<String, UserSummaryDto> users = actorIds.isEmpty()
                ? Collections.emptyMap()
                : authClient.batchResolveUsers(actorIds);

        List<RecentActivityItemDto> enriched = enrichWithActors(all, users);

        int peerCount = wfResult.peerScopeCount() + ifResult.peerScopeCount()
                + agResult.peerScopeCount() + dsResult.peerScopeCount();
        // Cap aggregate to LIMIT to keep the user-facing number bounded
        // (each branch already capped at 50; sum could reach 200 but
        // "you have 50+ items in Personal" carries the same product
        // signal as the exact count).
        peerCount = Math.min(LIMIT, peerCount);

        String peerLabel = (orgId != null && !orgId.isBlank() && peerCount > 0)
                ? PERSONAL_PEER_LABEL
                : null;

        return new RecentActivityResponseDto(enriched, peerCount, peerLabel);
    }

    /**
     * Workflows + applications branch - returns BOTH items and the
     * peer-scope count in a single executor task (auditor v3.3 chunk-4 C4
     * - the count query no longer runs serially after the fan-out joins).
     */
    // Package-private for focused unit testing of the publicationId mapping.
    RecentActivityScopeResultDto fetchWorkflowsAndApplications(String tenantId, String orgId) {
        // Post-V261 (2026-05-19): the gateway always injects X-Organization-ID
        // (personal workspaces resolve to the user's default personal org), so
        // orgId is non-null/non-blank for normal traffic. The legacy
        // strict-personal IS NULL finder + companion count are removed -
        // peer-scope for the active workspace stays at 0 (the v3.3
        // deferred-decision contract) and the blank-orgId branch is a
        // defensive empty-list fallback only.
        List<WorkflowEntity> rows;
        int peerCount = 0;
        if (orgId != null && !orgId.isBlank()) {
            rows = workflowRepository.findRecentByOrganizationIdStrict(orgId, PageRequest.of(0, LIMIT));
        } else {
            rows = java.util.Collections.emptyList();
        }
        List<RecentActivityItemDto> items = new ArrayList<>(rows.size());
        for (WorkflowEntity w : rows) {
            ResourceKind kind = w.getWorkflowType() == WorkflowEntity.WorkflowType.APPLICATION
                    ? ResourceKind.APPLICATION
                    : ResourceKind.WORKFLOW;
            // APPLICATION rows must route to /app/applications/{publicationId},
            // NOT /app/applications/{workflowId} (which fails to load). Mirror
            // ActiveAutomationsService: carry source_publication_id so the
            // frontend can build the correct route; null for WORKFLOW kind and
            // for legacy applications without a publication (UI falls back to
            // the workflow editor).
            String publicationId = (kind == ResourceKind.APPLICATION && w.getSourcePublicationId() != null)
                    ? w.getSourcePublicationId().toString()
                    : null;
            items.add(RecentActivityItemDto.builder()
                    .kind(kind)
                    .resourceId(w.getId().toString())
                    .name(w.getName())
                    .lastEditedAt(w.getUpdatedAt())
                    .actorId(w.getTenantId())
                    .publicationId(publicationId)
                    .build());
        }
        return new RecentActivityScopeResultDto(items, peerCount);
    }

    // Package-private so the unit test can assert enrichment preserves the
    // APPLICATION publicationId when it rewrites the DTO with a resolved name.
    List<RecentActivityItemDto> enrichWithActors(List<RecentActivityItemDto> items,
                                                         Map<String, UserSummaryDto> users) {
        if (users.isEmpty()) return items;
        List<RecentActivityItemDto> out = new ArrayList<>(items.size());
        for (RecentActivityItemDto item : items) {
            UserSummaryDto u = item.actorId() != null ? users.get(item.actorId()) : null;
            if (u == null || (u.displayName() == null && u.avatarUrl() == null)) {
                out.add(item);
            } else {
                out.add(RecentActivityItemDto.builder()
                        .kind(item.kind())
                        .resourceId(item.resourceId())
                        .name(item.name())
                        .lastEditedAt(item.lastEditedAt())
                        .actorId(item.actorId())
                        .actorDisplayName(u.displayName())
                        .actorAvatarUrl(u.avatarUrl())
                        // Preserve the routing id - enrichment rebuilds the DTO,
                        // so dropping it here would silently re-break the
                        // APPLICATION link for every actor that resolves a name.
                        .publicationId(item.publicationId())
                        .build());
            }
        }
        return out;
    }

    // ---- Helpers --------------------------------------------------------

    private void recordBranchFailure(String branch, Throwable ex) {
        branchFailures.increment();
        log.warn("Recent-activity branch '{}' degraded to empty: {}", branch, ex.toString());
    }

    private static String scopeSuffix(String tenantId, String orgId) {
        return tenantId + ":" + (orgId != null && !orgId.isBlank() ? orgId : "none");
    }

    private static String cacheKey(String tenantId, String orgId) {
        return CACHE_KEY_PREFIX + scopeSuffix(tenantId, orgId);
    }

    private static RecentActivityScopeResultDto emptyScope() {
        return new RecentActivityScopeResultDto(Collections.emptyList(), 0);
    }

    private static RecentActivityResponseDto empty() {
        return new RecentActivityResponseDto(Collections.emptyList(), 0, null);
    }

    private String serializeOrNull(RecentActivityResponseDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize recent-activity response: {}", e.getMessage());
            return "{}";
        }
    }

    private RecentActivityResponseDto deserializeOrEmpty(String json) {
        try {
            return objectMapper.readValue(json, RecentActivityResponseDto.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize cached recent-activity payload: {}", e.getMessage());
            return empty();
        }
    }
}
