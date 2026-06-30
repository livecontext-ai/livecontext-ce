package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parser tests for the optional {@code nodePolicy} block on plan node entries
 * (Phase 2 - generic per-node execution policies).
 */
@DisplayName("WorkflowPlanParser - nodePolicy block")
class WorkflowPlanNodePolicyParsingTest {

    private static Map<String, Object> policyBlock(Object retryCount, Object backoffMs, Object continueOnFailure) {
        Map<String, Object> block = new HashMap<>();
        if (retryCount != null) block.put("retryCount", retryCount);
        if (backoffMs != null) block.put("retryBackoffMs", backoffMs);
        if (continueOnFailure != null) block.put("continueOnFailure", continueOnFailure);
        return block;
    }

    private static Map<String, Object> planWith(String arrayName, Map<String, Object> entry) {
        Map<String, Object> plan = new HashMap<>();
        plan.put(arrayName, List.of(entry));
        return plan;
    }

    // =====================================================================
    // Defaults - absent block = exact current behavior
    // =====================================================================

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("a node WITHOUT a nodePolicy block resolves to DEFAULT (no policy stored at all)")
        void absentBlockResolvesToDefault() {
            Map<String, Object> plan = planWith("mcps", new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User")));

            WorkflowPlan parsed = WorkflowPlan.fromMap(plan, "tenant-1");

            assertThat(parsed.getNodePolicies()).isEmpty();
            assertThat(parsed.getNodePolicy("mcp:get_user")).isEqualTo(NodePolicy.DEFAULT);
        }

        @Test
        @DisplayName("an EMPTY nodePolicy block resolves to DEFAULT and is not stored (byte-identical execution)")
        void emptyBlockResolvesToDefault() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User",
                "nodePolicy", Map.of()));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1");

            assertThat(parsed.getNodePolicies()).isEmpty();
            assertThat(parsed.getNodePolicy("mcp:get_user")).isEqualTo(NodePolicy.DEFAULT);
        }

        @Test
        @DisplayName("absent fields inside the block default individually (retryCount=0, backoff=0, continueOnFailure=false)")
        void absentFieldsDefaultIndividually() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User",
                "nodePolicy", policyBlock(2, null, null)));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1");

            assertThat(parsed.getNodePolicy("mcp:get_user"))
                .isEqualTo(new NodePolicy(2, 0L, false));
        }
    }

    // =====================================================================
    // Valid policies on every included node array
    // =====================================================================

    @Nested
    @DisplayName("Valid policy on each node array (keys mirror getNormalizedKey)")
    class ValidPerArray {

        @Test
        @DisplayName("mcp entry: stored under mcp:<normalized label> (matches Step.getNormalizedKey)")
        void mcpPolicyKeyed() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "slack/send-message", "label", "Send Slack Message",
                "nodePolicy", policyBlock(3, 1500, true)));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1");

            Step step = parsed.getMcps().get(0);
            assertThat(parsed.getNodePolicy(step.getNormalizedKey()))
                .isEqualTo(new NodePolicy(3, 1500L, true));
        }

        @Test
        @DisplayName("table entry: stored under table:<normalized label> (matches Step.getNormalizedKey)")
        void tablePolicyKeyed() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "type", "crud-update-row", "label", "Mark Processed", "dataSourceId", "123",
                "nodePolicy", policyBlock(1, 0, false)));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("tables", entry), "tenant-1");

            Step table = parsed.getTables().get(0);
            assertThat(table.getNormalizedKey()).startsWith("table:");
            assertThat(parsed.getNodePolicy(table.getNormalizedKey()))
                .isEqualTo(new NodePolicy(1, 0L, false));
        }

        @Test
        @DisplayName("agent entry: stored under agent:<normalized label> (matches Agent.getNormalizedKey)")
        void agentPolicyKeyed() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "agent-1", "type", "agent", "label", "Research Agent",
                "nodePolicy", policyBlock(2, 100, false)));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("agents", entry), "tenant-1");

            Agent agent = parsed.getAgents().get(0);
            assertThat(parsed.getNodePolicy(agent.getNormalizedKey()))
                .isEqualTo(new NodePolicy(2, 100L, false));
        }

        @Test
        @DisplayName("core entry: stored under core:<normalized label> (matches Core.getNormalizedKey, label-or-id fallback)")
        void corePolicyKeyed() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "transform-1", "type", "transform", "label", "Format Données",
                "nodePolicy", policyBlock(1, 50, true)));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");

            Core core = parsed.getCores().get(0);
            assertThat(parsed.getNodePolicy(core.getNormalizedKey()))
                .isEqualTo(new NodePolicy(1, 50L, true));
        }

        @Test
        @DisplayName("core entry WITHOUT label: key falls back to the id (same fallback as Core.getNormalizedKey)")
        void corePolicyKeyedByIdFallback() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "wait-1", "type", "wait",
                "nodePolicy", policyBlock(1, 0, false)));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");

            Core core = parsed.getCores().get(0);
            assertThat(parsed.getNodePolicy(core.getNormalizedKey()))
                .isEqualTo(new NodePolicy(1, 0L, false));
        }

        @Test
        @DisplayName("interface entry: stored under interface:<normalized label> (matches InterfaceDef.getNormalizedKey)")
        void interfacePolicyKeyed() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "interface-1", "label", "Product Card",
                "nodePolicy", policyBlock(0, 0, true)));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("interfaces", entry), "tenant-1");

            InterfaceDef iface = parsed.getInterfaces().get(0);
            assertThat(parsed.getNodePolicy(iface.getNormalizedKey()))
                .isEqualTo(new NodePolicy(0, 0L, true));
        }

        @Test
        @DisplayName("string-typed numeric values are coerced ('3' → 3)")
        void stringNumbersCoerced() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User",
                "nodePolicy", policyBlock("3", "250", "true")));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1");

            assertThat(parsed.getNodePolicy("mcp:get_user"))
                .isEqualTo(new NodePolicy(3, 250L, true));
        }

        @Test
        @DisplayName("getNodePolicy resolves a port-suffixed nodeId to the base core's policy (core:check:if → core:check)")
        void portSuffixedLookupResolvesBaseNode() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "decision-1", "type", "decision", "label", "Check",
                "nodePolicy", policyBlock(1, 0, false)));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");

            assertThat(parsed.getNodePolicy("core:check:if"))
                .isEqualTo(new NodePolicy(1, 0L, false));
        }
    }

    // =====================================================================
    // Exclusions
    // =====================================================================

    @Nested
    @DisplayName("Exclusions (triggers / notes)")
    class Exclusions {

        @Test
        @DisplayName("a nodePolicy block on a TRIGGER is ignored (triggers are entry points, not executed steps)")
        void triggerPolicyIgnored() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "trigger-1", "type", "webhook", "label", "My Webhook",
                "nodePolicy", policyBlock(5, 1000, true)));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("triggers", entry), "tenant-1");

            assertThat(parsed.getNodePolicies()).isEmpty();
            assertThat(parsed.getNodePolicy("trigger:my_webhook")).isEqualTo(NodePolicy.DEFAULT);
        }
    }

    // =====================================================================
    // Validation - clear parse-time rejection
    // =====================================================================

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("negative retryCount is rejected with an error naming the node and the field")
        void negativeRetryCountRejected() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User",
                "nodePolicy", policyBlock(-1, 0, false)));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryCount")
                .hasMessageContaining("mcp:get_user")
                .hasMessageContaining("-1");
        }

        @Test
        @DisplayName("negative retryBackoffMs is rejected with a clear error")
        void negativeBackoffRejected() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "core-1", "type", "wait", "label", "Pause",
                "nodePolicy", policyBlock(0, -500, false)));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryBackoffMs")
                .hasMessageContaining("core:pause");
        }

        @Test
        @DisplayName("non-numeric retryCount is rejected with a clear error")
        void nonNumericRetryCountRejected() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User",
                "nodePolicy", policyBlock("lots", 0, false)));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryCount")
                .hasMessageContaining("not a number");
        }

        @Test
        @DisplayName("non-boolean continueOnFailure is rejected with a clear error")
        void nonBooleanContinueOnFailureRejected() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User",
                "nodePolicy", policyBlock(0, 0, "maybe")));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("continueOnFailure")
                .hasMessageContaining("boolean");
        }

        @Test
        @DisplayName("a non-object nodePolicy value is rejected with a clear error")
        void nonObjectBlockRejected() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User",
                "nodePolicy", "retry-please"));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodePolicy")
                .hasMessageContaining("mcp:get_user");
        }

        @Test
        @DisplayName("unknown keys inside the block are ignored (forward-compatible: future fallbackValue)")
        void unknownKeysIgnored() {
            Map<String, Object> block = policyBlock(1, 0, false);
            block.put("fallbackValue", Map.of("x", 1));
            block.put("someFutureKnob", "whatever");
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User",
                "nodePolicy", block));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1");

            assertThat(parsed.getNodePolicy("mcp:get_user")).isEqualTo(new NodePolicy(1, 0L, false));
        }

        @Test
        @DisplayName("NodePolicy record itself rejects negative components (defense in depth)")
        void recordRejectsNegatives() {
            assertThatThrownBy(() -> new NodePolicy(-1, 0L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryCount");
            assertThatThrownBy(() -> new NodePolicy(0, -1L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryBackoffMs");
            assertThatThrownBy(() -> new NodePolicy(0, 0L, false, -1L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
        }
    }

    // =====================================================================
    // timeoutMs / executeOnce (3→5 component widening)
    // =====================================================================

    @Nested
    @DisplayName("timeoutMs / executeOnce parsing & validation")
    class TimeoutAndExecuteOnce {

        private Map<String, Object> mcpEntry(Map<String, Object> policy) {
            return new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User",
                "nodePolicy", policy));
        }

        @Test
        @DisplayName("REGRESSION (widening back-compat): the 3-arg constructor equals the 5-arg shape with timeoutMs=0/executeOnce=false")
        void backCompatConstructorShape() {
            assertThat(new NodePolicy(2, 500L, true))
                .isEqualTo(new NodePolicy(2, 500L, true, 0L, false));
            assertThat(NodePolicy.DEFAULT)
                .isEqualTo(new NodePolicy(0, 0L, false, 0L, false));
            assertThat(NodePolicy.DEFAULT.isDefault()).isTrue();
        }

        @Test
        @DisplayName("timeoutMs is parsed (number or string) and hasTimeout() reflects it")
        void timeoutParsed() {
            Map<String, Object> block = policyBlock(0, 0, false);
            block.put("timeoutMs", "30000");

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("mcps", mcpEntry(block)), "tenant-1");

            NodePolicy policy = parsed.getNodePolicy("mcp:get_user");
            assertThat(policy.timeoutMs()).isEqualTo(30000L);
            assertThat(policy.hasTimeout()).isTrue();
            assertThat(policy.isDefault()).isFalse();
        }

        @Test
        @DisplayName("a block with ONLY timeoutMs=0 stays DEFAULT (not stored - byte-identical execution)")
        void zeroTimeoutStaysDefault() {
            Map<String, Object> block = new HashMap<>();
            block.put("timeoutMs", 0);

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("mcps", mcpEntry(block)), "tenant-1");

            assertThat(parsed.getNodePolicies()).isEmpty();
            assertThat(parsed.getNodePolicy("mcp:get_user")).isEqualTo(NodePolicy.DEFAULT);
        }

        @Test
        @DisplayName("negative timeoutMs is rejected at parse time, naming the node and the field")
        void negativeTimeoutRejected() {
            Map<String, Object> block = policyBlock(0, 0, false);
            block.put("timeoutMs", -5000);

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("mcps", mcpEntry(block)), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs")
                .hasMessageContaining("mcp:get_user")
                .hasMessageContaining("-5000");
        }

        @Test
        @DisplayName("non-numeric timeoutMs is rejected with a clear error")
        void nonNumericTimeoutRejected() {
            Map<String, Object> block = policyBlock(0, 0, false);
            block.put("timeoutMs", "forever");

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("mcps", mcpEntry(block)), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs")
                .hasMessageContaining("not a number");
        }

        @Test
        @DisplayName("executeOnce is parsed (boolean or string) on an mcp entry")
        void executeOnceParsed() {
            Map<String, Object> block = new HashMap<>();
            block.put("executeOnce", "true");

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("mcps", mcpEntry(block)), "tenant-1");

            NodePolicy policy = parsed.getNodePolicy("mcp:get_user");
            assertThat(policy.executeOnce()).isTrue();
            assertThat(policy.isDefault()).isFalse();
        }

        @Test
        @DisplayName("non-boolean executeOnce is rejected with a clear error")
        void nonBooleanExecuteOnceRejected() {
            Map<String, Object> block = new HashMap<>();
            block.put("executeOnce", "sometimes");

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("mcps", mcpEntry(block)), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executeOnce")
                .hasMessageContaining("boolean");
        }
    }

    // =====================================================================
    // executeOnce incompatible-core rejection (split/aggregate/merge/loop)
    // =====================================================================

    @Nested
    @DisplayName("executeOnce incompatible-core rejection (split / aggregate / merge / loop)")
    class ExecuteOnceIncompatibleCoreRejection {

        private Map<String, Object> coreEntry(String type, String label, boolean executeOnce) {
            Map<String, Object> block = new HashMap<>();
            block.put("executeOnce", executeOnce);
            return new HashMap<>(Map.of(
                "id", type + "-1", "type", type, "label", label,
                "nodePolicy", block));
        }

        @Test
        @DisplayName("executeOnce=true on the SPLIT node itself is rejected, naming the node and pointing at body nodes")
        void executeOnceOnSplitRejected() {
            Map<String, Object> entry = coreEntry("split", "Process Items", true);

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:process_items")
                .hasMessageContaining("executeOnce")
                .hasMessageContaining("split")
                .hasMessageContaining("INSIDE the split scope");
        }

        @Test
        @DisplayName("executeOnce=true on an AGGREGATE core is rejected (aggregate consumes ALL items by design)")
        void executeOnceOnAggregateRejected() {
            Map<String, Object> entry = coreEntry("aggregate", "Collect Results", true);

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:collect_results")
                .hasMessageContaining("aggregate")
                .hasMessageContaining("ALL split items");
        }

        @Test
        @DisplayName("executeOnce=true on a MERGE core is rejected (convergence barrier)")
        void executeOnceOnMergeRejected() {
            Map<String, Object> entry = coreEntry("merge", "Join Branches", true);

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:join_branches")
                .hasMessageContaining("merge");
        }

        @Test
        @DisplayName("executeOnce=true on a LOOP core is rejected as AMBIGUOUS (first-iteration-only is NOT implemented)")
        void executeOnceOnLoopRejected() {
            Map<String, Object> entry = coreEntry("loop", "Retry Until Done", true);

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:retry_until_done")
                .hasMessageContaining("loop")
                .hasMessageContaining("never loop iterations");
        }

        @Test
        @DisplayName("executeOnce=false on a split core stays ALLOWED (only the true flag is incompatible)")
        void executeOnceFalseAllowedOnSplit() {
            Map<String, Object> block = new HashMap<>();
            block.put("executeOnce", false);
            block.put("retryCount", 1);
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "split-1", "type", "split", "label", "Process Items",
                "nodePolicy", block));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");

            assertThat(parsed.getNodePolicy("core:process_items"))
                .isEqualTo(new NodePolicy(1, 0L, false, 0L, false));
        }

        @Test
        @DisplayName("executeOnce=true stays ALLOWED on a non-listed core (transform) and on mcp entries")
        void executeOnceAllowedElsewhere() {
            Map<String, Object> coreEntry = coreEntry("transform", "Format Data", true);
            WorkflowPlan coreParsed = WorkflowPlan.fromMap(planWith("cores", coreEntry), "tenant-1");
            assertThat(coreParsed.getNodePolicy("core:format_data").executeOnce()).isTrue();

            Map<String, Object> block = new HashMap<>();
            block.put("executeOnce", true);
            Map<String, Object> mcpEntry = new HashMap<>(Map.of(
                "id", "slack/send", "label", "Notify Once",
                "nodePolicy", block));
            WorkflowPlan mcpParsed = WorkflowPlan.fromMap(planWith("mcps", mcpEntry), "tenant-1");
            assertThat(mcpParsed.getNodePolicy("mcp:notify_once").executeOnce()).isTrue();
        }

        @Test
        @DisplayName("timeoutMs/retryCount remain ALLOWED on split/aggregate/merge/loop cores (only executeOnce is gated)")
        void otherFieldsAllowedOnGatedCores() {
            Map<String, Object> block = new HashMap<>();
            block.put("retryCount", 2);
            block.put("timeoutMs", 10000);
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "merge-1", "type", "merge", "label", "Join Branches",
                "nodePolicy", block));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");

            assertThat(parsed.getNodePolicy("core:join_branches"))
                .isEqualTo(new NodePolicy(2, 0L, false, 10000L, false));
        }
    }

    // =====================================================================
    // Branching-node rejection - continueOnFailure on single-port branching
    // cores would fan out ALL ports at once (audit item 5)
    // =====================================================================

    @Nested
    @DisplayName("Branching-node rejection (continueOnFailure on decision/switch/option)")
    class BranchingNodeRejection {

        private Map<String, Object> coreEntry(String type, String label, Map<String, Object> policy) {
            return new HashMap<>(Map.of(
                "id", type + "-1", "type", type, "label", label,
                "nodePolicy", policy));
        }

        @Test
        @DisplayName("continueOnFailure=true on a DECISION core is rejected, naming the node and the fan-out reason")
        void continueOnFailureOnDecisionRejected() {
            Map<String, Object> entry = coreEntry("decision", "Check Status", policyBlock(0, 0, true));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:check_status")
                .hasMessageContaining("continueOnFailure")
                .hasMessageContaining("decision")
                .hasMessageContaining("ALL its ports");
        }

        @Test
        @DisplayName("continueOnFailure=true on a SWITCH core is rejected")
        void continueOnFailureOnSwitchRejected() {
            Map<String, Object> entry = coreEntry("switch", "Route By Type", policyBlock(1, 100, true));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:route_by_type")
                .hasMessageContaining("continueOnFailure")
                .hasMessageContaining("switch");
        }

        @Test
        @DisplayName("continueOnFailure=true on an OPTION core is rejected")
        void continueOnFailureOnOptionRejected() {
            Map<String, Object> entry = coreEntry("option", "Pick One", policyBlock(0, 0, true));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:pick_one")
                .hasMessageContaining("option");
        }

        @Test
        @DisplayName("retryCount WITHOUT continueOnFailure stays ALLOWED on a decision core")
        void retryOnlyAllowedOnDecision() {
            Map<String, Object> entry = coreEntry("decision", "Check Status", policyBlock(2, 100, false));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");

            assertThat(parsed.getNodePolicy("core:check_status"))
                .isEqualTo(new NodePolicy(2, 100L, false));
        }

        @Test
        @DisplayName("continueOnFailure=true stays ALLOWED on a non-branching core (transform: linear continuation)")
        void continueOnFailureAllowedOnTransform() {
            Map<String, Object> entry = coreEntry("transform", "Format Data", policyBlock(0, 0, true));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");

            assertThat(parsed.getNodePolicy("core:format_data"))
                .isEqualTo(new NodePolicy(0, 0L, true));
        }

        @Test
        @DisplayName("continueOnFailure=true stays ALLOWED on a FORK core (fan-out-all IS its semantic)")
        void continueOnFailureAllowedOnFork() {
            Map<String, Object> entry = coreEntry("fork", "Parallel Work", policyBlock(0, 0, true));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");

            assertThat(parsed.getNodePolicy("core:parallel_work"))
                .isEqualTo(new NodePolicy(0, 0L, true));
        }
    }
}
