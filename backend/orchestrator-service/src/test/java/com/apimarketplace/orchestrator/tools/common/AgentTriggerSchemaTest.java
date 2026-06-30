package com.apimarketplace.orchestrator.tools.common;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentTriggerSchema}.
 *
 * <p>The 2026-05-15 prod miss on FlyFinder (agent guessed {@code origin/destination/departure_date}
 * instead of SerpAPI's {@code departure_id/arrival_id/outbound_date}) drove this helper: every
 * agent-facing payload that points at a workflow MUST expose the exact form-field names so the
 * agent stops guessing.
 */
@DisplayName("AgentTriggerSchema")
class AgentTriggerSchemaTest {

    @SafeVarargs
    private static WorkflowPlan planWith(Map<String, Object>... triggers) {
        Map<String, Object> planData = Map.of(
                "id", "wf",
                "tenantId", "t1",
                "triggers", List.of(triggers),
                "tools", List.of(),
                "edges", List.of()
        );
        return WorkflowPlanParser.parse(planData, "wf", "t1");
    }

    private static Map<String, Object> formTrigger(String label, List<Map<String, Object>> fields) {
        return Map.of(
                "id", label,
                "label", label,
                "type", "form",
                "strategy", "single",
                "params", Map.of("fields", fields)
        );
    }

    private static Map<String, Object> simpleTrigger(String label, String type) {
        return Map.of(
                "id", label,
                "label", label,
                "type", type,
                "strategy", "single",
                "params", Map.of()
        );
    }

    @Nested
    @DisplayName("defaultTriggerId")
    class DefaultTriggerId {

        @Test
        @DisplayName("Returns the single fireable trigger's normalized key")
        void singleFireable() {
            WorkflowPlan plan = planWith(simpleTrigger("Search", "form"));
            assertThat(AgentTriggerSchema.defaultTriggerId(plan)).isEqualTo("trigger:search");
        }

        @Test
        @DisplayName("Returns null when zero fireable triggers")
        void noFireable() {
            WorkflowPlan plan = planWith(simpleTrigger("On Complete", "workflow"));
            assertThat(AgentTriggerSchema.defaultTriggerId(plan)).isNull();
        }

        @Test
        @DisplayName("Returns null when multiple fireable triggers (agent must specify trigger_id)")
        void multipleFireable() {
            WorkflowPlan plan = planWith(
                    simpleTrigger("Manual", "manual"),
                    simpleTrigger("Cron", "schedule")
            );
            assertThat(AgentTriggerSchema.defaultTriggerId(plan)).isNull();
        }

        @Test
        @DisplayName("Skips workflow/error triggers - picks the single user-fireable one")
        void mixedWithSystemTriggers() {
            WorkflowPlan plan = planWith(
                    simpleTrigger("On Complete", "workflow"),
                    simpleTrigger("Search", "form")
            );
            assertThat(AgentTriggerSchema.defaultTriggerId(plan)).isEqualTo("trigger:search");
        }
    }

    @Nested
    @DisplayName("fireableTriggerTypes")
    class FireableTriggerTypes {

        @Test
        @DisplayName("Lists distinct fireable types ignoring workflow/error")
        void distinctTypes() {
            WorkflowPlan plan = planWith(
                    simpleTrigger("Webhook", "webhook"),
                    simpleTrigger("Manual", "manual"),
                    simpleTrigger("On Complete", "workflow")
            );
            assertThat(AgentTriggerSchema.fireableTriggerTypes(plan))
                    .containsExactlyInAnyOrder("webhook", "manual");
        }

        @Test
        @DisplayName("Returns empty list when no fireable triggers (all system)")
        void noneFireable() {
            WorkflowPlan plan = planWith(simpleTrigger("On Complete", "workflow"));
            assertThat(AgentTriggerSchema.fireableTriggerTypes(plan)).isEmpty();
        }
    }

    @Nested
    @DisplayName("dataInputsSchema")
    class DataInputsSchemaTests {

        @Test
        @DisplayName("FORM trigger exposes exact field names + required flags (FlyFinder regression)")
        void formExposesFieldsVerbatim() {
            WorkflowPlan plan = planWith(formTrigger("Search", List.of(
                    Map.of("name", "departure_id", "type", "text", "required", true),
                    Map.of("name", "arrival_id", "type", "text", "required", true),
                    Map.of("name", "outbound_date", "type", "date", "required", true),
                    Map.of("name", "children", "type", "number", "required", false)
            )));

            Map<String, Object> schema = AgentTriggerSchema.dataInputsSchema(plan);
            assertThat(schema).isNotNull();
            assertThat(schema.get("trigger_type")).isEqualTo("form");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
            assertThat(fields).hasSize(4);
            assertThat(fields).extracting(f -> f.get("name"))
                    .containsExactly("departure_id", "arrival_id", "outbound_date", "children");
            assertThat(fields.get(0).get("required")).isEqualTo(true);
            assertThat(fields.get(3).get("required")).isEqualTo(false);
        }

        @Test
        @DisplayName("CHAT trigger advertises message:required canonical field")
        void chatExposesMessageField() {
            WorkflowPlan plan = planWith(simpleTrigger("Greet", "chat"));

            Map<String, Object> schema = AgentTriggerSchema.dataInputsSchema(plan);
            assertThat(schema.get("trigger_type")).isEqualTo("chat");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
            assertThat(fields).hasSize(1);
            assertThat(fields.get(0).get("name")).isEqualTo("message");
            assertThat(fields.get(0).get("required")).isEqualTo(true);
        }

        @Test
        @DisplayName("WEBHOOK trigger omits fields key - signals free-form data_inputs")
        void webhookOmitsFields() {
            WorkflowPlan plan = planWith(simpleTrigger("Hook", "webhook"));
            Map<String, Object> schema = AgentTriggerSchema.dataInputsSchema(plan);
            assertThat(schema.get("trigger_type")).isEqualTo("webhook");
            assertThat(schema).doesNotContainKey("fields");
        }

        @Test
        @DisplayName("Returns null when multiple fireable triggers exist (agent must disambiguate)")
        void nullOnMultipleFireable() {
            WorkflowPlan plan = planWith(
                    simpleTrigger("Manual", "manual"),
                    simpleTrigger("Cron", "schedule")
            );
            assertThat(AgentTriggerSchema.dataInputsSchema(plan)).isNull();
        }

        @Test
        @DisplayName("FORM with no fields returns schema sans fields (no false-positive guidance)")
        void formWithNoFieldsHasNoFieldsKey() {
            WorkflowPlan plan = planWith(formTrigger("Empty", List.of()));
            Map<String, Object> schema = AgentTriggerSchema.dataInputsSchema(plan);
            assertThat(schema.get("trigger_type")).isEqualTo("form");
            assertThat(schema).doesNotContainKey("fields");
        }
    }

    @Nested
    @DisplayName("null / empty inputs")
    class NullSafety {

        @Test
        @DisplayName("dataInputsSchema(null plan) returns null without throwing")
        void dataInputsSchemaNullPlan() {
            assertThat(AgentTriggerSchema.dataInputsSchema((WorkflowPlan) null)).isNull();
        }

        @Test
        @DisplayName("defaultTriggerId(null plan) returns null without throwing")
        void defaultTriggerIdNullPlan() {
            assertThat(AgentTriggerSchema.defaultTriggerId(null)).isNull();
        }

        @Test
        @DisplayName("fireableTriggerTypes(null plan) returns empty list")
        void fireableTriggerTypesNullPlan() {
            assertThat(AgentTriggerSchema.fireableTriggerTypes(null)).isEmpty();
        }
    }
}
