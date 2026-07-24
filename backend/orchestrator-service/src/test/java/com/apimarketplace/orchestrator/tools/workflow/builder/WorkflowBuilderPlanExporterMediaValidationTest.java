package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
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
 * {@code workflow(action='set_plan')} validation of the {@code media} core type:
 * operation is required and must be one of the four, and each operation's required
 * file params (probe/extract_audio: input; mux_audio: video+audio; mix: non-empty
 * tracks) are enforced with dedicated errors. Without the {@code case "media"} these
 * plans would all be rejected as "Unknown type 'media'" even though add_node creates
 * the node and the engine runs it (same regression class as public_link).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderPlanExporter - set_plan validation of the media core type")
class WorkflowBuilderPlanExporterMediaValidationTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;
    @Mock
    private ToolSchemaFetcher toolSchemaFetcher;

    private WorkflowBuilderPlanExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher);
    }

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Soundtrack a clip")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Map<String, Object> manualTrigger() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("label", "start");
        t.put("type", "manual");
        return t;
    }

    private Map<String, Object> mediaCore(Map<String, Object> params) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("id", "m1");
        core.put("type", "media");
        core.put("label", "Add Music");
        if (params != null) {
            core.put("params", new LinkedHashMap<>(params));
        }
        return core;
    }

    private ToolExecutionResult setPlan(WorkflowBuilderSession session, List<Map<String, Object>> cores) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(manualTrigger())));
        plan.put("cores", new ArrayList<>(cores));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("plan", plan);
        return exporter.executeSetPlan(session, parameters);
    }

    @Test
    @DisplayName("media mux_audio core with video+audio passes validation and lands in the session")
    void muxWithVideoAndAudioImportsSuccessfully() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of(
                        "operation", "mux_audio",
                        "video", "{{interface:card.output.video}}",
                        "audio", "{{core:dl.output.file}}",
                        "volume", 80))));

        assertThat(result.success())
                .as("a well-formed media core must be accepted, got: " + result.error())
                .isTrue();
        assertThat(session.getCores()).hasSize(1);
        Map<String, Object> imported = session.getCores().get(0);
        assertThat(imported.get("type")).isEqualTo("media");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) imported.get("params");
        assertThat(params).containsEntry("operation", "mux_audio");
    }

    @Test
    @DisplayName("media core WITHOUT a params map is rejected with the dedicated operation error, not Unknown type")
    void mediaWithoutParamsRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, List.of(mediaCore(null)));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("'params' with an 'operation' is required for media")
                .doesNotContain("Unknown type 'media'");
    }

    @Test
    @DisplayName("media core without params.operation is rejected listing the seven operations")
    void mediaWithoutOperationRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("video", "{{x}}"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params.operation' is required for media")
                .contains("probe, mux_audio, mix, extract_audio");
    }

    @Test
    @DisplayName("unknown media operation is rejected naming the bad value")
    void unknownOperationRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "transcode"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("unknown media operation 'transcode'");
    }

    @Test
    @DisplayName("probe without params.input is rejected with the dedicated input error")
    void probeWithoutInputRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "probe"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params.input' is required for media probe");
    }

    @Test
    @DisplayName("mux_audio missing BOTH video and audio yields both dedicated errors")
    void muxMissingBothRejectedWithBothErrors() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "mux_audio"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("'params.video' is required for media mux_audio")
                .contains("'params.audio' is required for media mux_audio");
    }

    @Test
    @DisplayName("mix with an empty tracks array is rejected with the dedicated tracks error")
    void mixEmptyTracksRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "mix", "tracks", List.of()))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params.tracks' is required for media mix");
    }

    @Test
    @DisplayName("mix with one sourced track passes validation")
    void mixWithTrackImportsSuccessfully() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of(
                        "operation", "mix",
                        "tracks", List.of(Map.of("source", "{{core:voice.output.file}}"))))));

        assertThat(result.success())
                .as("a mix core with tracks must be accepted, got: " + result.error())
                .isTrue();
    }

    @Test
    @DisplayName("mix with NINE tracks is rejected naming the 8-track cap and the actual count")
    void mixWithNineTracksRejected() {
        WorkflowBuilderSession session = newSession();
        List<Map<String, Object>> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(Map.of("source", "{{core:voice.output.file}}"));
        }

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "mix", "tracks", nine))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params.tracks' accepts at most 8 tracks (got 9)");
    }

    @Test
    @DisplayName("mix track WITHOUT a source is rejected with the indexed per-track error")
    void mixTrackMissingSourceRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of(
                        "operation", "mix",
                        "tracks", List.of(Map.of("volume", 60))))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params.tracks[0].source' is required");
    }

    @Test
    @DisplayName("mix track with a BLANK source is rejected at ITS index, not the whole array")
    void mixTrackBlankSourceRejectedAtItsIndex() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of(
                        "operation", "mix",
                        "tracks", List.of(
                                Map.of("source", "{{core:voice.output.file}}"),
                                Map.of("source", "  "))))));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("'params.tracks[1].source' is required")
                .doesNotContain("'params.tracks[0].source'");
    }

    @Test
    @DisplayName("mix with exactly EIGHT sourced tracks passes validation")
    void mixWithEightTracksAccepted() {
        WorkflowBuilderSession session = newSession();
        List<Map<String, Object>> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(Map.of("source", "{{core:voice" + i + ".output.file}}"));
        }

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "mix", "tracks", eight))));

        assertThat(result.success())
                .as("8 tracks is the documented maximum and must be accepted, got: " + result.error())
                .isTrue();
    }

    // ---- v2 operations: concat / frame / overlay ---------------------------

    @Test
    @DisplayName("concat core with two sourced clips passes validation")
    void concatWithClipsImportsSuccessfully() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of(
                        "operation", "concat",
                        "inputs", List.of(
                                Map.of("source", "{{core:clip_a.output.file}}"),
                                Map.of("source", "{{core:clip_b.output.file}}", "trim_end_seconds", 12)),
                        "transition", "crossfade"))));

        assertThat(result.success())
                .as("a well-formed concat core must be accepted, got: " + result.error())
                .isTrue();
    }

    @Test
    @DisplayName("concat without params.inputs is rejected with the dedicated inputs error")
    void concatWithoutInputsRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "concat"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params.inputs' is required for media concat");
    }

    @Test
    @DisplayName("concat with NINE clips is rejected naming the 8-clip cap and the actual count")
    void concatWithNineClipsRejected() {
        WorkflowBuilderSession session = newSession();
        List<Map<String, Object>> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(Map.of("source", "{{core:clip.output.file}}"));
        }

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "concat", "inputs", nine))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params.inputs' accepts at most 8 clips (got 9)");
    }

    @Test
    @DisplayName("concat clip WITHOUT a source is rejected with the indexed per-clip error")
    void concatClipMissingSourceRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of(
                        "operation", "concat",
                        "inputs", List.of(
                                Map.of("source", "{{core:clip_a.output.file}}"),
                                Map.of("speed", 1.5))))));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("'params.inputs[1].source' is required")
                .doesNotContain("'params.inputs[0].source'");
    }

    @Test
    @DisplayName("concat clip with a literal FileRef OBJECT source passes validation (Files picker contract)")
    void concatLiteralFileRefSourceAccepted() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of(
                        "operation", "concat",
                        "inputs", List.of(Map.of("source",
                                Map.of("_type", "file", "path", "1/files/clip.mp4", "name", "clip.mp4")))))));

        assertThat(result.success())
                .as("a literal FileRef source must be accepted, got: " + result.error())
                .isTrue();
    }

    @Test
    @DisplayName("frame without params.input is rejected with the dedicated input error")
    void frameWithoutInputRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "frame"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params.input' is required for media frame");
    }

    @Test
    @DisplayName("frame with an input passes validation")
    void frameWithInputImportsSuccessfully() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of(
                        "operation", "frame",
                        "input", "{{core:reel.output.file}}",
                        "at_seconds", 3))));

        assertThat(result.success())
                .as("a well-formed frame core must be accepted, got: " + result.error())
                .isTrue();
    }

    @Test
    @DisplayName("overlay missing BOTH video and image yields both dedicated errors")
    void overlayMissingBothRejectedWithBothErrors() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "overlay"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("'params.video' is required for media overlay")
                .contains("'params.image' is required for media overlay");
    }

    @Test
    @DisplayName("overlay with video + image passes validation")
    void overlayWithVideoAndImageImportsSuccessfully() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of(
                        "operation", "overlay",
                        "video", "{{core:reel.output.file}}",
                        "image", "{{core:logo.output.file}}",
                        "position", "bottom_right"))));

        assertThat(result.success())
                .as("a well-formed overlay core must be accepted, got: " + result.error())
                .isTrue();
    }

    @Test
    @DisplayName("the unknown-operation error now lists all seven operations")
    void unknownOperationListsSevenOperations() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(mediaCore(Map.of("operation", "transcode"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("unknown media operation 'transcode'")
                .contains("concat")
                .contains("frame")
                .contains("overlay");
    }

    @Test
    @DisplayName("the default Unknown type message now lists media among the expected core types")
    void unknownTypeMessageListsMedia() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> bogus = new LinkedHashMap<>();
        bogus.put("id", "b1");
        bogus.put("type", "bogus");
        bogus.put("label", "Broken");

        ToolExecutionResult result = setPlan(session, List.of(bogus));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("Unknown type 'bogus'")
                .as("media must be advertised as a valid core type in the expected-types list")
                .contains("media");
    }
}
