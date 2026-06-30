package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowBuilderModifier nested config handling.
 *
 * Nodes like download_file, http_request, transform, etc. store their config in a
 * nested sub-object (e.g., node.download.url). When the LLM sends flat params like
 * {url: "new"}, the modifier MUST route them into the nested config - NOT the top level.
 *
 * This test covers all 24 node types with nested config.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - Nested Config Routing")
class WorkflowBuilderModifierNestedConfigTest {

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

    /**
     * Add a core node with nested config to the session.
     */
    private void addCoreNode(WorkflowBuilderSession session, String label, String type,
                              String nestedKey, Map<String, Object> nestedConfig) {
        Map<String, Object> node = new LinkedHashMap<>();
        String nodeId = "core:" + WorkflowBuilderSession.normalizeLabel(label);
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", type);
        node.put(nestedKey, new LinkedHashMap<>(nestedConfig));
        session.getCores().add(node);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getNestedConfig(WorkflowBuilderSession session, String label, String nestedKey) {
        String nodeId = "core:" + WorkflowBuilderSession.normalizeLabel(label);
        for (Map<String, Object> core : session.getCores()) {
            if (nodeId.equals(core.get("id"))) {
                return (Map<String, Object>) core.get(nestedKey);
            }
        }
        return null;
    }

    private Map<String, Object> getNode(WorkflowBuilderSession session, String label) {
        String nodeId = "core:" + WorkflowBuilderSession.normalizeLabel(label);
        for (Map<String, Object> core : session.getCores()) {
            if (nodeId.equals(core.get("id"))) {
                return core;
            }
        }
        return null;
    }

    // ─── All 24 nested config node types as parameterized test source ───

    static Stream<Arguments> nestedConfigNodeTypes() {
        return Stream.of(
            // nodeType, nestedKey, sampleParamKey, sampleParamValue, existingParamKey, existingParamValue
            Arguments.of("download_file", "download", "url", "https://example.com/file.zip", "filename", "output.zip"),
            Arguments.of("http_request", "httpRequest", "url", "https://api.example.com", "method", "GET"),
            Arguments.of("transform", "transform", "mappings", List.of(Map.of("label", "name", "expression", "{{input.name}}")), "dummy", "x"),
            Arguments.of("wait", "wait", "duration", 5000, "dummy", "x"),
            Arguments.of("response", "response", "message", "Done!", "dummy", "x"),
            Arguments.of("aggregate", "aggregate", "fields", List.of(Map.of("label", "total", "expression", "{{sum}}")), "dummy", "x"),
            Arguments.of("filter", "filter", "conditions", List.of(Map.of("field", "age", "operator", "gt", "value", "18")), "mode", "all"),
            Arguments.of("sort", "sort", "fields", List.of(Map.of("field", "name", "direction", "asc")), "dummy", "x"),
            Arguments.of("limit", "limit", "count", 10, "offset", 0),
            Arguments.of("remove_duplicates", "removeDuplicates", "fields", List.of("email"), "keep", "first"),
            Arguments.of("summarize", "summarize", "groupBy", List.of("category"), "input", "{{data}}"),
            Arguments.of("date_time", "dateTime", "operation", "format", "timezone", "UTC"),
            Arguments.of("crypto_jwt", "cryptoJwt", "operation", "sign", "algorithm", "HS256"),
            Arguments.of("xml", "xml", "operation", "parse", "rootElement", "data"),
            Arguments.of("compression", "compression", "operation", "compress", "format", "gzip"),
            Arguments.of("rss", "rss", "url", "https://example.com/feed.xml", "maxItems", 10),
            Arguments.of("convert_to_file", "convertToFile", "format", "csv", "filename", "export.csv"),
            Arguments.of("extract_from_file", "extractFromFile", "format", "csv", "delimiter", ","),
            Arguments.of("compare_datasets", "compareDatasets", "inputA", "{{data1}}", "inputB", "{{data2}}"),
            Arguments.of("sub_workflow", "subWorkflow", "workflowId", "wf-123", "timeoutSeconds", 300),
            Arguments.of("respond_to_webhook", "respondToWebhook", "statusCode", 200, "contentType", "application/json"),
            Arguments.of("send_email", "sendEmail", "toEmail", "test@example.com", "subject", "Hello"),
            Arguments.of("code", "code", "language", "javascript", "code", "return 42;"),
            Arguments.of("data_input", "dataInput", "items", List.of(Map.of("id", "q1", "label", "Name", "type", "text")), "dummy", "x")
        );
    }

    // ==================== Core Test: Flat Params → Nested Config ====================

    @Nested
    @DisplayName("Flat params are routed into nested config")
    class FlatParamsRouting {

        @ParameterizedTest(name = "{0}: flat param ''{2}'' goes into node.{1}")
        @MethodSource("com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderModifierNestedConfigTest#nestedConfigNodeTypes")
        @DisplayName("Should route flat param into nested config")
        void shouldRouteFlatParamIntoNestedConfig(String nodeType, String nestedKey,
                                                    String paramKey, Object paramValue,
                                                    String existingKey, Object existingValue) {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> existingConfig = new LinkedHashMap<>();
            existingConfig.put(existingKey, existingValue);
            addCoreNode(session, "My Node", nodeType, nestedKey, existingConfig);

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Node");
            modifyParams.put("params", Map.of(paramKey, paramValue));

            ToolExecutionResult result = modifier.executeModifyNode(session, modifyParams);
            assertThat(result.success()).isTrue();

            // Param must be INSIDE nested config
            Map<String, Object> config = getNestedConfig(session, "My Node", nestedKey);
            assertThat(config).isNotNull();
            assertThat(config.get(paramKey)).isEqualTo(paramValue);

            // Param must NOT be at top level
            Map<String, Object> node = getNode(session, "My Node");
            assertThat(node).doesNotContainKey(paramKey);
        }

        @ParameterizedTest(name = "{0}: existing config field ''{4}'' preserved when modifying ''{2}''")
        @MethodSource("com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderModifierNestedConfigTest#nestedConfigNodeTypes")
        @DisplayName("Should preserve existing nested config fields during modify")
        void shouldPreserveExistingConfigFields(String nodeType, String nestedKey,
                                                  String paramKey, Object paramValue,
                                                  String existingKey, Object existingValue) {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> existingConfig = new LinkedHashMap<>();
            existingConfig.put(existingKey, existingValue);
            existingConfig.put("preserved_field", "keep_me");
            addCoreNode(session, "My Node", nodeType, nestedKey, existingConfig);

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Node");
            modifyParams.put("params", Map.of(paramKey, paramValue));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> config = getNestedConfig(session, "My Node", nestedKey);
            assertThat(config.get("preserved_field")).isEqualTo("keep_me");
            assertThat(config.get(existingKey)).isEqualTo(existingValue);
        }
    }

    // ==================== Direct Nested Key ====================

    @Nested
    @DisplayName("LLM sends nested config key directly")
    class DirectNestedKey {

        @Test
        @DisplayName("Should handle params={download: {url: 'new'}} on download_file node")
        void shouldHandleDirectNestedKeyForDownload() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Get File", "download_file", "download",
                    Map.of("url", "https://old.com/file.txt", "filename", "old.txt"));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Get File");
            modifyParams.put("params", Map.of("download", Map.of("url", "https://new.com/file.zip")));

            ToolExecutionResult result = modifier.executeModifyNode(session, modifyParams);
            assertThat(result.success()).isTrue();

            Map<String, Object> config = getNestedConfig(session, "Get File", "download");
            assertThat(config.get("url")).isEqualTo("https://new.com/file.zip");
            // Existing field should be preserved (merge, not replace)
            assertThat(config.get("filename")).isEqualTo("old.txt");
        }

