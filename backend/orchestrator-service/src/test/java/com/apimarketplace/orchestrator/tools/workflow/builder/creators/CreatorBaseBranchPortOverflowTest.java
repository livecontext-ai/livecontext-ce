package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connect_after MIRROR of the branch-port overflow fix
 * ({@link CreatorBase#autoAssignBranchPort} via {@link CreatorBase#createSimpleEdge}).
 * Pre-fix this surface was WORSE than the connect action: fork/option/classify
 * had no bounds handling at all (they emitted port_N straight from the edge
 * count) and switch fell through to a port-less edge.
 *
 * <p>Fixed contract, pinned here: fork auto-extends its declaration; option /
 * classify / switch overflow SKIPS the edge entirely (same contract as the
 * already-wired-port skip: the node stays, no out-of-range edge ever lands in
 * the session; the orphan surfaces at the next validate/finish).
 */
@DisplayName("CreatorBase.createSimpleEdge - declared-port overflow (connect_after path)")
class CreatorBaseBranchPortOverflowTest {

    private static class TestCreator extends CreatorBase {
        void connect(WorkflowBuilderSession session, String from, String to) {
            createSimpleEdge(session, from, to);
        }
    }

    private TestCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TestCreator();
    }

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

    private void addClassify(WorkflowBuilderSession session, String label, List<Map<String, Object>> outputs) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "agent:" + WorkflowBuilderSession.normalizeLabel(label));
        node.put("type", "agent");
        node.put("label", label);
        node.put("isAgent", true);
        node.put("isClassify", true);
        node.put("classifyOutputs", outputs);
        session.getMcps().add(node);
    }

    private static List<Map<String, Object>> conds(String... types) {
        return java.util.Arrays.stream(types)
                .map(t -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("type", t); return (Map<String, Object>) m; })
                .toList();
    }

    private static List<Map<String, Object>> outputs(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("label", "out" + i); return (Map<String, Object>) m; })
                .toList();
    }

    @Nested
    @DisplayName("fork auto-extends the declaration (was: unbounded branch_N emission)")
    class ForkAutoExtends {

        @Test
        @DisplayName("3rd connect_after on a 2-branch fork extends forkOutputs and wires branch_2")
        @SuppressWarnings("unchecked")
        void thirdConnectAfterExtendsFork() {
            WorkflowBuilderSession s = createSession();
            Map<String, Object> fork = addCore(s, "fork", "Par", "forkOutputs", outputs(2));

            creator.connect(s, "Par", "Step A");
            creator.connect(s, "Par", "Step B");
            creator.connect(s, "Par", "Step C");

            List<Map<String, Object>> declared = (List<Map<String, Object>>) fork.get("forkOutputs");
            assertThat(declared).as("declaration must grow with the 3rd edge").hasSize(3);
            assertThat(s.getEdges()).extracting(e -> (String) e.get("from"))
                    .containsExactly("core:par:branch_0", "core:par:branch_1", "core:par:branch_2");
        }

        @Test
        @DisplayName("in-range connect_after stays bounds-checked (uses declared count, not blind edge count)")
        void inRangeUsesDeclaredPorts() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "fork", "Par", "forkOutputs", outputs(3));

            creator.connect(s, "Par", "Step A");
            creator.connect(s, "Par", "Step B");

            assertThat(s.getEdges()).extracting(e -> (String) e.get("from"))
                    .containsExactly("core:par:branch_0", "core:par:branch_1");
        }
    }

    @Nested
    @DisplayName("option / classify / switch overflow skips the edge (node stays, graph stays valid)")
    class SemanticOverflowSkipsEdge {

        @Test
        @DisplayName("option: 3rd connect_after on 2 declared choices creates NO edge")
        void optionOverflowSkipsEdge() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "option", "Pick", "optionChoices", outputs(2));

            creator.connect(s, "Pick", "Step A");
            creator.connect(s, "Pick", "Step B");
            creator.connect(s, "Pick", "Step C");

            assertThat(s.getEdges()).hasSize(2);
            assertThat(s.getEdges()).extracting(e -> (String) e.get("from"))
                    .containsExactly("core:pick:choice_0", "core:pick:choice_1");
        }

        @Test
        @DisplayName("switch: overflow no longer creates a port-less edge - it creates NO edge")
        void switchOverflowSkipsEdge() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "switch", "Route", "switchCases", conds("case", "default"));

            creator.connect(s, "Route", "Step A");
            creator.connect(s, "Route", "Step B");
            creator.connect(s, "Route", "Step C");

            assertThat(s.getEdges()).hasSize(2);
            assertThat(s.getEdges())
                    .as("pre-fix regression shape: no port-less edge from the switch")
                    .noneMatch(e -> "core:route".equals(e.get("from")));
        }

        @Test
        @DisplayName("classify: 3rd connect_after on 2 declared categories creates NO edge")
        void classifyOverflowSkipsEdge() {
            WorkflowBuilderSession s = createSession();
            addClassify(s, "Sort", outputs(2));

            creator.connect(s, "Sort", "Step A");
            creator.connect(s, "Sort", "Step B");
            creator.connect(s, "Sort", "Step C");

            assertThat(s.getEdges()).hasSize(2);
            assertThat(s.getEdges()).extracting(e -> (String) e.get("from"))
                    .containsExactly("agent:sort:category_0", "agent:sort:category_1");
        }

        @Test
        @DisplayName("option with NO declared choices keeps the historical port-less edge (mirror parity)")
        void zeroChoiceOptionKeepsPortlessEdge() {
            WorkflowBuilderSession s = createSession();
            addCore(s, "option", "Pick", "optionChoices", List.of());

            creator.connect(s, "Pick", "Step A");

            assertThat(s.getEdges()).hasSize(1);
            assertThat(s.getEdges().get(0).get("from"))
                    .as("unconfigured option falls through port-less, same as the connect action")
                    .isEqualTo("core:pick");
        }
    }
}
