package com.apimarketplace.orchestrator.tools.interface_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for InterfaceNodeConfig.
 */
@DisplayName("InterfaceNodeConfig")
class InterfaceNodeConfigTest {

    @Nested
    @DisplayName("fromParams")
    class FromParamsTests {

        @Test
        @DisplayName("Should extract interface_id from params")
        void shouldExtractInterfaceId() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-123");

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.interfaceId()).isEqualTo("uuid-123");
        }

        @Test
        @DisplayName("Should support id alias")
        void shouldSupportIdAlias() {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "uuid-456");

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.interfaceId()).isEqualTo("uuid-456");
        }

        @Test
        @DisplayName("Should prefer interface_id over id")
        void shouldPreferInterfaceId() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "preferred");
            params.put("id", "fallback");

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.interfaceId()).isEqualTo("preferred");
        }

        @Test
        @DisplayName("Should extract variable_mapping")
        void shouldExtractVariableMapping() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("variable_mapping", Map.of("username", "{{trigger:start.output.name}}"));

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.variableMapping()).isNotNull();
            assertThat(config.variableMapping()).containsKey("username");
        }

        @Test
        @DisplayName("Should extract action_mapping")
        void shouldExtractActionMapping() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("action_mapping", Map.of("#btn-submit", "trigger:form:submit"));

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.actionMapping()).isNotNull();
            assertThat(config.actionMapping()).containsEntry("#btn-submit", "trigger:form:submit");
        }

        @Test
        @DisplayName("Should return null for missing interface_id")
        void shouldReturnNullForMissingId() {
            Map<String, Object> params = new HashMap<>();

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.interfaceId()).isNull();
        }

        @Test
        @DisplayName("Should return null mappings when not present")
        void shouldReturnNullMappingsWhenNotPresent() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.variableMapping()).isNull();
            assertThat(config.actionMapping()).isNull();
        }
    }

    @Nested
    @DisplayName("toNodeMap")
    class ToNodeMapTests {

        @Test
        @DisplayName("Should create node map with required fields")
        void shouldCreateNodeMap() {
            InterfaceNodeConfig config = new InterfaceNodeConfig("uuid-1", null, null, null, null, null);

            Map<String, Object> nodeMap = config.toNodeMap("My Interface", Map.of("x", 100, "y", 200));

            assertThat(nodeMap).containsEntry("id", "uuid-1");
            assertThat(nodeMap).containsEntry("label", "My Interface");
            assertThat(nodeMap).containsEntry("type", "interface");
            assertThat(nodeMap).containsKey("position");
        }

        @Test
        @DisplayName("Should include variable_mapping when present")
        void shouldIncludeVariableMapping() {
            Map<String, String> varMap = Map.of("name", "{{trigger:start.output.name}}");
            InterfaceNodeConfig config = new InterfaceNodeConfig("uuid-1", varMap, null, null, null, null);

            Map<String, Object> nodeMap = config.toNodeMap("Test", Map.of("x", 0, "y", 0));

            assertThat(nodeMap).containsKey("variableMapping");
        }

        @Test
        @DisplayName("Should not include empty variable_mapping")
        void shouldNotIncludeEmptyVariableMapping() {
            InterfaceNodeConfig config = new InterfaceNodeConfig("uuid-1", Map.of(), null, null, null, null);

            Map<String, Object> nodeMap = config.toNodeMap("Test", Map.of("x", 0, "y", 0));

            assertThat(nodeMap).doesNotContainKey("variableMapping");
        }
    }

    @Nested
    @DisplayName("toSavedParams")
    class ToSavedParamsTests {

        @Test
        @DisplayName("Should include interface_id")
        void shouldIncludeInterfaceId() {
            InterfaceNodeConfig config = new InterfaceNodeConfig("uuid-1", null, null, null, null, null);

            Map<String, Object> saved = config.toSavedParams();

            assertThat(saved).containsEntry("interface_id", "uuid-1");
        }

        @Test
        @DisplayName("Should include mappings when present")
        void shouldIncludeMappingsWhenPresent() {
            InterfaceNodeConfig config = new InterfaceNodeConfig("uuid-1",
                Map.of("k", "v"), Map.of("a", "b"), null, null, null);

            Map<String, Object> saved = config.toSavedParams();

            assertThat(saved).containsKey("variable_mapping");
            assertThat(saved).containsKey("action_mapping");
        }
    }

    @Nested
    @DisplayName("toExtras")
    class ToExtrasTests {

        @Test
        @DisplayName("Should include interface_id")
        void shouldIncludeInterfaceId() {
            InterfaceNodeConfig config = new InterfaceNodeConfig("uuid-1", null, null, null, null, null);

            Map<String, Object> extras = config.toExtras();

            assertThat(extras).containsEntry("interface_id", "uuid-1");
        }
    }

    @Nested
    @DisplayName("KNOWN_PARAMS")
    class KnownParamsTests {

        @Test
        @DisplayName("Should contain expected keys")
        void shouldContainExpectedKeys() {
            assertThat(InterfaceNodeConfig.KNOWN_PARAMS).contains(
                "interface_id", "id", "variable_mapping", "action_mapping",
                "is_entry_interface", "isEntryInterface",
                "generate_screenshot", "generateScreenshot",
                "expose_rendered_source", "exposeRenderedSource");
        }
    }

    @Nested
    @DisplayName("toggle plumbing (generateScreenshot / exposeRenderedSource)")
    class TogglePlumbing {

        @Test
        @DisplayName("Agent uses snake_case → both toggles extracted, persisted in nodeMap as camelCase")
        void agentSnakeCaseExtractsAndPersists() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("generate_screenshot", true);
            params.put("expose_rendered_source", true);

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generateScreenshot()).isTrue();
            assertThat(config.exposeRenderedSource()).isTrue();

            // nodeMap stores camelCase to match WorkflowPlanParser.parseInterfaces() lookups
            Map<String, Object> nodeMap = config.toNodeMap("My Form", Map.of("x", 0, "y", 0));
            assertThat(nodeMap).containsEntry("generateScreenshot", true);
            assertThat(nodeMap).containsEntry("exposeRenderedSource", true);
            assertThat(nodeMap).doesNotContainKey("generate_screenshot");
            assertThat(nodeMap).doesNotContainKey("expose_rendered_source");
        }

        @Test
        @DisplayName("Agent uses camelCase → both toggles extracted and persisted")
        void agentCamelCaseExtractsAndPersists() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("generateScreenshot", true);
            params.put("exposeRenderedSource", true);

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generateScreenshot()).isTrue();
            assertThat(config.exposeRenderedSource()).isTrue();
        }

        @Test
        @DisplayName("Both toggles default to null (= omitted from plan) when not supplied")
        void bothTogglesDefaultNullWhenMissing() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generateScreenshot()).isNull();
            assertThat(config.exposeRenderedSource()).isNull();

            // null toggles must NOT pollute the nodeMap - WorkflowPlanParser default-falses them.
            Map<String, Object> nodeMap = config.toNodeMap("X", Map.of("x", 0, "y", 0));
            assertThat(nodeMap).doesNotContainKey("generateScreenshot");
            assertThat(nodeMap).doesNotContainKey("exposeRenderedSource");
        }

        @Test
        @DisplayName("Agent passes false explicitly → toggle persisted as false (not omitted)")
        void explicitFalsePersistedAsFalse() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("generate_screenshot", false);
            params.put("expose_rendered_source", false);

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generateScreenshot()).isFalse();
            assertThat(config.exposeRenderedSource()).isFalse();

            Map<String, Object> nodeMap = config.toNodeMap("X", Map.of("x", 0, "y", 0));
            assertThat(nodeMap).containsEntry("generateScreenshot", false);
            assertThat(nodeMap).containsEntry("exposeRenderedSource", false);
        }

        @Test
        @DisplayName("Agent passes string 'true' → coerced to Boolean.TRUE (back-compat with LLMs serialising as strings)")
        void stringTrueCoercedToBoolean() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("generate_screenshot", "true");
            params.put("expose_rendered_source", "true");

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generateScreenshot()).isTrue();
            assertThat(config.exposeRenderedSource()).isTrue();
        }

        @Test
        @DisplayName("toSavedParams + toExtras emit snake_case (response visibility for the agent)")
        void savedParamsAndExtrasEmitSnakeCase() {
            InterfaceNodeConfig config = new InterfaceNodeConfig(
                "uuid-1", null, null, true, true, true);

            Map<String, Object> saved = config.toSavedParams();
            assertThat(saved).containsEntry("is_entry_interface", true);
            assertThat(saved).containsEntry("generate_screenshot", true);
            assertThat(saved).containsEntry("expose_rendered_source", true);

            Map<String, Object> extras = config.toExtras();
            assertThat(extras).containsEntry("generate_screenshot", true);
            assertThat(extras).containsEntry("expose_rendered_source", true);
        }
    }

    @Nested
    @DisplayName("action_mapping value validation (regression: agent invented {trigger,mapping} object)")
    class ActionMappingValueValidation {

        @Test
        @DisplayName("Rejects Map value for action_mapping with agent-actionable message")
        void rejectsMapValueWithActionableMessage() {
            // Reproduces the exact prod bug: agent supplied a structured object as the value
            // of an action_mapping entry trying to express HTML→trigger field renaming.
            // The pre-fix code coerced this via Map.toString() and stored garbage in DB.
            Map<String, Object> badInner = Map.of(
                "trigger", "trigger:search_criteria",
                "mapping", Map.of("search_query", "search_keyword")
            );
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("action_mapping", Map.of("#search-form", badInner));

            assertThatThrownBy(() -> InterfaceNodeConfig.fromParams(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action_mapping value")
                .hasMessageContaining("#search-form")
                .hasMessageContaining("must be a single string token")
                .hasMessageContaining("Field renaming inside action_mapping is NOT supported")
                .hasMessageContaining("workflow(action='help', topics=['interface'])");
        }

        @Test
        @DisplayName("Rejects List value for action_mapping")
        void rejectsListValue() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("action_mapping", Map.of("#btn", List.of("trigger:a:click", "trigger:b:click")));

            assertThatThrownBy(() -> InterfaceNodeConfig.fromParams(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a single string token");
        }

        @Test
        @DisplayName("Accepts a valid string token without throwing")
        void acceptsValidStringToken() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("action_mapping", Map.of("#search-form", "trigger:search:submit"));

            assertThatCode(() -> InterfaceNodeConfig.fromParams(params)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Variable_mapping still tolerates non-string scalars (no regression)")
        void variableMappingStillTolerantToScalars() {
            // variable_mapping has historically toString()'d Numbers/Booleans because
            // template values can legitimately be scalar. The strict check is action_mapping-only.
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("variable_mapping", Map.of("count", 42, "enabled", true));

            assertThatCode(() -> InterfaceNodeConfig.fromParams(params)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertActionMappingValuesAreStrings rejects map with object value")
        void publicHelperRejectsObjectValue() {
            Map<String, Object> bad = Map.of(
                "#form", Map.of("trigger", "trigger:x", "mapping", Map.of("a", "b"))
            );

            assertThatThrownBy(() -> InterfaceNodeConfig.assertActionMappingValuesAreStrings(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#form")
                .hasMessageContaining("must be a single string token");
        }

        @Test
        @DisplayName("assertActionMappingValuesAreStrings is no-op for null and non-Map")
        void publicHelperNoOpsOnNullAndNonMap() {
            assertThatCode(() -> InterfaceNodeConfig.assertActionMappingValuesAreStrings(null))
                .doesNotThrowAnyException();
            assertThatCode(() -> InterfaceNodeConfig.assertActionMappingValuesAreStrings("not a map"))
                .doesNotThrowAnyException();
            assertThatCode(() -> InterfaceNodeConfig.assertActionMappingValuesAreStrings(List.of("x")))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertActionMappingValuesAreStrings accepts valid all-string map")
        void publicHelperAcceptsAllStringMap() {
            Map<String, Object> good = Map.of(
                "#search-form", "trigger:search:submit",
                "#del", "trigger:delete:click",
                "#next", "__continue"
            );

            assertThatCode(() -> InterfaceNodeConfig.assertActionMappingValuesAreStrings(good))
                .doesNotThrowAnyException();
        }
    }
}
