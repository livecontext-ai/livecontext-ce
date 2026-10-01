package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parametrized sweep over every node type to lock in merge semantics.
 *
 * <p>Goal: catch a regression in any one node type without writing a
 * dedicated nested test class for each. Each node type is exercised with
 * the same shape of update - populate two fields, modify one, assert the
 * other survives - so a {@code node.put}-style write that wipes untouched
 * data fails LOUDLY here regardless of which type slipped.
 *
 * <p>Coverage:
 * <ul>
 *   <li>All 24 nested-config core nodes (download_file, http_request,
 *       send_email, transform, wait, aggregate, filter, sort, limit,
 *       remove_duplicates, summarize, date_time, crypto_jwt, xml,
 *       compression, rss, convert_to_file, extract_from_file,
 *       compare_datasets, sub_workflow, respond_to_webhook, code,
 *       data_input, response)</li>
 *   <li>All params-aware triggers (schedule, form, chat) - they all use
 *       the same params-routing block as the MCP fix.</li>
 *   <li>Table (CRUD) and note nodes - pass through the generic apply loop.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - parametrized sweep across all node types")
class WorkflowBuilderModifierParametrizedTest {

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

    // ═══════════════════════════════════════════════════════════════════════
    // Sweep: every nested-config core node type
    //
    // For each (nodeType, nestedConfigKey) pair: create a node with TWO
    // fields under the nested config, modify ONE via flat params, assert
    // the OTHER survived. Catches any regression in the nested-config
    // routing block (lines 853-893 in WorkflowBuilderModifier).
    // ═══════════════════════════════════════════════════════════════════════