        @Test
        @DisplayName("Should handle params={httpRequest: {method: 'POST'}} on http_request node")
        void shouldHandleDirectNestedKeyForHttpRequest() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "API Call", "http_request", "httpRequest",
                    Map.of("url", "https://api.com", "method", "GET", "timeout", 30));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "API Call");
            modifyParams.put("params", Map.of("httpRequest", Map.of("method", "POST", "body", "{}")));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> config = getNestedConfig(session, "API Call", "httpRequest");
            assertThat(config.get("method")).isEqualTo("POST");
            assertThat(config.get("body")).isEqualTo("{}");
            assertThat(config.get("url")).isEqualTo("https://api.com");
            assertThat(config.get("timeout")).isEqualTo(30);
        }
    }

    // ==================== Top-Level Keys Stay at Top Level ====================

    @Nested
    @DisplayName("Top-level keys stay at node level, not nested")
    class TopLevelKeys {

        @Test
        @DisplayName("Should keep 'label' at top level when modifying download_file")
        void shouldKeepLabelAtTopLevel() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Get File", "download_file", "download",
                    Map.of("url", "https://example.com/file.txt", "filename", "output.txt"));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Get File");
            modifyParams.put("params", Map.of("label", "Download Report", "url", "https://new.com/report.pdf"));

            modifier.executeModifyNode(session, modifyParams);

            // label at top level
            Map<String, Object> node = getNode(session, "Download Report");
            assertThat(node).isNotNull();
            assertThat(node.get("label")).isEqualTo("Download Report");

            // url inside nested config
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) node.get("download");
            assertThat(config.get("url")).isEqualTo("https://new.com/report.pdf");
            // label should NOT be in nested config
            assertThat(config).doesNotContainKey("label");
        }

        @Test
        @DisplayName("Should keep 'description' at top level when modifying http_request")
        void shouldKeepDescriptionAtTopLevel() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "API Call", "http_request", "httpRequest",
                    Map.of("url", "https://api.com", "method", "GET"));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "API Call");
            modifyParams.put("params", Map.of("description", "Fetch user data", "url", "https://api.com/users"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> node = getNode(session, "API Call");
            assertThat(node.get("description")).isEqualTo("Fetch user data");

            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) node.get("httpRequest");
            assertThat(config.get("url")).isEqualTo("https://api.com/users");
            assertThat(config).doesNotContainKey("description");
        }
    }

    // ==================== Undo/Redo for Nested Config ====================

    @Nested
    @DisplayName("Undo/Redo correctly handles nested config")
    class UndoRedo {

        @Test
        @DisplayName("Undo restores original nested config after flat param modify")
        void undoRestoresOriginalNestedConfig() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Get File", "download_file", "download",
                    Map.of("url", "https://old.com/file.txt", "filename", "old.txt", "timeout", 30));

            // Modify: change url via flat param
            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Get File");
            modifyParams.put("params", Map.of("url", "https://new.com/file.zip"));

            modifier.executeModifyNode(session, modifyParams);

            // Verify modification applied
            Map<String, Object> configAfterModify = getNestedConfig(session, "Get File", "download");
            assertThat(configAfterModify.get("url")).isEqualTo("https://new.com/file.zip");

            // Undo
            ToolExecutionResult undoResult = modifier.executeUndo(session);
            assertThat(undoResult.success()).isTrue();

            // Original config should be restored
            Map<String, Object> configAfterUndo = getNestedConfig(session, "Get File", "download");
            assertThat(configAfterUndo.get("url")).isEqualTo("https://old.com/file.txt");
            assertThat(configAfterUndo.get("filename")).isEqualTo("old.txt");
            assertThat(configAfterUndo.get("timeout")).isEqualTo(30);
        }

        @Test
        @DisplayName("Redo re-applies nested config changes")
        void redoReAppliesNestedConfig() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "API Call", "http_request", "httpRequest",
                    Map.of("url", "https://old.com", "method", "GET"));

            // Modify
            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "API Call");
            modifyParams.put("params", Map.of("url", "https://new.com", "method", "POST"));

            modifier.executeModifyNode(session, modifyParams);

            // Undo
            modifier.executeUndo(session);
            Map<String, Object> configAfterUndo = getNestedConfig(session, "API Call", "httpRequest");
            assertThat(configAfterUndo.get("url")).isEqualTo("https://old.com");

            // Redo
            ToolExecutionResult redoResult = modifier.executeRedo(session);
            assertThat(redoResult.success()).isTrue();

            Map<String, Object> configAfterRedo = getNestedConfig(session, "API Call", "httpRequest");
            assertThat(configAfterRedo.get("url")).isEqualTo("https://new.com");
            assertThat(configAfterRedo.get("method")).isEqualTo("POST");
        }

        @Test
        @DisplayName("Undo restores nested config when multiple flat params were modified")
        void undoRestoresMultipleFlatParams() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Call API", "http_request", "httpRequest",
                    Map.of("url", "https://old.com/api", "method", "GET", "timeout", 30));

            // Modify two nested params at once
            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Call API");
            modifyParams.put("params", Map.of("url", "https://new.com/v2", "method", "POST"));

            modifier.executeModifyNode(session, modifyParams);

            // Verify changes applied
            Map<String, Object> configAfterModify = getNestedConfig(session, "Call API", "httpRequest");
            assertThat(configAfterModify.get("url")).isEqualTo("https://new.com/v2");
            assertThat(configAfterModify.get("method")).isEqualTo("POST");

            // Undo
            modifier.executeUndo(session);

            // Both params should be restored
            Map<String, Object> configAfterUndo = getNestedConfig(session, "Call API", "httpRequest");
            assertThat(configAfterUndo.get("url")).isEqualTo("https://old.com/api");
            assertThat(configAfterUndo.get("method")).isEqualTo("GET");
            assertThat(configAfterUndo.get("timeout")).isEqualTo(30);
        }
    }

    // ==================== Loop harmonization (flat node, no nesting) ====================

    @Nested
    @DisplayName("Loop param harmonization")
    class LoopHarmonization {

        private void addLoopNode(WorkflowBuilderSession session, String label) {
            Map<String, Object> node = new LinkedHashMap<>();
            String nodeId = "core:" + WorkflowBuilderSession.normalizeLabel(label);
            node.put("id", nodeId);
            node.put("label", label);
            node.put("type", "loop");
            session.getCores().add(node);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> getLoopNode(WorkflowBuilderSession session, String label) {
            String nodeId = "core:" + WorkflowBuilderSession.normalizeLabel(label);
            return session.getCores().stream()
                .filter(n -> nodeId.equals(n.get("id")))
                .findFirst().orElse(null);
        }

        @Test
        @DisplayName("'condition' alias is converted to canonical 'loopCondition'")
        void conditionAliasConverted() {
            WorkflowBuilderSession session = createSession();
            addLoopNode(session, "My Loop");

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("node", "My Loop");
            p.put("params", Map.of("condition", "{{mcp:api.output.has_more}} == true"));

            ToolExecutionResult result = modifier.executeModifyNode(session, p);
            assertThat(result.success()).isTrue();

            Map<String, Object> node = getLoopNode(session, "My Loop");
            assertThat(node.get("loopCondition")).isEqualTo("{{mcp:api.output.has_more}} == true");
            assertThat(node).doesNotContainKey("condition");
        }

        @Test
        @DisplayName("'loop_condition' alias is converted to canonical 'loopCondition'")
        void snakeLoopConditionConverted() {
            WorkflowBuilderSession session = createSession();
            addLoopNode(session, "My Loop");

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("node", "My Loop");
            p.put("params", Map.of("loop_condition", "{{x}} == 1"));

            modifier.executeModifyNode(session, p);

            Map<String, Object> node = getLoopNode(session, "My Loop");
            assertThat(node.get("loopCondition")).isEqualTo("{{x}} == 1");
            assertThat(node).doesNotContainKey("loop_condition");
        }

        @Test
        @DisplayName("'max_iterations' alias is converted to canonical 'maxIterations'")
        void maxIterationsAliasConverted() {
            WorkflowBuilderSession session = createSession();
            addLoopNode(session, "My Loop");

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("node", "My Loop");
            p.put("params", Map.of("max_iterations", 10));

            modifier.executeModifyNode(session, p);

            Map<String, Object> node = getLoopNode(session, "My Loop");
            assertThat(node.get("maxIterations")).isEqualTo(10);
            assertThat(node).doesNotContainKey("max_iterations");
        }
    }

    // ==================== Flat config nodes (no nesting) ====================

    @Nested
    @DisplayName("Flat config nodes are unaffected by nested routing")
    class FlatConfigNodes {

        @Test
        @DisplayName("Split node: 'list' stays at top level")
        void splitNodeListStaysTopLevel() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:split_items");
            node.put("label", "Split Items");
            node.put("type", "split");
            node.put("list", "{{trigger:start.output.items}}");
            node.put("maxItems", 100);
            session.getCores().add(node);

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Split Items");
            modifyParams.put("params", Map.of("list", "{{mcp:fetch.output.data}}", "maxItems", 50));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> modified = session.getCores().get(0);
            assertThat(modified.get("list")).isEqualTo("{{mcp:fetch.output.data}}");
            assertThat(modified.get("maxItems")).isEqualTo(50);
        }

        @Test
        @DisplayName("Loop node: params stay at top level")
        void loopNodeParamsStayTopLevel() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:retry_loop");
            node.put("label", "Retry Loop");
            node.put("type", "loop");
            node.put("loopCondition", "{{attempts}} < 3");
            node.put("maxIterations", 5);
            session.getCores().add(node);

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Retry Loop");
            modifyParams.put("params", Map.of("maxIterations", 10));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> modified = session.getCores().get(0);
            assertThat(modified.get("maxIterations")).isEqualTo(10);
            assertThat(modified.get("loopCondition")).isEqualTo("{{attempts}} < 3");
        }

        @Test
        @DisplayName("Decision node: conditions are harmonized to decisionConditions at top level")
        void decisionNodeConditionsHarmonized() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:check");
            node.put("label", "Check");
            node.put("type", "decision");
            node.put("decisionConditions", List.of(
                    Map.of("id", "check-if", "type", "if", "expression", "true", "label", "Yes"),
                    Map.of("id", "check-else", "type", "else", "expression", "default", "label", "No")
            ));
            session.getCores().add(node);

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Check");
            modifyParams.put("params", Map.of("conditions", List.of(
                    Map.of("condition", "{{x}} > 0", "label", "Positive"),
                    Map.of("condition", "default", "label", "Non-Positive")
            )));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> modified = session.getCores().get(0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) modified.get("decisionConditions");
            assertThat(conditions).hasSize(2);
            assertThat(conditions.get(0).get("expression")).isEqualTo("{{x}} > 0");
            assertThat(conditions.get(1).get("type")).isEqualTo("else");
        }
    }

    // ==================== Filter node conditions vs Decision node conditions ====================

    @Nested
    @DisplayName("Filter conditions are NOT confused with decision conditions")
    class FilterVsDecision {

        @Test
        @DisplayName("Filter node: 'conditions' goes into nested config, not converted to decisionConditions")
        void filterConditionsGoIntoNestedConfig() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Filter Users", "filter", "filter",
                    Map.of("mode", "all", "conditions", List.of()));

            List<Map<String, Object>> filterConditions = List.of(
                    Map.of("field", "age", "operator", "gt", "value", "18"),
                    Map.of("field", "status", "operator", "eq", "value", "active")
            );

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Filter Users");
            modifyParams.put("params", Map.of("conditions", filterConditions));

            modifier.executeModifyNode(session, modifyParams);

            // conditions should be INSIDE filter nested config
            Map<String, Object> config = getNestedConfig(session, "Filter Users", "filter");
            assertThat(config).isNotNull();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> storedConditions = (List<Map<String, Object>>) config.get("conditions");
            assertThat(storedConditions).hasSize(2);
            assertThat(storedConditions.get(0).get("field")).isEqualTo("age");

            // Should NOT have decisionConditions at top level
            Map<String, Object> node = getNode(session, "Filter Users");
            assertThat(node).doesNotContainKey("decisionConditions");
        }
    }

    // ==================== No existing nested config ====================

    @Nested
    @DisplayName("Handles nodes with missing nested config gracefully")
    class MissingNestedConfig {

        @Test
        @DisplayName("Should create nested config if it doesn't exist yet")
        void shouldCreateNestedConfigIfMissing() {
            WorkflowBuilderSession session = createSession();
            // Node has type but no nested config object (edge case)
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:fetch");
            node.put("label", "Fetch");
            node.put("type", "download_file");
            // No "download" key at all
            session.getCores().add(node);

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Fetch");
            modifyParams.put("params", Map.of("url", "https://example.com/file.txt"));

            ToolExecutionResult result = modifier.executeModifyNode(session, modifyParams);
            assertThat(result.success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) session.getCores().get(0).get("download");
            assertThat(config).isNotNull();
            assertThat(config.get("url")).isEqualTo("https://example.com/file.txt");
        }
    }

    // ==================== Specific high-value scenarios ====================

    @Nested
    @DisplayName("Real-world modification scenarios")
    class RealWorldScenarios {

        @Test
        @DisplayName("Modify download_file URL (the original bug report)")
        void modifyDownloadFileUrl() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Download Report", "download_file", "download",
                    Map.of("url", "https://api.com/report/v1",
                           "filename", "report.pdf",
                           "timeout", 60,
                           "headers", Map.of("Authorization", "Bearer {{token}}")));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Download Report");
            modifyParams.put("params", Map.of("url", "https://api.com/report/v2"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> config = getNestedConfig(session, "Download Report", "download");
            assertThat(config.get("url")).isEqualTo("https://api.com/report/v2");
            assertThat(config.get("filename")).isEqualTo("report.pdf");
            assertThat(config.get("timeout")).isEqualTo(60);
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) config.get("headers");
            assertThat(headers.get("Authorization")).isEqualTo("Bearer {{token}}");

            // Top level should NOT have url
            Map<String, Object> node = getNode(session, "Download Report");
            assertThat(node).doesNotContainKey("url");
            assertThat(node).doesNotContainKey("filename");
            assertThat(node).doesNotContainKey("timeout");
        }

        @Test
        @DisplayName("Modify http_request method and body")
        void modifyHttpRequestMethodAndBody() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Call API", "http_request", "httpRequest",
                    Map.of("url", "https://api.com/data",
                           "method", "GET",
                           "timeout", 30));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Call API");
            modifyParams.put("params", Map.of("method", "POST", "body", "{\"key\": \"value\"}", "bodyType", "json"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> config = getNestedConfig(session, "Call API", "httpRequest");
            assertThat(config.get("method")).isEqualTo("POST");
            assertThat(config.get("body")).isEqualTo("{\"key\": \"value\"}");
            assertThat(config.get("bodyType")).isEqualTo("json");
            assertThat(config.get("url")).isEqualTo("https://api.com/data");
            assertThat(config.get("timeout")).isEqualTo(30);
        }

        @Test
        @DisplayName("Modify transform mappings")
        void modifyTransformMappings() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Format Data", "transform", "transform",
                    Map.of("mappings", List.of(Map.of("label", "name", "expression", "{{input.first_name}}"))));

            List<Map<String, Object>> newMappings = List.of(
                    Map.of("label", "full_name", "expression", "{{input.first_name}} {{input.last_name}}"),
                    Map.of("label", "email", "expression", "{{input.email}}")
            );

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Format Data");
            modifyParams.put("params", Map.of("mappings", newMappings));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> config = getNestedConfig(session, "Format Data", "transform");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mappings = (List<Map<String, Object>>) config.get("mappings");
            assertThat(mappings).hasSize(2);
            assertThat(mappings.get(0).get("label")).isEqualTo("full_name");
        }

        @Test
        @DisplayName("Modify send_email recipient and subject")
        void modifySendEmailRecipient() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Notify User", "send_email", "sendEmail",
                    Map.of("toEmail", "old@example.com",
                           "subject", "Old Subject",
                           "body", "Hello!",
                           "isHtml", false));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Notify User");
            modifyParams.put("params", Map.of("toEmail", "new@example.com", "subject", "Updated Subject"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> config = getNestedConfig(session, "Notify User", "sendEmail");
            assertThat(config.get("toEmail")).isEqualTo("new@example.com");
            assertThat(config.get("subject")).isEqualTo("Updated Subject");
            assertThat(config.get("body")).isEqualTo("Hello!");
            assertThat(config.get("isHtml")).isEqualTo(false);
        }

        @Test
        @DisplayName("Modify code node language and source")
        void modifyCodeNode() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Run Script", "code", "code",
                    Map.of("language", "javascript", "code", "console.log('hello');", "timeoutSeconds", 10));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Run Script");
            modifyParams.put("params", Map.of("code", "return data.map(x => x * 2);"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> config = getNestedConfig(session, "Run Script", "code");
            assertThat(config.get("code")).isEqualTo("return data.map(x => x * 2);");
            assertThat(config.get("language")).isEqualTo("javascript");
            assertThat(config.get("timeoutSeconds")).isEqualTo(10);
        }

        @Test
        @DisplayName("Modify wait duration")
        void modifyWaitDuration() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Pause", "wait", "wait", Map.of("duration", 5000));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Pause");
            modifyParams.put("params", Map.of("duration", 10000));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> config = getNestedConfig(session, "Pause", "wait");
            assertThat(config.get("duration")).isEqualTo(10000);
        }

        @Test
        @DisplayName("Modify sub_workflow workflowId")
        void modifySubWorkflowId() {
            WorkflowBuilderSession session = createSession();
            addCoreNode(session, "Run Sub", "sub_workflow", "subWorkflow",
                    Map.of("workflowId", "old-wf-id", "timeoutSeconds", 300));

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Run Sub");
            modifyParams.put("params", Map.of("workflowId", "new-wf-id"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> config = getNestedConfig(session, "Run Sub", "subWorkflow");
            assertThat(config.get("workflowId")).isEqualTo("new-wf-id");
            assertThat(config.get("timeoutSeconds")).isEqualTo(300);
        }
    }
}
