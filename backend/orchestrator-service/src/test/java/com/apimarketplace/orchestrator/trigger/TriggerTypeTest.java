package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for TriggerType enum.
 */
@DisplayName("TriggerType")
class TriggerTypeTest {

    @Nested
    @DisplayName("fromString")
    class FromStringTests {

        @ParameterizedTest(name = "''{0}'' should parse to {1}")
        @CsvSource({
            "webhook, WEBHOOK",
            "manual, MANUAL",
            "chat, CHAT",
            "datasource, DATASOURCE",
            "schedule, SCHEDULE",
            "form, FORM",
            "workflow, WORKFLOW",
            "error, ERROR"
        })
        @DisplayName("Should parse all valid trigger types")
        void shouldParseAllValidTypes(String input, String expectedEnum) {
            TriggerType result = TriggerType.fromString(input);
            assertThat(result.name()).isEqualTo(expectedEnum);
        }

        @ParameterizedTest(name = "''{0}'' should parse case-insensitively")
        @ValueSource(strings = {"WEBHOOK", "Webhook", "wEbHoOk"})
        @DisplayName("Should parse case-insensitively")
        void shouldParseCaseInsensitively(String input) {
            TriggerType result = TriggerType.fromString(input);
            assertThat(result).isEqualTo(TriggerType.WEBHOOK);
        }

        @Test
        @DisplayName("Should throw for null input")
        void shouldThrowForNullInput() {
            assertThatThrownBy(() -> TriggerType.fromString(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
        }

        @ParameterizedTest(name = "''{0}'' should throw for unknown type")
        @ValueSource(strings = {"unknown", "trigger", "cron", ""})
        @DisplayName("Should throw for unknown types")
        void shouldThrowForUnknownTypes(String type) {
            assertThatThrownBy(() -> TriggerType.fromString(type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown trigger type");
        }
    }

    @Nested
    @DisplayName("fromValue")
    class FromValueTests {

        @Test
        @DisplayName("fromValue should behave the same as fromString")
        void shouldBehaveSameAsFromString() {
            assertThat(TriggerType.fromValue("webhook")).isEqualTo(TriggerType.fromString("webhook"));
        }
    }

    @Nested
    @DisplayName("isReusableTriggerType")
    class IsReusableTriggerTypeTests {

        @ParameterizedTest(name = "''{0}'' should be reusable")
        @ValueSource(strings = {"webhook", "manual", "chat", "datasource", "schedule", "form", "workflow", "error"})
        @DisplayName("Should return true for all trigger types")
        void shouldReturnTrueForAllTypes(String type) {
            assertThat(TriggerType.isReusableTriggerType(type)).isTrue();
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            assertThat(TriggerType.isReusableTriggerType(null)).isFalse();
        }

        @Test
        @DisplayName("Should return false for unknown type")
        void shouldReturnFalseForUnknownType() {
            assertThat(TriggerType.isReusableTriggerType("unknown")).isFalse();
        }
    }

    @Nested
    @DisplayName("isReusable")
    class IsReusableTests {

        @Test
        @DisplayName("Should return true for a reusable trigger")
        void shouldReturnTrueForReusableTrigger() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.type()).thenReturn("webhook");

            assertThat(TriggerType.isReusable(trigger)).isTrue();
        }

        @Test
        @DisplayName("Should return false for null trigger")
        void shouldReturnFalseForNullTrigger() {
            assertThat(TriggerType.isReusable(null)).isFalse();
        }

        @Test
        @DisplayName("Should return false when trigger type is null")
        void shouldReturnFalseWhenTypeIsNull() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.type()).thenReturn(null);

            assertThat(TriggerType.isReusable(trigger)).isFalse();
        }
    }

    @Nested
    @DisplayName("fromTrigger")
    class FromTriggerTests {

        @Test
        @DisplayName("Should return correct type for known trigger")
        void shouldReturnCorrectType() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.type()).thenReturn("schedule");

            assertThat(TriggerType.fromTrigger(trigger)).isEqualTo(TriggerType.SCHEDULE);
        }

        @Test
        @DisplayName("Should return null for null trigger")
        void shouldReturnNullForNullTrigger() {
            assertThat(TriggerType.fromTrigger(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when trigger type is null")
        void shouldReturnNullWhenTypeIsNull() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.type()).thenReturn(null);

            assertThat(TriggerType.fromTrigger(trigger)).isNull();
        }

        @Test
        @DisplayName("Should return null for unknown trigger type")
        void shouldReturnNullForUnknownType() {
            Trigger trigger = mock(Trigger.class);
            when(trigger.type()).thenReturn("unknown_type");

            assertThat(TriggerType.fromTrigger(trigger)).isNull();
        }
    }

    @Nested
    @DisplayName("supportsAccumulation")
    class SupportsAccumulationTests {

        @Test
        @DisplayName("All trigger types should support accumulation")
        void allTypesShouldSupportAccumulation() {
            for (TriggerType type : TriggerType.values()) {
                assertThat(type.supportsAccumulation()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("getValue")
    class GetValueTests {

        @Test
        @DisplayName("Should return lowercase string values")
        void shouldReturnLowercaseValues() {
            assertThat(TriggerType.WEBHOOK.getValue()).isEqualTo("webhook");
            assertThat(TriggerType.MANUAL.getValue()).isEqualTo("manual");
            assertThat(TriggerType.CHAT.getValue()).isEqualTo("chat");
            assertThat(TriggerType.DATASOURCE.getValue()).isEqualTo("datasource");
            assertThat(TriggerType.SCHEDULE.getValue()).isEqualTo("schedule");
            assertThat(TriggerType.FORM.getValue()).isEqualTo("form");
            assertThat(TriggerType.WORKFLOW.getValue()).isEqualTo("workflow");
            assertThat(TriggerType.ERROR.getValue()).isEqualTo("error");
        }
    }
}
