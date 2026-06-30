package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Trigger record.
 *
 * Trigger represents the entry point of a workflow.
 * Types: webhook, chat, schedule, datasource, manual, workflow.
 */
@DisplayName("Trigger")
class TriggerTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should create trigger with all fields")
        void shouldCreateTriggerWithAllFields() {
            Map<String, Object> params = Map.of("cron", "0 9 * * *");
            Trigger trigger = new Trigger("t1", "My Trigger", "single", "webhook", params, null);

            assertEquals("t1", trigger.id());
            assertEquals("my trigger", trigger.label());
            assertEquals("single", trigger.strategy());
            assertEquals("webhook", trigger.type());
            assertEquals(params, trigger.params());
        }

        @Test
        @DisplayName("Should create trigger with minimal constructor")
        void shouldCreateTriggerWithMinimalConstructor() {
            Trigger trigger = new Trigger("t1", "My Trigger", "single", "webhook");

            assertEquals("t1", trigger.id());
            assertEquals("my trigger", trigger.label());
            assertEquals("single", trigger.strategy());
            assertEquals("webhook", trigger.type());
            assertNotNull(trigger.params());
            assertTrue(trigger.params().isEmpty());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Should throw for null or blank id")
        void shouldThrowForNullOrBlankId(String id) {
            assertThrows(IllegalArgumentException.class,
                () -> new Trigger(id, "label", "single", "webhook"));
        }

        @Test
        @DisplayName("Should normalize id to lowercase")
        void shouldNormalizeIdToLowercase() {
            Trigger trigger = new Trigger("TRIGGER_1", "Label", "single", "webhook");

            assertEquals("trigger_1", trigger.id());
        }

        @Test
        @DisplayName("Should normalize label to lowercase")
        void shouldNormalizeLabelToLowercase() {
            Trigger trigger = new Trigger("t1", "My WEBHOOK", "single", "webhook");

            assertEquals("my webhook", trigger.label());
        }

        @Test
        @DisplayName("Should default strategy to 'single'")
        void shouldDefaultStrategyToSingle() {
            Trigger trigger = new Trigger("t1", "Label", null, "webhook");

            assertEquals("single", trigger.strategy());
        }

        @Test
        @DisplayName("Should default type to 'datasource'")
        void shouldDefaultTypeToDatasource() {
            Trigger trigger = new Trigger("t1", "Label", "single", null);

            assertEquals("datasource", trigger.type());
        }

        @Test
        @DisplayName("Should make params map unmodifiable")
        void shouldMakeParamsMapUnmodifiable() {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("key", "value");
            Trigger trigger = new Trigger("t1", "Label", "single", "webhook", params, null);

            assertThrows(UnsupportedOperationException.class,
                () -> trigger.params().put("new", "value"));
        }
    }

    @Nested
    @DisplayName("Trigger types")
    class TriggerTypesTests {

        @Test
        @DisplayName("Should create webhook trigger")
        void shouldCreateWebhookTrigger() {
            Trigger trigger = new Trigger("t1", "Webhook", "single", "webhook");

            assertEquals("webhook", trigger.type());
        }

        @Test
        @DisplayName("Should create chat trigger with default chatMatch")
        void shouldCreateChatTriggerWithDefaultChatMatch() {
            Trigger trigger = new Trigger("t1", "Chat", "single", "chat");

            assertEquals("chat", trigger.type());
            assertNotNull(trigger.chatMatch());
        }

        @Test
        @DisplayName("Should create schedule trigger")
        void shouldCreateScheduleTrigger() {
            Map<String, Object> params = Map.of("cron", "0 9 * * MON-FRI", "timezone", "Europe/Paris");
            Trigger trigger = new Trigger("t1", "Daily Report", "single", "schedule", params, null);

            assertEquals("schedule", trigger.type());
            assertEquals("0 9 * * MON-FRI", trigger.params().get("cron"));
        }

        @Test
        @DisplayName("Should create datasource trigger")
        void shouldCreateDatasourceTrigger() {
            Trigger trigger = new Trigger("ds-123", "Customer Data", "one_row", "datasource");

            assertEquals("datasource", trigger.type());
            assertEquals("one_row", trigger.strategy());
        }

        @Test
        @DisplayName("Should create manual trigger")
        void shouldCreateManualTrigger() {
            Trigger trigger = new Trigger("t1", "Start", "single", "manual");

            assertEquals("manual", trigger.type());
        }
    }

    @Nested
    @DisplayName("getNormalizedKey()")
    class GetNormalizedKeyTests {

        @Test
        @DisplayName("Should return normalized key with trigger: prefix")
        void shouldReturnNormalizedKeyWithPrefix() {
            Trigger trigger = new Trigger("t1", "My Webhook", "single", "webhook");

            assertEquals("trigger:my_webhook", trigger.getNormalizedKey());
        }

        @ParameterizedTest
        @CsvSource({
            "My Webhook, trigger:my_webhook",
            "Daily Report, trigger:daily_report",
            "Chat Support, trigger:chat_support",
            "API-Trigger, trigger:api_trigger"
        })
        @DisplayName("Should normalize various labels")
        void shouldNormalizeVariousLabels(String label, String expectedKey) {
            Trigger trigger = new Trigger("t1", label, "single", "webhook");

            assertEquals(expectedKey, trigger.getNormalizedKey());
        }

        @Test
        @DisplayName("Should fallback to id when label is null")
        void shouldFallbackToIdWhenLabelNull() {
            Trigger trigger = new Trigger("trigger_1", null, "single", "webhook");

            assertEquals("trigger:trigger_1", trigger.getNormalizedKey());
        }

        @Test
        @DisplayName("Should fallback to id when label is blank")
        void shouldFallbackToIdWhenLabelBlank() {
            Trigger trigger = new Trigger("trigger_1", "   ", "single", "webhook");

            assertEquals("trigger:trigger_1", trigger.getNormalizedKey());
        }
    }

    @Nested
    @DisplayName("Record equality")
    class RecordEqualityTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            Trigger trigger1 = new Trigger("t1", "Label", "single", "webhook");
            Trigger trigger2 = new Trigger("t1", "Label", "single", "webhook");

            assertEquals(trigger1, trigger2);
            assertEquals(trigger1.hashCode(), trigger2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different id")
        void shouldNotBeEqualForDifferentId() {
            Trigger trigger1 = new Trigger("t1", "Label", "single", "webhook");
            Trigger trigger2 = new Trigger("t2", "Label", "single", "webhook");

            assertNotEquals(trigger1, trigger2);
        }
    }
}
