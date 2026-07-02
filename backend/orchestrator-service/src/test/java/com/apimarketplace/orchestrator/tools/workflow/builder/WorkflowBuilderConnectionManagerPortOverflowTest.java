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
 * Regression for the branch-port OVERFLOW hole: connecting one more edge than
 * a branching node DECLARES used to emit the out-of-range port with only a
 * log.warn (fork branch_N, option choice_N, classify category_N) or fall
 * through to a port-less edge (switch). The declared-vs-wired desync survived
 * every builder check and broke at RUNTIME: the undeclared port collapsed onto
 * a declared index, hanging two successors off one branch while another branch
 * never fired (the "Dispatch fork: 3 declared branches, 4 wired agents" bug).
 *
 * <p>Fixed semantics, pinned here:
 * <ul>
 *   <li>FORK auto-extends its declaration (branches are anonymous parallel
 *       lanes - same pattern as decision's elseif auto-expansion), so declared
 *       always equals wired.</li>
 *   <li>OPTION / CLASSIFY / SWITCH outputs carry runtime semantics (a
 *       user-facing choice, an LLM routing category, a case condition) that
 *       cannot be invented: the connect is REFUSED with an actionable error
 *       and no edge is created.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderConnectionManager - declared-port overflow")
class WorkflowBuilderConnectionManagerPortOverflowTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        connectionManager = new WorkflowBuilderConnectionManager(sessionStore);
    }

    // ==================== Fixtures (mirrors PortFanOutTest) ====================

    private WorkflowBuilderSession createSession() {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                .sessionId("test")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        addMcp(session, "Step A");
        addMcp(session, "Step B");
        addMcp(session, "Step C");
        addMcp(session, "Step D");
        return session;
    }

    private void addMcp(WorkflowBuilderSession session, String label) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "mcp:" + WorkflowBuilderSession.normalizeLabel(label));
        node.put("type", "mcp");
        node.put("label", label);
        session.getMcps().add(node);
    }

    private Map<String, Object> addCore(WorkflowBuilderSession session, String type, String label,
                                        String configKey, List<Map<String, Object>> config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:" + WorkflowBuilderSession.normalizeLabel(label));
        node.put("type", type);
        node.put("label", label);
        if (configKey != null) {
            node.put(configKey, config);
        }
        session.getCores().add(node);
        return node;
    }

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
                .map(t -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("type", t); return (Map<String, Object>) m; })
                .toList();
    }

    /** Deliberately an IMMUTABLE list, like a plan imported via set_plan. */
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

    // ==================== Fork: declaration auto-extends ====================

    @Nested
    @DisplayName("fork overflow auto-extends the declaration (declared == wired, always)")
    class ForkAutoExtends {

        @Test
        @DisplayName("the Dispatch bug: 4th connect on a 3-branch fork extends forkOutputs to 4 and wires branch_3")
        @SuppressWarnings("unchecked")
        void fourthConnectExtendsThreeBranchFork() {
            WorkflowBuilderSession s = createSession();
            Map<String, Object> fork = addCore(s, "fork", "Dispatch", "forkOutputs", outputs(3));

            assertThat(connect(s, "Dispatch", "Step A").success()).isTrue();
            assertThat(connect(s, "Dispatch", "Step B").success()).isTrue();
            assertThat(connect(s, "Dispatch", "Step C").success()).isTrue();
            ToolExecutionResult fourth = connect(s, "Dispatch", "Step D");

            assertThat(fourth.success())
                    .as("4th no-port connect must succeed by extending the declaration")
                    .isTrue();
            List<Map<String, Object>> declared = (List<Map<String, Object>>) fork.get("forkOutputs");
            assertThat(declared).as("forkOutputs must now DECLARE the 4th branch").hasSize(4);
            assertThat(s.getEdges())
                    .extracting(e -> (String) e.get("from"))
                    .containsExactly("core:dispatch:branch_0", "core:dispatch:branch_1",
                            "core:dispatch:branch_2", "core:dispatch:branch_3");
        }

        @Test
        @DisplayName("extension works on an immutable forkOutputs list (set_plan import) by replacing it")
        @SuppressWarnings("unchecked")
        void extensionReplacesImmutableList() {
            WorkflowBuilderSession s = createSession();
            Map<String, Object> fork = addCore(s, "fork", "Par", "forkOutputs", List.of());

            assertThat(connect(s, "Par", "Step A").success()).isTrue();

            List<Map<String, Object>> declared = (List<Map<String, Object>>) fork.get("forkOutputs");
            assertThat(declared).hasSize(1);
            assertThat(s.getEdges()).extracting(e -> (String) e.get("from"))
                    .containsExactly("core:par:branch_0");
        }

        @Test
        @DisplayName("extended entries follow the creator's shape (id + human label)")
        @SuppressWarnings("unchecked")
        void extendedEntriesHaveIdAndLabel() {
            WorkflowBuilderSession s = createSession();
            Map<String, Object> fork = addCore(s, "fork", "Par", "forkOutputs", outputs(1));

            assertThat(connect(s, "Par", "Step A").success()).isTrue();
            assertThat(connect(s, "Par", "Step B").success()).isTrue();

            List<Map<String, Object>> declared = (List<Map<String, Object>>) fork.get("forkOutputs");
            assertThat(declared).hasSize(2);
            assertThat(declared.get(1))
                    .containsEntry("id", "core:par-output-1")
                    .containsEntry("label", "Branch 2");
        }
    }

    // ==================== Option / Switch / Classify: loud refusal ====================

    @Nested
    @DisplayName("semantic outputs refuse overflow with an actionable error (no edge created)")
    class SemanticOutputsRefuse {

        @Test
        @DisplayName("option: 3rd connect on 2 declared choices fails and names the fix")
        void optionOverflowRefused() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "option", "Pick", "optionChoices", outputs(2));

            assertThat(connect(s, "Pick", "Step A").success()).isTrue();
            assertThat(connect(s, "Pick", "Step B").success()).isTrue();
            ToolExecutionResult third = connect(s, "Pick", "Step C");

            assertThat(third.success()).isFalse();
            assertThat(third.error())
                    .containsIgnoringCase("choice")
                    .contains("modify");
            assertThat(s.getEdges()).as("no out-of-range edge may be created").hasSize(2);
        }

        @Test
        @DisplayName("switch: overflow no longer falls through to a port-less edge - it fails")
        void switchOverflowRefused() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "switch", "Route", "switchCases", conds("case", "default"));

            assertThat(connect(s, "Route", "Step A").success()).isTrue();
            assertThat(connect(s, "Route", "Step B").success()).isTrue();
            ToolExecutionResult third = connect(s, "Route", "Step C");

            assertThat(third.success()).isFalse();
            assertThat(third.error())
                    .containsIgnoringCase("case")
                    .contains("modify");
            assertThat(s.getEdges()).hasSize(2);
            assertThat(s.getEdges())
                    .as("pre-fix regression shape: no port-less edge from the switch")
                    .noneMatch(e -> "core:route".equals(e.get("from")));
        }

        @Test
        @DisplayName("classify: 3rd connect on 2 declared categories fails and names the fix")
        void classifyOverflowRefused() {
            WorkflowBuilderSession s = createSession();
            addAgent(s, "Sort", "isClassify", "classifyOutputs", outputs(2));

            assertThat(connect(s, "Sort", "Step A").success()).isTrue();
            assertThat(connect(s, "Sort", "Step B").success()).isTrue();
            ToolExecutionResult third = connect(s, "Sort", "Step C");

            assertThat(third.success()).isFalse();
            assertThat(third.error())
                    .containsIgnoringCase("categor")
                    .contains("modify");
            assertThat(s.getEdges()).hasSize(2);
        }
    }

    // ==================== Non-regressions ====================

    @Test
    @DisplayName("in-range auto-assign still distributes option choices (choice_0 then choice_1)")
    void optionInRangeStillWorks() {
        WorkflowBuilderSession s = createSession();
        addCore(s, "option", "Pick", "optionChoices", outputs(2));

        assertThat(connect(s, "Pick", "Step A").success()).isTrue();
        assertThat(connect(s, "Pick", "Step B").success()).isTrue();
        assertThat(s.getEdges()).extracting(e -> (String) e.get("from"))
                .containsExactly("core:pick:choice_0", "core:pick:choice_1");
    }

    @Test
    @DisplayName("in-range auto-assign still distributes switch cases including default")
    void switchInRangeStillWorks() {
        WorkflowBuilderSession s = createSession();
        addCore(s, "switch", "Route", "switchCases", conds("case", "case", "default"));

        assertThat(connect(s, "Route", "Step A").success()).isTrue();
        assertThat(connect(s, "Route", "Step B").success()).isTrue();
        assertThat(connect(s, "Route", "Step C").success()).isTrue();
        assertThat(s.getEdges()).extracting(e -> (String) e.get("from"))
                .containsExactly("core:route:case_0", "core:route:case_1", "core:route:default");
    }

    @Test
    @DisplayName("decision overflow auto-expands AND wires the port the runtime declares (elseif_0, not elseif_1)")
    void decisionAutoExpandsWithRuntimeAlignedPort() {
        WorkflowBuilderSession s = createSession();
        Map<String, Object> decision = addCore(s, "decision", "Check", "decisionConditions",
                new java.util.ArrayList<>(List.of(
                        new LinkedHashMap<>(Map.of("type", "if")),
                        new LinkedHashMap<>(Map.of("type", "else")))));

        assertThat(connect(s, "Check", "Step A").success()).isTrue();
        assertThat(connect(s, "Check", "Step B").success()).isTrue();
        assertThat(connect(s, "Check", "Step C").success())
                .as("decision auto-expands an elseif on overflow - the pattern fork now follows")
                .isTrue();

        // Runtime numbering (Core.getDecisionPorts) is position-1: the inserted
        // elseif sits at position 1 of [if, elseif, else] => port elseif_0. The
        // pre-fix code emitted elseif_1 (it also counted the wired else edge),
        // wiring an edge to a port the runtime never declares.
        assertThat(s.getEdges()).extracting(e -> (String) e.get("from"))
                .containsExactly("core:check:if", "core:check:else", "core:check:elseif_0");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) decision.get("decisionConditions");
        assertThat(conditions).extracting(c -> (String) c.get("type"))
                .containsExactly("if", "elseif", "else");
    }

    // ==================== Explicit ports + builder->validator round-trip ====================

    @Test
    @DisplayName("explicit out-of-range port is refused AT CONNECT (not only later at validate)")
    void explicitOutOfRangePortRefusedInline() {
        WorkflowBuilderSession s = createSession();
        addCore(s, "option", "Pick", "optionChoices", outputs(2));

        ToolExecutionResult res = connect(s, "core:pick:choice_5", "Step A");

        assertThat(res.success())
                .as("pre-fix: this returned success and only finish/validate contradicted it")
                .isFalse();
        assertThat(res.error()).containsIgnoringCase("undeclared");
        assertThat(s.getEdges()).isEmpty();
    }

    @Test
    @DisplayName("explicit in-range port still connects")
    void explicitInRangePortStillConnects() {
        WorkflowBuilderSession s = createSession();
        addCore(s, "option", "Pick", "optionChoices", outputs(2));

        assertThat(connect(s, "core:pick:choice_1", "Step A").success()).isTrue();
        assertThat(s.getEdges()).hasSize(1);
    }

    /**
     * The graphs the builder itself produces (auto-assign, auto-extension,
     * decision expansion) must pass the PORT_INDEX_OUT_OF_RANGE backstop -
     * the audit found the switch rule initially counted non-default cases
     * while the builder and the runtime number by list POSITION, so the
     * builder's own output failed its own validator.
     */
    @Test
    @DisplayName("round-trip: every builder-produced graph passes the range validator")
    void builderOutputPassesRangeValidator() {
        WorkflowBuilderSession s = createSession();

        // fork auto-extended past its declaration
        addCore(s, "fork", "Par", "forkOutputs", outputs(1));
        assertThat(connect(s, "Par", "Step A").success()).isTrue();
        assertThat(connect(s, "Par", "Step B").success()).isTrue();

        // switch fully wired through auto-assign, DEFAULT IN THE MIDDLE - the
        // shape the round-1 count-based rule wrongly rejected (case_2 exists at
        // position 2 even though only 2 cases are non-default)
        addCore(s, "switch", "Route", "switchCases", conds("case", "default", "case"));
        assertThat(connect(s, "Route", "Step A").success()).isTrue();
        assertThat(connect(s, "Route", "Step B").success()).isTrue();
        assertThat(connect(s, "Route", "Step C").success()).isTrue();

        // decision expanded past [if, else]
        addCore(s, "decision", "Check", "decisionConditions",
                new java.util.ArrayList<>(List.of(
                        new LinkedHashMap<>(Map.of("type", "if")),
                        new LinkedHashMap<>(Map.of("type", "else")))));
        assertThat(connect(s, "Check", "Step A").success()).isTrue();
        assertThat(connect(s, "Check", "Step B").success()).isTrue();
        assertThat(connect(s, "Check", "Step C").success()).isTrue();

        com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult result =
                com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult.builder().build();
        new com.apimarketplace.orchestrator.tools.workflow.builder.validation.EdgeValidator().validate(s, result);

        assertThat(result.getErrors())
                .as("the builder must never produce a graph its own validator rejects")
                .noneMatch(e -> e.code().equals("PORT_INDEX_OUT_OF_RANGE"));
    }
}
