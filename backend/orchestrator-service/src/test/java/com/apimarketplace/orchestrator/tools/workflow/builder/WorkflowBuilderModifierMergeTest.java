package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end merge-semantic tests for {@code workflow(action='modify')}.
 *
 * <p>For each supported node type, verifies that calling
 * {@link WorkflowBuilderModifier#executeModifyNode} with a partial update
 * MERGES with existing data (preserving untouched fields) instead of
 * REPLACING the whole node. The merge logic itself lives in
 * {@link NodeFieldMerger}; harmonization (key-routing) lives in
 * {@code WorkflowBuilderModifier.harmonizeParams}. Together they implement
 * the contract documented in {@code NodeFieldMerger}'s javadoc.
 *
 * <p>This test exercises the full path (harmonize → merge → apply) so
 * regressions in either layer surface here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - merge semantics across node types")
class WorkflowBuilderModifierMergeTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderModifier modifier;

    @BeforeEach
    void setUp() {
        modifier = new WorkflowBuilderModifier(sessionStore);
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findMcp(WorkflowBuilderSession session, String label) {
        String nodeId = "mcp:" + WorkflowBuilderSession.normalizeLabel(label);
        return session.getMcps().stream()
                .filter(m -> nodeId.equals(m.get("id")) || label.equals(m.get("label")))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findCore(WorkflowBuilderSession session, String label) {
        String nodeId = "core:" + WorkflowBuilderSession.normalizeLabel(label);
        return session.getCores().stream()
                .filter(c -> nodeId.equals(c.get("id")))
                .findFirst().orElseThrow();
    }

    private Map<String, Object> findTrigger(WorkflowBuilderSession session, String label) {
        return session.getTriggers().stream()
                .filter(t -> label.equals(t.get("label")))
                .findFirst().orElseThrow();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MCP nodes - the main bug the user hit
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MCP node - flat tool params route into node.params and merge")
    class McpNode {

        private WorkflowBuilderSession sessionWithGmailNode() {
            WorkflowBuilderSession session = createSession();
            // Pre-existing MCP node with the canonical structure McpCreator produces.
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "d67b712e-c76e-4bae-a17e-24fe7f73a589"); // catalog tool UUID
            node.put("type", "mcp");
            node.put("label", "Lire Email");
            node.put("params", new LinkedHashMap<>(Map.of(
                    "userId", "me",
                    "format", "metadata"
            )));
            session.getMcps().add(node);
            return session;
        }

        @Test
        @DisplayName("flat tool params land in node.params (not at top level)")
        void flatParamsRouteIntoNodeParams() {
            WorkflowBuilderSession session = sessionWithGmailNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Lire Email");
            args.put("params", Map.of("messageId", "{{...}}", "format", "full"));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).isTrue();

            Map<String, Object> node = findMcp(session, "Lire Email");
            // Canonical id MUST NOT have been touched
            assertThat(node.get("id")).isEqualTo("d67b712e-c76e-4bae-a17e-24fe7f73a589");
            // Top level should still only contain meta fields
            assertThat(node).doesNotContainKey("messageId");
            // Tool params merged into node.params
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params)
                    .containsEntry("userId", "me")          // preserved
                    .containsEntry("messageId", "{{...}}")  // added
                    .containsEntry("format", "full");       // updated
        }

        @Test
        @DisplayName("tool_id swaps the canonical node.id (catalog UUID change)")
        void toolIdSwapsCanonicalId() {
            WorkflowBuilderSession session = sessionWithGmailNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Lire Email");
            args.put("params", Map.of("tool_id", "11111111-2222-3333-4444-555555555555"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> node = findMcp(session, "Lire Email");
            assertThat(node.get("id")).isEqualTo("11111111-2222-3333-4444-555555555555");
            // params still preserved
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params).containsEntry("userId", "me").containsEntry("format", "metadata");
            // tool_id should NOT survive as a top-level orphan
            assertThat(node).doesNotContainKey("tool_id");
        }

        @Test
        @DisplayName("a tool param literally named 'id' lands in node.params, not on top level")
        void toolParamNamedIdDoesNotCorruptNodeId() {
            WorkflowBuilderSession session = sessionWithGmailNode();
            String originalUuid = (String) findMcp(session, "Lire Email").get("id");

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Lire Email");
            // Gmail get_message has a param literally named 'id' (the messageId).
            // The previous bug overwrote node.id with this template string.
            args.put("params", Map.of("id", "{{core:parcourir_emails.output.current_item.id}}"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> node = findMcp(session, "Lire Email");
            assertThat(node.get("id"))
                    .as("canonical node.id (catalog UUID) must NOT be overwritten by a tool param named 'id'")
                    .isEqualTo(originalUuid);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params).containsEntry("id", "{{core:parcourir_emails.output.current_item.id}}");
        }

        @Test
        @DisplayName("explicit params={...} entry is also routed (LLM may send shaped or flat)")
        void explicitParamsEntryAlsoMerges() {
            WorkflowBuilderSession session = sessionWithGmailNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Lire Email");
            // LLM sends BOTH a shaped params and a flat key - both should land
            // in the merged node.params (no top-level orphans).
            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("params", Map.of("format", "full"));
            outer.put("messageId", "{{X}}");
            args.put("params", outer);

            modifier.executeModifyNode(session, args);

            Map<String, Object> node = findMcp(session, "Lire Email");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params)
                    .containsEntry("userId", "me")
                    .containsEntry("format", "full")
                    .containsEntry("messageId", "{{X}}");
            assertThat(node).doesNotContainKey("messageId");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Schedule trigger - same routing fix as MCP
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Schedule trigger - flat params merge into node.params")
    class ScheduleTrigger {

        private WorkflowBuilderSession sessionWithSchedule() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("id", "trigger:declencheur");
            trigger.put("type", "schedule");
            trigger.put("label", "Déclencheur");
            trigger.put("params", new LinkedHashMap<>(Map.of(
                    "cron", "*/15 * * * *",
                    "timezone", "UTC",
                    "enabled", true
            )));
            session.getTriggers().add(trigger);
            return session;
        }

        @Test
        @DisplayName("changing only cron preserves timezone and enabled")
        void cronOnlyPreservesOtherParams() {
            WorkflowBuilderSession session = sessionWithSchedule();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Déclencheur");
            args.put("params", Map.of("cron", "0 9 * * *"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> trigger = findTrigger(session, "Déclencheur");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) trigger.get("params");
            assertThat(params)
                    .containsEntry("cron", "0 9 * * *")
                    .containsEntry("timezone", "UTC")
                    .containsEntry("enabled", true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Classify agent - list-by-label merge
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Classify agent - adding a category preserves existing ones")
    class ClassifyAgent {

        private WorkflowBuilderSession sessionWithClassifier() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("id", "agent:classifier");
            agent.put("type", "classify");
            agent.put("label", "Classifier");
            agent.put("isAgent", true);
            agent.put("isClassify", true);
            agent.put("classifyCategories", List.of(
                    new LinkedHashMap<>(Map.of("label", "Finance", "description", "Bank")),
                    new LinkedHashMap<>(Map.of("label", "Tech", "description", "Dev tools"))
            ));
            session.getMcps().add(agent);  // agents are stored in mcps with isAgent=true
            return session;
        }

        @Test
        @DisplayName("adds Spam without losing Finance/Tech")
        void addsCategoryPreservingOthers() {
            WorkflowBuilderSession session = sessionWithClassifier();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Classifier");
            args.put("params", Map.of("classifyCategories", List.of(
                    Map.of("label", "Spam", "description", "Junk email")
            )));

            modifier.executeModifyNode(session, args);

            Map<String, Object> node = findMcp(session, "Classifier");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories = (List<Map<String, Object>>) node.get("classifyCategories");
            assertThat(categories).hasSize(3);
            assertThat(categories).extracting(c -> c.get("label"))
                    .containsExactly("Spam", "Finance", "Tech");
        }

        @Test
        @DisplayName("updating an existing category by label merges its fields")
        void updateCategoryByLabel() {
            WorkflowBuilderSession session = sessionWithClassifier();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Classifier");
            args.put("params", Map.of("classifyCategories", List.of(
                    Map.of("label", "Finance", "description", "Banks, invoices, payments")
            )));

            modifier.executeModifyNode(session, args);

            Map<String, Object> node = findMcp(session, "Classifier");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories = (List<Map<String, Object>>) node.get("classifyCategories");
            assertThat(categories).hasSize(2);
            Map<String, Object> finance = categories.stream()
                    .filter(c -> "Finance".equals(c.get("label"))).findFirst().orElseThrow();
            assertThat(finance.get("description")).isEqualTo("Banks, invoices, payments");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Decision node - REPLACE semantic (positional roles)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Decision node - list is REPLACED (positional if/elseif/else)")
    class DecisionNode {

        @Test
        @DisplayName("sending two new conditions replaces the list (does not merge)")
        void replacesEntireList() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:check");
            node.put("type", "decision");
            node.put("label", "Check");
            node.put("decisionConditions", List.of(
                    Map.of("id", "check-if", "type", "if", "label", "Yes", "expression", "true"),
                    Map.of("id", "check-else", "type", "else", "label", "No", "expression", "default")
            ));
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Check");
            args.put("params", Map.of("conditions", List.of(
                    Map.of("condition", "{{x}} > 0", "label", "Positive"),
                    Map.of("condition", "default", "label", "Negative")
            )));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findCore(session, "Check");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) modified.get("decisionConditions");
            assertThat(conditions).hasSize(2);  // not 4 - replace, not merge
            assertThat(conditions).extracting(c -> c.get("label"))
                    .containsExactly("Positive", "Negative");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTTP request - nested config merge (regression coverage)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("HTTP request - nested config merge still works")
    class HttpRequestNode {

        @Test
        @DisplayName("changing url preserves headers and method")
        void changingUrlPreservesNestedFields() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:fetch_data");
            node.put("type", "http_request");
            node.put("label", "Fetch Data");
            Map<String, Object> httpConfig = new LinkedHashMap<>();
            httpConfig.put("url", "https://api.example.com/old");
            httpConfig.put("method", "GET");
            httpConfig.put("headers", Map.of("Authorization", "Bearer abc"));
            node.put("httpRequest", httpConfig);
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Fetch Data");
            args.put("params", Map.of("url", "https://api.example.com/new"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findCore(session, "Fetch Data");
            @SuppressWarnings("unchecked")
            Map<String, Object> http = (Map<String, Object>) modified.get("httpRequest");
            assertThat(http).containsEntry("url", "https://api.example.com/new")
                    .containsEntry("method", "GET");
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) http.get("headers");
            assertThat(headers).containsEntry("Authorization", "Bearer abc");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Interface node - actionMapping/variableMapping merge
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Interface node - actionMapping merges entries")
    class InterfaceNode {

        @Test
        @DisplayName("adding one action mapping preserves the others")
        void addingOneActionMappingPreservesOthers() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> ifaceNode = new LinkedHashMap<>();
            ifaceNode.put("id", "interface:dashboard");
            ifaceNode.put("type", "interface");
            ifaceNode.put("label", "Dashboard");
            ifaceNode.put("actionMapping", new LinkedHashMap<>(Map.of(
                    "save", "{{trigger:save}}",
                    "delete", "{{trigger:delete}}"
            )));
            session.getInterfaces().add(ifaceNode);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Dashboard");
            args.put("params", Map.of("action_mapping", Map.of(
                    "export", "{{trigger:export}}"
            )));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = session.getInterfaces().stream()
                    .filter(i -> "interface:dashboard".equals(i.get("id")))
                    .findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            Map<String, Object> mapping = (Map<String, Object>) modified.get("actionMapping");
            assertThat(mapping).hasSize(3)
                    .containsEntry("save", "{{trigger:save}}")
                    .containsEntry("delete", "{{trigger:delete}}")
                    .containsEntry("export", "{{trigger:export}}");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Switch node - list-by-label merge end-to-end
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Switch node - switchCases merge by label end-to-end")
    class SwitchNode {

        @Test
        @DisplayName("adding a new case preserves existing cases and updates by label")
        void addNewCasePreservesExisting() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:route");
            node.put("type", "switch");
            node.put("label", "Route");
            node.put("switchCases", List.of(
                    new LinkedHashMap<>(Map.of("id", "route-case-0", "type", "case", "label", "A", "value", "1")),
                    new LinkedHashMap<>(Map.of("id", "route-case-1", "type", "case", "label", "B", "value", "2"))
            ));
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Route");
            args.put("params", Map.of("cases", List.of(
                    Map.of("label", "C", "value", "3"),
                    Map.of("label", "A", "value", "99")  // update existing A
            )));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findCore(session, "Route");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) modified.get("switchCases");
            assertThat(cases).hasSize(3);
            assertThat(cases).extracting(c -> c.get("label"))
                    .containsExactly("C", "A", "B"); // incoming first, preserved last
            // Updated A keeps its original auto-generated id
            Map<String, Object> aCase = cases.stream()
                    .filter(c -> "A".equals(c.get("label"))).findFirst().orElseThrow();
            assertThat(aCase.get("id")).isEqualTo("route-case-0");
            assertThat(aCase.get("value")).isEqualTo("99");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Webhook trigger - pre-existing routing must keep working (regression)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Webhook trigger - params merge via mergeIntoTriggerParams (regression)")
    class WebhookTrigger {

        @Test
        @DisplayName("changing httpMethod preserves authType and existing params")
        void changingHttpMethodPreservesAuth() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("id", "trigger:webhook");
            trigger.put("type", "webhook");
            trigger.put("label", "Incoming");
            trigger.put("params", new LinkedHashMap<>(Map.of(
                    "httpMethod", "GET",
                    "authType", "basic",
                    "basicUsername", "alice"
            )));
            session.getTriggers().add(trigger);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Incoming");
            args.put("params", Map.of("http_method", "POST"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findTrigger(session, "Incoming");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) modified.get("params");
            assertThat(params)
                    .containsEntry("httpMethod", "POST")
                    .containsEntry("authType", "basic")
                    .containsEntry("basicUsername", "alice");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Loop / Split - top-level field-level replace (regression)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Loop and Split - top-level fields update individually")
    class LoopAndSplit {

        @Test
        @DisplayName("Loop: updating maxIterations preserves loopCondition")
        void loopMaxIterationsOnly() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:retry");
            node.put("type", "loop");
            node.put("label", "Retry");
            node.put("loopCondition", "{{attempts}} < 3");
            node.put("maxIterations", 5);
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Retry");
            args.put("params", Map.of("max_iterations", 10));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findCore(session, "Retry");
            assertThat(modified.get("maxIterations")).isEqualTo(10);
            assertThat(modified.get("loopCondition")).isEqualTo("{{attempts}} < 3");
        }

        @Test
        @DisplayName("Split: updating list preserves maxItems and splitStrategy")
        void splitListOnly() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:iter");
            node.put("type", "split");
            node.put("label", "Iter");
            node.put("list", "{{old}}");
            node.put("maxItems", 50);
            node.put("splitStrategy", "fail-fast");
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Iter");
            args.put("params", Map.of("list", "{{new}}"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findCore(session, "Iter");
            assertThat(modified.get("list")).isEqualTo("{{new}}");
            assertThat(modified.get("maxItems")).isEqualTo(50);
            assertThat(modified.get("splitStrategy")).isEqualTo("fail-fast");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge cases - empty params, invalid input
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty params={} returns failure (no-op rejected)")
        void emptyParamsRejected() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "mcp:send_email");
            node.put("type", "mcp");
            node.put("label", "Send Email");
            node.put("params", new LinkedHashMap<>(Map.of("to", "x@y.z")));
            session.getMcps().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Send Email");
            args.put("params", Map.of());

            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("cannot be empty");
        }

        @Test
        @DisplayName("missing node parameter returns failure")
        void missingNodeRejected() {
            WorkflowBuilderSession session = createSession();
            ToolExecutionResult result = modifier.executeModifyNode(
                    session, Map.of("params", Map.of("foo", "bar")));
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("'node' parameter is required");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Agent node - top-level fields are field-level replaced
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent node - top-level fields field-level replace")
    class AgentNode {

        @Test
        @DisplayName("updating prompt preserves temperature and other fields")
        void updatingPromptPreservesOthers() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("id", "agent:analyzer");
            agent.put("type", "agent");
            agent.put("label", "Analyzer");
            agent.put("isAgent", true);
            agent.put("prompt", "Analyze: {{data}}");
            agent.put("temperature", 0.7);
            agent.put("agentConfigId", "cfg-123");
            session.getMcps().add(agent);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Analyzer");
            args.put("params", Map.of("prompt", "Summarize: {{data}}"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findMcp(session, "Analyzer");
            assertThat(modified.get("prompt")).isEqualTo("Summarize: {{data}}");
            assertThat(modified.get("temperature")).isEqualTo(0.7);
            assertThat(modified.get("agentConfigId")).isEqualTo("cfg-123");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Agent node - agent_id alias routes to canonical agentConfigId
    // (the "Stock Sentiment Pulse" bug from 2026-06-08)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent node - agent_id alias re-points the canonical agentConfigId")
    class AgentConfigIdRouting {

        private WorkflowBuilderSession sessionWithAgentNode() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("id", "node-uuid-1");
            agent.put("type", "agent");
            agent.put("label", "Narrate Sentiment");
            agent.put("isAgent", true);
            agent.put("prompt", "Narrate: {{data}}");
            agent.put("withMemory", false);
            // OLD agent (was deleted in the user's case)
            agent.put("agentConfigId", "3c6ca023-b9b7-415e-9036-c9501c13465e");
            agent.put("agentConfigName", "Old Narrator");
            agent.put("agentAvatarUrl", "https://avatars/old.png");
            session.getMcps().add(agent);
            return session;
        }

        /**
         * Reproduces the prod bug verbatim: the agent re-points a node at a new
         * agent entity via modify(params={agent_id: NEW}). Pre-fix this landed
         * as a zombie top-level `agent_id` (dropped on round-trip) while
         * agentConfigId stayed on the OLD (deleted) agent - so execution
         * resolved the wrong agent and the right-side panel showed the stale one.
         */
        @Test
        @DisplayName("agent_id updates the canonical agentConfigId (not a params echo)")
        void agentIdRoutesToCanonicalField() {
            WorkflowBuilderSession session = sessionWithAgentNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Narrate Sentiment");
            args.put("params", Map.of("agent_id", "809878da-8928-4e2f-940f-cd35ae88d8ae"));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).isTrue();

            Map<String, Object> node = findMcp(session, "Narrate Sentiment");
            // Canonical field re-pointed - read by runtime AND the panel.
            assertThat(node.get("agentConfigId"))
                    .as("modify(agent_id) must update the canonical top-level agentConfigId")
                    .isEqualTo("809878da-8928-4e2f-940f-cd35ae88d8ae");
            // No zombie aliases left behind.
            assertThat(node)
                    .doesNotContainKey("agent_id")
                    .doesNotContainKey("agentId");
            // Stale display cache from the OLD agent dropped so it re-resolves.
            assertThat(node)
                    .as("name/avatar of the previous agent must be cleared")
                    .doesNotContainKey("agentConfigName")
                    .doesNotContainKey("agentAvatarUrl");
            // Untouched node fields preserved.
            assertThat(node.get("prompt")).isEqualTo("Narrate: {{data}}");
            assertThat(node.get("withMemory")).isEqualTo(false);
        }

        @Test
        @DisplayName("camelCase agentId alias is also routed to agentConfigId")
        void camelCaseAliasAlsoRoutes() {
            WorkflowBuilderSession session = sessionWithAgentNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Narrate Sentiment");
            args.put("params", Map.of("agentId", "809878da-8928-4e2f-940f-cd35ae88d8ae"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> node = findMcp(session, "Narrate Sentiment");
            assertThat(node.get("agentConfigId")).isEqualTo("809878da-8928-4e2f-940f-cd35ae88d8ae");
            assertThat(node).doesNotContainKey("agentId");
        }

        @Test
        @DisplayName("re-setting the SAME agent id keeps the cached name/avatar")
        void sameIdKeepsDisplayCache() {
            WorkflowBuilderSession session = sessionWithAgentNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Narrate Sentiment");
            args.put("params", Map.of(
                    "agent_id", "3c6ca023-b9b7-415e-9036-c9501c13465e",  // unchanged
                    "prompt", "New prompt"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> node = findMcp(session, "Narrate Sentiment");
            assertThat(node.get("agentConfigId")).isEqualTo("3c6ca023-b9b7-415e-9036-c9501c13465e");
            // Unchanged id → display cache preserved (no needless flicker).
            assertThat(node.get("agentConfigName")).isEqualTo("Old Narrator");
            assertThat(node.get("agentAvatarUrl")).isEqualTo("https://avatars/old.png");
            assertThat(node.get("prompt")).isEqualTo("New prompt");
        }

        /**
         * Guard: classify/guardrail nodes also carry isAgent=true but have NO
         * agentConfigId (inline config). An agent_id-shaped param on them must
         * NOT be hijacked into agentConfigId - it stays a normal param merge.
         */
        @Test
        @DisplayName("classify node (isAgent=true, type=classify) is NOT treated as an agent-config node")
        void classifyNodeNotHijacked() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> classify = new LinkedHashMap<>();
            classify.put("id", "agent:classifier");
            classify.put("type", "classify");
            classify.put("label", "Classifier");
            classify.put("isAgent", true);
            classify.put("isClassify", true);
            session.getMcps().add(classify);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Classifier");
            args.put("params", Map.of("agent_id", "should-not-become-agentConfigId"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> node = findMcp(session, "Classifier");
            assertThat(node)
                    .as("only type=agent nodes get agent_id→agentConfigId routing")
                    .doesNotContainKey("agentConfigId");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Nested-config dual-write regression - the prod bug from 2026-05-14
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Nested-config nodes - modify writes into the nested slot, not top-level")
    class NestedConfigDualWriteRegression {

        /**
         * Reproduces the user's "Hourly Price Alert Monitor" bug verbatim. Set
         * node was created via add_node (correct shape: {set: {assignments:
         * [stale]}}), then the agent called modify with a corrected list of
         * assignments. Pre-fix, the LLM patch landed at top-level
         * (node.assignments), the engine kept reading node.set.assignments
         * (stale), and the node produced {price: null, triggered: false} every
         * run. The agent re-modified, re-saw null, looped 30+ tool calls.
         */
        @Test
        @DisplayName("set node: modify with assignments=[...] updates set.assignments (not top-level)")
        void setNodeModifyLandsInNestedSlot() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:set_stock_values");
            node.put("type", "set");
            node.put("label", "Set Stock Values");
            Map<String, Object> setConfig = new LinkedHashMap<>();
            setConfig.put("assignments", List.of(
                    new LinkedHashMap<>(Map.of("name", "price", "type", "number",
                            "value", "{{mcp:polygon_stock_price.output.results[0].c}}"))
            ));
            setConfig.put("keepOnlySet", true);
            node.put("set", setConfig);
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Set Stock Values");
            args.put("params", Map.of("assignments", List.of(
                    Map.of("name", "price", "type", "number",
                            "value", "{{mcp:stock_quote.output.results[0].c}}")
            )));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).isTrue();

            Map<String, Object> modified = findCore(session, "Set Stock Values");
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedSet = (Map<String, Object>) modified.get("set");
            assertThat(nestedSet).as("engine reads from set.*, modify must land there").isNotNull();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nestedAssignments =
                    (List<Map<String, Object>>) nestedSet.get("assignments");
            assertThat(nestedAssignments).hasSize(1);
            assertThat(nestedAssignments.get(0).get("value"))
                    .isEqualTo("{{mcp:stock_quote.output.results[0].c}}");
            assertThat(nestedSet.get("keepOnlySet"))
                    .as("untouched nested keys must be preserved").isEqualTo(true);
            assertThat(modified)
                    .as("no top-level orphan assignments - that was the prod bug")
                    .doesNotContainKey("assignments");
        }

        /**
         * Self-heal scenario. A node already in the broken dual-write state
         * (carrying BOTH set.assignments stale AND assignments top-level
         * orphan, from a pre-fix run) must converge to canonical shape on the
         * next modify, even if the LLM modifies an unrelated field.
         */
        @Test
        @DisplayName("self-heal: existing top-level orphan scrubbed when modify touches the node")
        void scrubsExistingTopLevelOrphan() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:set_stock_values");
            node.put("type", "set");
            node.put("label", "Set Stock Values");
            Map<String, Object> setConfig = new LinkedHashMap<>();
            setConfig.put("assignments", List.of(
                    Map.of("name", "price", "type", "number", "value", "42")
            ));
            node.put("set", setConfig);
            // Zombie left by a past buggy modify
            node.put("assignments", List.of(
                    Map.of("name", "wrong", "type", "string", "value", "stale")
            ));
            session.getCores().add(node);

            // Touch only the label - engine config untouched
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Set Stock Values");
            args.put("params", Map.of("label", "Set Stock Values Renamed"));

            modifier.executeModifyNode(session, args);

            // After rename, look up by the new label
            String newId = "core:" + WorkflowBuilderSession.normalizeLabel("Set Stock Values Renamed");
            Map<String, Object> modified = session.getCores().stream()
                    .filter(c -> newId.equals(c.get("id"))).findFirst().orElseThrow();
            assertThat(modified)
                    .as("orphan top-level assignments must be cleaned even on unrelated modify")
                    .doesNotContainKey("assignments");
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedSet = (Map<String, Object>) modified.get("set");
            assertThat(nestedSet).isNotNull();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nestedAssignments =
                    (List<Map<String, Object>>) nestedSet.get("assignments");
            assertThat(nestedAssignments).hasSize(1);
            assertThat(nestedAssignments.get(0).get("value")).isEqualTo("42");
        }

        /**
         * Generic coverage for the 7 other node types that were missing from
         * NESTED_CONFIG_KEYS before this fix. One representative each: code,
         * approval, html_extract, task, stop_on_error, ssh, sftp, database.
         * Each was at risk of the same dual-write because the parser only
         * reads from node[nestedKey].*.
         */
        @Test
        @DisplayName("code node: code field routes into code.code")
        void codeNodeRoutesIntoNestedSlot() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:transform_data");
            node.put("type", "code");
            node.put("label", "Transform Data");
            Map<String, Object> codeConfig = new LinkedHashMap<>();
            codeConfig.put("language", "javascript");
            codeConfig.put("code", "$output = { ok: true };");
            node.put("code", codeConfig);
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Transform Data");
            args.put("params", Map.of("code", "$output = { ok: false };"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findCore(session, "Transform Data");
            @SuppressWarnings("unchecked")
            Map<String, Object> codeNested = (Map<String, Object>) modified.get("code");
            assertThat(codeNested.get("code")).isEqualTo("$output = { ok: false };");
            assertThat(codeNested.get("language"))
                    .as("untouched language preserved").isEqualTo("javascript");
            assertThat(modified)
                    .as("'code' is also the nested key name - must stay as the slot, not be shadowed")
                    .doesNotContainKey("language");  // ← was at risk pre-fix
        }

        @Test
        @DisplayName("approval node: message field routes into approval.message")
        void approvalNodeRoutesIntoNestedSlot() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:human_review");
            node.put("type", "approval");
            node.put("label", "Human Review");
            Map<String, Object> approvalConfig = new LinkedHashMap<>();
            approvalConfig.put("message", "Please approve");
            approvalConfig.put("timeoutMs", 86400000L);
            node.put("approval", approvalConfig);
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Human Review");
            args.put("params", Map.of("message", "Updated approval text"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findCore(session, "Human Review");
            @SuppressWarnings("unchecked")
            Map<String, Object> approvalNested = (Map<String, Object>) modified.get("approval");
            assertThat(approvalNested.get("message")).isEqualTo("Updated approval text");
            assertThat(approvalNested.get("timeoutMs")).isEqualTo(86400000L);
            assertThat(modified).doesNotContainKey("message");
        }

        /**
         * Contract guard for the {@code NESTED_CONFIG_KEYS} routing table.
         * For every entry, sending a flat scalar param under any inner-field
         * name must land inside the nested slot - not at top level. If
         * {@code WorkflowPlanParser} gains a new nested config and someone
         * forgets to register it here, this test fires.
         *
         * <p>Each row's {@code probeField} is a known inner-field name for the
         * config so the harmonize routing has something to move. We probe with
         * a string everywhere because the routing is structure-agnostic - it
         * cares about the key shape, not the value type.
         */
        @ParameterizedTest(name = "{0}: modify with flat {1} routes into {2}.{1}")
        @MethodSource("nestedConfigTypeProbes")
        @DisplayName("All NESTED_CONFIG_KEYS entries route flat params into the nested slot")
        void allNestedConfigTypesRouteIntoNestedSlot(String nodeType, String probeField,
                                                     String nestedKey, String probeValue) {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            String label = "Probe " + nodeType;
            node.put("id", "core:" + WorkflowBuilderSession.normalizeLabel(label));
            node.put("type", nodeType);
            node.put("label", label);
            node.put(nestedKey, new LinkedHashMap<>(Map.of(probeField, "INITIAL")));
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", label);
            args.put("params", Map.of(probeField, probeValue));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findCore(session, label);
            assertThat(modified)
                    .as("flat %s on a %s node must NOT land at top level - engine reads %s.%s only",
                            probeField, nodeType, nestedKey, probeField)
                    .doesNotContainKey(probeField);
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) modified.get(nestedKey);
            assertThat(nested)
                    .as("nested slot %s must exist after modify on %s", nestedKey, nodeType)
                    .isNotNull();
            assertThat(nested.get(probeField))
                    .as("flat %s must land inside %s.%s", probeField, nestedKey, probeField)
                    .isEqualTo(probeValue);
        }

        static Stream<Arguments> nestedConfigTypeProbes() {
            // One probe per NESTED_CONFIG_KEYS entry. Inner-field name is chosen
            // from the type's known schema; the harmonize routing is generic and
            // doesn't care what the field means.
            return Stream.of(
                    Arguments.of("transform", "input", "transform", "{{x}}"),
                    Arguments.of("wait", "duration", "wait", "PT10S"),
                    Arguments.of("download_file", "url", "download", "https://a.example/x"),
                    Arguments.of("http_request", "url", "httpRequest", "https://a.example/y"),
                    Arguments.of("response", "message", "response", "ok"),
                    Arguments.of("aggregate", "strategy", "aggregate", "concat"),
                    Arguments.of("filter", "condition", "filter", "{{x}} > 0"),
                    Arguments.of("sort", "sortBy", "sort", "name"),
                    Arguments.of("limit", "maxRows", "limit", "10"),
                    Arguments.of("remove_duplicates", "strategy", "removeDuplicates", "first"),
                    Arguments.of("summarize", "operation", "summarize", "sum"),
                    Arguments.of("date_time", "operation", "dateTime", "format"),
                    Arguments.of("crypto_jwt", "operation", "cryptoJwt", "sign"),
                    Arguments.of("xml", "operation", "xml", "extract"),
                    Arguments.of("compression", "operation", "compression", "gzip"),
                    Arguments.of("rss", "feedUrl", "rss", "https://a.example/feed"),
                    Arguments.of("convert_to_file", "format", "convertToFile", "csv"),
                    Arguments.of("extract_from_file", "format", "extractFromFile", "json"),
                    Arguments.of("compare_datasets", "strategy", "compareDatasets", "diff"),
                    Arguments.of("sub_workflow", "workflowId", "subWorkflow", "wf-123"),
                    Arguments.of("respond_to_webhook", "statusCode", "respondToWebhook", "200"),
                    Arguments.of("send_email", "subject", "sendEmail", "Hi"),
                    // 'code' is unique: its nested-slot key collides with its inner-field
                    // 'code' name. Probe a different inner field so doesNotContainKey on
                    // the slot name doesn't accidentally fire - the dedicated
                    // codeNodeRoutesIntoNestedSlot test still covers the slot-name case.
                    Arguments.of("code", "language", "code", "python"),
                    Arguments.of("data_input", "fields", "dataInput", "[]"),
                    Arguments.of("set", "assignments", "set", "[]"),
                    Arguments.of("approval", "message", "approval", "please"),
                    Arguments.of("html_extract", "sourceHtml", "htmlExtract", "<p>x</p>"),
                    Arguments.of("task", "operation", "task", "create"),
                    Arguments.of("stop_on_error", "errorMessage", "stopOnError", "boom"),
                    Arguments.of("ssh", "command", "ssh", "uptime"),
                    Arguments.of("sftp", "operation", "sftp", "upload"),
                    Arguments.of("database", "operation", "database", "select")
            );
        }

        @Test
        @DisplayName("ssh node: command field routes into ssh.command (no top-level orphan)")
        void sshNodeRoutesIntoNestedSlot() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:deploy");
            node.put("type", "ssh");
            node.put("label", "Deploy");
            Map<String, Object> sshConfig = new LinkedHashMap<>();
            sshConfig.put("host", "app-host");
            sshConfig.put("command", "uptime");
            node.put("ssh", sshConfig);
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Deploy");
            args.put("params", Map.of("command", "df -h"));

            modifier.executeModifyNode(session, args);

            Map<String, Object> modified = findCore(session, "Deploy");
            @SuppressWarnings("unchecked")
            Map<String, Object> sshNested = (Map<String, Object>) modified.get("ssh");
            assertThat(sshNested.get("command")).isEqualTo("df -h");
            assertThat(sshNested.get("host")).isEqualTo("app-host");
            assertThat(modified).doesNotContainKey("command");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Response diff truthfulness - the modify-masking bug from 2026-06-03
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("modify response diff shows the ACTUAL merged value, not the input patch")
    class ResponseDiffTruthfulness {

        private WorkflowBuilderSession sessionWithSmsNode() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "e44e883a-2822-428e-9ace-8064d17d8a14");
            node.put("type", "mcp");
            node.put("label", "send_sms");
            node.put("params", new LinkedHashMap<>(Map.of(
                    "To", "{{trigger:sms_form.output.to}}",
                    "From", "{{trigger:sms_form.output.from}}",
                    "Body", "{{trigger:sms_form.output.body}}",
                    "AccountSid", "{{trigger:sms_form.output.account_sid}}")));
            session.getMcps().add(node);
            return session;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> paramsAfterDiff(ToolExecutionResult result) {
            Map<String, Object> data = (Map<String, Object>) result.data();
            Map<String, Object> changes = (Map<String, Object>) data.get("changes");
            Map<String, Object> paramsDiff = (Map<String, Object>) changes.get("params");
            return (Map<String, Object>) paramsDiff.get("after");
        }

        /**
         * Reproduces the agent's confusion verbatim: re-sending params WITHOUT
         * AccountSid does NOT drop it (params merges). Pre-fix the response
         * {@code changes.after} echoed the INPUT {To,From,Body}, hiding that
         * AccountSid was still present - the agent believed the deletion worked
         * and stopped, leaving the stale key in place.
         */
        @Test
        @DisplayName("omitting a key does NOT delete it, and changes.after reveals it still there")
        void omittedKeyStaysAndDiffShowsTheTruth() {
            WorkflowBuilderSession session = sessionWithSmsNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "send_sms");
            args.put("params", Map.of(
                    "To", "{{trigger:sms_form.output.to}}",
                    "From", "{{trigger:sms_form.output.from}}",
                    "Body", "{{trigger:sms_form.output.body}}"));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).isTrue();

            // The node still carries AccountSid (merge semantics).
            Map<String, Object> node = findMcp(session, "send_sms");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params).containsKey("AccountSid");

            // And the response diff is HONEST about it (the bug: it used to omit it).
            assertThat(paramsAfterDiff(result))
                    .as("changes.after must reflect the node's real merged params, including the un-dropped AccountSid")
                    .containsKey("AccountSid");
        }

        /**
         * The documented remedy: {@code params={key:null}} actually deletes the
         * key, and {@code changes.after} reflects the deletion (AccountSid gone).
         */
        @Test
        @DisplayName("params={key:null} deletes the key and changes.after no longer lists it")
        void explicitNullDeletesAndDiffReflectsIt() {
            WorkflowBuilderSession session = sessionWithSmsNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "send_sms");
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("AccountSid", null);  // explicit null = delete (Map.of forbids null)
            args.put("params", patch);

            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).isTrue();

            Map<String, Object> node = findMcp(session, "send_sms");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params)
                    .doesNotContainKey("AccountSid")
                    .containsKeys("To", "From", "Body");

            assertThat(paramsAfterDiff(result))
                    .as("changes.after must show the post-delete params (no AccountSid)")
                    .doesNotContainKey("AccountSid");
        }
    }
}
