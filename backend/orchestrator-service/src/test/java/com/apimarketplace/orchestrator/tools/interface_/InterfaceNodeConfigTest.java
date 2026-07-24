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
                "expose_rendered_source", "exposeRenderedSource",
                "generate_pdf", "generatePdf",
                "pdf_format", "pdfFormat",
                "pdf_landscape", "pdfLandscape",
                "generate_video", "generateVideo",
                "video_preset", "videoPreset",
                "video_max_duration_seconds", "videoMaxDurationSeconds");
        }

        @Test
        @DisplayName("Should contain the three global-format alias keys")
        void shouldContainFormatAliasKeys() {
            assertThat(InterfaceNodeConfig.KNOWN_PARAMS).contains(
                "format", "interface_format", "interfaceFormat");
        }
    }

    @Nested
    @DisplayName("Retired node-level format (moved to the interface entity)")
    class RetiredNodeFormat {

        private Map<String, Object> paramsWith(String key, String value) {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put(key, value);
            return params;
        }

        @Test
        @DisplayName("A legacy format key is dropped, not carried into the node")
        void legacyFormatKeyDropped() {
            // The shape belongs to the interface (its HTML is authored for one fixed viewport
            // width). Pre-refactor plans still carry the key: fromParams must ignore it rather
            // than fail, so those workflows keep loading and running.
            for (String key : new String[] { "format", "interface_format", "interfaceFormat" }) {
                InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(paramsWith(key, "vertical"));
                assertThat(config.interfaceId()).isEqualTo("uuid-1");
            }
        }

        @Test
        @DisplayName("The legacy keys stay in KNOWN_PARAMS so validation still accepts them")
        void legacyKeysStillKnown() {
            assertThat(InterfaceNodeConfig.KNOWN_PARAMS).contains(
                "format", "interface_format", "interfaceFormat");
        }

        @Test
        @DisplayName("No emitter re-writes a format key back into the plan")
        void emittersNeverWriteFormat() {
            // Round-tripping a legacy plan must strip the key for good: re-emitting it would
            // resurrect a param the engine ignores, and the two would silently drift apart.
            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(paramsWith("format", "vertical"));

            assertThat(config.toNodeMap("X", Map.of("x", 0, "y", 0))).doesNotContainKey("format");
            assertThat(config.toSavedParams()).doesNotContainKey("format");
            assertThat(config.toExtras()).doesNotContainKey("format");
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
    @DisplayName("PDF plumbing (generatePdf / pdfFormat / pdfLandscape)")
    class PdfPlumbing {

        @Test
        @DisplayName("Agent uses snake_case → PDF fields extracted, persisted in nodeMap as camelCase")
        void agentSnakeCaseExtractsAndPersists() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("generate_pdf", true);
            params.put("pdf_format", "Letter");
            params.put("pdf_landscape", true);

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generatePdf()).isTrue();
            assertThat(config.pdfFormat()).isEqualTo("Letter");
            assertThat(config.pdfLandscape()).isTrue();

            Map<String, Object> nodeMap = config.toNodeMap("My Form", Map.of("x", 0, "y", 0));
            assertThat(nodeMap).containsEntry("generatePdf", true);
            assertThat(nodeMap).containsEntry("pdfFormat", "Letter");
            assertThat(nodeMap).containsEntry("pdfLandscape", true);
            assertThat(nodeMap).doesNotContainKey("generate_pdf");
            assertThat(nodeMap).doesNotContainKey("pdf_format");
        }

        @Test
        @DisplayName("Agent uses camelCase → PDF fields extracted")
        void agentCamelCaseExtracts() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("generatePdf", true);
            params.put("pdfFormat", "A4");
            params.put("pdfLandscape", false);

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generatePdf()).isTrue();
            assertThat(config.pdfFormat()).isEqualTo("A4");
            assertThat(config.pdfLandscape()).isFalse();
        }

        @Test
        @DisplayName("pdfFormat is normalised to a supported page size (case-insensitive); unknown → null (falls back to A4 at render)")
        void pdfFormatNormalised() {
            assertThat(fromFormat("letter").pdfFormat()).isEqualTo("Letter");
            assertThat(fromFormat("LEGAL").pdfFormat()).isEqualTo("Legal");
            assertThat(fromFormat("a4").pdfFormat()).isEqualTo("A4");
            assertThat(fromFormat("Tabloid").pdfFormat()).isNull();
            assertThat(fromFormat("  ").pdfFormat()).isNull();
        }

        private InterfaceNodeConfig fromFormat(String format) {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("pdf_format", format);
            return InterfaceNodeConfig.fromParams(params);
        }

        @Test
        @DisplayName("PDF fields default to null (= omitted) when not supplied - no nodeMap pollution")
        void pdfFieldsDefaultNullWhenMissing() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generatePdf()).isNull();
            assertThat(config.pdfFormat()).isNull();
            assertThat(config.pdfLandscape()).isNull();

            Map<String, Object> nodeMap = config.toNodeMap("X", Map.of("x", 0, "y", 0));
            assertThat(nodeMap).doesNotContainKey("generatePdf");
            assertThat(nodeMap).doesNotContainKey("pdfFormat");
            assertThat(nodeMap).doesNotContainKey("pdfLandscape");
        }

        @Test
        @DisplayName("toSavedParams + toExtras emit PDF fields as snake_case (response visibility for the agent)")
        void savedParamsAndExtrasEmitSnakeCase() {
            InterfaceNodeConfig config = new InterfaceNodeConfig(
                "uuid-1", null, null, false, false, false, true, "Legal", true);

            Map<String, Object> saved = config.toSavedParams();
            assertThat(saved).containsEntry("generate_pdf", true);
            assertThat(saved).containsEntry("pdf_format", "Legal");
            assertThat(saved).containsEntry("pdf_landscape", true);

            Map<String, Object> extras = config.toExtras();
            assertThat(extras).containsEntry("generate_pdf", true);
            assertThat(extras).containsEntry("pdf_format", "Legal");
            assertThat(extras).containsEntry("pdf_landscape", true);
        }
    }

    @Nested
    @DisplayName("Video plumbing (generateVideo / videoPreset / videoMaxDurationSeconds)")
    class VideoPlumbing {

        @Test
        @DisplayName("Agent uses snake_case → video fields extracted, persisted in nodeMap as camelCase")
        void agentSnakeCaseExtractsAndPersists() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("generate_video", true);
            params.put("video_preset", "square");
            params.put("video_max_duration_seconds", 45);

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generateVideo()).isTrue();
            assertThat(config.videoPreset()).isEqualTo("square");
            assertThat(config.videoMaxDurationSeconds()).isEqualTo(45);

            Map<String, Object> nodeMap = config.toNodeMap("My Form", Map.of("x", 0, "y", 0));
            assertThat(nodeMap).containsEntry("generateVideo", true);
            assertThat(nodeMap).containsEntry("videoPreset", "square");
            assertThat(nodeMap).containsEntry("videoMaxDurationSeconds", 45);
            assertThat(nodeMap).doesNotContainKey("generate_video");
            assertThat(nodeMap).doesNotContainKey("video_preset");
        }

        @Test
        @DisplayName("Agent uses camelCase → video fields extracted; string duration is coerced")
        void agentCamelCaseExtracts() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("generateVideo", true);
            params.put("videoPreset", "horizontal");
            params.put("videoMaxDurationSeconds", "60");

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generateVideo()).isTrue();
            assertThat(config.videoPreset()).isEqualTo("horizontal");
            assertThat(config.videoMaxDurationSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("videoPreset is normalised to a supported preset (case-insensitive, lowercased); unknown → null (falls back to vertical at render)")
        void videoPresetNormalised() {
            assertThat(fromPreset("VERTICAL").videoPreset()).isEqualTo("vertical");
            assertThat(fromPreset("Horizontal").videoPreset()).isEqualTo("horizontal");
            assertThat(fromPreset("square").videoPreset()).isEqualTo("square");
            assertThat(fromPreset("cinema").videoPreset()).isNull();
            assertThat(fromPreset("  ").videoPreset()).isNull();
        }

        private InterfaceNodeConfig fromPreset(String preset) {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("video_preset", preset);
            return InterfaceNodeConfig.fromParams(params);
        }

        @Test
        @DisplayName("videoMaxDurationSeconds is clamped to 5-120; non-positive/junk → null (falls back to 30s default)")
        void videoDurationClamped() {
            assertThat(fromDuration(45).videoMaxDurationSeconds()).isEqualTo(45);
            assertThat(fromDuration(1).videoMaxDurationSeconds()).isEqualTo(5);
            assertThat(fromDuration(999).videoMaxDurationSeconds()).isEqualTo(120);
            assertThat(fromDuration(0).videoMaxDurationSeconds()).isNull();
            assertThat(fromDuration(-3).videoMaxDurationSeconds()).isNull();

            Map<String, Object> junk = new HashMap<>();
            junk.put("interface_id", "uuid-1");
            junk.put("video_max_duration_seconds", "not-a-number");
            assertThat(InterfaceNodeConfig.fromParams(junk).videoMaxDurationSeconds()).isNull();
        }

        private InterfaceNodeConfig fromDuration(int seconds) {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("video_max_duration_seconds", seconds);
            return InterfaceNodeConfig.fromParams(params);
        }

        @Test
        @DisplayName("Video fields default to null (= omitted) when not supplied - no nodeMap pollution")
        void videoFieldsDefaultNullWhenMissing() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");

            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

            assertThat(config.generateVideo()).isNull();
            assertThat(config.videoPreset()).isNull();
            assertThat(config.videoMaxDurationSeconds()).isNull();

            Map<String, Object> nodeMap = config.toNodeMap("X", Map.of("x", 0, "y", 0));
            assertThat(nodeMap).doesNotContainKey("generateVideo");
            assertThat(nodeMap).doesNotContainKey("videoPreset");
            assertThat(nodeMap).doesNotContainKey("videoMaxDurationSeconds");
        }

        @Test
        @DisplayName("toSavedParams + toExtras emit video fields as snake_case (response visibility for the agent)")
        void savedParamsAndExtrasEmitSnakeCase() {
            InterfaceNodeConfig config = new InterfaceNodeConfig(
                "uuid-1", null, null, false, false, false, null, null, null,
                true, "vertical", 30);

            Map<String, Object> saved = config.toSavedParams();
            assertThat(saved).containsEntry("generate_video", true);
            assertThat(saved).containsEntry("video_preset", "vertical");
            assertThat(saved).containsEntry("video_max_duration_seconds", 30);

            Map<String, Object> extras = config.toExtras();
            assertThat(extras).containsEntry("generate_video", true);
            assertThat(extras).containsEntry("video_preset", "vertical");
            assertThat(extras).containsEntry("video_max_duration_seconds", 30);
        }

        @Test
        @DisplayName("videoMode is normalised (smooth|live, lowercase; unknown → null) and videoFps clamped to 10-60")
        void videoModeAndFpsNormalised() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "uuid-1");
            params.put("video_mode", "LIVE");
            params.put("video_fps", 120);
            InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);
            assertThat(config.videoMode()).isEqualTo("live");
            assertThat(config.videoFps()).isEqualTo(60);

            Map<String, Object> unknown = new HashMap<>();
            unknown.put("interface_id", "uuid-1");
            unknown.put("videoMode", "turbo");
            unknown.put("videoFps", 5);
            InterfaceNodeConfig cfg2 = InterfaceNodeConfig.fromParams(unknown);
            assertThat(cfg2.videoMode()).isNull();
            assertThat(cfg2.videoFps()).isEqualTo(10);

            Map<String, Object> nodeMap = config.toNodeMap("X", Map.of("x", 0, "y", 0));
            assertThat(nodeMap).containsEntry("videoMode", "live");
            assertThat(nodeMap).containsEntry("videoFps", 60);
            Map<String, Object> saved = config.toSavedParams();
            assertThat(saved).containsEntry("video_mode", "live");
            assertThat(saved).containsEntry("video_fps", 60);
        }

        @Test
        @DisplayName("Back-compat 12-arg constructor leaves video mode/fps null (pre-smooth callers unaffected)")
        void twelveArgConstructorDefaultsModeFpsNull() {
            InterfaceNodeConfig config = new InterfaceNodeConfig(
                "uuid-1", null, null, false, false, false, null, null, null,
                true, "vertical", 30);
            assertThat(config.videoMode()).isNull();
            assertThat(config.videoFps()).isNull();
            assertThat(config.generateVideo()).isTrue();
        }

        @Test
        @DisplayName("Back-compat 9-arg constructor leaves video fields null (existing callers unaffected)")
        void nineArgConstructorDefaultsVideoNull() {
            InterfaceNodeConfig config = new InterfaceNodeConfig(
                "uuid-1", null, null, false, false, false, true, "A4", false);

            assertThat(config.generateVideo()).isNull();
            assertThat(config.videoPreset()).isNull();
            assertThat(config.videoMaxDurationSeconds()).isNull();
            assertThat(config.generatePdf()).isTrue();
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
