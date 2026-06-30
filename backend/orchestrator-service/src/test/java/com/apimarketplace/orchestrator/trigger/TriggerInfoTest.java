package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for TriggerInfo record.
 */
@DisplayName("TriggerInfo")
class TriggerInfoTest {

    @Nested
    @DisplayName("record fields")
    class RecordFieldTests {

        @Test
        @DisplayName("Should store all fields correctly")
        void shouldStoreAllFields() {
            TriggerInfo info = new TriggerInfo(
                "trigger:my_webhook", "My Webhook", "webhook",
                true, Map.of("httpMethod", "POST")
            );

            assertThat(info.triggerId()).isEqualTo("trigger:my_webhook");
            assertThat(info.label()).isEqualTo("My Webhook");
            assertThat(info.type()).isEqualTo("webhook");
            assertThat(info.isReusable()).isTrue();
            assertThat(info.config()).containsEntry("httpMethod", "POST");
        }
    }

    @Nested
    @DisplayName("fromTrigger")
    class FromTriggerTests {

        @Test
        @DisplayName("Should create TriggerInfo from webhook trigger")
        void shouldCreateFromWebhookTrigger() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.getNormalizedKey()).thenReturn("trigger:my_webhook");
            when(trigger.label()).thenReturn("My Webhook");
            when(trigger.type()).thenReturn("webhook");
            when(trigger.params()).thenReturn(Map.of("httpMethod", "POST", "authType", "none"));

            TriggerInfo info = TriggerInfo.fromTrigger(trigger);

            assertThat(info.triggerId()).isEqualTo("trigger:my_webhook");
            assertThat(info.label()).isEqualTo("My Webhook");
            assertThat(info.type()).isEqualTo("webhook");
            assertThat(info.isReusable()).isTrue();
            assertThat(info.config()).containsEntry("httpMethod", "POST");
            assertThat(info.config()).containsEntry("authType", "none");
        }

        @Test
        @DisplayName("Should use id as label fallback when label is null")
        void shouldUseIdAsLabelFallback() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.getNormalizedKey()).thenReturn("trigger:manual");
            when(trigger.label()).thenReturn(null);
            when(trigger.id()).thenReturn("manual-trigger-id");
            when(trigger.type()).thenReturn("manual");
            when(trigger.params()).thenReturn(null);

            TriggerInfo info = TriggerInfo.fromTrigger(trigger);

            assertThat(info.label()).isEqualTo("manual-trigger-id");
        }

        @Test
        @DisplayName("Should return empty config when params is null")
        void shouldReturnEmptyConfigWhenParamsNull() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.getNormalizedKey()).thenReturn("trigger:manual");
            when(trigger.label()).thenReturn("Manual");
            when(trigger.type()).thenReturn("manual");
            when(trigger.params()).thenReturn(null);

            TriggerInfo info = TriggerInfo.fromTrigger(trigger);

            assertThat(info.config()).isEmpty();
        }

        @Test
        @DisplayName("Should extract form config for form trigger type")
        void shouldExtractFormConfig() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.getNormalizedKey()).thenReturn("trigger:contact_form");
            when(trigger.label()).thenReturn("Contact Form");
            when(trigger.type()).thenReturn("form");
            when(trigger.params()).thenReturn(Map.of(
                "formTitle", "Contact Us",
                "formDescription", "Send a message",
                "submitButtonText", "Send",
                "fields", java.util.List.of(Map.of("name", "email"))
            ));

            TriggerInfo info = TriggerInfo.fromTrigger(trigger);

            assertThat(info.config()).containsEntry("formTitle", "Contact Us");
            assertThat(info.config()).containsEntry("formDescription", "Send a message");
            assertThat(info.config()).containsEntry("submitButtonText", "Send");
            assertThat(info.config()).containsKey("fields");
        }

        @Test
        @DisplayName("Should extract schedule config for schedule trigger type")
        void shouldExtractScheduleConfig() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.getNormalizedKey()).thenReturn("trigger:daily_job");
            when(trigger.label()).thenReturn("Daily Job");
            when(trigger.type()).thenReturn("schedule");
            when(trigger.params()).thenReturn(Map.of(
                "cron", "0 0 * * *",
                "timezone", "America/New_York"
            ));

            TriggerInfo info = TriggerInfo.fromTrigger(trigger);

            assertThat(info.config()).containsEntry("cron", "0 0 * * *");
            assertThat(info.config()).containsEntry("timezone", "America/New_York");
        }

        @Test
        @DisplayName("Should return empty config for unknown trigger type")
        void shouldReturnEmptyConfigForUnknownType() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.getNormalizedKey()).thenReturn("trigger:custom");
            when(trigger.label()).thenReturn("Custom");
            when(trigger.type()).thenReturn("custom_unknown_type");
            when(trigger.params()).thenReturn(Map.of("key", "value"));

            TriggerInfo info = TriggerInfo.fromTrigger(trigger);

            assertThat(info.config()).isEmpty();
        }
    }
}
