package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for ValidationGraphAnalyzer.
 * Verifies graph construction, node existence checks, reachability analysis,
 * cycle detection, and edge counting logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ValidationGraphAnalyzer")
class ValidationGraphAnalyzerTest {

    @Mock
    private WorkflowBuilderSession session;

    private void stubEmptySession() {
        when(session.getTriggers()).thenReturn(List.of());
        when(session.getMcps()).thenReturn(List.of());
        when(session.getCores()).thenReturn(List.of());
        when(session.getInterfaces()).thenReturn(List.of());
        when(session.getTables()).thenReturn(List.of());
        when(session.getEdges()).thenReturn(List.of());
    }

    private void stubSessionWith(List<Map<String, Object>> triggers,
                                 List<Map<String, Object>> mcps,
                                 List<Map<String, Object>> cores,
                                 List<Map<String, Object>> edges) {
        when(session.getTriggers()).thenReturn(triggers);
        when(session.getMcps()).thenReturn(mcps);
        when(session.getCores()).thenReturn(cores);
        when(session.getInterfaces()).thenReturn(List.of());
        when(session.getTables()).thenReturn(List.of());
        when(session.getEdges()).thenReturn(edges);
    }

    @Nested
    @DisplayName("Graph construction and node existence")
    class NodeExistenceTests {

        @Test
        @DisplayName("Should return empty graph for empty session")
        void shouldReturnEmptyGraphForEmptySession() {
            stubEmptySession();
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.getAllNodeIds()).isEmpty();
        }

        @Test
        @DisplayName("Should register trigger nodes")
        void shouldRegisterTriggerNodes() {
            stubSessionWith(
                    List.of(Map.of("label", "My Webhook")),
                    List.of(), List.of(), List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("trigger:my_webhook")).isTrue();
            assertThat(graph.getAllNodeIds()).contains("trigger:my_webhook");
        }

        @Test
        @DisplayName("Should register mcp nodes")
        void shouldRegisterMcpNodes() {
            stubSessionWith(
                    List.of(), List.of(Map.of("label", "API Call")),
                    List.of(), List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("mcp:api_call")).isTrue();
        }

        @Test
        @DisplayName("Should register agent nodes with agent prefix")
        void shouldRegisterAgentNodesWithAgentPrefix() {
            stubSessionWith(
                    List.of(),
                    List.of(Map.of("label", "Classifier", "isAgent", true)),
                    List.of(), List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("agent:classifier")).isTrue();
            assertThat(graph.nodeExists("mcp:classifier")).isFalse();
        }

        @Test
        @DisplayName("Should register core nodes")
        void shouldRegisterCoreNodes() {
            stubSessionWith(
                    List.of(), List.of(),
                    List.of(Map.of("label", "Check User", "type", "decision")),
                    List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("core:check_user")).isTrue();
        }

