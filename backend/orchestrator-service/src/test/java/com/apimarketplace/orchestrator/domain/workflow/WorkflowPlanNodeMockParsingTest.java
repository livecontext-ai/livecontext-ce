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
 * Parser tests for the optional {@code mock} block on plan node entries
 * (per-node mock mode) - section collection, node-type compatibility rules,
 * key normalization and {@code getNodeMock} resolution.
 */
@DisplayName("WorkflowPlanParser - mock block")
class WorkflowPlanNodeMockParsingTest {

    private static Map<String, Object> planWith(String arrayName, Map<String, Object> entry) {
        Map<String, Object> plan = new HashMap<>();
        plan.put(arrayName, List.of(entry));
        return plan;
    }

    private static Map<String, Object> mcpEntry(Map<String, Object> mock) {
        return new HashMap<>(Map.of(
            "id", "gmail/gmail-list-messages", "label", "Fetch Emails",
            "mock", mock));
    }

    private static Map<String, Object> decisionEntry(Map<String, Object> mock) {
        Map<String, Object> entry = new HashMap<>(Map.of(
            "id", "decision-1", "type", "decision", "label", "Check Status",
            "decisionConditions", List.of(
                new HashMap<>(Map.of("id", "c1", "type", "if", "expression", "x > 1")),
                new HashMap<>(Map.of("id", "c2", "type", "elsif", "expression", "x > 0")),
                new HashMap<>(Map.of("id", "c3", "type", "else")))));
        entry.put("mock", mock);
        return entry;
    }

    // =====================================================================
    // Collection per section, key normalization, resolution
    // =====================================================================

    @Nested
    @DisplayName("Collection and resolution")
    class CollectionAndResolution {