    static Stream<Arguments> nestedConfigNodeTypes() {
        return Stream.of(
            Arguments.of("download_file",      "download",          "url",     "filename"),
            Arguments.of("http_request",       "httpRequest",       "url",     "method"),
            Arguments.of("send_email",         "sendEmail",         "toEmail", "subject"),
            Arguments.of("transform",          "transform",         "input",   "mapping"),
            Arguments.of("wait",               "wait",              "duration","unit"),
            Arguments.of("aggregate",          "aggregate",         "input",   "groupBy"),
            Arguments.of("filter",             "filter",            "input",   "mode"),
            Arguments.of("sort",               "sort",              "input",   "by"),
            Arguments.of("limit",              "limit",             "input",   "count"),
            Arguments.of("remove_duplicates",  "removeDuplicates",  "input",   "key"),
            Arguments.of("summarize",          "summarize",         "input",   "operation"),
            Arguments.of("date_time",          "dateTime",          "input",   "operation"),
            Arguments.of("crypto_jwt",         "cryptoJwt",         "secret",  "algorithm"),
            Arguments.of("xml",                "xml",               "value",   "operation"),
            Arguments.of("compression",        "compression",       "value",   "format"),
            Arguments.of("rss",                "rss",               "url",     "limit"),
            Arguments.of("convert_to_file",    "convertToFile",     "value",   "format"),
            Arguments.of("extract_from_file",  "extractFromFile",   "value",   "format"),
            Arguments.of("compare_datasets",   "compareDatasets",   "inputA",  "inputB"),
            Arguments.of("sub_workflow",       "subWorkflow",       "workflowId", "input"),
            Arguments.of("respond_to_webhook", "respondToWebhook",  "status",  "body"),
            Arguments.of("code",               "code",              "code",    "language"),
            Arguments.of("data_input",         "dataInput",         "value",   "type"),
            Arguments.of("response",           "response",          "message", "status")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nestedConfigNodeTypes")
    @DisplayName("nested-config nodes: changing one field preserves the other")
    void nestedConfigNodePreservesUntouchedFields(
            String nodeType, String nestedKey, String fieldToUpdate, String fieldToPreserve) {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> node = new LinkedHashMap<>();
        String label = "Step " + nodeType;
        String nodeId = "core:" + WorkflowBuilderSession.normalizeLabel(label);
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", nodeType);

        Map<String, Object> existingConfig = new LinkedHashMap<>();
        existingConfig.put(fieldToUpdate, "old-value");
        existingConfig.put(fieldToPreserve, "must-survive");
        node.put(nestedKey, existingConfig);
        session.getCores().add(node);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", label);
        args.put("params", Map.of(fieldToUpdate, "new-value"));

        ToolExecutionResult result = modifier.executeModifyNode(session, args);
        assertThat(result.success())
                .as("modify should succeed for nodeType=%s", nodeType)
                .isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> mergedConfig = (Map<String, Object>) session.getCores().get(0).get(nestedKey);
        assertThat(mergedConfig)
                .as("nested config should still exist for nodeType=%s", nodeType)
                .isNotNull();
        assertThat(mergedConfig.get(fieldToUpdate))
                .as("updated field should hold the new value for nodeType=%s", nodeType)
                .isEqualTo("new-value");
        assertThat(mergedConfig.get(fieldToPreserve))
                .as("untouched field MUST survive for nodeType=%s - regression in nested-config merge!", nodeType)
                .isEqualTo("must-survive");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sweep: every params-aware trigger type
    // ═══════════════════════════════════════════════════════════════════════

    static Stream<Arguments> paramsAwareTriggerTypes() {
        return Stream.of(
            Arguments.of("schedule", "cron",        "*/15 * * * *", "timezone",    "UTC"),
            Arguments.of("form",     "formTitle",   "Old Title",    "submitButtonText", "Submit"),
            Arguments.of("chat",     "chatEndpointId", "abc-123",   "welcomeMessage",   "Hi")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paramsAwareTriggerTypes")
    @DisplayName("params-aware triggers: changing one params field preserves the others")
    void paramsAwareTriggerPreservesUntouchedFields(
            String triggerType, String fieldToUpdate, String oldValue,
            String fieldToPreserve, String preservedValue) {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("id", "trigger:start");
        trigger.put("type", triggerType);
        trigger.put("label", "Start");
        Map<String, Object> existingParams = new LinkedHashMap<>();
        existingParams.put(fieldToUpdate, oldValue);
        existingParams.put(fieldToPreserve, preservedValue);
        trigger.put("params", existingParams);
        session.getTriggers().add(trigger);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Start");
        args.put("params", Map.of(fieldToUpdate, "new-value"));

        ToolExecutionResult result = modifier.executeModifyNode(session, args);
        assertThat(result.success())
                .as("modify should succeed for trigger=%s", triggerType)
                .isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) session.getTriggers().get(0).get("params");
        assertThat(params)
                .as("trigger params should still exist for type=%s", triggerType)
                .isNotNull();
        assertThat(params.get(fieldToUpdate))
                .as("updated field should hold the new value for trigger=%s", triggerType)
                .isEqualTo("new-value");
        assertThat(params.get(fieldToPreserve))
                .as("untouched field MUST survive for trigger=%s - regression in trigger params routing!", triggerType)
                .isEqualTo(preservedValue);
    }

    @Test
    @DisplayName("form trigger: modify stores a field's `default` as defaultValue, like add_node (2026-09-29 Gemini chat)")
    void formModifyCanonicalizesFieldDefault() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("id", "trigger:start");
        trigger.put("type", "form");
        trigger.put("label", "Start");
        trigger.put("params", new LinkedHashMap<>(Map.of("formTitle", "Keep me")));
        session.getTriggers().add(trigger);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Start");
        args.put("params", Map.of("fields", List.of(
                Map.of("name", "theme", "type", "text", "required", true, "default", "Innovation"),
                Map.of("name", "plain", "type", "text"))));

        ToolExecutionResult result = modifier.executeModifyNode(session, args);
        assertThat(result.success()).isTrue();
        // What the builder canonicalized is what was asked: never reported as NOT_APPLIED.
        assertThat(String.valueOf(result.data())).doesNotContain("NOT_APPLIED");

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) session.getTriggers().get(0).get("params");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) params.get("fields");
        assertThat(params).containsEntry("formTitle", "Keep me");
        assertThat(fields.get(0)).containsEntry("defaultValue", "Innovation").doesNotContainKey("default");
        assertThat(fields.get(1)).doesNotContainKeys("defaultValue", "default");
    }

    @Test
    @DisplayName("form trigger: modify with an explicit params block canonicalizes default_value too")
    void formModifyExplicitParamsCanonicalizesDefaultValueAlias() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("id", "trigger:start");
        trigger.put("type", "form");
        trigger.put("label", "Start");
        trigger.put("params", new LinkedHashMap<>(Map.of("formTitle", "Keep me")));
        session.getTriggers().add(trigger);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Start");
        args.put("params", Map.of("params", Map.of("formTitle", "Keep me",
                "fields", List.of(Map.of("name", "auteur", "type", "text", "default_value", "Ada")))));

        ToolExecutionResult result = modifier.executeModifyNode(session, args);
        assertThat(result.success()).isTrue();
        // What the builder canonicalized is what was asked: never reported as NOT_APPLIED.
        assertThat(String.valueOf(result.data())).doesNotContain("NOT_APPLIED");

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) session.getTriggers().get(0).get("params");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) params.get("fields");
        assertThat(params).containsEntry("formTitle", "Keep me");
        assertThat(fields.get(0)).containsEntry("defaultValue", "Ada").doesNotContainKey("default_value");
    }

    @Test
    @DisplayName("form trigger: modify gives fields add_node's shape (ids, [{id, label, value}] options, real types)")
    void formModifyCanonicalizesOptionsIdsAndTypes() {
        WorkflowBuilderSession session = createSession();
        session.getTriggers().add(formTrigger());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Start");
        args.put("params", Map.of("fields", List.of(
                Map.of("name", "tier", "type", "select", "options", List.of("free", "pro")),
                Map.of("name", "phone", "type", "phone"))));

        ToolExecutionResult result = modifier.executeModifyNode(session, args);
        assertThat(result.success()).isTrue();
        // What the builder canonicalized is what was asked: never reported as NOT_APPLIED.
        assertThat(String.valueOf(result.data())).doesNotContain("NOT_APPLIED");

        List<Map<String, Object>> fields = storedFields(session);
        assertThat(fields).extracting(f -> f.get("id")).containsExactly("field-0", "field-1");
        assertThat(fields.get(0).get("options")).isEqualTo(List.of(
                Map.of("id", "opt-0", "label", "free", "value", "free"),
                Map.of("id", "opt-1", "label", "pro", "value", "pro")));
        assertThat(fields.get(1)).containsEntry("type", "tel");
    }

    @Test
    @DisplayName("form trigger: modify refuses what add_node refuses, with add_node's message, and writes nothing")
    void formModifyRefusesInvalidFields() {
        WorkflowBuilderSession session = createSession();
        session.getTriggers().add(formTrigger());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Start");
        args.put("params", Map.of("fields", List.of(
                Map.of("name", "tier", "type", "select", "options", List.of()))));

        ToolExecutionResult result = modifier.executeModifyNode(session, args);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).startsWith("Form field validation failed:")
                .contains("field 'tier': 'options' is empty");
        assertThat(storedFields(session)).isNull();
    }

    @Test
    @DisplayName("a non-form trigger's `fields` is never taken for form fields: not reshaped, not refused")
    @SuppressWarnings("unchecked")
    void nonFormTriggerFieldsUntouched() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("id", "trigger:start");
        chat.put("type", "chat");
        chat.put("label", "Start");
        chat.put("params", new LinkedHashMap<>(Map.of("chatEndpointId", "abc")));
        session.getTriggers().add(chat);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Start");
        args.put("params", Map.of("fields", List.of(Map.of("name", "x", "type", "SELECT"))));

        assertThat(modifier.executeModifyNode(session, args).success()).isTrue();

        Map<String, Object> params = (Map<String, Object>) session.getTriggers().get(0).get("params");
        Map<String, Object> field = ((List<Map<String, Object>>) params.get("fields")).get(0);
        // No id added and no type rewritten: the form canonicalizer never ran on a chat trigger.
        assertThat(field).doesNotContainKey("id").containsEntry("type", "SELECT");
    }

    private Map<String, Object> formTrigger() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("id", "trigger:start");
        trigger.put("type", "form");
        trigger.put("label", "Start");
        trigger.put("params", new LinkedHashMap<>(Map.of("formTitle", "Keep me")));
        return trigger;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> storedFields(WorkflowBuilderSession session) {
        Map<String, Object> params = (Map<String, Object>) session.getTriggers().get(0).get("params");
        return (List<Map<String, Object>>) params.get("fields");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Top-level field-level replace: scalar fields stay top-level and update
    // independently. Covers Loop, Split, Decision, generic core nodes, and
    // tables / notes.
    // ═══════════════════════════════════════════════════════════════════════

    static Stream<Arguments> topLevelScalarNodeTypes() {
        return Stream.of(
            // (nodeList, type, fieldA, fieldB) - both top-level scalars
            Arguments.of("cores", "loop",   "loopCondition", "{{x}} < 5", "maxIterations", 10),
            Arguments.of("cores", "split",  "list",          "{{items}}", "maxItems",      20),
            Arguments.of("cores", "switch", "switchExpression", "{{x}}",   "defaultLabel",  "Other"),
            Arguments.of("tables","table",  "dataSourceId",   "tbl-1",     "operation",     "list_rows"),
            Arguments.of("notes", "note",   "text",           "Hello",     "color",         "yellow")
        );
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("topLevelScalarNodeTypes")
    @DisplayName("top-level scalar fields: field-level replace preserves siblings")
    void topLevelScalarPreservesSiblings(
            String nodeList, String nodeType,
            String fieldA, Object valueA,
            String fieldB, Object valueB) {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> node = new LinkedHashMap<>();
        String label = "Node " + nodeType;
        String prefix = switch (nodeList) {
            case "cores" -> "core:";
            case "tables" -> "table:";
            case "notes" -> "note:";
            default -> "";
        };
        node.put("id", prefix + WorkflowBuilderSession.normalizeLabel(label));
        node.put("label", label);
        node.put("type", nodeType);
        node.put(fieldA, valueA);
        node.put(fieldB, valueB);

        switch (nodeList) {
            case "cores"  -> session.getCores().add(node);
            case "tables" -> session.getTables().add(node);
            case "notes"  -> session.getNotes().add(node);
        }

        // Update fieldA, leave fieldB untouched.
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", label);
        args.put("params", Map.of(fieldA, "updated"));

        ToolExecutionResult result = modifier.executeModifyNode(session, args);
        assertThat(result.success())
                .as("modify should succeed for nodeType=%s", nodeType)
                .isTrue();

        Map<String, Object> modified = (switch (nodeList) {
            case "cores"  -> session.getCores();
            case "tables" -> session.getTables();
            case "notes"  -> session.getNotes();
            default       -> List.<Map<String, Object>>of();
        }).get(0);
        assertThat(modified.get(fieldA))
                .as("updated field for nodeType=%s", nodeType)
                .isEqualTo("updated");
        assertThat(modified.get(fieldB))
                .as("untouched sibling field MUST survive for nodeType=%s", nodeType)
                .isEqualTo(valueB);
    }
}
