package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.SmartDefaultsEngine;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the form-trigger options coercion introduced after a
 * prod incident on 2026-05-01: an LLM agent submitted
 * {@code options: ["gpt-image-1.5", "gpt-image-2"]} on a {@code select}
 * field, the backend persisted the string-array as-is, and the inspector
 * (which keys on {@code option.id / .label / .value}) rendered empty inputs.
 *
 * <p>The coerce path now normalizes string shorthand into the canonical
 * {@code {id, label, value}} object form, rejects malformed shapes loud
 * (instead of silently empty), and surfaces a usable error to the agent.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerCreator - Form options coercion (V161 regression)")
class TriggerCreatorFormOptionsTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private SmartDefaultsEngine smartDefaultsEngine;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private TriggerClient triggerClient;

    private TriggerCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TriggerCreator(sessionStore, dataSourceClient,
                smartDefaultsEngine, responseOptimizer, triggerClient);
        lenient().when(smartDefaultsEngine.applyTriggerDefaults(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(responseOptimizer.buildTriggerResponse(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LinkedHashMap<>());
    }

    private WorkflowBuilderSession session() {
        return WorkflowBuilderSession.create("tenant-1", "conv-1", "Test Workflow", null);
    }

    private Map<String, Object> formParams(List<Map<String, Object>> fields) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("label", "Image Prompt");
        params.put("trigger_type", "form");
        params.put("title", "AI Image Comparator");
        params.put("fields", fields);
        return params;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> persistedFields(WorkflowBuilderSession s) {
        Map<String, Object> trigger = s.getTriggers().get(s.getTriggers().size() - 1);
        Map<String, Object> params = (Map<String, Object>) trigger.get("params");
        return (List<Map<String, Object>>) params.get("fields");
    }

    @Nested
    @DisplayName("string-shorthand coercion")
    class StringShorthand {

        @Test
        @DisplayName("Coerces options: [\"a\", \"b\"] to [{id, label=value=string}, ...] on select")
        void coercesStringArrayToObjects() {
            WorkflowBuilderSession s = session();
            Map<String, Object> selectField = new LinkedHashMap<>();
            selectField.put("name", "openai_model");
            selectField.put("type", "select");
            selectField.put("label", "OpenAI Model");
            selectField.put("required", true);
            selectField.put("options", List.of("gpt-image-1.5", "gpt-image-2", "gpt-image-1-mini"));

            ToolExecutionResult result = creator.executeAddTrigger(s,
                    formParams(List.of(selectField)), "tenant-1");

            assertThat(result.success()).isTrue();
            List<Map<String, Object>> fields = persistedFields(s);
            assertThat(fields).hasSize(1);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> options = (List<Map<String, Object>>) fields.get(0).get("options");
            assertThat(options).hasSize(3);
            assertThat(options.get(0))
                    .containsEntry("id", "opt-0")
                    .containsEntry("label", "gpt-image-1.5")
                    .containsEntry("value", "gpt-image-1.5");
            assertThat(options.get(2))
                    .containsEntry("id", "opt-2")
                    .containsEntry("label", "gpt-image-1-mini")
                    .containsEntry("value", "gpt-image-1-mini");
        }

        @Test
        @DisplayName("Same coercion applies to multiselect / radio / checkboxGroup")
        void coercesAcrossAllOptionBearingTypes() {
            for (String fieldType : List.of("multiselect", "radio", "checkboxGroup")) {
                WorkflowBuilderSession s = session();
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", "choice_" + fieldType);
                field.put("type", fieldType);
                field.put("label", "Pick");
                field.put("options", List.of("a", "b"));

                ToolExecutionResult result = creator.executeAddTrigger(s,
                        formParams(List.of(field)), "tenant-1");
                assertThat(result.success())
                        .as("type=%s should be coerced", fieldType)
                        .isTrue();

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> options = (List<Map<String, Object>>) persistedFields(s).get(0).get("options");
                assertThat(options)
                        .as("coerced options for type=%s", fieldType)
                        .extracting("label", "value")
                        .containsExactly(
                                org.assertj.core.groups.Tuple.tuple("a", "a"),
                                org.assertj.core.groups.Tuple.tuple("b", "b"));
            }
        }
    }

    @Nested
    @DisplayName("canonical-shape passthrough")
    class CanonicalShape {

        @Test
        @DisplayName("Preserves {label, value} object form and fills missing id with opt-N")
        void preservesObjectFormFillsMissingId() {
            WorkflowBuilderSession s = session();
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", "model");
            field.put("type", "select");
            field.put("label", "Model");
            field.put("options", List.of(
                    Map.of("label", "GPT 2", "value", "gpt-2"),
                    Map.of("label", "GPT 1.5", "value", "gpt-1.5", "id", "user-id-keep")
            ));

            ToolExecutionResult result = creator.executeAddTrigger(s,
                    formParams(List.of(field)), "tenant-1");
            assertThat(result.success()).isTrue();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> options = (List<Map<String, Object>>) persistedFields(s).get(0).get("options");
            assertThat(options.get(0))
                    .containsEntry("id", "opt-0")
                    .containsEntry("label", "GPT 2")
                    .containsEntry("value", "gpt-2");
            assertThat(options.get(1))
                    .containsEntry("id", "user-id-keep")
                    .containsEntry("label", "GPT 1.5")
                    .containsEntry("value", "gpt-1.5");
        }
    }

    @Nested
    @DisplayName("error reporting (loud-not-silent)")
    class ErrorReporting {

        @Test
        @DisplayName("Rejects select field with missing options entirely")
        void rejectsSelectMissingOptions() {
            WorkflowBuilderSession s = session();
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", "model");
            field.put("type", "select");
            field.put("label", "Model");
            // no options

            ToolExecutionResult result = creator.executeAddTrigger(s,
                    formParams(List.of(field)), "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error())
                    .contains("model")
                    .contains("options");
        }

        @Test
        @DisplayName("Rejects empty options array")
        void rejectsEmptyOptionsArray() {
            WorkflowBuilderSession s = session();
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", "model");
            field.put("type", "select");
            field.put("label", "Model");
            field.put("options", List.of());

            ToolExecutionResult result = creator.executeAddTrigger(s,
                    formParams(List.of(field)), "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("empty");
        }

        @Test
        @DisplayName("Rejects object option missing label")
        void rejectsOptionMissingLabel() {
            WorkflowBuilderSession s = session();
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", "model");
            field.put("type", "select");
            field.put("label", "Model");
            field.put("options", List.of(Map.of("value", "v1")));

            ToolExecutionResult result = creator.executeAddTrigger(s,
                    formParams(List.of(field)), "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("label");
        }

        @Test
        @DisplayName("Rejects mixed-shape options (number in array)")
        void rejectsNumberInOptionsArray() {
            WorkflowBuilderSession s = session();
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", "qty");
            field.put("type", "select");
            field.put("label", "Quantity");
            field.put("options", List.of("1", 2, "3"));

            ToolExecutionResult result = creator.executeAddTrigger(s,
                    formParams(List.of(field)), "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Integer");
        }
    }

    @Nested
    @DisplayName("non-option-bearing fields untouched")
    class NonOptionFields {

        @Test
        @DisplayName("text/textarea/email fields skip options coercion")
        void skipsCoercionForNonOptionFields() {
            WorkflowBuilderSession s = session();
            Map<String, Object> textField = new LinkedHashMap<>();
            textField.put("name", "prompt");
            textField.put("type", "textarea");
            textField.put("label", "Prompt");
            textField.put("required", true);

            ToolExecutionResult result = creator.executeAddTrigger(s,
                    formParams(List.of(textField)), "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(persistedFields(s).get(0))
                    .doesNotContainKey("options");
        }

        @Test
        @DisplayName("Auto-fills missing field.id with field-N for inspector React keys")
        void autoFillsFieldIdWhenMissing() {
            WorkflowBuilderSession s = session();
            Map<String, Object> f1 = new LinkedHashMap<>();
            f1.put("name", "a");
            f1.put("type", "text");
            Map<String, Object> f2 = new LinkedHashMap<>();
            f2.put("name", "b");
            f2.put("type", "text");

            ToolExecutionResult result = creator.executeAddTrigger(s,
                    formParams(List.of(f1, f2)), "tenant-1");
            assertThat(result.success()).isTrue();

            assertThat(persistedFields(s).get(0)).containsEntry("id", "field-0");
            assertThat(persistedFields(s).get(1)).containsEntry("id", "field-1");
        }
    }
}
