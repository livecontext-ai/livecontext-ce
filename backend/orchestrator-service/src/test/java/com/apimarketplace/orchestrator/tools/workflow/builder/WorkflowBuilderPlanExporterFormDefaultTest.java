package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.DisplayName;
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
 * set_plan is the third way a form field reaches a plan, after add_node and modify. It imported
 * triggers as written, so a field default spelled `default` stayed under a key the public form
 * never reads (the spelling an agent used on 2026-09-29).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderPlanExporter - set_plan gives form fields add_node's canonical shape")
class WorkflowBuilderPlanExporterFormDefaultTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ToolSchemaFetcher toolSchemaFetcher;

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("s").tenantId("t").workflowName("W")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    private WorkflowBuilderSession importTrigger(Map<String, Object> trigger) {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", List.of(trigger));
        ToolExecutionResult result = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher)
                .executeSetPlan(session, Map.of("plan", plan));
        assertThat(result.success()).isTrue();
        return session;
    }

    @Test
    @DisplayName("`default` / `default_value` become defaultValue; other params and fields are kept")
    @SuppressWarnings("unchecked")
    void aliasesBecomeDefaultValue() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Demande");
        trigger.put("type", "form");
        trigger.put("params", Map.of("formTitle", "T", "fields", List.of(
                Map.of("name", "theme", "type", "text", "required", true, "default", "Innovation"),
                Map.of("name", "auteur", "type", "text", "default_value", "Ada"),
                Map.of("name", "note", "type", "text"))));

        Map<String, Object> params = (Map<String, Object>) importTrigger(trigger).getTriggers().get(0).get("params");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) params.get("fields");

        assertThat(params).containsEntry("formTitle", "T");
        assertThat(fields.get(0)).containsEntry("defaultValue", "Innovation").containsEntry("required", true)
                .doesNotContainKey("default");
        assertThat(fields.get(1)).containsEntry("defaultValue", "Ada").doesNotContainKey("default_value");
        assertThat(fields.get(2)).doesNotContainKeys("defaultValue", "default");
    }

    @Test
    @DisplayName("A non-form trigger's params are imported untouched")
    @SuppressWarnings("unchecked")
    void nonFormTriggerUntouched() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Hook");
        trigger.put("type", "webhook");
        trigger.put("params", Map.of("fields", List.of(Map.of("name", "x", "default", "y"))));

        Map<String, Object> params = (Map<String, Object>) importTrigger(trigger).getTriggers().get(0).get("params");

        assertThat(((List<Map<String, Object>>) params.get("fields")).get(0)).containsEntry("default", "y");
    }

    @Test
    @DisplayName("set_plan gives form fields add_node's shape: ids, and string-shorthand options as [{id, label, value}]")
    @SuppressWarnings("unchecked")
    void fieldsGetAddNodeShape() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Demande");
        trigger.put("type", "form");
        trigger.put("params", Map.of("fields", List.of(
                Map.of("name", "tier", "type", "select", "options", List.of("free", "pro")),
                Map.of("name", "note", "type", "text"))));

        WorkflowBuilderSession session = newSession();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", List.of(trigger));
        ToolExecutionResult result = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher)
                .executeSetPlan(session, Map.of("plan", plan));

        assertThat(result.success()).isTrue();
        assertThat(String.valueOf(result.data())).doesNotContain("Form field validation failed");
        List<Map<String, Object>> fields = (List<Map<String, Object>>)
                ((Map<String, Object>) session.getTriggers().get(0).get("params")).get("fields");
        assertThat(fields).extracting(f -> f.get("id")).containsExactly("field-0", "field-1");
        List<Map<String, Object>> options = (List<Map<String, Object>>) fields.get(0).get("options");
        assertThat(options.get(0)).containsEntry("id", "opt-0").containsEntry("label", "free").containsEntry("value", "free");
    }

    @Test
    @DisplayName("A field add_node would refuse is REPORTED by set_plan, never a failed import (round trips of old plans)")
    void invalidFieldIsReportedNotRefused() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Demande");
        trigger.put("type", "form");
        trigger.put("params", Map.of("fields", List.of(
                Map.of("name", "tier", "type", "select", "options", List.of(Map.of("label", "", "value", "free"))))));

        WorkflowBuilderSession session = newSession();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", List.of(trigger));
        ToolExecutionResult result = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher)
                .executeSetPlan(session, Map.of("plan", plan));

        assertThat(result.success()).isTrue();
        assertThat(String.valueOf(result.data()))
                .contains("Form trigger 'Demande' was imported with fields add_node would refuse")
                .contains("options[0] is missing a non-empty 'label'")
                .contains("workflow(action='modify', node='Demande'")
                .doesNotContain("Example: fields:");
        // What is stored: the field as far as it could be made canonical (its id), the rest as sent.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>)
                ((Map<String, Object>) session.getTriggers().get(0).get("params")).get("fields");
        assertThat(fields.get(0)).containsEntry("id", "field-0").containsEntry("name", "tier");
    }
}
