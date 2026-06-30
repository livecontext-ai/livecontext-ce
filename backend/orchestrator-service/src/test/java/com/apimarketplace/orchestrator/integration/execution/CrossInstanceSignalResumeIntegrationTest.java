package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.WorkflowExecutionServiceV2;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.cache.RunCacheRegistry;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * CROSS-INSTANCE RESUME - horizontal-scaling invariant test.
 *
 * <p><b>The invariant under test:</b> one instance drives a run's traversal at a
 * time; the in-memory {@code ParallelMergeRegistry} is traversal-scoped (a local
 * variable of {@code handleForkParallelExecution}, discarded when the traversal
 * returns); cross-instance correctness comes ONLY from shared persistence
 * (DB state snapshot + step data + signal rows) plus shared Redis primitives
 * (dedup SETNX + per-run signal-resume lock). A signal resolved AFTER the
 * traversal instance is gone must therefore be resumable by a DIFFERENT,
 * freshly-constructed service stack with zero in-memory carry-over.
 *
 * <p><b>How "instance B" is simulated (strongest feasible in one JVM):</b>
 * <ol>
 *   <li>The context's singleton {@link SignalResumeService} is replaced with a
 *       {@code @MockitoBean} - the production auto-resume listeners
 *       ({@code @TransactionalEventListener} after {@code resolveSignal} commits,
 *       and the Redis cross-instance pub/sub listener) route into a no-op, so
 *       "instance A" (the traversal instance) provably never resumes.</li>
 *   <li>"Instance B" is a <b>brand-new {@code SignalResumeService} object</b>
 *       built via {@code AutowireCapableBeanFactory.createBean} over the SAME
 *       repositories / DB / Redis - a fresh instance with no shared mutable
 *       fields with the traversal run (SignalResumeService keeps no per-run
 *       state; the point is that resume needs none).</li>
 *   <li>Every JVM-LOCAL run-scoped cache that the traversal populated
 *       (step-by-step contexts, split contexts, merge integration tracking,
 *       readiness cache, persistence dedup, back-edge claims, plan caches) is
 *       purged between phase 1 and phase 2 - what a fresh JVM would not have.
 *       Redis-backed run state (running-node tracker, snapshot JSON cache,
 *       event seq) is intentionally KEPT: in a real cluster it is shared
 *       infrastructure that instance B would also see.</li>
 * </ol>
 *
 * <p><b>What this proves:</b> resume re-derives readiness exclusively from
 * persisted state - the fork/merge bookkeeping of the traversal instance
 * (ParallelMergeRegistry, branch contexts) is NOT needed; the merge deferred at
 * the traversal's root join (left unexecuted because a predecessor was awaiting
 * a signal) fires exactly once on the resuming instance; sibling-branch work is
 * not re-executed; concurrent resumes from two instances are serialized by the
 * Redis per-run lock and still produce exactly-once downstream execution.
 *
 * <p><b>What this does NOT prove (requires a true 2-JVM / 2-pod environment):</b>
 * <ul>
 *   <li>two separate Spring contexts / connection pools / classloaders - all
 *       downstream singletons (V2StepByStepService, engine, repositories) are
 *       shared; they are stateless per-run after the cache purge above, but a
 *       second JVM is the only hard proof;</li>
 *   <li>{@code pg_advisory_xact_lock} serialization - H2 lacks the function, so
 *       {@code AdvisoryLockHelper} runs its documented degraded path (swallow +
 *       row-lock backstop). The lock asserted here is the Redis per-run
 *       signal-resume lock, which IS the cross-instance resume serializer;</li>
 *   <li>JSONB CAS patch contention ({@code state-snapshot.use-jsonb-patch=false}
 *       on H2 → full-rewrite path) and heartbeat-failover signal claims
 *       (flag off in this profile).</li>
 * </ul>
 *
 * <p>Not {@code @Transactional} on purpose: the engine executes fork branches on
 * {@code ForkJoinPool} workers whose transactions are independent of the test
 * thread - a test-scoped transaction would hide committed state from them (and
 * roll back what they must see). Each test uses a unique runId; H2 is per-JVM.
 *
 * <p>Runs against a Testcontainers Redis instance, so a clean checkout does not
 * require a machine-local Redis daemon on {@code localhost:6379}.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@DisplayName("Cross-instance signal resume - fork/merge over shared persistence")
