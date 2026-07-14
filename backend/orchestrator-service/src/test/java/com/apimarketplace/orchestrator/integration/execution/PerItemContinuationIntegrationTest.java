package com.apimarketplace.orchestrator.integration.execution;

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
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * APPROVAL PER-ITEM CONTINUATION - full in-process resolve→walk→seal→merge cycle.
 *
 * <p>Codifies the live-verified e2e scenario of
 * {@code the project docs} as a CI-run integration test.
 * The plan mirrors the e2e shape:
 *
 * <pre>
 * trigger:start → core:split (3 items) → core:gate (USER_APPROVAL, continuationMode=per_item)
 *                     ├─[approved]→ mcp:work ──→ core:join → mcp:final
 *                     └─[rejected]──────────────→ core:join
 * </pre>
 *
 * <p><b>The behavior under test:</b> with {@code continuationMode=per_item}, EACH
 * per-item approval resolution immediately walks THAT item through its downstream
 * chain of plain per-item nodes ({@code mcp:work}), while the barrier moves to the
 * first cross-item consumer ({@code core:join}), which still waits for ALL items.
 * The LAST resolution seals the walked region (node-level EpochState mark exactly
 * once + per-item SKIPPED rows for unrouted items) and only then lets the normal
 * ready-loop fire the merge and everything after it, exactly like all_items mode.
 *
 * <p><b>Fixture (mirrors {@link CrossInstanceSignalResumeIntegrationTest}):</b> the
 * production auto-resume is {@code @Async} on an AFTER_COMMIT event, which is
 * non-deterministic in a test. The context singleton {@link SignalResumeService} is
 * therefore replaced with a no-op {@code @MockitoBean} and each resume is driven
 * synchronously on a fresh {@code createBean} instance under the run's org scope -
 * the exact call the production listener makes, minus the async hop. All state the
 * assertions read (workflow_step_data rows, signal_waits) is DB state, not mocks.
 *
 * <p>Not {@code @Transactional} on purpose: the engine executes split fan-out on
 * pool workers whose transactions are independent of the test thread. Each test
 * uses a unique runId; H2 is per-JVM; Redis comes from Testcontainers so a clean
 * checkout does not require a machine-local Redis daemon.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@DisplayName("Approval continuationMode=per_item in a split - resolve→walk→seal→merge")