        @Test
        @DisplayName("Should register interface nodes")
        void shouldRegisterInterfaceNodes() {
            when(session.getTriggers()).thenReturn(List.of());
            when(session.getMcps()).thenReturn(List.of());
            when(session.getCores()).thenReturn(List.of());
            when(session.getInterfaces()).thenReturn(List.of(Map.of("label", "My Form")));
            when(session.getTables()).thenReturn(List.of());
            when(session.getEdges()).thenReturn(List.of());

            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("interface:my_form")).isTrue();
        }

        @Test
        @DisplayName("Should register interface nodes using name fallback")
        void shouldRegisterInterfaceNodesUsingNameFallback() {
            Map<String, Object> iface = new HashMap<>();
            iface.put("label", null);
            iface.put("name", "Backup Form");

            when(session.getTriggers()).thenReturn(List.of());
            when(session.getMcps()).thenReturn(List.of());
            when(session.getCores()).thenReturn(List.of());
            when(session.getInterfaces()).thenReturn(List.of(iface));
            when(session.getTables()).thenReturn(List.of());
            when(session.getEdges()).thenReturn(List.of());

            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("interface:backup_form")).isTrue();
        }

        @Test
        @DisplayName("Should register table nodes")
        void shouldRegisterTableNodes() {
            when(session.getTriggers()).thenReturn(List.of());
            when(session.getMcps()).thenReturn(List.of());
            when(session.getCores()).thenReturn(List.of());
            when(session.getInterfaces()).thenReturn(List.of());
            when(session.getTables()).thenReturn(List.of(Map.of("label", "Users Table")));
            when(session.getEdges()).thenReturn(List.of());

            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("table:users_table")).isTrue();
        }

        @Test
        @DisplayName("Should return false for non-existent node")
        void shouldReturnFalseForNonExistentNode() {
            stubEmptySession();
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("mcp:non_existent")).isFalse();
        }

        @Test
        @DisplayName("Should recognize port-based references as existing nodes")
        void shouldRecognizePortBasedReferences() {
            stubSessionWith(
                    List.of(), List.of(),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("core:check:if")).isTrue();
            assertThat(graph.nodeExists("core:check:else")).isTrue();
            assertThat(graph.nodeExists("core:check:elseif_0")).isTrue();
        }

        @Test
        @DisplayName("Should recognize loop port references")
        void shouldRecognizeLoopPortReferences() {
            stubSessionWith(
                    List.of(), List.of(),
                    List.of(Map.of("label", "Process", "type", "loop")),
                    List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("core:process:body")).isTrue();
            assertThat(graph.nodeExists("core:process:exit")).isTrue();
            assertThat(graph.nodeExists("core:process:iterate")).isTrue();
        }

        @Test
        @DisplayName("Should recognize fork branch port references")
        void shouldRecognizeForkBranchPortReferences() {
            stubSessionWith(
                    List.of(), List.of(),
                    List.of(Map.of("label", "Parallel", "type", "fork")),
                    List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("core:parallel:branch_0")).isTrue();
            assertThat(graph.nodeExists("core:parallel:branch_1")).isTrue();
        }

        @Test
        @DisplayName("Should recognize switch case port references")
        void shouldRecognizeSwitchCasePortReferences() {
            stubSessionWith(
                    List.of(), List.of(),
                    List.of(Map.of("label", "Route", "type", "switch")),
                    List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("core:route:case_0")).isTrue();
            assertThat(graph.nodeExists("core:route:case_1")).isTrue();
        }

        @Test
        @DisplayName("Should recognize agent category port references")
        void shouldRecognizeAgentCategoryPortReferences() {
            stubSessionWith(
                    List.of(),
                    List.of(Map.of("label", "Classify", "isAgent", true)),
                    List.of(), List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.nodeExists("agent:classify:category_0")).isTrue();
        }
    }

    @Nested
    @DisplayName("Edge building and outgoing edges")
    class EdgeBuildingTests {

        @Test
        @DisplayName("Should build outgoing edges from simple edge format")
        void shouldBuildOutgoingEdgesFromSimpleFormat() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step One")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step_one"))
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.hasOutgoingEdges("trigger:start")).isTrue();
        }

        @Test
        @DisplayName("Should return false for node without outgoing edges")
        void shouldReturnFalseForNodeWithoutOutgoingEdges() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step One")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step_one"))
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.hasOutgoingEdges("mcp:step_one")).isFalse();
        }

        @Test
        @DisplayName("Should handle port-based edges extracting base node for outgoing")
        void shouldHandlePortBasedEdges() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Success"), Map.of("label", "Failure")),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(
                            Map.of("from", "trigger:start", "to", "core:check"),
                            Map.of("from", "core:check:if", "to", "mcp:success"),
                            Map.of("from", "core:check:else", "to", "mcp:failure")
                    )
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.hasOutgoingEdges("core:check")).isTrue();
        }

        @Test
        @DisplayName("Should skip edges with null to field")
        void shouldSkipEdgesWithNullToField() {
            Map<String, Object> edgeWithNullTo = new HashMap<>();
            edgeWithNullTo.put("from", "trigger:start");
            edgeWithNullTo.put("to", null);

            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(),
                    List.of(edgeWithNullTo)
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.hasOutgoingEdges("trigger:start")).isFalse();
        }

        @Test
        @DisplayName("Should skip edges with non-string to field")
        void shouldSkipEdgesWithNonStringToField() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", 42))
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            assertThat(graph.hasOutgoingEdges("trigger:start")).isFalse();
        }
    }

    @Nested
    @DisplayName("Incoming edge counts")
    class IncomingEdgeCountTests {

        @Test
        @DisplayName("Should return 0 incoming for trigger")
        void shouldReturnZeroIncomingForTrigger() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step"))
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            Map<String, Integer> counts = graph.getIncomingEdgeCounts();
            assertThat(counts.get("trigger:start")).isEqualTo(0);
        }

        @Test
        @DisplayName("Should count incoming edges correctly")
        void shouldCountIncomingEdgesCorrectly() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step One"), Map.of("label", "Final")),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:step_one"),
                            Map.of("from", "trigger:start", "to", "mcp:final"),
                            Map.of("from", "mcp:step_one", "to", "mcp:final")
                    )
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            Map<String, Integer> counts = graph.getIncomingEdgeCounts();
            assertThat(counts.get("mcp:step_one")).isEqualTo(1);
            assertThat(counts.get("mcp:final")).isEqualTo(2);
        }

        @Test
        @DisplayName("Should count port-based incoming edges on base node")
        void shouldCountPortBasedIncomingEdgesOnBaseNode() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Success")),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(
                            Map.of("from", "trigger:start", "to", "core:check"),
                            Map.of("from", "core:check:if", "to", "mcp:success")
                    )
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            Map<String, Integer> counts = graph.getIncomingEdgeCounts();
            assertThat(counts.get("core:check")).isEqualTo(1);
            assertThat(counts.get("mcp:success")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Reachability from triggers")
    class ReachabilityTests {

        @Test
        @DisplayName("Should include triggers in reachable set")
        void shouldIncludeTriggersInReachableSet() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(), List.of(), List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            Set<String> reachable = graph.getReachableFromTriggers();
            assertThat(reachable).contains("trigger:start");
        }

        @Test
        @DisplayName("Should find all reachable nodes via BFS")
        void shouldFindAllReachableNodesViaBFS() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step A"), Map.of("label", "Step B")),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:step_a"),
                            Map.of("from", "mcp:step_a", "to", "mcp:step_b")
                    )
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            Set<String> reachable = graph.getReachableFromTriggers();
            assertThat(reachable).containsExactlyInAnyOrder("trigger:start", "mcp:step_a", "mcp:step_b");
        }

        @Test
        @DisplayName("Should not include disconnected nodes")
        void shouldNotIncludeDisconnectedNodes() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Connected"), Map.of("label", "Orphan")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:connected"))
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            Set<String> reachable = graph.getReachableFromTriggers();
            assertThat(reachable).contains("mcp:connected");
            assertThat(reachable).doesNotContain("mcp:orphan");
        }

        @Test
        @DisplayName("Should handle diamond-shaped graph")
        void shouldHandleDiamondShapedGraph() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(
                            Map.of("label", "Left"),
                            Map.of("label", "Right"),
                            Map.of("label", "Merge")
                    ),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:left"),
                            Map.of("from", "trigger:start", "to", "mcp:right"),
                            Map.of("from", "mcp:left", "to", "mcp:merge"),
                            Map.of("from", "mcp:right", "to", "mcp:merge")
                    )
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            Set<String> reachable = graph.getReachableFromTriggers();
            assertThat(reachable).containsExactlyInAnyOrder(
                    "trigger:start", "mcp:left", "mcp:right", "mcp:merge"
            );
        }

        @Test
        @DisplayName("Should return empty set when no triggers")
        void shouldReturnEmptySetWhenNoTriggers() {
            stubSessionWith(
                    List.of(),
                    List.of(Map.of("label", "Orphan")),
                    List.of(),
                    List.of()
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            Set<String> reachable = graph.getReachableFromTriggers();
            assertThat(reachable).isEmpty();
        }
    }

    @Nested
    @DisplayName("Cycle detection")
    class CycleDetectionTests {

        @Test
        @DisplayName("Should detect no cycles in linear graph")
        void shouldDetectNoCyclesInLinearGraph() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:a"),
                            Map.of("from", "mcp:a", "to", "mcp:b")
                    )
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            List<String> cycles = graph.detectCycles();
            assertThat(cycles).isEmpty();
        }

        @Test
        @DisplayName("Should detect cycle between mcp nodes")
        void shouldDetectCycleBetweenMcpNodes() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:a"),
                            Map.of("from", "mcp:a", "to", "mcp:b"),
                            Map.of("from", "mcp:b", "to", "mcp:a")
                    )
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            List<String> cycles = graph.detectCycles();
            assertThat(cycles).isNotEmpty();
        }

        @Test
        @DisplayName("Should detect no cycles in DAG")
        void shouldDetectNoCyclesInDAG() {
            stubSessionWith(
                    List.of(Map.of("label", "Start")),
                    List.of(
                            Map.of("label", "A"),
                            Map.of("label", "B"),
                            Map.of("label", "C")
                    ),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:a"),
                            Map.of("from", "trigger:start", "to", "mcp:b"),
                            Map.of("from", "mcp:a", "to", "mcp:c"),
                            Map.of("from", "mcp:b", "to", "mcp:c")
                    )
            );
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            List<String> cycles = graph.detectCycles();
            assertThat(cycles).isEmpty();
        }

        @Test
        @DisplayName("Should return empty list for empty graph")
        void shouldReturnEmptyListForEmptyGraph() {
            stubEmptySession();
            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

            List<String> cycles = graph.detectCycles();
            assertThat(cycles).isEmpty();
        }
    }
}