@Testcontainers(disabledWithoutDocker = true)
class CrossInstanceSignalResumeIntegrationTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORGANIZATION_ID = "org-1";
    private static final long PHASE_TIMEOUT_S = 90;
    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired private WorkflowExecutionServiceV2 executionServiceV2;
    @Autowired private UnifiedSignalService unifiedSignalService;
    @Autowired private SignalWaitRepository signalWaitRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private RunCacheRegistry runCacheRegistry;
    @Autowired private com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager splitContextManager;

    /**
     * Replaces the singleton so the in-context auto-resume paths (AFTER_COMMIT
     * event listener + Redis pub/sub listener) are no-ops - "instance A" never
     * resumes; only the explicitly-driven fresh instance(s) do.
     */
    @MockitoBean
    private SignalResumeService inContextResumeSingleton;

    /**
     * The auth-service credit endpoint is unreachable in tests (localhost:1
     * safety net), which makes the real budget cache resolve to 0 credits and
     * fail every node. Billing exactness is covered elsewhere - here the budget
     * gate must simply pass.
     */
    @MockitoBean
    private CreditBudgetService creditBudgetService;

    @BeforeEach
    void allowAllCredits() {
        lenient().when(creditBudgetService.tryConsume(anyString(), any(java.math.BigDecimal.class)))
            .thenReturn(true);
        lenient().when(creditBudgetService.tryConsumeOne(anyString())).thenReturn(true);
    }

    // =====================================================================
    // Fixture
    // =====================================================================

    private record RunFixture(String runId, WorkflowPlan plan, WorkflowExecution execution) {}

    /**
     * Persists workflow + run rows (committed - the run row, including its plan
     * JSON, is what a second instance loads) and builds the execution object the
     * way ReusableTriggerService / the execution controller do.
     */
    private RunFixture createCommittedRun(Map<String, Object> planMap) {
        WorkflowEntity workflow = new WorkflowEntity(TENANT_ID, "Cross-instance resume workflow", "user-1");
        workflow.setId(UUID.randomUUID());
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        workflow.setOrganizationId(ORGANIZATION_ID);
        workflowRepository.save(workflow);

        String runId = "run-xinst-" + UUID.randomUUID().toString().substring(0, 8);
        WorkflowRunEntity run = new WorkflowRunEntity(workflow, TENANT_ID, runId, null, null, "user-1");
        run.setStatus(RunStatus.RUNNING);
        run.setOrganizationId(ORGANIZATION_ID);
        run.setPlan(planMap);
        runRepository.save(run);

        WorkflowPlan plan = WorkflowPlan.fromMap(planMap, workflow.getId().toString(), TENANT_ID);
        WorkflowExecution execution = new WorkflowExecution(runId, plan, Map.of());
        execution.setWorkflowRunId(run.getId());
        execution.setDisplayName("Cross-instance resume workflow");
        return new RunFixture(runId, plan, execution);
    }

    /**
     * Fork plan with ONE approval branch:
     *
     * <pre>
     * trigger:start → core:gate (USER_APPROVAL, yields)   → [approved] → core:join
     * trigger:start → mcp:branch_b (mock tool, completes)             → core:join
     *                                                  core:join → mcp:final
     * </pre>
     */
    private Map<String, Object> singleApprovalForkPlan() {
        return TestWorkflowPlan.create()
            .withTenantId(TENANT_ID)
            .withManualTrigger("Start")
            .withApproval("Gate")
            .withStep("Branch B", "mock_tool")
            .withMerge("Join")
            .withStep("Final", "mock_tool")
            .edge("trigger:start", "core:gate")
            .edge("trigger:start", "mcp:branch_b")
            .edge("core:gate:approved", "core:join")
            .edge("mcp:branch_b", "core:join")
            .edge("core:join", "mcp:final")
            .buildAsMap();
    }

    /** Fork plan with TWO approval branches feeding the same merge. */
    private Map<String, Object> dualApprovalForkPlan() {
        return TestWorkflowPlan.create()
            .withTenantId(TENANT_ID)
            .withManualTrigger("Start")
            .withApproval("Gate A")
            .withApproval("Gate B")
            .withMerge("Join")
            .withStep("Final", "mock_tool")
            .edge("trigger:start", "core:gate_a")
            .edge("trigger:start", "core:gate_b")
            .edge("core:gate_a:approved", "core:join")
            .edge("core:gate_b:approved", "core:join")
            .edge("core:join", "mcp:final")
            .buildAsMap();
    }

    /**
     * Split fanning out N items, each gated by a PER-ITEM USER_APPROVAL, then a downstream
     * tool. The cross-pod hazard: the per-item split scope must survive a resume on an
     * instance that never held the in-memory SplitContext.
     *
     * <pre>
     * trigger:start → core:split (3 items) → core:gate (USER_APPROVAL, per item)
     *                                       → [approved] → mcp:notify (one execution per item)
     * </pre>
     */
    private Map<String, Object> splitApprovalNotifyPlan() {
        return TestWorkflowPlan.create()
            .withTenantId(TENANT_ID)
            .withManualTrigger("Start")
            .withSplit("Split", "[\"Alice\",\"Bob\",\"Charlie\"]")
            .withApproval("Gate")
            .withStep("Notify", "mock_tool")
            .edge("trigger:start", "core:split")
            .edge("core:split", "core:gate")
            .edge("core:gate:approved", "mcp:notify")
            .buildAsMap();
    }

    private static final class TestWorkflowPlan {
        private final List<Map<String, Object>> triggers = new java.util.ArrayList<>();
        private final List<Map<String, Object>> mcps = new java.util.ArrayList<>();
        private final List<Map<String, Object>> cores = new java.util.ArrayList<>();
        private final List<Map<String, Object>> edges = new java.util.ArrayList<>();
        private String tenantId = "test-tenant";

        static TestWorkflowPlan create() {
            return new TestWorkflowPlan();
        }

        TestWorkflowPlan withTenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        TestWorkflowPlan withManualTrigger(String label) {
            Map<String, Object> trigger = new java.util.LinkedHashMap<>();
            trigger.put("id", "t" + (triggers.size() + 1));
            trigger.put("type", "manual");
            trigger.put("label", label);
            trigger.put("strategy", "single");
            triggers.add(trigger);
            return this;
        }

        TestWorkflowPlan withStep(String label, String toolName) {
            Map<String, Object> step = new java.util.LinkedHashMap<>();
            step.put("id", "s" + (mcps.size() + 1));
            step.put("label", label);
            step.put("alias", normalize(label));
            step.put("tool_name", toolName);
            step.put("params", Map.of());
            mcps.add(step);
            return this;
        }

        TestWorkflowPlan withApproval(String label) {
            Map<String, Object> core = new java.util.LinkedHashMap<>();
            core.put("id", "c" + (cores.size() + 1));
            core.put("label", label);
            core.put("type", "approval");
            core.put("approval", Map.of());
            cores.add(core);
            return this;
        }

        TestWorkflowPlan withMerge(String label) {
            Map<String, Object> core = new java.util.LinkedHashMap<>();
            core.put("id", "c" + (cores.size() + 1));
            core.put("label", label);
            core.put("type", "merge");
            cores.add(core);
            return this;
        }

        /** Split node fanning out over a literal JSON list (e.g. {@code ["Alice","Bob","Charlie"]}). */
        TestWorkflowPlan withSplit(String label, String jsonListLiteral) {
            Map<String, Object> core = new java.util.LinkedHashMap<>();
            core.put("id", "c" + (cores.size() + 1));
            core.put("label", label);
            core.put("type", "split");
            core.put("list", "{{json('" + jsonListLiteral + "')}}");
            core.put("maxItems", 100);
            core.put("splitStrategy", "continue-anyway");
            cores.add(core);
            return this;
        }

        TestWorkflowPlan edge(String from, String to) {
            Map<String, Object> edge = new java.util.LinkedHashMap<>();
            edge.put("from", from);
            edge.put("to", to);
            edges.add(edge);
            return this;
        }

        Map<String, Object> buildAsMap() {
            Map<String, Object> plan = new java.util.LinkedHashMap<>();
            plan.put("id", "cross-instance-test-plan");
            plan.put("tenant_id", tenantId);
            plan.put("triggers", triggers);
            plan.put("mcps", mcps);
            plan.put("agents", List.of());
            plan.put("cores", cores);
            plan.put("tables", List.of());
            plan.put("edges", edges);
            plan.put("notes", List.of());
            plan.put("interfaces", List.of());
            return plan;
        }

        private static String normalize(String label) {
            return label.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_|_$", "")
                    .replaceAll("__+", "_");
        }
    }

    /** Runs phase 1 (the "instance A" traversal) to the yield point. */
    private void executeUntilYield(RunFixture fixture) throws Exception {
        executionServiceV2.executeWorkflow(
                fixture.plan(),
                List.of(Map.of("source", "cross-instance-test")),
                TENANT_ID,
                fixture.execution(),
                "trigger:start")
            .get(PHASE_TIMEOUT_S, TimeUnit.SECONDS);
    }

    /**
     * Simulates the instance boundary: purge every JVM-LOCAL run-scoped cache the
     * traversal populated. Redis-backed caches are kept - in a real cluster they
     * are shared infrastructure both instances see (see class javadoc).
     */
    private void simulateInstanceBoundary(String runId) {
        Set<String> jvmLocalCaches = Set.of(
            "V2StepByStepContextCache",      // in-memory SBS contexts
            "SplitContextCache",             // in-memory split contexts
            "MergeIntegrationCache",         // in-memory merge tracking
            "ItemMergeCache",                // in-memory per-item merge collector
            "ReadinessContextCache",         // in-memory readiness contexts
            "WorkflowCacheManager",          // in-memory plan/tree caches
            "PersistenceDeduplicationCache", // in-memory persistence dedup
            "BackEdgeClaimedCalls");         // in-memory loop claims
        runCacheRegistry.getAllCaches().stream()
            .filter(cache -> jvmLocalCaches.contains(cache.getCacheName()))
            .forEach(cache -> cache.cleanupRun(runId));
    }

    /** Builds a brand-new resume stack instance over the same persistence. */
    private SignalResumeService freshResumeInstance() {
        return applicationContext.getAutowireCapableBeanFactory()
            .createBean(SignalResumeService.class);
    }

    /**
     * Drives a resume exactly the way the production entry points do
     * ({@code onSignalResolved} / the Redis pub/sub listener): the run's org
     * scope is bound on the calling thread BEFORE {@code resumeAfterSignal}
     * (V261 org-scoped inserts fail-fast on unbound non-request threads).
     */
    private void resumeWithRunScope(SignalResumeService instance, SignalWaitEntity resolvedSignal) {
        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(
            ORGANIZATION_ID, () -> instance.resumeAfterSignal(resolvedSignal));
    }

    private SignalWaitEntity onlyActiveSignal(String runId, String nodeId) {
        List<SignalWaitEntity> active = signalWaitRepository.findActiveByRunId(runId);
        List<SignalWaitEntity> forNode = active.stream()
            .filter(s -> nodeId.equals(s.getNodeId())).toList();
        assertThat(forNode)
            .as("exactly one active signal for %s (all active: %s)", nodeId,
                active.stream().map(SignalWaitEntity::getNodeId).toList())
            .hasSize(1);
        return forNode.get(0);
    }

    private int completedCount(String runId, String normalizedKey) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workflow_step_data WHERE run_id=? AND normalized_key=? AND status='COMPLETED'",
            Integer.class, runId, normalizedKey);
        return count != null ? count : 0;
    }

    private void assertPhase1YieldState(String runId, List<String> pendingGates) {
        for (String gate : pendingGates) {
            onlyActiveSignal(runId, gate);
        }
        assertThat(completedCount(runId, "mcp:final"))
            .as("final node must NOT run while the approval is pending").isZero();
        assertThat(completedCount(runId, "core:join"))
            .as("merge must be left unexecuted at the traversal's root join "
                + "(predecessor awaiting signal) - resume owns it").isZero();
    }

    private void assertResumedExactlyOnce(String runId) {
        assertThat(completedCount(runId, "core:join"))
            .as("merge fires exactly once on resume").isEqualTo(1);
        assertThat(completedCount(runId, "mcp:final"))
            .as("post-merge node executes exactly once").isEqualTo(1);
        assertThat(signalWaitRepository.countActiveByRunId(runId))
            .as("no orphan PENDING/CLAIMED signals after resume").isZero();
        RunStatus status = runRepository.findByRunIdPublic(runId).orElseThrow().getStatus();
        assertThat(status)
            .as("run must settle in a healthy post-cycle state")
            .isIn(RunStatus.WAITING_TRIGGER, RunStatus.COMPLETED);
    }

    // =====================================================================
    // 1. Resume executes on a FRESH instance over shared persistence
    // =====================================================================

    @Test
    @Timeout(180)
    @DisplayName("fork: branch A yields on USER_APPROVAL while branch B completes; the approval resumes on a FRESH resume-stack instance - merge fires exactly once there, branch B is not re-executed, no orphan signals")
    void approvalResumesOnFreshInstanceAfterForkYield() throws Exception {
        RunFixture fixture = createCommittedRun(singleApprovalForkPlan());
        String runId = fixture.runId();

        // ---- Phase 1 - "instance A" traversal up to the yield ----
        executeUntilYield(fixture);

        assertPhase1YieldState(runId, List.of("core:gate"));
        assertThat(completedCount(runId, "mcp:branch_b"))
            .as("sibling branch B completed during the initial traversal").isEqualTo(1);
        SignalWaitEntity pending = onlyActiveSignal(runId, "core:gate");

        // ---- Instance boundary ----
        simulateInstanceBoundary(runId);

        // ---- Resolve the approval (HTTP-equivalent path). The in-context
        // singleton is a no-op mock: the AFTER_COMMIT listener and the Redis
        // pub/sub cross-instance notification both land on the mock, so the
        // resume below is provably the ONLY real one. ----
        boolean resolved = unifiedSignalService.resolveSignal(
            pending.getId(), SignalResolution.APPROVED, Map.of("comment", "lgtm"), "approver-1");
        assertThat(resolved).as("signal resolution claims the PENDING row").isTrue();

        // ---- Phase 2 - "instance B": fresh stack, same persistence ----
        SignalResumeService instanceB = freshResumeInstance();
        SignalWaitEntity resolvedSignal = signalWaitRepository.findById(pending.getId()).orElseThrow();
        resumeWithRunScope(instanceB, resolvedSignal);

        // ---- Invariants ----
        assertResumedExactlyOnce(runId);
        assertThat(completedCount(runId, "mcp:branch_b"))
            .as("branch B must NOT be re-executed by the resuming instance").isEqualTo(1);
        assertThat(completedCount(runId, "core:gate"))
            .as("the approval's resolution output is persisted exactly once").isEqualTo(1);
    }

    // =====================================================================
    // 1b. SPLIT per-item approval restored cross-instance - the downstream
    //     node fans out per item (regression: collapsed to 1 at replicas>=2).
    // =====================================================================

    @Test
    @Timeout(180)
    @DisplayName("split -> per-item USER_APPROVAL resumed on a FRESH instance (in-memory split context purged): the downstream node fans out once PER item (N), not collapsed to 1 - cross-pod split-scope restore from persisted split_item_data")
    void splitApprovalFansOutPerItemOnFreshInstance() throws Exception {
        RunFixture fixture = createCommittedRun(splitApprovalNotifyPlan());
        String runId = fixture.runId();

        // ---- Phase 1 ("instance A"): the split spawns 3 items, each yields a per-item approval ----
        executeUntilYield(fixture);

        List<SignalWaitEntity> gates = signalWaitRepository.findActiveByRunId(runId).stream()
            .filter(s -> "core:gate".equals(s.getNodeId()))
            .toList();
        assertThat(gates).as("one per-item USER_APPROVAL signal per split item").hasSize(3);
        assertThat(completedCount(runId, "mcp:notify"))
            .as("downstream must not run while approvals are pending").isZero();
        // The fix: each per-item signal persists the cross-pod restoration fields.
        assertThat(gates).allSatisfy(g ->
            assertThat(g.getSplitItemData())
                .as("split_item_data must carry splitNodeId+items for cross-pod restore")
                .containsKeys("splitNodeId", "items"));

        // ---- Instance boundary: drop the JVM-local split context (SplitContextCache),
        //      exactly like a DIFFERENT pod resolving the signal. The fresh instance must
        //      rebuild the split scope purely from the persisted split_item_data. ----
        simulateInstanceBoundary(runId);
        assertThat(splitContextManager.hasContexts(runId))
            .as("the in-memory split context is gone, as on a fresh pod").isFalse();

        // ---- Resolve all 3 per-item approvals, then resume each on a brand-new instance ----
        for (SignalWaitEntity gate : gates) {
            assertThat(unifiedSignalService.resolveSignal(
                gate.getId(), SignalResolution.APPROVED, Map.of(), "approver")).isTrue();
        }
        for (SignalWaitEntity gate : gates) {
            SignalResumeService instanceB = freshResumeInstance();
            resumeWithRunScope(instanceB, signalWaitRepository.findById(gate.getId()).orElseThrow());
        }

        // ---- THE invariant: the downstream node fanned out ONCE PER ITEM (3), not 1.
        //      Pre-fix the fresh instance could not rebuild the split scope (split_item_data
        //      lacked splitNodeId/items) so notify ran once; post-fix it restores and fans out. ----
        assertThat(completedCount(runId, "mcp:notify"))
            .as("downstream node runs once per split item after a cross-instance resume (1 pre-fix, 3 post-fix)")
            .isEqualTo(3);
        assertThat(signalWaitRepository.countActiveByRunId(runId))
            .as("no orphan signals after all per-item resumes").isZero();
    }

    // =====================================================================
    // 2. Two instances resuming concurrently - exactly-once via shared locks
    // =====================================================================

    @Test
    @Timeout(180)
    @DisplayName("two approvals in sibling branches resolved together, resumed CONCURRENTLY by two fresh instances - the Redis per-run lock serializes them and the merge still fires exactly once")
    void concurrentResumesOnTwoFreshInstancesAreSerialized() throws Exception {
        RunFixture fixture = createCommittedRun(dualApprovalForkPlan());
        String runId = fixture.runId();

        executeUntilYield(fixture);
        assertPhase1YieldState(runId, List.of("core:gate_a", "core:gate_b"));
        SignalWaitEntity signalA = onlyActiveSignal(runId, "core:gate_a");
        SignalWaitEntity signalB = onlyActiveSignal(runId, "core:gate_b");

        simulateInstanceBoundary(runId);

        assertThat(unifiedSignalService.resolveSignal(
            signalA.getId(), SignalResolution.APPROVED, Map.of(), "approver-a")).isTrue();
        assertThat(unifiedSignalService.resolveSignal(
            signalB.getId(), SignalResolution.APPROVED, Map.of(), "approver-b")).isTrue();

        // Two independent fresh instances, racing on the same run.
        SignalResumeService instance1 = freshResumeInstance();
        SignalResumeService instance2 = freshResumeInstance();
        SignalWaitEntity resolvedA = signalWaitRepository.findById(signalA.getId()).orElseThrow();
        SignalWaitEntity resolvedB = signalWaitRepository.findById(signalB.getId()).orElseThrow();

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            awaitQuietly(start);
            resumeWithRunScope(instance1, resolvedA);
        });
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> {
            awaitQuietly(start);
            resumeWithRunScope(instance2, resolvedB);
        });
        start.countDown();
        CompletableFuture.allOf(f1, f2).get(PHASE_TIMEOUT_S, TimeUnit.SECONDS);

        assertResumedExactlyOnce(runId);
        assertThat(completedCount(runId, "core:gate_a"))
            .as("gate A resolution output persisted exactly once").isEqualTo(1);
        assertThat(completedCount(runId, "core:gate_b"))
            .as("gate B resolution output persisted exactly once").isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start latch never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // =====================================================================
    // 3. Lock serialization - a resume cannot interleave with a lock holder
    // =====================================================================

    @Test
    @Timeout(180)
    @DisplayName("while another instance holds the per-run signal-resume lock (sibling work mid-flight), a fresh instance's resume blocks WITHOUT touching the DB; it proceeds only after the lock is released")
    void resumeBlocksWhilePerRunLockIsHeldByAnotherInstance() throws Exception {
        RunFixture fixture = createCommittedRun(singleApprovalForkPlan());
        String runId = fixture.runId();

        executeUntilYield(fixture);
        SignalWaitEntity pending = onlyActiveSignal(runId, "core:gate");
        simulateInstanceBoundary(runId);

        assertThat(unifiedSignalService.resolveSignal(
            pending.getId(), SignalResolution.APPROVED, Map.of(), "approver-1")).isTrue();

        // "Instance A" holds the per-run resume lock (as it would while driving
        // its own resume / sibling work for this run).
        String lockKey = RedisCacheKeys.lockSignalResume(runId);
        Boolean lockTaken = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "instance-A-holder", Duration.ofMinutes(2));
        assertThat(lockTaken).isTrue();

        SignalResumeService instanceB = freshResumeInstance();
        SignalWaitEntity resolvedSignal = signalWaitRepository.findById(pending.getId()).orElseThrow();
        Thread resumer = new Thread(
            () -> resumeWithRunScope(instanceB, resolvedSignal), "xinst-resumer");
        try {
            resumer.start();

            // Wait (bounded) until the resumer is provably parked in the lock
            // acquisition spin-wait (Thread.sleep between SETNX attempts).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean observedBlocked = false;
            while (System.nanoTime() < deadline) {
                Thread.State state = resumer.getState();
                if (state == Thread.State.TIMED_WAITING || state == Thread.State.WAITING) {
                    observedBlocked = true;
                    break;
                }
                if (state == Thread.State.TERMINATED) {
                    break; // would mean it never blocked - asserted below
                }
                Thread.onSpinWait();
            }
            assertThat(observedBlocked)
                .as("the resume must park in the per-run lock spin-wait while the lock is held")
                .isTrue();

            // While the lock is held, the resume has made NO persistent progress:
            // the entire persist→ready→execute flow sits INSIDE the lock.
            assertThat(completedCount(runId, "core:gate"))
                .as("no signal-resolution output may be persisted while the lock is held").isZero();
            assertThat(completedCount(runId, "core:join"))
                .as("merge must not execute while the lock is held").isZero();
            assertThat(completedCount(runId, "mcp:final"))
                .as("downstream must not execute while the lock is held").isZero();

            // Release the lock - instance B's spin-wait acquires it and completes.
            redisTemplate.delete(lockKey);
            resumer.join(TimeUnit.SECONDS.toMillis(PHASE_TIMEOUT_S));
            assertThat(resumer.isAlive())
                .as("resume must complete after the lock is released").isFalse();
        } finally {
            redisTemplate.delete(lockKey);
            if (resumer.isAlive()) {
                resumer.interrupt();
                resumer.join(5_000);
            }
        }

        assertResumedExactlyOnce(runId);
        assertThat(completedCount(runId, "mcp:branch_b")).isEqualTo(1);
    }
}
