package com.apimarketplace.orchestrator.validation;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for DAGIndependenceValidator.
 */
@DisplayName("DAGIndependenceValidator")
class DAGIndependenceValidatorTest {

    private DAGIndependenceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DAGIndependenceValidator();
    }

    private Trigger createTrigger(String label, String type) {
        Trigger trigger = mock(Trigger.class);
        when(trigger.label()).thenReturn(label);
        when(trigger.type()).thenReturn(type);
        when(trigger.getNormalizedKey()).thenReturn("trigger:" + label.toLowerCase().replace(" ", "_"));
        return trigger;
    }

    private Edge createEdge(String from, String to) {
        return new Edge(from, to, null);
    }

    private WorkflowPlan createPlan(List<Trigger> triggers, List<Edge> edges) {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getTriggers()).thenReturn(triggers);
        when(plan.getEdges()).thenReturn(edges);
        return plan;
    }

    @Nested
    @DisplayName("validateIndependence")
    class ValidateIndependenceTests {

        @Test
        @DisplayName("Should pass for single trigger")
        void shouldPassForSingleTrigger() {
            WorkflowPlan plan = createPlan(
                List.of(createTrigger("webhook_1", "webhook")),
                List.of(createEdge("trigger:webhook_1", "mcp:step1"))
            );

            // Should not throw
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Should pass for null triggers")
        void shouldPassForNullTriggers() {
            WorkflowPlan plan = createPlan(null, List.of());

            // Should not throw
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Should pass for independent DAGs")
        void shouldPassForIndependentDAGs() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:step_a"),
                    createEdge("trigger:webhook_2", "mcp:step_b")
                )
            );

            // Should not throw - DAGs are independent
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Should throw for overlapping DAGs")
        void shouldThrowForOverlappingDAGs() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:shared_step"),
                    createEdge("trigger:webhook_2", "mcp:shared_step")
                )
            );

            assertThatThrownBy(() -> validator.validateIndependence(plan))
                .isInstanceOf(DAGValidationException.class)
                .satisfies(ex -> {
                    DAGValidationException dagEx = (DAGValidationException) ex;
                    assertThat(dagEx.getSharedNodes()).contains("mcp:shared_step");
                });
        }
    }

    @Nested
    @DisplayName("buildAdjacencyList")
    class BuildAdjacencyListTests {

        @Test
        @DisplayName("Should build correct adjacency list from edges")
        void shouldBuildCorrectAdjacencyList() {
            WorkflowPlan plan = createPlan(
                List.of(),
                List.of(
                    createEdge("trigger:start", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2"),
                    createEdge("trigger:start", "mcp:step3")
                )
            );

            Map<String, Set<String>> adjacencyList = validator.buildAdjacencyList(plan);

            assertThat(adjacencyList.get("trigger:start")).containsExactlyInAnyOrder("mcp:step1", "mcp:step3");
            assertThat(adjacencyList.get("mcp:step1")).containsExactly("mcp:step2");
        }

        @Test
        @DisplayName("Should handle null edges")
        void shouldHandleNullEdges() {
            WorkflowPlan plan = createPlan(List.of(), null);

            Map<String, Set<String>> adjacencyList = validator.buildAdjacencyList(plan);

            assertThat(adjacencyList).isEmpty();
        }

        @Test
        @DisplayName("Should strip port from edge refs")
        void shouldStripPortFromEdgeRefs() {
            WorkflowPlan plan = createPlan(
                List.of(),
                List.of(
                    createEdge("core:decision:if", "mcp:step1"),
                    createEdge("core:decision:else", "mcp:step2")
                )
            );

            Map<String, Set<String>> adjacencyList = validator.buildAdjacencyList(plan);

            assertThat(adjacencyList.get("core:decision")).containsExactlyInAnyOrder("mcp:step1", "mcp:step2");
        }
    }

    @Nested
    @DisplayName("collectDescendants")
    class CollectDescendantsTests {

        @Test
        @DisplayName("Should collect all descendants via BFS")
        void shouldCollectAllDescendants() {
            Map<String, Set<String>> adjacencyList = Map.of(
                "A", Set.of("B", "C"),
                "B", Set.of("D"),
                "C", Set.of("D", "E")
            );

            Set<String> descendants = validator.collectDescendants(adjacencyList, "A");

            assertThat(descendants).containsExactlyInAnyOrder("B", "C", "D", "E");
            assertThat(descendants).doesNotContain("A"); // Start node excluded
        }

        @Test
        @DisplayName("Should return empty set for leaf node")
        void shouldReturnEmptyForLeafNode() {
            Map<String, Set<String>> adjacencyList = Map.of(
                "A", Set.of("B")
            );

            Set<String> descendants = validator.collectDescendants(adjacencyList, "B");

            assertThat(descendants).isEmpty();
        }

        @Test
        @DisplayName("Should handle cycles gracefully")
        void shouldHandleCycles() {
            Map<String, Set<String>> adjacencyList = new HashMap<>();
            adjacencyList.put("A", Set.of("B"));
            adjacencyList.put("B", Set.of("C"));
            adjacencyList.put("C", Set.of("A")); // Cycle

            Set<String> descendants = validator.collectDescendants(adjacencyList, "A");

            assertThat(descendants).containsExactlyInAnyOrder("B", "C");
        }
    }

    @Nested
    @DisplayName("collectDagNodes")
    class CollectDagNodesTests {

        @Test
        @DisplayName("Should include trigger node itself plus descendants")
        void shouldIncludeTriggerAndDescendants() {
            Trigger trigger = createTrigger("start", "webhook");
            WorkflowPlan plan = createPlan(
                List.of(trigger),
                List.of(
                    createEdge("trigger:start", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2")
                )
            );

            Set<String> dagNodes = validator.collectDagNodes(plan, "trigger:start");

            assertThat(dagNodes).containsExactlyInAnyOrder("trigger:start", "mcp:step1", "mcp:step2");
        }
    }

    @Nested
    @DisplayName("findOwnerTrigger")
    class FindOwnerTriggerTests {

        @Test
        @DisplayName("Should find single owner trigger")
        void shouldFindSingleOwner() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:step_a"),
                    createEdge("trigger:webhook_2", "mcp:step_b")
                )
            );

            Optional<String> owner = validator.findOwnerTrigger(plan, "mcp:step_a");

            assertThat(owner).isPresent().contains("trigger:webhook_1");
        }

        @Test
        @DisplayName("Should return empty when node is shared between triggers")
        void shouldReturnEmptyForSharedNode() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:shared"),
                    createEdge("trigger:webhook_2", "mcp:shared")
                )
            );

            Optional<String> owner = validator.findOwnerTrigger(plan, "mcp:shared");

            assertThat(owner).isEmpty();
        }

        @Test
        @DisplayName("Should return empty when no triggers")
        void shouldReturnEmptyWhenNoTriggers() {
            WorkflowPlan plan = createPlan(null, List.of());

            Optional<String> owner = validator.findOwnerTrigger(plan, "mcp:step1");

            assertThat(owner).isEmpty();
        }
    }

    @Nested
    @DisplayName("Multi-DAG Complex Scenarios")
    class MultiDagComplexTests {

        @Test
        @DisplayName("Should pass for 3 independent triggers with deep chains")
        void shouldPassFor3IndependentTriggersWithDeepChains() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("chat_start", "chat");
            Trigger t3 = createTrigger("schedule_daily", "schedule");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    // DAG1: webhook -> step_a -> step_b -> step_c
                    createEdge("trigger:webhook_1", "mcp:step_a"),
                    createEdge("mcp:step_a", "mcp:step_b"),
                    createEdge("mcp:step_b", "mcp:step_c"),
                    // DAG2: chat -> classify -> respond
                    createEdge("trigger:chat_start", "agent:classify"),
                    createEdge("agent:classify", "agent:respond"),
                    // DAG3: schedule -> get_data -> loop -> send_report
                    createEdge("trigger:schedule_daily", "table:get_data"),
                    createEdge("table:get_data", "core:loop"),
                    createEdge("core:loop:body", "mcp:send_report")
                )
            );

            // Should not throw
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Should detect transitive overlap in deep chains")
        void shouldDetectTransitiveOverlapInDeepChains() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            // DAG1: webhook_1 -> a -> b -> shared_end
            // DAG2: webhook_2 -> c -> d -> shared_end
            // Overlap at "shared_end" (transitive, not direct)
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:step_a"),
                    createEdge("mcp:step_a", "mcp:step_b"),
                    createEdge("mcp:step_b", "mcp:shared_end"),
                    createEdge("trigger:webhook_2", "mcp:step_c"),
                    createEdge("mcp:step_c", "mcp:step_d"),
                    createEdge("mcp:step_d", "mcp:shared_end")
                )
            );

            assertThatThrownBy(() -> validator.validateIndependence(plan))
                .isInstanceOf(DAGValidationException.class)
                .satisfies(ex -> {
                    DAGValidationException dagEx = (DAGValidationException) ex;
                    assertThat(dagEx.getSharedNodes()).contains("mcp:shared_end");
                });
        }

        @Test
        @DisplayName("Should detect overlap when 3rd trigger shares a node with 1st")
        void shouldDetectOverlapBetween1stAnd3rdTriggers() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");
            Trigger t3 = createTrigger("webhook_3", "webhook");

            // DAG1 and DAG3 share "mcp:shared", but DAG2 is independent
            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:step_a"),
                    createEdge("mcp:step_a", "mcp:shared"),
                    createEdge("trigger:webhook_2", "mcp:step_b"),
                    createEdge("trigger:webhook_3", "mcp:step_c"),
                    createEdge("mcp:step_c", "mcp:shared")
                )
            );

            assertThatThrownBy(() -> validator.validateIndependence(plan))
                .isInstanceOf(DAGValidationException.class)
                .satisfies(ex -> {
                    DAGValidationException dagEx = (DAGValidationException) ex;
                    assertThat(dagEx.getSharedNodes()).contains("mcp:shared");
                });
        }

        @Test
        @DisplayName("Should pass for DAGs with decision branches (ports) within single DAG")
        void shouldPassForDagsWithDecisionBranches() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            // DAG1 has a decision with if/else branches - all within single DAG
            // DAG2 is independent
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "core:check"),
                    createEdge("core:check:if", "mcp:success_handler"),
                    createEdge("core:check:else", "mcp:failure_handler"),
                    createEdge("trigger:webhook_2", "agent:responder")
                )
            );

            // Should not throw - decision branches are within DAG1
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Should pass for DAGs with diamond pattern within single DAG")
        void shouldPassForDagsWithDiamondPattern() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            // DAG1: fork -> [step_a, step_b] -> merge (diamond, all within DAG1)
            // DAG2: independent
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "core:fork"),
                    createEdge("core:fork:branch_0", "mcp:step_a"),
                    createEdge("core:fork:branch_1", "mcp:step_b"),
                    createEdge("mcp:step_a", "core:merge"),
                    createEdge("mcp:step_b", "core:merge"),
                    createEdge("trigger:webhook_2", "table:get_users")
                )
            );

            // Should not throw
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Should handle mixed node type prefixes in independent DAGs")
        void shouldHandleMixedNodeTypePrefixes() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("chat_start", "chat");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    // DAG1: uses mcp, table, core
                    createEdge("trigger:webhook_1", "mcp:fetch_api"),
                    createEdge("mcp:fetch_api", "table:insert_data"),
                    createEdge("table:insert_data", "core:transform"),
                    // DAG2: uses agent, mcp
                    createEdge("trigger:chat_start", "agent:ai_agent"),
                    createEdge("agent:ai_agent", "mcp:send_response")
                )
            );

            // Should not throw
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Should detect overlap even with different node type prefixes")
        void shouldDetectOverlapWithDifferentPrefixes() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            // Both DAGs converge to the same core:transform node
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:fetch"),
                    createEdge("mcp:fetch", "core:transform"),
                    createEdge("trigger:webhook_2", "agent:classify"),
                    createEdge("agent:classify", "core:transform")
                )
            );

            assertThatThrownBy(() -> validator.validateIndependence(plan))
                .isInstanceOf(DAGValidationException.class)
                .satisfies(ex -> {
                    DAGValidationException dagEx = (DAGValidationException) ex;
                    assertThat(dagEx.getSharedNodes()).contains("core:transform");
                });
        }

        @Test
        @DisplayName("collectDagNodes should return trigger + all descendants for complex chain")
        void collectDagNodesShouldReturnFullDag() {
            WorkflowPlan plan = createPlan(
                List.of(createTrigger("webhook_1", "webhook")),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:step_a"),
                    createEdge("trigger:webhook_1", "mcp:step_b"),
                    createEdge("mcp:step_a", "core:decision"),
                    createEdge("core:decision:if", "table:insert"),
                    createEdge("core:decision:else", "agent:notify"),
                    createEdge("mcp:step_b", "mcp:step_c")
                )
            );

            Set<String> dagNodes = validator.collectDagNodes(plan, "trigger:webhook_1");

            assertThat(dagNodes).containsExactlyInAnyOrder(
                "trigger:webhook_1",
                "mcp:step_a",
                "mcp:step_b",
                "core:decision",
                "table:insert",
                "agent:notify",
                "mcp:step_c"
            );
        }

        @Test
        @DisplayName("buildNodeOwnershipMap should correctly assign mixed node types across 3 DAGs")
        void ownershipMapShouldAssignCorrectlyFor3Dags() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("chat_start", "chat");
            Trigger t3 = createTrigger("schedule_daily", "schedule");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:api_call"),
                    createEdge("trigger:chat_start", "agent:responder"),
                    createEdge("trigger:schedule_daily", "table:cleanup"),
                    createEdge("table:cleanup", "core:transform")
                )
            );

            Map<String, String> ownership = validator.buildNodeOwnershipMap(plan);

            assertThat(ownership).containsEntry("mcp:api_call", "trigger:webhook_1");
            assertThat(ownership).containsEntry("agent:responder", "trigger:chat_start");
            assertThat(ownership).containsEntry("table:cleanup", "trigger:schedule_daily");
            assertThat(ownership).containsEntry("core:transform", "trigger:schedule_daily");
        }

        @Test
        @DisplayName("findOwnerTrigger should work correctly with 3 independent DAGs")
        void findOwnerTriggerShouldWorkWith3Dags() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("chat_start", "chat");
            Trigger t3 = createTrigger("schedule_daily", "schedule");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:api_call"),
                    createEdge("trigger:chat_start", "agent:responder"),
                    createEdge("trigger:schedule_daily", "table:cleanup")
                )
            );

            assertThat(validator.findOwnerTrigger(plan, "mcp:api_call"))
                .isPresent().contains("trigger:webhook_1");
            assertThat(validator.findOwnerTrigger(plan, "agent:responder"))
                .isPresent().contains("trigger:chat_start");
            assertThat(validator.findOwnerTrigger(plan, "table:cleanup"))
                .isPresent().contains("trigger:schedule_daily");
            // Unknown node
            assertThat(validator.findOwnerTrigger(plan, "mcp:unknown"))
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("Stress Tests & Edge Cases")
    class StressTests {

        @Test
        @DisplayName("STRESS: 5 independent triggers with 20 nodes each - all independent")
        void stress5TriggersDeepChains() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            for (int t = 0; t < 5; t++) {
                Trigger trigger = createTrigger("trigger_" + t, "webhook");
                triggers.add(trigger);

                String prev = "trigger:trigger_" + t;
                for (int n = 0; n < 20; n++) {
                    String curr = "mcp:dag" + t + "_step" + n;
                    edges.add(createEdge(prev, curr));
                    prev = curr;
                }
            }

            WorkflowPlan plan = createPlan(triggers, edges);

            // Should not throw - all 5 DAGs with 20-deep chains are independent
            validator.validateIndependence(plan);

            // Verify each DAG has exactly 20 descendants
            for (int t = 0; t < 5; t++) {
                Set<String> dagNodes = validator.collectDagNodes(plan, "trigger:trigger_" + t);
                assertThat(dagNodes).hasSize(21);
            }
        }

        @Test
        @DisplayName("STRESS: Overlap at depth 15 in a 20-deep chain - detected")
        void stressDeepOverlapAtDepth15() {
            List<Edge> edges = new ArrayList<>();

            // DAG1: trigger_1 -> d1_0 -> d1_1 -> ... -> d1_14 -> SHARED -> d1_16 -> ...
            String prev1 = "trigger:trigger_1";
            for (int i = 0; i < 15; i++) {
                String curr = "mcp:d1_step" + i;
                edges.add(createEdge(prev1, curr));
                prev1 = curr;
            }
            edges.add(createEdge(prev1, "mcp:shared_deep"));
            edges.add(createEdge("mcp:shared_deep", "mcp:d1_final"));

            // DAG2: trigger_2 -> d2_0 -> d2_1 -> ... -> d2_14 -> SHARED
            String prev2 = "trigger:trigger_2";
            for (int i = 0; i < 15; i++) {
                String curr = "mcp:d2_step" + i;
                edges.add(createEdge(prev2, curr));
                prev2 = curr;
            }
            edges.add(createEdge(prev2, "mcp:shared_deep"));

            WorkflowPlan plan = createPlan(
                List.of(createTrigger("trigger_1", "webhook"), createTrigger("trigger_2", "webhook")),
                edges
            );

            assertThatThrownBy(() -> validator.validateIndependence(plan))
                .isInstanceOf(DAGValidationException.class)
                .satisfies(ex -> {
                    DAGValidationException dagEx = (DAGValidationException) ex;
                    assertThat(dagEx.getSharedNodes()).contains("mcp:shared_deep");
                    // d1_final is also shared (reachable from both via shared_deep)
                    assertThat(dagEx.getSharedNodes()).contains("mcp:d1_final");
                });
        }

        @Test
        @DisplayName("STRESS: Wide DAG (trigger with 10 direct successors) - all belong to same DAG")
        void stressWideDag10DirectSuccessors() {
            List<Edge> edges = new ArrayList<>();
            edges.add(createEdge("trigger:fan_out", "mcp:branch_0"));
            for (int i = 0; i < 10; i++) {
                edges.add(createEdge("trigger:fan_out", "mcp:branch_" + i));
            }
            // Second trigger is independent
            edges.add(createEdge("trigger:other", "agent:reply"));

            WorkflowPlan plan = createPlan(
                List.of(createTrigger("fan_out", "webhook"), createTrigger("other", "webhook")),
                edges
            );

            // Should not throw
            validator.validateIndependence(plan);

            Set<String> dagNodes = validator.collectDagNodes(plan, "trigger:fan_out");
            assertThat(dagNodes).hasSize(11); // trigger + 10 branches
        }

        @Test
        @DisplayName("STRESS: Nested diamond within diamond - single DAG, no false overlap")
        void stressNestedDiamondPattern() {
            // trigger -> fork1 -> [A, B] -> merge1 -> fork2 -> [C, D] -> merge2
            WorkflowPlan plan = createPlan(
                List.of(
                    createTrigger("start", "webhook"),
                    createTrigger("other", "chat")
                ),
                List.of(
                    createEdge("trigger:start", "core:fork1"),
                    createEdge("core:fork1:branch_0", "mcp:a"),
                    createEdge("core:fork1:branch_1", "mcp:b"),
                    createEdge("mcp:a", "core:merge1"),
                    createEdge("mcp:b", "core:merge1"),
                    createEdge("core:merge1", "core:fork2"),
                    createEdge("core:fork2:branch_0", "agent:c"),
                    createEdge("core:fork2:branch_1", "table:d"),
                    createEdge("agent:c", "core:merge2"),
                    createEdge("table:d", "core:merge2"),
                    // Other DAG
                    createEdge("trigger:other", "mcp:independent")
                )
            );

            // Should not throw - both diamonds are within DAG1
            validator.validateIndependence(plan);

            Set<String> dagNodes = validator.collectDagNodes(plan, "trigger:start");
            assertThat(dagNodes).containsExactlyInAnyOrder(
                "trigger:start", "core:fork1", "mcp:a", "mcp:b", "core:merge1",
                "core:fork2", "agent:c", "table:d", "core:merge2"
            );
        }

        @Test
        @DisplayName("STRESS: Trigger with no edges - empty DAG, still valid")
        void stressTriggerWithNoEdges() {
            WorkflowPlan plan = createPlan(
                List.of(
                    createTrigger("orphan_1", "webhook"),
                    createTrigger("orphan_2", "chat")
                ),
                List.of() // no edges at all
            );

            // Should not throw - empty DAGs don't overlap
            validator.validateIndependence(plan);

            Set<String> dagNodes = validator.collectDagNodes(plan, "trigger:orphan_1");
            assertThat(dagNodes).containsExactly("trigger:orphan_1");
        }

        @Test
        @DisplayName("STRESS: Near-miss overlap - similar names but different DAGs")
        void stressNearMissOverlapDifferentPrefixes() {
            // DAG1 has "mcp:process_data", DAG2 has "table:process_data"
            // Same label, different prefix - NOT shared
            WorkflowPlan plan = createPlan(
                List.of(createTrigger("dag_1", "webhook"), createTrigger("dag_2", "webhook")),
                List.of(
                    createEdge("trigger:dag_1", "mcp:process_data"),
                    createEdge("trigger:dag_2", "table:process_data")
                )
            );

            // Should not throw - different prefix = different node
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("STRESS: DAG with cycle (loop back-edge) - doesn't cause infinite loop in BFS")
        void stressDagWithCycleBackEdge() {
            // trigger -> step_a -> step_b -> step_a (back-edge, loop)
            WorkflowPlan plan = createPlan(
                List.of(createTrigger("loop_dag", "webhook"), createTrigger("other", "chat")),
                List.of(
                    createEdge("trigger:loop_dag", "mcp:step_a"),
                    createEdge("mcp:step_a", "mcp:step_b"),
                    createEdge("mcp:step_b", "mcp:step_a"),  // cycle
                    createEdge("trigger:other", "agent:reply")
                )
            );

            // Should not throw - cycle handled, no overlap
            validator.validateIndependence(plan);

            Set<String> dagNodes = validator.collectDagNodes(plan, "trigger:loop_dag");
            assertThat(dagNodes).containsExactlyInAnyOrder("trigger:loop_dag", "mcp:step_a", "mcp:step_b");
        }

        @Test
        @DisplayName("STRESS: Multiple decision nodes with many ports in single DAG")
        void stressMultipleDecisionWithPorts() {
            List<Edge> edges = new ArrayList<>();
            // trigger -> decision1 -> [if, elseif_0, elseif_1, else] -> 4 targets
            edges.add(createEdge("trigger:complex", "core:decision1"));
            edges.add(createEdge("core:decision1:if", "mcp:target_0"));
            edges.add(createEdge("core:decision1:elseif_0", "mcp:target_1"));
            edges.add(createEdge("core:decision1:elseif_1", "mcp:target_2"));
            edges.add(createEdge("core:decision1:else", "mcp:target_3"));
            // Each target -> decision2 -> [if, else] -> 2 targets each
            for (int i = 0; i < 4; i++) {
                edges.add(createEdge("mcp:target_" + i, "core:decision2_" + i));
                edges.add(createEdge("core:decision2_" + i + ":if", "mcp:final_if_" + i));
                edges.add(createEdge("core:decision2_" + i + ":else", "mcp:final_else_" + i));
            }
            // Independent trigger
            edges.add(createEdge("trigger:simple", "mcp:simple_step"));

            WorkflowPlan plan = createPlan(
                List.of(createTrigger("complex", "webhook"), createTrigger("simple", "chat")),
                edges
            );

            // Should not throw
            validator.validateIndependence(plan);

            Set<String> complexDag = validator.collectDagNodes(plan, "trigger:complex");
            // trigger + decision1 + 4 targets + 4 decision2s + 8 finals = 18
            assertThat(complexDag).hasSize(18);
        }

        @Test
        @DisplayName("STRESS: Shared node is a leaf (no further descendants) - still detected")
        void stressSharedLeafNode() {
            WorkflowPlan plan = createPlan(
                List.of(createTrigger("t1", "webhook"), createTrigger("t2", "webhook")),
                List.of(
                    createEdge("trigger:t1", "mcp:a"),
                    createEdge("mcp:a", "mcp:shared_leaf"),  // shared_leaf is a leaf
                    createEdge("trigger:t2", "mcp:b"),
                    createEdge("mcp:b", "mcp:shared_leaf")
                )
            );

            assertThatThrownBy(() -> validator.validateIndependence(plan))
                .isInstanceOf(DAGValidationException.class)
                .satisfies(ex -> {
                    DAGValidationException dagEx = (DAGValidationException) ex;
                    assertThat(dagEx.getSharedNodes()).containsExactly("mcp:shared_leaf");
                });
        }

        @Test
        @DisplayName("STRESS: 3 triggers - 2 overlap, 1 independent - exception reports correct pair")
        void stress3TriggersOnlyTwoOverlap() {
            WorkflowPlan plan = createPlan(
                List.of(
                    createTrigger("t1", "webhook"),
                    createTrigger("t2", "webhook"),
                    createTrigger("t3", "chat")
                ),
                List.of(
                    createEdge("trigger:t1", "mcp:shared"),
                    createEdge("trigger:t2", "mcp:shared"),
                    createEdge("trigger:t3", "agent:independent")
                )
            );

            assertThatThrownBy(() -> validator.validateIndependence(plan))
                .isInstanceOf(DAGValidationException.class)
                .satisfies(ex -> {
                    DAGValidationException dagEx = (DAGValidationException) ex;
                    assertThat(dagEx.getSharedNodes()).contains("mcp:shared");
                    // t3's agent:independent should NOT be in shared nodes
                    assertThat(dagEx.getSharedNodes()).doesNotContain("agent:independent");
                });
        }

        @Test
        @DisplayName("STRESS: buildNodeOwnershipMap with 5 triggers and complex topology")
        void stressOwnershipMapWith5Triggers() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            for (int t = 0; t < 5; t++) {
                triggers.add(createTrigger("t" + t, "webhook"));
                // Each trigger has: step -> decision -> [if_target, else_target]
                String trig = "trigger:t" + t;
                String step = "mcp:step_" + t;
                String decision = "core:decision_" + t;
                String ifTarget = "mcp:if_" + t;
                String elseTarget = "agent:else_" + t;

                edges.add(createEdge(trig, step));
                edges.add(createEdge(step, decision));
                edges.add(createEdge(decision + ":if", ifTarget));
                edges.add(createEdge(decision + ":else", elseTarget));
            }

            WorkflowPlan plan = createPlan(triggers, edges);

            // All independent
            validator.validateIndependence(plan);

            Map<String, String> ownership = validator.buildNodeOwnershipMap(plan);

            for (int t = 0; t < 5; t++) {
                String owner = "trigger:t" + t;
                assertThat(ownership.get("mcp:step_" + t)).isEqualTo(owner);
                assertThat(ownership.get("core:decision_" + t)).isEqualTo(owner);
                assertThat(ownership.get("mcp:if_" + t)).isEqualTo(owner);
                assertThat(ownership.get("agent:else_" + t)).isEqualTo(owner);
            }
        }
    }

    @Nested
    @DisplayName("buildNodeOwnershipMap")
    class BuildNodeOwnershipMapTests {

        @Test
        @DisplayName("Should build ownership map for independent DAGs")
        void shouldBuildOwnershipMap() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:step_a"),
                    createEdge("trigger:webhook_2", "mcp:step_b")
                )
            );

            Map<String, String> ownership = validator.buildNodeOwnershipMap(plan);

            assertThat(ownership).containsEntry("mcp:step_a", "trigger:webhook_1");
            assertThat(ownership).containsEntry("mcp:step_b", "trigger:webhook_2");
        }

        @Test
        @DisplayName("Should set null for shared nodes")
        void shouldSetNullForSharedNodes() {
            Trigger t1 = createTrigger("webhook_1", "webhook");
            Trigger t2 = createTrigger("webhook_2", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook_1", "mcp:shared"),
                    createEdge("trigger:webhook_2", "mcp:shared")
                )
            );

            Map<String, String> ownership = validator.buildNodeOwnershipMap(plan);

            assertThat(ownership.get("mcp:shared")).isNull();
        }

        @Test
        @DisplayName("Should return empty map when no triggers")
        void shouldReturnEmptyWhenNoTriggers() {
            WorkflowPlan plan = createPlan(null, List.of());

            Map<String, String> ownership = validator.buildNodeOwnershipMap(plan);

            assertThat(ownership).isEmpty();
        }
    }
}
