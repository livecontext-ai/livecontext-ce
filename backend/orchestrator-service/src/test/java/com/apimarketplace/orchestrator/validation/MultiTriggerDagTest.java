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

/**
 * Tests for multi-trigger DAG support.
 * Validates that triggers sharing downstream nodes (detected from edge topology)
 * are auto-grouped, while triggers with independent graphs remain separate.
 */
@DisplayName("Multi-Trigger DAG")
class MultiTriggerDagTest {

    private DAGIndependenceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DAGIndependenceValidator();
    }

    // ===== Helper methods =====

    private Trigger createTrigger(String label, String type) {
        return new Trigger(label, label, "single", type);
    }

    private Edge createEdge(String from, String to) {
        return new Edge(from, to, null);
    }

    private WorkflowPlan createPlan(List<Trigger> triggers, List<Edge> edges) {
        return new WorkflowPlan("test-plan", "tenant1", triggers, List.of(),
            List.of(), edges, List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    // ===== Edge-based sharing detection (replaces dagGroup field tests) =====

    @Nested
    @DisplayName("Edge-based sharing detection")
    class EdgeBasedSharingDetectionTests {

        @Test
        @DisplayName("areTriggersInSameDagGroup: triggers sharing a node returns true")
        void sharingNodeReturnsTrue() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:shared"),
                    createEdge("trigger:manual", "mcp:shared")
                )
            );

            assertThat(plan.areTriggersInSameDagGroup(
                t1.getNormalizedKey(), t2.getNormalizedKey())).isTrue();
        }

        @Test
        @DisplayName("areTriggersInSameDagGroup: triggers with disjoint graphs returns false")
        void disjointGraphsReturnsFalse() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step_a"),
                    createEdge("trigger:manual", "mcp:step_b")
                )
            );

            assertThat(plan.areTriggersInSameDagGroup(
                t1.getNormalizedKey(), t2.getNormalizedKey())).isFalse();
        }

        @Test
        @DisplayName("areTriggersInSameDagGroup: triggers with no edges returns false")
        void noEdgesReturnsFalse() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(List.of(t1, t2), List.of());

            assertThat(plan.areTriggersInSameDagGroup(
                t1.getNormalizedKey(), t2.getNormalizedKey())).isFalse();
        }

        @Test
        @DisplayName("areTriggersInSameDagGroup: transitive sharing via deep chain")
        void transitiveSharingViaDeepChain() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            // t1 -> A -> B -> C, t2 -> A -> B -> C (share at A)
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step_a"),
                    createEdge("trigger:manual", "mcp:step_a"),
                    createEdge("mcp:step_a", "mcp:step_b"),
                    createEdge("mcp:step_b", "mcp:step_c")
                )
            );

            assertThat(plan.areTriggersInSameDagGroup(
                t1.getNormalizedKey(), t2.getNormalizedKey())).isTrue();
        }

        @Test
        @DisplayName("areTriggersInSameDagGroup: sharing at a deep descendant only")
        void sharingAtDeepDescendantOnly() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            // t1 -> A -> C, t2 -> B -> C (share only at C, deep downstream)
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step_a"),
                    createEdge("mcp:step_a", "mcp:shared_c"),
                    createEdge("trigger:manual", "mcp:step_b"),
                    createEdge("mcp:step_b", "mcp:shared_c")
                )
            );

            assertThat(plan.areTriggersInSameDagGroup(
                t1.getNormalizedKey(), t2.getNormalizedKey())).isTrue();
        }

        @Test
        @DisplayName("getTriggersInSameDagGroup: returns all triggers sharing descendants")
        void returnAllTriggersSharingDescendants() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("schedule", "schedule");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("trigger:schedule", "mcp:independent")
                )
            );

            List<Trigger> group = plan.getTriggersInSameDagGroup(t1.getNormalizedKey());

            assertThat(group).hasSize(2);
            assertThat(group.stream().map(Trigger::getNormalizedKey))
                .containsExactlyInAnyOrder(t1.getNormalizedKey(), t2.getNormalizedKey());
        }

        @Test
        @DisplayName("getTriggersInSameDagGroup: returns empty for non-sharing trigger")
        void returnEmptyForNonSharingTrigger() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step_a"),
                    createEdge("trigger:manual", "mcp:step_b")
                )
            );

            List<Trigger> group = plan.getTriggersInSameDagGroup(t1.getNormalizedKey());
            assertThat(group).isEmpty();
        }

        @Test
        @DisplayName("hasMultiTriggerDagGroups: true when triggers share descendants")
        void hasMultiTriggerWhenTriggersShareDescendants() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:shared"),
                    createEdge("trigger:manual", "mcp:shared")
                )
            );

            assertThat(plan.hasMultiTriggerDagGroups()).isTrue();
        }

        @Test
        @DisplayName("hasMultiTriggerDagGroups: false when all triggers are independent")
        void noMultiTriggerWhenAllIndependent() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step_a"),
                    createEdge("trigger:manual", "mcp:step_b")
                )
            );

            assertThat(plan.hasMultiTriggerDagGroups()).isFalse();
        }

        @Test
        @DisplayName("hasMultiTriggerDagGroups: false when no edges exist")
        void noMultiTriggerWhenNoEdges() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(List.of(t1, t2), List.of());

            assertThat(plan.hasMultiTriggerDagGroups()).isFalse();
        }
    }

    // ===== Validation tests =====

    @Nested
    @DisplayName("DAGIndependenceValidator with edge-based sharing")
    class ValidationTests {

        @Test
        @DisplayName("Should ALLOW shared descendants for triggers detected as sharing (same DAG)")
        void shouldAllowSharedDescendantsInSameDag() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            // Should NOT throw - triggers share descendants so they are auto-grouped
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Should ALLOW independent triggers with separate graphs")
        void shouldAllowIndependentTriggersWithSeparateGraphs() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step_a"),
                    createEdge("mcp:step_a", "mcp:step_a2"),
                    createEdge("trigger:manual", "mcp:step_b"),
                    createEdge("mcp:step_b", "mcp:step_b2")
                )
            );

            // Should NOT throw - completely separate graphs
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Mixed: 2 triggers share descendants, 3rd is independent - passes")
        void mixedSharedAndIndependentPasses() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("schedule", "schedule");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2"),
                    createEdge("trigger:schedule", "mcp:independent")
                )
            );

            // Should NOT throw - t1/t2 share DAG, t3 is independent
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("3 triggers all sharing the same complex graph - allowed")
        void threeTriggersAllSharingComplexGraph() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("chat", "chat");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook", "mcp:enrich"),
                    createEdge("trigger:manual", "mcp:enrich"),
                    createEdge("trigger:chat", "mcp:enrich"),
                    createEdge("mcp:enrich", "core:decision"),
                    createEdge("core:decision:if", "mcp:success"),
                    createEdge("core:decision:else", "mcp:failure"),
                    createEdge("mcp:success", "agent:notify"),
                    createEdge("mcp:failure", "agent:notify")
                )
            );

            // Should NOT throw
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Two separate sharing groups coexisting - no cross-contamination")
        void twoSharingGroupsCoexisting() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("schedule", "schedule");
            Trigger t4 = createTrigger("datasource", "datasource");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3, t4),
                List.of(
                    // Group 1: webhook + manual share api_call
                    createEdge("trigger:webhook", "mcp:api_call"),
                    createEdge("trigger:manual", "mcp:api_call"),
                    createEdge("mcp:api_call", "agent:process"),
                    // Group 2: schedule + datasource share cleanup
                    createEdge("trigger:schedule", "table:cleanup"),
                    createEdge("trigger:datasource", "table:cleanup"),
                    createEdge("table:cleanup", "mcp:report")
                )
            );

            // Should NOT throw - the two groups are independent of each other
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("REJECT: two separate groups that cross-contaminate at a shared node")
        void rejectCrossContaminationBetweenGroups() {
            // t1+t2 share group_a_entry, t3+t4 share group_b_entry,
            // but both groups connect to the same downstream node
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("schedule", "schedule");
            Trigger t4 = createTrigger("datasource", "datasource");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3, t4),
                List.of(
                    createEdge("trigger:webhook", "mcp:group_a_entry"),
                    createEdge("trigger:manual", "mcp:group_a_entry"),
                    createEdge("trigger:schedule", "mcp:group_b_entry"),
                    createEdge("trigger:datasource", "mcp:group_b_entry"),
                    // Cross-contamination: both group entries connect to same node
                    createEdge("mcp:group_a_entry", "mcp:contaminated"),
                    createEdge("mcp:group_b_entry", "mcp:contaminated")
                )
            );

            // All 4 triggers share "mcp:contaminated" transitively, so they all form
            // one big group. Validation passes because auto-detection groups them.
            validator.validateIndependence(plan);

            // All 4 triggers should see contaminated as multi-trigger
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:contaminated")).hasSize(4);
        }
    }

    // ===== findOwnerTrigger tests =====

    @Nested
    @DisplayName("findOwnerTrigger with edge-based sharing")
    class FindOwnerTriggerTests {

        @Test
        @DisplayName("Should return first trigger for shared node (both triggers share DAG)")
        void shouldReturnFirstTriggerForSharedNode() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            Optional<String> owner = validator.findOwnerTrigger(plan, "mcp:step1");

            // Should return first trigger (both are valid owners)
            assertThat(owner).isPresent();
            // Could be either one - both are in same auto-detected DAG group
            assertThat(List.of("trigger:webhook", "trigger:manual"))
                .contains(owner.get());
        }

        @Test
        @DisplayName("Should return single owner for independent DAG nodes")
        void shouldReturnSingleOwnerForIndependentDag() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("schedule", "schedule");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("trigger:schedule", "mcp:independent")
                )
            );

            // Independent node -> single owner
            Optional<String> owner = validator.findOwnerTrigger(plan, "mcp:independent");
            assertThat(owner).isPresent().contains("trigger:schedule");
        }

        @Test
        @DisplayName("Should return empty for orphan node (not reachable from any trigger)")
        void shouldReturnEmptyForOrphanNode() {
            Trigger t1 = createTrigger("webhook", "webhook");
            WorkflowPlan plan = createPlan(
                List.of(t1),
                List.of(createEdge("trigger:webhook", "mcp:step1"))
            );

            Optional<String> owner = validator.findOwnerTrigger(plan, "mcp:orphan");
            assertThat(owner).isEmpty();
        }
    }

    // ===== findAllOwnerTriggers tests =====

    @Nested
    @DisplayName("findAllOwnerTriggers")
    class FindAllOwnerTriggersTests {

        @Test
        @DisplayName("Should return all triggers for shared node in same DAG")
        void shouldReturnAllTriggersForSharedNode() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            List<String> owners = validator.findAllOwnerTriggers(plan, "mcp:step1");
            assertThat(owners).containsExactlyInAnyOrder("trigger:webhook", "trigger:manual");
        }

        @Test
        @DisplayName("Should return single trigger for non-shared node")
        void shouldReturnSingleTriggerForNonSharedNode() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:only_webhook"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            List<String> owners = validator.findAllOwnerTriggers(plan, "mcp:only_webhook");
            assertThat(owners).containsExactly("trigger:webhook");
        }

        @Test
        @DisplayName("Should return empty for orphan node")
        void shouldReturnEmptyForOrphanNode() {
            Trigger t1 = createTrigger("webhook", "webhook");
            WorkflowPlan plan = createPlan(List.of(t1), List.of());

            List<String> owners = validator.findAllOwnerTriggers(plan, "mcp:orphan");
            assertThat(owners).isEmpty();
        }
    }

    // ===== isMultiTriggerNode tests =====

    @Nested
    @DisplayName("isMultiTriggerNode")
    class IsMultiTriggerNodeTests {

        @Test
        @DisplayName("Should return true for node reachable from multiple triggers")
        void shouldReturnTrueForSharedNode() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            assertThat(validator.isMultiTriggerNode(plan, "mcp:step1")).isTrue();
        }

        @Test
        @DisplayName("Should return false for node reachable from single trigger")
        void shouldReturnFalseForSingleOwner() {
            Trigger t1 = createTrigger("webhook", "webhook");
            WorkflowPlan plan = createPlan(
                List.of(t1),
                List.of(createEdge("trigger:webhook", "mcp:step1"))
            );

            assertThat(validator.isMultiTriggerNode(plan, "mcp:step1")).isFalse();
        }

        @Test
        @DisplayName("Should return true for transitive shared node (deep in chain)")
        void shouldReturnTrueForTransitiveSharedNode() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2"),
                    createEdge("mcp:step2", "agent:final")
                )
            );

            // agent:final is reachable from both triggers (transitively)
            assertThat(validator.isMultiTriggerNode(plan, "agent:final")).isTrue();
        }
    }

    // ===== Complex multi-trigger DAG scenarios =====

    @Nested
    @DisplayName("Complex multi-trigger DAG scenarios")
    class ComplexScenarios {

        @Test
        @DisplayName("Multi-trigger DAG with decision branches")
        void multiTriggerWithDecision() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("form", "form");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:validate"),
                    createEdge("trigger:form", "mcp:validate"),
                    createEdge("mcp:validate", "core:check"),
                    createEdge("core:check:if", "mcp:process"),
                    createEdge("core:check:else", "mcp:reject"),
                    createEdge("mcp:process", "agent:notify"),
                    createEdge("mcp:reject", "agent:notify")
                )
            );

            // Validation should pass
            validator.validateIndependence(plan);

            // All nodes should be reachable from both triggers
            assertThat(validator.isMultiTriggerNode(plan, "mcp:validate")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "core:check")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "mcp:process")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "agent:notify")).isTrue();
        }

        @Test
        @DisplayName("Multi-trigger DAG with fork/merge")
        void multiTriggerWithForkMerge() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "core:fork"),
                    createEdge("trigger:manual", "core:fork"),
                    createEdge("core:fork:branch_0", "mcp:branch_a"),
                    createEdge("core:fork:branch_1", "mcp:branch_b"),
                    createEdge("mcp:branch_a", "core:merge"),
                    createEdge("mcp:branch_b", "core:merge")
                )
            );

            validator.validateIndependence(plan);

            assertThat(validator.isMultiTriggerNode(plan, "core:fork")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "mcp:branch_a")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "core:merge")).isTrue();
        }

        @Test
        @DisplayName("Two separate sharing groups coexisting with independent graphs")
        void twoSharingGroupsCoexisting() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("schedule", "schedule");
            Trigger t4 = createTrigger("datasource", "datasource");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3, t4),
                List.of(
                    // API group: webhook + manual share api_call
                    createEdge("trigger:webhook", "mcp:api_call"),
                    createEdge("trigger:manual", "mcp:api_call"),
                    createEdge("mcp:api_call", "agent:process"),
                    // Cron group: schedule + datasource share cleanup
                    createEdge("trigger:schedule", "table:cleanup"),
                    createEdge("trigger:datasource", "table:cleanup"),
                    createEdge("table:cleanup", "mcp:report")
                )
            );

            validator.validateIndependence(plan);

            // API group nodes
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:api_call"))
                .containsExactlyInAnyOrder("trigger:webhook", "trigger:manual");
            // Cron group nodes
            assertThat(validator.findAllOwnerTriggers(plan, "table:cleanup"))
                .containsExactlyInAnyOrder("trigger:schedule", "trigger:datasource");
        }

        @Test
        @DisplayName("STRESS: 5 triggers sharing entry point, deep chain")
        void stress5TriggersSharingEntryDeepChain() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            for (int i = 0; i < 5; i++) {
                triggers.add(createTrigger("trigger_" + i, "webhook"));
                edges.add(createEdge("trigger:trigger_" + i, "mcp:shared_entry"));
            }

            // Deep chain after shared entry
            String prev = "mcp:shared_entry";
            for (int i = 0; i < 10; i++) {
                String next = "mcp:step_" + i;
                edges.add(createEdge(prev, next));
                prev = next;
            }

            WorkflowPlan plan = createPlan(triggers, edges);

            // Should pass - all triggers share descendants (auto-grouped)
            validator.validateIndependence(plan);

            // All chain nodes should be multi-trigger
            assertThat(validator.isMultiTriggerNode(plan, "mcp:shared_entry")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "mcp:step_9")).isTrue();
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:shared_entry")).hasSize(5);
        }
    }

    // =========================================================================
    // Stress Tests - Large-Scale Topologies
    // =========================================================================

    @Nested
    @DisplayName("Stress: large-scale multi-trigger topologies")
    class StressTests {

        @Test
        @DisplayName("STRESS: 10 triggers sharing single entry point")
        void stress10TriggersOneEntry() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                triggers.add(createTrigger("t" + i, "webhook"));
                edges.add(createEdge("trigger:t" + i, "mcp:mega_entry"));
            }
            edges.add(createEdge("mcp:mega_entry", "agent:process"));

            WorkflowPlan plan = createPlan(triggers, edges);
            validator.validateIndependence(plan);

            assertThat(validator.isMultiTriggerNode(plan, "mcp:mega_entry")).isTrue();
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:mega_entry")).hasSize(10);
            assertThat(validator.isMultiTriggerNode(plan, "agent:process")).isTrue();
        }

        @Test
        @DisplayName("STRESS: 20 triggers sharing entry point with wide fan-out and reconvergence")
        void stress20TriggersWideFanOut() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            for (int i = 0; i < 20; i++) {
                triggers.add(createTrigger("t" + i, "webhook"));
                edges.add(createEdge("trigger:t" + i, "mcp:entry"));
            }

            // Fan out from entry to 5 parallel branches
            for (int i = 0; i < 5; i++) {
                edges.add(createEdge("mcp:entry", "mcp:branch_" + i));
                edges.add(createEdge("mcp:branch_" + i, "core:final_merge"));
            }

            WorkflowPlan plan = createPlan(triggers, edges);
            validator.validateIndependence(plan);

            assertThat(validator.findAllOwnerTriggers(plan, "mcp:entry")).hasSize(20);
            assertThat(validator.isMultiTriggerNode(plan, "core:final_merge")).isTrue();
        }

        @Test
        @DisplayName("STRESS: 5 independent sharing groups of 4 triggers each")
        void stress5GroupsOf4() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            String[] groups = {"alpha", "beta", "gamma", "delta", "epsilon"};
            for (String group : groups) {
                for (int i = 0; i < 4; i++) {
                    String label = group + "_t" + i;
                    triggers.add(createTrigger(label, "webhook"));
                    edges.add(createEdge("trigger:" + label, "mcp:" + group + "_entry"));
                }
                edges.add(createEdge("mcp:" + group + "_entry", "mcp:" + group + "_process"));
                edges.add(createEdge("mcp:" + group + "_process", "agent:" + group + "_notify"));
            }

            WorkflowPlan plan = createPlan(triggers, edges);
            validator.validateIndependence(plan);

            // Verify each group's nodes are owned by 4 triggers
            for (String group : groups) {
                assertThat(validator.findAllOwnerTriggers(plan, "mcp:" + group + "_entry")).hasSize(4);
                assertThat(validator.findAllOwnerTriggers(plan, "agent:" + group + "_notify")).hasSize(4);
            }
        }

        @Test
        @DisplayName("STRESS: 3 sharing groups of 3, one group's downstream merges with another")
        void stressGroupsWithTransitiveMerge() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            // Group alpha: 3 triggers -> alpha_entry
            for (int i = 0; i < 3; i++) {
                triggers.add(createTrigger("alpha_t" + i, "webhook"));
                edges.add(createEdge("trigger:alpha_t" + i, "mcp:alpha_entry"));
            }
            // Group beta: 3 triggers -> beta_entry
            for (int i = 0; i < 3; i++) {
                triggers.add(createTrigger("beta_t" + i, "webhook"));
                edges.add(createEdge("trigger:beta_t" + i, "mcp:beta_entry"));
            }

            // MERGE: alpha_entry and beta_entry both connect to shared_node
            // This means all 6 triggers transitively share shared_node
            edges.add(createEdge("mcp:alpha_entry", "mcp:shared_node"));
            edges.add(createEdge("mcp:beta_entry", "mcp:shared_node"));

            WorkflowPlan plan = createPlan(triggers, edges);

            // All 6 triggers share mcp:shared_node transitively, forming one big group
            // Auto-detection groups them all together, so validation passes
            validator.validateIndependence(plan);

            assertThat(validator.findAllOwnerTriggers(plan, "mcp:shared_node")).hasSize(6);
        }

        @Test
        @DisplayName("STRESS: deep chain of 50 nodes shared by 3 triggers")
        void stressDeep50NodeChain() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            for (int i = 0; i < 3; i++) {
                triggers.add(createTrigger("t" + i, "webhook"));
                edges.add(createEdge("trigger:t" + i, "mcp:node_0"));
            }

            for (int i = 0; i < 49; i++) {
                edges.add(createEdge("mcp:node_" + i, "mcp:node_" + (i + 1)));
            }

            WorkflowPlan plan = createPlan(triggers, edges);
            validator.validateIndependence(plan);

            // First and last nodes should be multi-trigger
            assertThat(validator.isMultiTriggerNode(plan, "mcp:node_0")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "mcp:node_49")).isTrue();
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:node_49")).hasSize(3);
        }

        @Test
        @DisplayName("STRESS: sharing group + independent trigger, both with deep chains - no cross-contamination")
        void stressSharingGroupPlusIndependentDeepChains() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            // Sharing group: 3 triggers -> shared chain of 20 nodes
            for (int i = 0; i < 3; i++) {
                triggers.add(createTrigger("shared_t" + i, "webhook"));
                edges.add(createEdge("trigger:shared_t" + i, "mcp:shared_0"));
            }
            for (int i = 0; i < 19; i++) {
                edges.add(createEdge("mcp:shared_" + i, "mcp:shared_" + (i + 1)));
            }

            // Independent trigger -> own chain of 20 nodes
            triggers.add(createTrigger("indie", "schedule"));
            edges.add(createEdge("trigger:indie", "mcp:indie_0"));
            for (int i = 0; i < 19; i++) {
                edges.add(createEdge("mcp:indie_" + i, "mcp:indie_" + (i + 1)));
            }

            WorkflowPlan plan = createPlan(triggers, edges);
            validator.validateIndependence(plan);

            // Shared chain is multi-trigger
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:shared_19")).hasSize(3);
            // Independent chain is single-trigger
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:indie_19")).hasSize(1);
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:indie_19")).containsExactly("trigger:indie");
        }
    }

    // =========================================================================
    // Advanced Topology Tests
    // =========================================================================

    @Nested
    @DisplayName("Advanced topologies with multi-trigger DAG")
    class AdvancedTopologyTests {

        @Test
        @DisplayName("Diamond: 2 triggers -> entry -> (A, B) -> merge -> exit")
        void diamondWithMultiTrigger() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:entry"),
                    createEdge("trigger:manual", "mcp:entry"),
                    createEdge("mcp:entry", "mcp:left"),
                    createEdge("mcp:entry", "mcp:right"),
                    createEdge("mcp:left", "core:merge"),
                    createEdge("mcp:right", "core:merge"),
                    createEdge("core:merge", "agent:exit")
                )
            );

            validator.validateIndependence(plan);

            assertThat(validator.isMultiTriggerNode(plan, "mcp:entry")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "mcp:left")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "mcp:right")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "core:merge")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "agent:exit")).isTrue();
        }

        @Test
        @DisplayName("Multi-trigger DAG with loop node")
        void multiTriggerWithLoop() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("chat", "chat");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:fetch_data"),
                    createEdge("trigger:chat", "mcp:fetch_data"),
                    createEdge("mcp:fetch_data", "core:loop"),
                    createEdge("core:loop:body", "mcp:process_item"),
                    createEdge("core:loop:exit", "agent:summarize")
                )
            );

            validator.validateIndependence(plan);

            assertThat(validator.isMultiTriggerNode(plan, "mcp:fetch_data")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "core:loop")).isTrue();
        }

        @Test
        @DisplayName("Multi-trigger DAG with switch node and multiple branches")
        void multiTriggerWithSwitch() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("form", "form");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:classify"),
                    createEdge("trigger:form", "mcp:classify"),
                    createEdge("mcp:classify", "core:switch"),
                    createEdge("core:switch:case_0", "mcp:handle_type_a"),
                    createEdge("core:switch:case_1", "mcp:handle_type_b"),
                    createEdge("core:switch:default", "mcp:handle_unknown")
                )
            );

            validator.validateIndependence(plan);

            assertThat(validator.isMultiTriggerNode(plan, "core:switch")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "mcp:handle_type_a")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "mcp:handle_unknown")).isTrue();
        }

        @Test
        @DisplayName("Nested diamonds: 2 triggers -> entry -> diamond1 -> diamond2 -> exit")
        void nestedDiamonds() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:entry"),
                    createEdge("trigger:manual", "mcp:entry"),
                    // Diamond 1
                    createEdge("mcp:entry", "mcp:d1_left"),
                    createEdge("mcp:entry", "mcp:d1_right"),
                    createEdge("mcp:d1_left", "mcp:d1_merge"),
                    createEdge("mcp:d1_right", "mcp:d1_merge"),
                    // Diamond 2
                    createEdge("mcp:d1_merge", "mcp:d2_left"),
                    createEdge("mcp:d1_merge", "mcp:d2_right"),
                    createEdge("mcp:d2_left", "mcp:d2_merge"),
                    createEdge("mcp:d2_right", "mcp:d2_merge"),
                    // Exit
                    createEdge("mcp:d2_merge", "agent:exit")
                )
            );

            validator.validateIndependence(plan);

            // All nodes are multi-trigger
            for (String nodeId : List.of("mcp:entry", "mcp:d1_left", "mcp:d1_right",
                "mcp:d1_merge", "mcp:d2_left", "mcp:d2_right", "mcp:d2_merge", "agent:exit")) {
                assertThat(validator.isMultiTriggerNode(plan, nodeId))
                    .as("Node %s should be multi-trigger", nodeId)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("Wide fan-out: 3 triggers -> entry -> 10 parallel branches -> merge")
        void wideFanOut() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("chat", "chat");

            List<Edge> edges = new ArrayList<>();
            edges.add(createEdge("trigger:webhook", "mcp:entry"));
            edges.add(createEdge("trigger:manual", "mcp:entry"));
            edges.add(createEdge("trigger:chat", "mcp:entry"));

            for (int i = 0; i < 10; i++) {
                edges.add(createEdge("mcp:entry", "mcp:parallel_" + i));
                edges.add(createEdge("mcp:parallel_" + i, "core:mega_merge"));
            }

            WorkflowPlan plan = createPlan(List.of(t1, t2, t3), edges);
            validator.validateIndependence(plan);

            assertThat(validator.findAllOwnerTriggers(plan, "mcp:entry")).hasSize(3);
            assertThat(validator.isMultiTriggerNode(plan, "core:mega_merge")).isTrue();
        }

        @Test
        @DisplayName("Sharing group with interface node -> signal node -> continue pattern")
        void sharingGroupWithInterfaceSignal() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("form", "form");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:prepare"),
                    createEdge("trigger:form", "mcp:prepare"),
                    createEdge("mcp:prepare", "interface:review_page"),
                    createEdge("interface:review_page", "mcp:post_review")
                )
            );

            validator.validateIndependence(plan);

            assertThat(validator.isMultiTriggerNode(plan, "interface:review_page")).isTrue();
            assertThat(validator.isMultiTriggerNode(plan, "mcp:post_review")).isTrue();
        }

        @Test
        @DisplayName("Single trigger - verify no false positive for multi-trigger detection")
        void singleTriggerNoFalsePositive() {
            Trigger t1 = createTrigger("webhook", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2"),
                    createEdge("mcp:step2", "agent:final")
                )
            );

            validator.validateIndependence(plan);

            assertThat(validator.isMultiTriggerNode(plan, "mcp:step1")).isFalse();
            assertThat(validator.isMultiTriggerNode(plan, "agent:final")).isFalse();
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:step1")).hasSize(1);
        }
    }

    // =========================================================================
    // Validation Edge Cases
    // =========================================================================

    @Nested
    @DisplayName("Validation edge cases")
    class ValidationEdgeCases {

        @Test
        @DisplayName("Triggers with no downstream nodes - validation passes")
        void triggersWithNoDownstream() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(List.of(t1, t2), List.of());
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Self-referencing edge (loop back) - validation still handles it")
        void selfReferencingEdge() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step1") // self-loop
                )
            );

            // Should not throw - both triggers share descendants (auto-grouped)
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Plan with single trigger - always valid")
        void singleTriggerAlwaysValid() {
            Trigger t1 = createTrigger("webhook", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2")
                )
            );

            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Plan with no triggers - always valid")
        void noTriggersAlwaysValid() {
            WorkflowPlan plan = createPlan(List.of(), List.of());
            validator.validateIndependence(plan);
        }

        @Test
        @DisplayName("Two triggers connecting to different nodes at different depths but converging")
        void triggersConvergingAtDifferentDepths() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            // t1 -> A -> B -> converge, t2 -> converge (t2 shares at depth 0, t1 at depth 2)
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step_a"),
                    createEdge("mcp:step_a", "mcp:step_b"),
                    createEdge("mcp:step_b", "mcp:converge"),
                    createEdge("trigger:manual", "mcp:converge")
                )
            );

            // Auto-detected as sharing since they have overlapping descendants
            validator.validateIndependence(plan);
            assertThat(validator.isMultiTriggerNode(plan, "mcp:converge")).isTrue();

            // step_a is only reachable from webhook
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:step_a"))
                .containsExactly("trigger:webhook");
            // converge is reachable from both
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:converge"))
                .containsExactlyInAnyOrder("trigger:webhook", "trigger:manual");
        }

        @Test
        @DisplayName("Three triggers: two sharing, one completely disconnected (no edges)")
        void twoSharingOneDisconnected() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("schedule", "schedule");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook", "mcp:shared"),
                    createEdge("trigger:manual", "mcp:shared")
                    // t3 has no edges at all
                )
            );

            validator.validateIndependence(plan);

            assertThat(validator.isMultiTriggerNode(plan, "mcp:shared")).isTrue();
            assertThat(validator.findAllOwnerTriggers(plan, "mcp:shared"))
                .containsExactlyInAnyOrder("trigger:webhook", "trigger:manual");
        }

        @Test
        @DisplayName("Backward compatibility: 4-arg Trigger constructor works correctly")
        void backwardCompatibilityFourArgConstructor() {
            Trigger trigger = new Trigger("id", "label", "single", "webhook");
            assertThat(trigger.id()).isEqualTo("id");
            assertThat(trigger.label()).isEqualTo("label");
            assertThat(trigger.strategy()).isEqualTo("single");
            assertThat(trigger.type()).isEqualTo("webhook");
        }

        @Test
        @DisplayName("Backward compatibility: 5-arg Trigger constructor works correctly")
        void backwardCompatibilityFiveArgConstructor() {
            Trigger trigger = new Trigger("id", "label", "single", "webhook", Map.of("key", "val"));
            assertThat(trigger.id()).isEqualTo("id");
            assertThat(trigger.params()).containsEntry("key", "val");
        }

        @Test
        @DisplayName("Backward compatibility: 6-arg Trigger constructor works correctly")
        void backwardCompatibilitySixArgConstructor() {
            Trigger trigger = new Trigger("id", "label", "single", "webhook", Map.of(), null);
            assertThat(trigger.id()).isEqualTo("id");
            assertThat(trigger.chatMatch()).isNull();
        }
    }
}
