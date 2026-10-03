package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression, seen in prod on 2026-10-02 (workflow 2372da11): an agent wrote a label-only plan
 * through set_plan, as the help documents it ({label, type} with no id). set_plan, validate and
 * finish all reported success, but the plan parser SKIPS a core without an id ("Core with
 * missing id, skipping"), so the saved workflow ran without its only step while the run still
 * said COMPLETED. Every way set_plan could still hand the parser a node it skips is refused
 * up front instead.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("set_plan never stores a node the plan parser would silently skip")
class WorkflowBuilderPlanExporterLabelOnlyIdsTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;
    @Mock
    private ToolSchemaFetcher toolSchemaFetcher;

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("s").tenantId("t").workflowName("Notes")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    private static Map<String, Object> setCore(String label) {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("type", "set");
        set.put("label", label);
        set.put("set", new LinkedHashMap<>(Map.of(
                "assignments", List.of(Map.of("name", "message", "type", "string", "value", "Pause")),
                "keepOnlySet", true)));
        return set;
    }

    /** The plan the agent sent, field for field (one core, edge written with the core key). */
    private static Map<String, Object> plan(List<Map<String, Object>> cores, List<Map<String, Object>> edges) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("type", "manual", "label", "Lancement")))));
        plan.put("cores", new ArrayList<>(cores));
        plan.put("edges", new ArrayList<>(edges));
        return plan;
    }

    private static Map<String, Object> edge(String from, String to) {
        return new LinkedHashMap<>(Map.of("from", from, "to", to));
    }

    private ToolExecutionResult setPlan(WorkflowBuilderSession session, Map<String, Object> plan) {
        return new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher)
                .executeSetPlan(session, new LinkedHashMap<>(Map.of("plan", plan)));
    }

    @Test
    @DisplayName("a label-only core gets the id add_node gives it, and survives the parser the engine runs")
    void labelOnlyCoreSurvivesTheParser() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, plan(
                List.of(setCore("Message au hasard")), List.of(edge("trigger:lancement", "core:message_au_hasard"))));

        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        assertThat(session.getCores().get(0)).containsEntry("id", "core:message_au_hasard");
        WorkflowPlan parsed = WorkflowPlan.fromMap(session.buildPlanMap());
        assertThat(parsed.getCores()).as("the step must not be skipped by the parser").hasSize(1);
        assertThat(session.getEdges().get(0)).containsEntry("to", "core:message_au_hasard");
    }

    @Test
    @DisplayName("a blank id is treated as missing")
    void blankIdIsReplaced() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> core = setCore("Message au hasard");
        core.put("id", " ");

        setPlan(session, plan(List.of(core), List.of(edge("Lancement", "Message au hasard"))));

        assertThat(session.getCores().get(0)).containsEntry("id", "core:message_au_hasard");
    }

    @Test
    @DisplayName("an id the plan states is kept as written, even when it differs from the label key")
    void statedIdIsKept() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> core = setCore("Message au hasard");
        core.put("id", "core:custom_id");

        setPlan(session, plan(List.of(core), List.of(edge("Lancement", "Message au hasard"))));

        assertThat(session.getCores().get(0)).containsEntry("id", "core:custom_id");
    }

    @Test
    @DisplayName("the port edges of a label-only decision still reach the generated key")
    void decisionPortEdgesResolve() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "decision");
        decision.put("label", "Check");
        decision.put("decisionConditions", List.of(
                new LinkedHashMap<>(Map.of("id", "check-if", "type", "if", "label", "Yes", "expression", "true")),
                new LinkedHashMap<>(Map.of("id", "check-else", "type", "else", "label", "No", "expression", "default"))));

        ToolExecutionResult result = setPlan(session, plan(
                List.of(decision, setCore("Message au hasard")),
                List.of(edge("Lancement", "Check"), edge("Check:if", "Message au hasard"))));

        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        assertThat(session.getCores()).extracting(c -> c.get("id")).containsExactly("core:check", "core:message_au_hasard");
        assertThat(session.getEdges()).extracting(e -> e.get("from")).contains("core:check:if");
        assertThat(WorkflowPlan.fromMap(session.buildPlanMap()).getCores()).hasSize(2);
    }

    @Test
    @DisplayName("a label with no latin letter or digit is refused: it would give no key and be skipped")
    void unkeyableLabelRefused() {
        ToolExecutionResult result = setPlan(newSession(), plan(List.of(setCore("发送消息")), List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("at least one latin letter or digit");
    }

    @Test
    @DisplayName("a non-latin label is accepted when the core states its id, as a canvas-saved core does")
    void unkeyableLabelWithStatedIdAccepted() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> core = setCore("发送消息");
        core.put("id", "set-1727000000000");

        ToolExecutionResult result = setPlan(session, plan(List.of(core), List.of()));

        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        assertThat(session.getCores().get(0)).containsEntry("id", "set-1727000000000");
        assertThat(WorkflowPlan.fromMap(session.buildPlanMap()).getCores()).hasSize(1);
    }

    @Test
    @DisplayName("two cores whose labels normalise alike are refused: they would share one id")
    void normalisedDuplicateRefused() {
        ToolExecutionResult result = setPlan(newSession(), plan(
                List.of(setCore("My Step"), setCore("my-step")), List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Duplicate label 'my-step'");
    }

    @Test
    @DisplayName("a stated id that another core's label key also gives is refused")
    void statedIdCollisionRefused() {
        Map<String, Object> first = setCore("Foo");
        first.put("id", "core:bar");

        ToolExecutionResult result = setPlan(newSession(), plan(List.of(first, setCore("Bar")), List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("id 'core:bar' is already used by another core");
    }

    @Test
    @DisplayName("a table without a type is refused: the parser would skip it")
    void tableWithoutTypeRefused() {
        Map<String, Object> p = plan(List.of(), List.of());
        p.put("tables", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("label", "Save Row")))));

        ToolExecutionResult result = setPlan(newSession(), p);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("tables[0]: 'type' is required (insert-row, find, read-row, update-row, delete-row)");
    }

    @Test
    @DisplayName("a table without a label is refused: the parser would skip it")
    void tableWithoutLabelRefused() {
        Map<String, Object> p = plan(List.of(), List.of());
        p.put("tables", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("type", "insert-row")))));

        ToolExecutionResult result = setPlan(newSession(), p);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("tables[0]: 'label' is required");
    }

    @Test
    @DisplayName("a core whose label normalises like an earlier node of another type is refused, as add_node refuses it")
    void crossSectionNormalisedDuplicateRefused() {
        ToolExecutionResult result = setPlan(newSession(), plan(List.of(setCore("lancement!")), List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Duplicate label 'lancement!'");
    }
}
