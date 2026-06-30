package com.apimarketplace.common.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("PlanStripUtils - strip standalone trigger refs")
class PlanStripUtilsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> planWithTrigger(String type, Map<String, Object> params) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", type);
        trigger.put("params", params);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));
        return plan;
    }

    @Test
    @DisplayName("Removes scheduleId, webhookId, chatEndpointId, formEndpointId from params")
    void removesAllFourKeys() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scheduleId", "uuid-1");
        params.put("webhookId", "uuid-2");
        params.put("chatEndpointId", "uuid-3");
        params.put("formEndpointId", "uuid-4");
        params.put("cron", "0 * * * *");  // unrelated key - must remain
        params.put("timezone", "UTC");

        Map<String, Object> plan = planWithTrigger("schedule", params);
        PlanStripUtils.stripStandaloneTriggerRefs(plan);

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((Map<?, ?>) ((List<?>) plan.get("triggers")).get(0)).get("params");
        assertThat(after).doesNotContainKeys("scheduleId", "webhookId", "chatEndpointId", "formEndpointId");
        assertThat(after).containsEntry("cron", "0 * * * *");
        assertThat(after).containsEntry("timezone", "UTC");
    }

    @Test
    @DisplayName("Idempotent - re-running on already-stripped plan is a no-op")
    void idempotent() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scheduleId", "uuid-1");
        Map<String, Object> plan = planWithTrigger("schedule", params);

        PlanStripUtils.stripStandaloneTriggerRefs(plan);
        PlanStripUtils.stripStandaloneTriggerRefs(plan);  // second call

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((Map<?, ?>) ((List<?>) plan.get("triggers")).get(0)).get("params");
        assertThat(after).doesNotContainKey("scheduleId");
    }

    @Test
    @DisplayName("Fail-soft on null plan")
    void nullPlan() {
        assertThatCode(() -> PlanStripUtils.stripStandaloneTriggerRefs(null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Fail-soft on plan with no triggers field")
    void planWithoutTriggers() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("nodes", List.of());
        assertThatCode(() -> PlanStripUtils.stripStandaloneTriggerRefs(plan))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Fail-soft on triggers field that is not a list")
    void triggersNotList() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", "not a list");
        assertThatCode(() -> PlanStripUtils.stripStandaloneTriggerRefs(plan))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Fail-soft on trigger entry that is not a map")
    void triggerEntryNotMap() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of("string instead of map")));
        assertThatCode(() -> PlanStripUtils.stripStandaloneTriggerRefs(plan))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Fail-soft on missing or non-map params")
    void paramsNotMap() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "schedule");
        trigger.put("params", "not a map");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));

        assertThatCode(() -> PlanStripUtils.stripStandaloneTriggerRefs(plan))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Plan without standalone refs is unchanged (no-op)")
    void noStandaloneRefs() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cron", "*/5 * * * *");
        params.put("timezone", "UTC");
        Map<String, Object> plan = planWithTrigger("schedule", params);

        PlanStripUtils.stripStandaloneTriggerRefs(plan);

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((Map<?, ?>) ((List<?>) plan.get("triggers")).get(0)).get("params");
        assertThat(after).containsEntry("cron", "*/5 * * * *");
        assertThat(after).containsEntry("timezone", "UTC");
        assertThat(after).hasSize(2);
    }

    @Test
    @DisplayName("Multiple triggers - strip each independently")
    void multipleTriggers() {
        Map<String, Object> scheduleParams = new LinkedHashMap<>();
        scheduleParams.put("scheduleId", "sched-1");
        scheduleParams.put("cron", "0 * * * *");

        Map<String, Object> webhookParams = new LinkedHashMap<>();
        webhookParams.put("webhookId", "wh-1");
        webhookParams.put("method", "POST");

        Map<String, Object> scheduleTrig = new LinkedHashMap<>();
        scheduleTrig.put("type", "schedule");
        scheduleTrig.put("params", scheduleParams);

        Map<String, Object> webhookTrig = new LinkedHashMap<>();
        webhookTrig.put("type", "webhook");
        webhookTrig.put("params", webhookParams);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(scheduleTrig, webhookTrig)));

        PlanStripUtils.stripStandaloneTriggerRefs(plan);

        assertThat(scheduleParams).doesNotContainKey("scheduleId").containsKey("cron");
        assertThat(webhookParams).doesNotContainKey("webhookId").containsKey("method");
    }

    @Test
    @DisplayName("deepCopyAndStrip: source plan is unmodified after strip")
    void deepCopyDoesNotMutateSource() {
        Map<String, Object> sourceParams = new LinkedHashMap<>();
        sourceParams.put("scheduleId", "uuid-source");
        sourceParams.put("cron", "0 * * * *");
        Map<String, Object> sourcePlan = planWithTrigger("schedule", sourceParams);

        Map<String, Object> stripped = PlanStripUtils.deepCopyAndStrip(sourcePlan, objectMapper);

        // Source plan unchanged
        assertThat(sourceParams).containsEntry("scheduleId", "uuid-source");

        // Stripped copy has no scheduleId
        @SuppressWarnings("unchecked")
        Map<String, Object> strippedParams = (Map<String, Object>) ((Map<?, ?>) ((List<?>) stripped.get("triggers")).get(0)).get("params");
        assertThat(strippedParams).doesNotContainKey("scheduleId");
        assertThat(strippedParams).containsEntry("cron", "0 * * * *");
    }

    @Test
    @DisplayName("deepCopyAndStrip: nested list mutation in copy does not affect source")
    void deepCopyIsolatesNestedLists() {
        Map<String, Object> sourceParams = new LinkedHashMap<>();
        sourceParams.put("scheduleId", "uuid-source");
        Map<String, Object> sourcePlan = planWithTrigger("schedule", sourceParams);

        Map<String, Object> stripped = PlanStripUtils.deepCopyAndStrip(sourcePlan, objectMapper);

        // Mutate the stripped copy's triggers list
        @SuppressWarnings("unchecked")
        List<Object> strippedTriggers = (List<Object>) stripped.get("triggers");
        strippedTriggers.add(Map.of("type", "extra"));

        // Source triggers list unaffected
        @SuppressWarnings("unchecked")
        List<Object> sourceTriggers = (List<Object>) sourcePlan.get("triggers");
        assertThat(sourceTriggers).hasSize(1);
        assertThat(strippedTriggers).hasSize(2);
    }

    @Test
    @DisplayName("deepCopyAndStrip: null source returns null")
    void deepCopyNullSource() {
        assertThat(PlanStripUtils.deepCopyAndStrip(null, objectMapper)).isNull();
    }
}
