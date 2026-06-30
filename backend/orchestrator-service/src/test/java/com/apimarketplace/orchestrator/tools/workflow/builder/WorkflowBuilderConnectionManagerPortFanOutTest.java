package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule: a single NAMED OUTPUT PORT may connect to at most ONE target node
 * ("one port = one node"). To fan a single port out to several nodes in
 * parallel, the agent must insert a Fork node. The builder UI already enforces
 * this in {@code connectionValidator.ts}, but the agent/MCP path
 * ({@link WorkflowBuilderConnectionManager#executeConnect}) only rejected an
 * EXACT duplicate (same from→same to). A second edge from the SAME port to a
 * DIFFERENT target slipped through, producing a structurally invalid graph
 * (two successors hanging off one branch port).
 *
 * <p>This test pins the rule across ALL EIGHT ported node types - decision,
 * switch, loop, fork, option, approval, classify, guardrail - and proves the
 * complementary non-regressions: distinct ports still fan out, implicit fork
 * from a trigger / a plain step is untouched, and the exact-duplicate message
 * is preserved.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderConnectionManager - one output port = one target")
class WorkflowBuilderConnectionManagerPortFanOutTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        connectionManager = new WorkflowBuilderConnectionManager(sessionStore);
    }

    // ==================== Fixtures ====================

    private WorkflowBuilderSession createSession() {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                .sessionId("test")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        // Targets every test connects into.
        addMcp(session, "Step A");
        addMcp(session, "Step B");
        return session;
    }

    private void addMcp(WorkflowBuilderSession session, String label) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "mcp:" + WorkflowBuilderSession.normalizeLabel(label));
        node.put("type", "mcp");
        node.put("label", label);
        session.getMcps().add(node);
    }

    private void addTrigger(WorkflowBuilderSession session, String label) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "trigger:" + WorkflowBuilderSession.normalizeLabel(label));
        node.put("type", "manual");
        node.put("label", label);
        session.getTriggers().add(node);
    }

    /** Adds a core node, optionally with a port-config list (decisionConditions/switchCases/...). */
    private void addCore(WorkflowBuilderSession session, String type, String label,
                         String configKey, List<Map<String, Object>> config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:" + WorkflowBuilderSession.normalizeLabel(label));
        node.put("type", type);
        node.put("label", label);
        if (configKey != null) {
            node.put(configKey, config);
        }
        session.getCores().add(node);
    }

    /** Adds an agent step (classify/guardrail) to the mcps list with isAgent=true. */
    private void addAgent(WorkflowBuilderSession session, String label,
                          String flagKey, String outputsKey, List<Map<String, Object>> outputs) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "agent:" + WorkflowBuilderSession.normalizeLabel(label));
        node.put("type", "agent");
        node.put("label", label);
        node.put("isAgent", true);
        node.put(flagKey, true);
        node.put(outputsKey, outputs);
        session.getMcps().add(node);
    }

    private static List<Map<String, Object>> conds(String... types) {
        return java.util.Arrays.stream(types)
                .map(t -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("type", t); return m; })
                .map(m -> (Map<String, Object>) m)
                .toList();
    }

    private static List<Map<String, Object>> outputs(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("label", "out" + i); return (Map<String, Object>) m; })
                .toList();
    }

    private ToolExecutionResult connect(WorkflowBuilderSession session, String from, String to) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from", from);
        args.put("to", to);
        return connectionManager.executeConnect(session, args);
    }

    /** Asserts the second edge from the same port is rejected with a fork-hint, and not created. */
    private void assertSecondEdgeFromSamePortRejected(WorkflowBuilderSession session,
                                                      String portRef, String baseNodeId) {
        ToolExecutionResult first = connect(session, portRef, "Step A");
        assertThat(first.success()).as("first edge from %s must succeed", portRef).isTrue();

        ToolExecutionResult second = connect(session, portRef, "Step B");
        assertThat(second.success())
                .as("second edge from the SAME port %s must be rejected", portRef)
                .isFalse();
        assertThat(second.error())
                .as("rejection must name the port and hint at inserting a Fork")
                .containsIgnoringCase("port")
                .containsIgnoringCase("fork");

        // Only the first edge survives - the port still points at exactly one target.
        long fromThisPort = session.getEdges().stream()
                .filter(e -> portRefResolved(session, portRef).equals(e.get("from")))
                .count();
        assertThat(fromThisPort).as("port %s must keep exactly one outgoing edge", portRef).isEqualTo(1);
    }

    private String portRefResolved(WorkflowBuilderSession session, String portRef) {
        return session.resolveNodeReference(portRef);
    }

    // ==================== One rejection test per ported node type ====================

    @Nested
    @DisplayName("rejects a 2nd edge from an already-wired port")
    class RejectsSecondEdge {

        @Test
        @DisplayName("decision (if)")
        void decision() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "decision", "Check", "decisionConditions", conds("if", "else"));
            assertSecondEdgeFromSamePortRejected(s, "Check:if", "core:check");
        }

        @Test
        @DisplayName("switch (case_0)")
        void switchCase() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "switch", "Router", "switchCases", conds("case", "default"));
            assertSecondEdgeFromSamePortRejected(s, "Router:case_0", "core:router");
        }

        @Test
        @DisplayName("loop (body)")
        void loopBody() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "loop", "Repeat", null, null);
            assertSecondEdgeFromSamePortRejected(s, "Repeat:body", "core:repeat");
        }

        @Test
        @DisplayName("fork (branch_0)")
        void forkBranch() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "fork", "Parallel", "forkOutputs", outputs(2));
            assertSecondEdgeFromSamePortRejected(s, "Parallel:branch_0", "core:parallel");
        }

        @Test
        @DisplayName("option (choice_0)")
        void optionChoice() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "option", "Pick", "optionChoices", outputs(2));
            assertSecondEdgeFromSamePortRejected(s, "Pick:choice_0", "core:pick");
        }

        @Test
        @DisplayName("approval (approved)")
        void approval() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "approval", "Review", null, null);
            assertSecondEdgeFromSamePortRejected(s, "Review:approved", "core:review");
        }

        @Test
        @DisplayName("classify (category_0)")
        void classify() {
            WorkflowBuilderSession s = createSession();
            addAgent(s, "Sort", "isClassify", "classifyOutputs", outputs(2));
            assertSecondEdgeFromSamePortRejected(s, "Sort:category_0", "agent:sort");
        }

        @Test
        @DisplayName("guardrail (pass)")
        void guardrail() {
            WorkflowBuilderSession s = createSession();
            addAgent(s, "Gate", "isGuardrail", "guardrailOutputs", outputs(2));
            assertSecondEdgeFromSamePortRejected(s, "Gate:pass", "agent:gate");
        }
    }

    // ==================== Non-regressions ====================

    @Test
    @DisplayName("distinct ports on the same node still fan out (if→A, else→B)")
    void distinctPortsAllowed() {
        WorkflowBuilderSession s = createSession();
        addCore(s, "decision", "Check", "decisionConditions", conds("if", "else"));

        assertThat(connect(s, "Check:if", "Step A").success()).isTrue();
        assertThat(connect(s, "Check:else", "Step B").success())
                .as("a DIFFERENT port of the same node must remain connectable")
                .isTrue();
        assertThat(s.getEdges()).hasSize(2);
    }

    @Test
    @DisplayName("fork distinct branches still allowed (branch_0→A, branch_1→B)")
    void forkDistinctBranchesAllowed() {
        WorkflowBuilderSession s = createSession();
        addCore(s, "fork", "Parallel", "forkOutputs", outputs(2));

        assertThat(connect(s, "Parallel:branch_0", "Step A").success()).isTrue();
        assertThat(connect(s, "Parallel:branch_1", "Step B").success()).isTrue();
        assertThat(s.getEdges()).hasSize(2);
    }

    @Test
    @DisplayName("auto-assigned ports (no explicit port) distribute across branches, not rejected")
    void autoAssignDistributesAcrossPorts() {
        WorkflowBuilderSession s = createSession();
        addCore(s, "fork", "Parallel", "forkOutputs", outputs(2));

        // No port qualifier - the connect action auto-assigns branch_0 then branch_1.
        assertThat(connect(s, "Parallel", "Step A").success()).isTrue();
        assertThat(connect(s, "Parallel", "Step B").success())
                .as("no-port connects must auto-distribute, never collide on one port")
                .isTrue();
        assertThat(s.getEdges()).hasSize(2);
    }

    @Test
    @DisplayName("implicit fork from a trigger is preserved (trigger→A, trigger→B both allowed)")
    void triggerImplicitForkAllowed() {
        WorkflowBuilderSession s = createSession();
        addTrigger(s, "Start");

        assertThat(connect(s, "Start", "Step A").success()).isTrue();
        assertThat(connect(s, "Start", "Step B").success())
                .as("triggers have no named port - implicit fork (parallel) stays allowed")
                .isTrue();
        assertThat(s.getEdges()).hasSize(2);
    }

    @Test
    @DisplayName("implicit fork from a plain step is preserved (step→A, step→B both allowed)")
    void plainStepImplicitForkAllowed() {
        WorkflowBuilderSession s = createSession();
        addMcp(s, "Fetch");

        assertThat(connect(s, "Fetch", "Step A").success()).isTrue();
        assertThat(connect(s, "Fetch", "Step B").success())
                .as("a plain step has no named port - implicit fork stays allowed")
                .isTrue();
        assertThat(s.getEdges()).hasSize(2);
    }

    @Test
    @DisplayName("exact duplicate (same port → same target) still gives the existing already-exists message, not the fork hint")
    void exactDuplicateStillReported() {
        WorkflowBuilderSession s = createSession();
        addCore(s, "decision", "Check", "decisionConditions", conds("if", "else"));

        assertThat(connect(s, "Check:if", "Step A").success()).isTrue();
        ToolExecutionResult dup = connect(s, "Check:if", "Step A");
        assertThat(dup.success()).isFalse();
        assertThat(dup.error()).containsIgnoringCase("already exists");
        assertThat(s.getEdges()).hasSize(1);
    }
}
