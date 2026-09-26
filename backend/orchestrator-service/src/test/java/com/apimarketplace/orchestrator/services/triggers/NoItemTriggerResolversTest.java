package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FormTriggerResolver} and {@link ErrorTriggerResolver} replace the
 * {@code "Unsupported trigger type"} exception the step-by-step loader used to hit for these
 * types. Their payload must keep the loader's outcome (zero items) through the explicit
 * {@code count: 0} marker, and must not invent output fields.
 */
@DisplayName("Form / error trigger resolvers")
class NoItemTriggerResolversTest {

    private static final Map<String, Object> FORM_PARAMS = Map.of(
        "title", "Contact",
        "fields", List.of(Map.of("name", "email", "type", "email", "label", "Email")));

    @Nested
    @DisplayName("FormTriggerResolver")
    class Form {

        private final FormTriggerResolver resolver = new FormTriggerResolver();

        @Test
        @DisplayName("handles 'form' in any case and nothing else")
        void canHandle() {
            assertThat(resolver.canHandle("form")).isTrue();
            assertThat(resolver.canHandle("FORM")).isTrue();
            assertThat(resolver.canHandle("webhook")).isFalse();
            assertThat(resolver.canHandle("error")).isFalse();
            assertThat(resolver.canHandle(null)).isFalse();
        }

        @Test
        @DisplayName("returns the no-item payload: data=[], count=0, trigger identity, no field definitions copied in")
        void noItemPayload() {
            Trigger trigger = new Trigger("trigger:contact", "Contact", "single", "form", FORM_PARAMS, null);

            Map<String, Object> payload = resolver.resolve(trigger, "tenant-1", Map.of("form_data", Map.of("email", "a@b.c")));

            assertThat(payload)
                .containsEntry("triggerId", "trigger:contact")
                .containsEntry("type", "form")
                .containsEntry("source", "form")
                .containsEntry("status", "success")
                .containsEntry("data", List.of())
                .containsEntry("count", 0);
            assertThat(payload).doesNotContainKeys("title", "fields", "form_data");
            assertThat(payload).hasSize(6);
        }

        @Test
        @DisplayName("null params and null resolved inputs are tolerated")
        void nullInputs() {
            Trigger trigger = new Trigger("trigger:f", "f", "single", "form", null, null);

            assertThat(resolver.resolve(trigger, "tenant-1", null)).containsEntry("count", 0);
        }
    }

    @Nested
    @DisplayName("ErrorTriggerResolver")
    class Error {

        private final ErrorTriggerResolver resolver = new ErrorTriggerResolver();

        @Test
        @DisplayName("handles 'error' in any case and nothing else")
        void canHandle() {
            assertThat(resolver.canHandle("error")).isTrue();
            assertThat(resolver.canHandle("Error")).isTrue();
            assertThat(resolver.canHandle("workflow")).isFalse();
            assertThat(resolver.canHandle("form")).isFalse();
            assertThat(resolver.canHandle(null)).isFalse();
        }

        @Test
        @DisplayName("returns the no-item payload: data=[], count=0, trigger identity only")
        void noItemPayload() {
            Trigger trigger = new Trigger("trigger:on_fail", "On fail", "single", "error", Map.of("watch", "wf-1"), null);

            Map<String, Object> payload = resolver.resolve(trigger, "tenant-1", Map.of());

            assertThat(payload)
                .containsEntry("triggerId", "trigger:on_fail")
                .containsEntry("type", "error")
                .containsEntry("source", "error")
                .containsEntry("status", "success")
                .containsEntry("data", List.of())
                .containsEntry("count", 0)
                .hasSize(6);
        }
    }
}