        @Test
        @DisplayName("a node WITHOUT a mock block resolves to null (nothing stored)")
        void absentBlockResolvesToNull() {
            Map<String, Object> plan = planWith("mcps", new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Get User")));

            WorkflowPlan parsed = WorkflowPlan.fromMap(plan, "tenant-1");

            assertThat(parsed.getNodeMocks()).isEmpty();
            assertThat(parsed.getNodeMock("mcp:get_user")).isNull();
        }

        @Test
        @DisplayName("an EMPTY mock block (the 'clear' form) stores nothing")
        void emptyBlockStoresNothing() {
            WorkflowPlan parsed = WorkflowPlan.fromMap(
                planWith("mcps", mcpEntry(Map.of())), "tenant-1");

            assertThat(parsed.getNodeMocks()).isEmpty();
        }

        @Test
        @DisplayName("mcp entry: stored under mcp:<normalized label> and resolvable via getNodeMock")
        void mcpMockKeyed() {
            WorkflowPlan parsed = WorkflowPlan.fromMap(
                planWith("mcps", mcpEntry(Map.of("output", Map.of("messages", List.of(), "result_count", 0)))),
                "tenant-1");

            NodeMock mock = parsed.getNodeMock("mcp:fetch_emails");
            assertThat(mock).isNotNull();
            assertThat(mock.isStatic()).isTrue();
            assertThat(mock.output()).containsEntry("result_count", 0);
        }

        @Test
        @DisplayName("table entry: static mock stored under table:<normalized label>")
        void tableMockKeyed() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "type", "crud-find", "label", "Find Processed", "dataSourceId", "123",
                "mock", Map.of("output", Map.of("rows", List.of(), "item_count", 0))));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("tables", entry), "tenant-1");

            assertThat(parsed.getNodeMock("table:find_processed")).isNotNull();
        }

        @Test
        @DisplayName("agent entry: static output mock stored under agent:<normalized label>")
        void agentMockKeyed() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "agent-1", "type", "agent", "label", "Research Agent",
                "mock", Map.of("output", Map.of("response", "mocked answer"))));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("agents", entry), "tenant-1");

            assertThat(parsed.getNodeMock("agent:research_agent")).isNotNull();
        }

        @Test
        @DisplayName("interface entry: static mock stored under interface:<normalized label>")
        void interfaceMockKeyed() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "interface-1", "label", "Product Card",
                "mock", Map.of("output", Map.of("action", "__continue"))));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("interfaces", entry), "tenant-1");

            assertThat(parsed.getNodeMock("interface:product_card")).isNotNull();
        }

        @Test
        @DisplayName("getNodeMock resolves a port-suffixed nodeId to the base core's mock (core:check_status:if)")
        void portSuffixedLookupResolvesBaseNode() {
            WorkflowPlan parsed = WorkflowPlan.fromMap(
                planWith("cores", decisionEntry(Map.of("port", "if"))), "tenant-1");

            NodeMock mock = parsed.getNodeMock("core:check_status:if");
            assertThat(mock).isNotNull();
            assertThat(mock.port()).isEqualTo("if");
        }

        @Test
        @DisplayName("a mock block on a TRIGGER is ignored (fake payloads = trigger_payload on manual fires)")
        void triggerMockIgnored() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "trigger-1", "type", "webhook", "label", "My Webhook",
                "mock", Map.of("output", Map.of("x", 1))));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("triggers", entry), "tenant-1");

            assertThat(parsed.getNodeMocks()).isEmpty();
        }

        @Test
        @DisplayName("a DISABLED mock is still collected (parked, surfaced by UI/agent, just not applied)")
        void disabledMockCollected() {
            WorkflowPlan parsed = WorkflowPlan.fromMap(
                planWith("mcps", mcpEntry(Map.of("enabled", false, "output", Map.of("x", 1)))),
                "tenant-1");

            NodeMock mock = parsed.getNodeMock("mcp:fetch_emails");
            assertThat(mock).isNotNull();
            assertThat(mock.isEffective()).isFalse();
        }
    }

    // =====================================================================
    // catalog_example - mcp catalog tools only
    // =====================================================================

    @Nested
    @DisplayName("catalog_example source rules")
    class CatalogExampleRules {

        @Test
        @DisplayName("catalog_example is accepted on an mcp entry with a slug-form tool id")
        void catalogExampleOnMcpAccepted() {
            WorkflowPlan parsed = WorkflowPlan.fromMap(
                planWith("mcps", mcpEntry(Map.of("source", "catalog_example"))), "tenant-1");

            assertThat(parsed.getNodeMock("mcp:fetch_emails").isCatalogExample()).isTrue();
        }

        @Test
        @DisplayName("catalog_example on a CRUD-style mcp id (crud/...) is rejected with guidance")
        void catalogExampleOnCrudRejected() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "crud/find_rows", "label", "Find Rows",
                "mock", Map.of("source", "catalog_example")));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("mcps", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp:find_rows")
                .hasMessageContaining("catalog_example")
                .hasMessageContaining("static");
        }

        @Test
        @DisplayName("catalog_example on a table entry is rejected")
        void catalogExampleOnTableRejected() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "type", "crud-find", "label", "Find Rows", "dataSourceId", "1",
                "mock", Map.of("source", "catalog_example")));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("tables", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table:find_rows")
                .hasMessageContaining("catalog_example");
        }

        @Test
        @DisplayName("catalog_example on an agent or core entry is rejected")
        void catalogExampleOnAgentAndCoreRejected() {
            Map<String, Object> agentEntry = new HashMap<>(Map.of(
                "id", "a-1", "type", "agent", "label", "Agent",
                "mock", Map.of("source", "catalog_example")));
            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("agents", agentEntry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent:agent")
                .hasMessageContaining("catalog_example");

            Map<String, Object> coreEntry = new HashMap<>(Map.of(
                "id", "t-1", "type", "transform", "label", "Format",
                "mock", Map.of("source", "catalog_example")));
            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", coreEntry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:format")
                .hasMessageContaining("catalog_example");
        }
    }

    // =====================================================================
    // Ports - required and validated on branching nodes, forbidden elsewhere
    // =====================================================================

    @Nested
    @DisplayName("Port rules")
    class PortRules {

        @Test
        @DisplayName("decision mock with a valid port is accepted (if / elseif_0 / else all derivable)")
        void decisionValidPortsAccepted() {
            for (String port : List.of("if", "elseif_0", "else")) {
                WorkflowPlan parsed = WorkflowPlan.fromMap(
                    planWith("cores", decisionEntry(Map.of("port", port))), "tenant-1");
                assertThat(parsed.getNodeMock("core:check_status").port()).isEqualTo(port);
            }
        }

        @Test
        @DisplayName("decision mock WITHOUT a port is rejected, listing the valid ports")
        void decisionWithoutPortRejected() {
            assertThatThrownBy(() -> WorkflowPlan.fromMap(
                planWith("cores", decisionEntry(Map.of("output", Map.of()))), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:check_status")
                .hasMessageContaining("must select a branch")
                .hasMessageContaining("if");
        }

        @Test
        @DisplayName("decision mock with an UNKNOWN port is rejected, listing the valid ports")
        void decisionUnknownPortRejected() {
            assertThatThrownBy(() -> WorkflowPlan.fromMap(
                planWith("cores", decisionEntry(Map.of("port", "elseif_7"))), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:check_status")
                .hasMessageContaining("elseif_7")
                .hasMessageContaining("elseif_0");
        }

        @Test
        @DisplayName("option mock WITHOUT a port is rejected with the corrected article: 'an option mock must select a branch'")
        void optionWithoutPortRejected_usesAnArticle() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "option-1", "type", "option", "label", "Pick Path",
                "optionChoices", List.of(
                    new HashMap<>(Map.of("id", "o1", "label", "A", "expression", "x == 1")),
                    new HashMap<>(Map.of("id", "o2", "label", "B", "expression", "x == 2")))));
            entry.put("mock", Map.of("output", Map.of()));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:pick_path")
                .hasMessageContaining("an option mock must select a branch")
                .hasMessageContaining("choice_0");
        }

        @Test
        @DisplayName("switch mock port is validated against case_N/default")
        void switchPortValidated() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "switch-1", "type", "switch", "label", "Route",
                "switchCases", List.of(
                    new HashMap<>(Map.of("id", "s1", "type", "case", "value", "a")),
                    new HashMap<>(Map.of("id", "s2", "type", "default")))));
            entry.put("mock", Map.of("port", "case_0"));
            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");
            assertThat(parsed.getNodeMock("core:route").port()).isEqualTo("case_0");

            entry.put("mock", Map.of("port", "case_9"));
            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("case_9")
                .hasMessageContaining("default");
        }

        @Test
        @DisplayName("approval mock accepts approved/rejected/timeout, with optional output")
        void approvalPortsAccepted() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "approval-1", "type", "approval", "label", "Manager OK"));
            entry.put("mock", Map.of("port", "approved", "output", Map.of("responded_by", "mock-user")));

            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1");

            NodeMock mock = parsed.getNodeMock("core:manager_ok");
            assertThat(mock.port()).isEqualTo("approved");
            assertThat(mock.output()).containsEntry("responded_by", "mock-user");
        }

        @Test
        @DisplayName("approval mock without a port is rejected")
        void approvalWithoutPortRejected() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "approval-1", "type", "approval", "label", "Manager OK",
                "mock", Map.of("output", Map.of())));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:manager_ok")
                .hasMessageContaining("approved");
        }

        @Test
        @DisplayName("port on an mcp / table / interface / non-branching core is rejected")
        void portOnNonBranchingRejected() {
            assertThatThrownBy(() -> WorkflowPlan.fromMap(
                planWith("mcps", mcpEntry(Map.of("port", "if"))), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp:fetch_emails")
                .hasMessageContaining("port");

            Map<String, Object> coreEntry = new HashMap<>(Map.of(
                "id", "w-1", "type", "wait", "label", "Pause",
                "mock", Map.of("port", "if")));
            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("cores", coreEntry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:pause")
                .hasMessageContaining("no ports");
        }

        @Test
        @DisplayName("classify agent mock requires port='category_N' within range")
        void classifyAgentPortRules() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "a-1", "type", "classify", "label", "Sort Email",
                "classifyCategories", List.of(
                    Map.of("name", "urgent"), Map.of("name", "normal"))));

            // Valid port accepted
            entry.put("mock", Map.of("port", "category_1"));
            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("agents", entry), "tenant-1");
            assertThat(parsed.getNodeMock("agent:sort_email").port()).isEqualTo("category_1");

            // Out-of-range port rejected
            entry.put("mock", Map.of("port", "category_5"));
            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("agents", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category_5")
                .hasMessageContaining("category_0");

            // Static without port rejected (classify must route)
            entry.put("mock", Map.of("output", Map.of("x", 1)));
            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("agents", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent:sort_email")
                .hasMessageContaining("category_N");
        }

        @Test
        @DisplayName("port on a plain (non-classify) agent is rejected")
        void portOnPlainAgentRejected() {
            Map<String, Object> entry = new HashMap<>(Map.of(
                "id", "a-1", "type", "agent", "label", "Research Agent",
                "mock", Map.of("port", "category_0")));

            assertThatThrownBy(() -> WorkflowPlan.fromMap(planWith("agents", entry), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent:research_agent")
                .hasMessageContaining("classify");
        }
    }

    // =====================================================================
    // Incompatible cores - split / merge / aggregate / loop / fork
    // =====================================================================

    @Nested
    @DisplayName("Incompatible core rejection (split / merge / aggregate / loop / fork)")
    class IncompatibleCores {

        private Map<String, Object> coreEntry(String type, String label) {
            return new HashMap<>(Map.of(
                "id", type + "-1", "type", type, "label", label,
                "mock", Map.of("output", Map.of("items", List.of(1, 2)))));
        }

        @Test
        @DisplayName("mock on the SPLIT node is rejected, pointing at the feeding node instead")
        void splitRejected() {
            assertThatThrownBy(() -> WorkflowPlan.fromMap(
                planWith("cores", coreEntry("split", "Process Items")), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core:process_items")
                .hasMessageContaining("FEEDS the split");
        }

        @Test
        @DisplayName("mock on merge / aggregate / loop / fork cores is rejected with per-type guidance")
        void otherCoordinationCoresRejected() {
            assertThatThrownBy(() -> WorkflowPlan.fromMap(
                planWith("cores", coreEntry("merge", "Join")), "tenant-1"))
                .hasMessageContaining("core:join")
                .hasMessageContaining("convergence barrier");
            assertThatThrownBy(() -> WorkflowPlan.fromMap(
                planWith("cores", coreEntry("aggregate", "Collect")), "tenant-1"))
                .hasMessageContaining("core:collect");
            assertThatThrownBy(() -> WorkflowPlan.fromMap(
                planWith("cores", coreEntry("loop", "Retry")), "tenant-1"))
                .hasMessageContaining("core:retry")
                .hasMessageContaining("loop body");
            assertThatThrownBy(() -> WorkflowPlan.fromMap(
                planWith("cores", coreEntry("fork", "Parallel")), "tenant-1"))
                .hasMessageContaining("core:parallel")
                .hasMessageContaining("fan-out");
        }

        @Test
        @DisplayName("mock stays ALLOWED on plain cores (transform / wait / code)")
        void plainCoresAllowed() {
            Map<String, Object> transform = new HashMap<>(Map.of(
                "id", "t-1", "type", "transform", "label", "Format",
                "mock", Map.of("output", Map.of("result", "ok"))));
            WorkflowPlan parsed = WorkflowPlan.fromMap(planWith("cores", transform), "tenant-1");
            assertThat(parsed.getNodeMock("core:format")).isNotNull();

            Map<String, Object> wait = new HashMap<>(Map.of(
                "id", "w-1", "type", "wait", "label", "Pause",
                "mock", Map.of("output", Map.of())));
            parsed = WorkflowPlan.fromMap(planWith("cores", wait), "tenant-1");
            assertThat(parsed.getNodeMock("core:pause")).isNotNull();
        }
    }

    // =====================================================================
    // Static output requirement
    // =====================================================================

    @Nested
    @DisplayName("Static output requirement")
    class StaticOutputRequirement {

        @Test
        @DisplayName("a static mock with neither output nor port is rejected (authoring mistake)")
        void outputlessStaticRejected() {
            assertThatThrownBy(() -> WorkflowPlan.fromMap(
                planWith("mcps", mcpEntry(Map.of("enabled", true))), "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp:fetch_emails")
                .hasMessageContaining("output");
        }

        @Test
        @DisplayName("output={} is the documented explicit empty form and is accepted")
        void explicitEmptyOutputAccepted() {
            WorkflowPlan parsed = WorkflowPlan.fromMap(
                planWith("mcps", mcpEntry(Map.of("output", Map.of()))), "tenant-1");

            NodeMock mock = parsed.getNodeMock("mcp:fetch_emails");
            assertThat(mock.output()).isEmpty();
        }
    }
}