@Testcontainers(disabledWithoutDocker = true)
class PerItemContinuationIntegrationTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORGANIZATION_ID = "org-1";
    private static final long PHASE_TIMEOUT_S = 90;
    private static final int REDIS_PORT = 6379;
    private static final int SPLIT_ITEM_COUNT = 3;

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
    @Autowired private ApplicationContext applicationContext;

    /**
     * Replaces the singleton so the in-context auto-resume paths (AFTER_COMMIT
     * async event listener + Redis pub/sub listener) are no-ops - every resume in
     * this test is the explicitly-driven, synchronous one, so each intermediate
     * assertion observes exactly one resolution's worth of progress.
     */
    @MockitoBean
    private SignalResumeService inContextResumeSingleton;

    /**
     * The auth-service credit endpoint is unreachable in tests, which makes the
     * real budget cache resolve to 0 credits and fail every node. Billing
     * exactness is covered elsewhere - here the budget gate must simply pass.
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
     * Persists workflow + run rows (committed - pool workers and the resume path
     * load them) and builds the execution object the way the execution controller
     * does.
     */
    private RunFixture createCommittedRun(Map<String, Object> planMap) {
        WorkflowEntity workflow = new WorkflowEntity(TENANT_ID, "Per-item continuation workflow", "user-1");
        workflow.setId(UUID.randomUUID());
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        workflow.setOrganizationId(ORGANIZATION_ID);
        workflowRepository.save(workflow);

        String runId = "run-peritem-" + UUID.randomUUID().toString().substring(0, 8);
        WorkflowRunEntity run = new WorkflowRunEntity(workflow, TENANT_ID, runId, null, null, "user-1");
        run.setStatus(RunStatus.RUNNING);
        run.setOrganizationId(ORGANIZATION_ID);
        run.setPlan(planMap);
        runRepository.save(run);

        WorkflowPlan plan = WorkflowPlan.fromMap(planMap, workflow.getId().toString(), TENANT_ID);
        WorkflowExecution execution = new WorkflowExecution(runId, plan, Map.of());
        execution.setWorkflowRunId(run.getId());
        execution.setDisplayName("Per-item continuation workflow");
        return new RunFixture(runId, plan, execution);
    }

    /**
     * The e2e plan shape (the project docs): a 3-item split
     * gated by a per-item approval, approved items flowing through a plain per-item
     * node into the split merge, rejected items rejoining the merge directly.
     *
     * @param approvalConfig the approval block ({@code Map.of()} = all_items default;
     *                       {@code continuationMode=per_item} opts into the feature)
     */
    private Map<String, Object> splitApprovalWorkJoinFinalPlan(Map<String, Object> approvalConfig) {
        return TestWorkflowPlan.create()
            .withTenantId(TENANT_ID)
            .withManualTrigger("Start")
            .withSplit("Split", "[\"Alice\",\"Bob\",\"Charlie\"]")
            .withApproval("Gate", approvalConfig)
            .withStep("Work", "mock_tool")
            .withMerge("Join")
            .withStep("Final", "mock_tool")
            .edge("trigger:start", "core:split")
            .edge("core:split", "core:gate")
            .edge("core:gate:approved", "mcp:work")
            .edge("mcp:work", "core:join")
            .edge("core:gate:rejected", "core:join")
            .edge("core:join", "mcp:final")
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

        /** Approval node; {@code approvalConfig} is the plan's {@code approval} block. */
        TestWorkflowPlan withApproval(String label, Map<String, Object> approvalConfig) {
            Map<String, Object> core = new java.util.LinkedHashMap<>();
            core.put("id", "c" + (cores.size() + 1));
            core.put("label", label);
            core.put("type", "approval");
            core.put("approval", approvalConfig);
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
            plan.put("id", "per-item-continuation-test-plan");
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

    /** Runs the initial traversal to the yield point (3 pending per-item approvals). */
    private void executeUntilYield(RunFixture fixture) throws Exception {
        executionServiceV2.executeWorkflow(
                fixture.plan(),
                List.of(Map.of("source", "per-item-continuation-test")),
                TENANT_ID,
                fixture.execution(),
                "trigger:start")
            .get(PHASE_TIMEOUT_S, TimeUnit.SECONDS);
    }

    /** The pending gate signal for one specific split item. */
    private SignalWaitEntity gateSignalForItem(String runId, String itemId) {
        List<SignalWaitEntity> matching = signalWaitRepository.findActiveByRunId(runId).stream()
            .filter(s -> "core:gate".equals(s.getNodeId()) && itemId.equals(s.getItemId()))
            .toList();
        assertThat(matching)
            .as("exactly one pending gate signal for item %s", itemId)
            .hasSize(1);
        return matching.get(0);
    }

    /**
     * Resolves one per-item signal and drives its resume the way the production
     * AFTER_COMMIT listener does (fresh instance = no interference from the mocked
     * singleton; org scope bound because V261 org-scoped inserts fail-fast on
     * unbound non-request threads).
     */
    private void resolveAndResume(String runId, String itemId, SignalResolution resolution) {
        SignalWaitEntity pending = gateSignalForItem(runId, itemId);
        boolean resolved = unifiedSignalService.resolveSignal(
            pending.getId(), resolution, Map.of(), "approver-1");
        assertThat(resolved)
            .as("signal resolution claims the PENDING row for item %s", itemId)
            .isTrue();
        SignalResumeService resumeInstance = applicationContext.getAutowireCapableBeanFactory()
            .createBean(SignalResumeService.class);
        SignalWaitEntity resolvedSignal = signalWaitRepository.findById(pending.getId()).orElseThrow();
        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(
            ORGANIZATION_ID, () -> resumeInstance.resumeAfterSignal(resolvedSignal));
    }

    private int completedCount(String runId, String normalizedKey) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workflow_step_data WHERE run_id=? AND normalized_key=? AND status='COMPLETED'",
            Integer.class, runId, normalizedKey);
        return count != null ? count : 0;
    }

    /** Per-item rows of one node as {@code item_index -> status} (fails on duplicate rows per item). */
    private Map<Integer, String> itemStatuses(String runId, String normalizedKey) {
        Map<Integer, String> byItem = new java.util.TreeMap<>();
        jdbcTemplate.query(
            "SELECT item_index, status FROM workflow_step_data WHERE run_id=? AND normalized_key=? ORDER BY item_index",
            rs -> {
                int itemIndex = rs.getInt(1);
                String previous = byItem.put(itemIndex, rs.getString(2));
                assertThat(previous)
                    .as("at most one %s row per item (duplicate for item %s)", normalizedKey, itemIndex)
                    .isNull();
            },
            runId, normalizedKey);
        return byItem;
    }

    private long pendingGateSignals(String runId) {
        return signalWaitRepository.findActiveByRunId(runId).stream()
            .filter(s -> "core:gate".equals(s.getNodeId()))
            .count();
    }

    private void assertPhase1YieldState(String runId) {
        assertThat(pendingGateSignals(runId))
            .as("one pending USER_APPROVAL signal per split item").isEqualTo(SPLIT_ITEM_COUNT);
        assertThat(itemStatuses(runId, "mcp:work"))
            .as("no work row may exist while every approval is pending").isEmpty();
        assertThat(completedCount(runId, "core:join"))
            .as("merge must not execute while approvals are pending").isZero();
        assertThat(completedCount(runId, "mcp:final"))
            .as("final must not execute while approvals are pending").isZero();
    }

    /**
     * The sealed end state both modes must converge to, row for row: item 0 and 2
     * approved (work COMPLETED), item 1 rejected (work SKIPPED at the barrier
     * seal / fan-out skip parity), merge fired once, final fired once, no pending
     * signals, run settled healthy.
     */
    private void assertSealedEndState(String runId) {
        assertThat(itemStatuses(runId, "mcp:work"))
            .as("work rows after the seal: approved items COMPLETED, rejected item SKIPPED")
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                0, "COMPLETED",
                1, "SKIPPED",
                2, "COMPLETED"));
        assertThat(completedCount(runId, "core:join"))
            .as("merge fires exactly once, after ALL items resolved").isEqualTo(1);
        assertThat(completedCount(runId, "mcp:final"))
            .as("post-merge node executes exactly once").isEqualTo(1);
        assertThat(signalWaitRepository.countActiveByRunId(runId))
            .as("no orphan PENDING/CLAIMED signals after the last resolution").isZero();
        RunStatus status = runRepository.findByRunIdPublic(runId).orElseThrow().getStatus();
        assertThat(status)
            .as("run must settle in a healthy post-cycle state")
            .isIn(RunStatus.WAITING_TRIGGER, RunStatus.COMPLETED);
    }

    // =====================================================================
    // 1. per_item: each resolution walks its own item; the LAST one seals
    // =====================================================================

    @Test
    @Timeout(180)
    @DisplayName("per_item: each approval resolution immediately executes THAT item's downstream chain while the merge barrier holds; the last resolution seals the region (SKIPPED row for the rejected item) and fires merge + final exactly once")
    void perItemContinuationWalksEachItemImmediatelyAndSealsOnLastResolution() throws Exception {
        // ---- Arrange: run the split to the yield (3 pending per-item approvals) ----
        RunFixture fixture = createCommittedRun(splitApprovalWorkJoinFinalPlan(
            Map.of("continuationMode", "per_item")));
        String runId = fixture.runId();
        executeUntilYield(fixture);
        assertPhase1YieldState(runId);

        // ---- Act 1: approve item 0 (first resolution - NOT the last) ----
        resolveAndResume(runId, "0", SignalResolution.APPROVED);

        // ---- Assert 1: THE feature - item 0 continued immediately, barrier intact ----
        assertThat(itemStatuses(runId, "mcp:work"))
            .as("the walk executes exactly the newly-approved item, before siblings resolve "
                + "(all_items would defer everything until the last resolution)")
            .containsExactlyInAnyOrderEntriesOf(Map.of(0, "COMPLETED"));
        assertThat(completedCount(runId, "core:join"))
            .as("the barrier MOVED to the merge - it must still hold").isZero();
        assertThat(completedCount(runId, "mcp:final"))
            .as("nothing past the merge may run mid-barrier").isZero();
        assertThat(pendingGateSignals(runId))
            .as("the two sibling approvals are still pending").isEqualTo(2);

        // ---- Act 2: reject item 1 (routes to the merge directly - no walkable work) ----
        resolveAndResume(runId, "1", SignalResolution.REJECTED);

        // ---- Assert 2: a rejected item adds NO work row and still holds the barrier ----
        assertThat(itemStatuses(runId, "mcp:work"))
            .as("a rejected item is not routed through the approved-port node")
            .containsExactlyInAnyOrderEntriesOf(Map.of(0, "COMPLETED"));
        assertThat(completedCount(runId, "core:join"))
            .as("merge still waits for the last sibling").isZero();
        assertThat(pendingGateSignals(runId))
            .as("one approval left").isEqualTo(1);

        // ---- Act 3: approve item 2 (LAST resolution - walk + seal + frontier) ----
        resolveAndResume(runId, "2", SignalResolution.APPROVED);

        // ---- Assert 3: sealed end state (walked rows kept, rejected item SKIPPED,
        //      merge + final exactly once, no orphan signals) ----
        assertSealedEndState(runId);
    }

    // =====================================================================
    // 2. all_items default: barrier regression pin + end-state equivalence
    // =====================================================================

    @Test
    @Timeout(180)
    @DisplayName("all_items (no continuationMode): the first approval produces ZERO downstream rows - the pre-feature barrier defers everything to the last resolution, and the sealed end state is row-for-row identical to per_item mode")
    void allItemsDefaultKeepsBarrierAndConvergesToSameEndState() throws Exception {
        // ---- Arrange: SAME plan, approval block without continuationMode ----
        RunFixture fixture = createCommittedRun(splitApprovalWorkJoinFinalPlan(Map.of()));
        String runId = fixture.runId();
        executeUntilYield(fixture);
        assertPhase1YieldState(runId);

        // ---- Act 1: approve item 0 ----
        resolveAndResume(runId, "0", SignalResolution.APPROVED);

        // ---- Assert 1: the regression pin - NO per-item walk in default mode ----
        assertThat(itemStatuses(runId, "mcp:work"))
            .as("all_items must defer ALL successor execution until the last sibling "
                + "resolves - a work row here means per_item behavior leaked into the default")
            .isEmpty();
        assertThat(completedCount(runId, "core:join")).isZero();
        assertThat(pendingGateSignals(runId)).isEqualTo(2);

        // ---- Act 2: reject item 1 - still fully deferred ----
        resolveAndResume(runId, "1", SignalResolution.REJECTED);
        assertThat(itemStatuses(runId, "mcp:work"))
            .as("still zero rows before the last resolution").isEmpty();
        assertThat(pendingGateSignals(runId)).isEqualTo(1);

        // ---- Act 3: approve item 2 - the single fan-out runs everything ----
        resolveAndResume(runId, "2", SignalResolution.APPROVED);

        // ---- Assert 3: row-for-row the SAME end state the per_item test asserts ----
        assertSealedEndState(runId);
    }
}
