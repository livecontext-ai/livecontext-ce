package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Verifies that filter/sort node creators accept the documented input aliases
 * (input, inputExpression, items, list) and store them under params.input.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UtilityNodeCreator - filter/sort input alias handling")
class UtilityNodeCreatorTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private WorkflowRepository workflowRepository;

    private UtilityNodeCreator creator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        creator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        session = WorkflowBuilderSession.builder()
            .sessionId("s")
            .tenantId("t")
            .workflowName("w")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        // Add a trigger so creators don't bail out
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("label", "Start");
        trig.put("id", "trigger:start");
        trig.put("type", "webhook");
        session.getTriggers().add(trig);

        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstCoreParams() {
        Map<String, Object> node = session.getCores().get(0);
        return (Map<String, Object>) node.get("params");
    }

    private Map<String, Object> baseFilterParams(String inputKey, String inputValue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "My Filter");
        p.put("connect_after", "Start");
        p.put("conditions", List.of(Map.of("field", "status", "operator", "equals", "value", "active")));
        p.put(inputKey, inputValue);
        return p;
    }

    @Test
    @DisplayName("filter: 'inputExpression' alias is stored under params.input")
    void filterInputExpressionAlias() {
        ToolExecutionResult r = creator.executeAddFilter(session, baseFilterParams("inputExpression", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("filter: 'items' alias is stored under params.input")
    void filterItemsAlias() {
        ToolExecutionResult r = creator.executeAddFilter(session, baseFilterParams("items", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("filter: 'list' alias is stored under params.input")
    void filterListAlias() {
        ToolExecutionResult r = creator.executeAddFilter(session, baseFilterParams("list", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("filter: canonical 'input' is stored under params.input")
    void filterCanonicalInput() {
        ToolExecutionResult r = creator.executeAddFilter(session, baseFilterParams("input", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("filter: no input → node has no params (caught by CoreValidator at validate time)")
    void filterWithoutInput() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "My Filter");
        p.put("connect_after", "Start");
        p.put("conditions", List.of(Map.of("field", "status", "operator", "equals", "value", "active")));
        // no input
        ToolExecutionResult r = creator.executeAddFilter(session, p);
        assertThat(r.success()).isTrue();
        // node has no 'params' key - CoreValidator.hasInputField will return false
        assertThat(session.getCores().get(0).containsKey("params")).isFalse();
    }

    private Map<String, Object> baseSortParams(String inputKey, String inputValue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "My Sort");
        p.put("connect_after", "Start");
        p.put("fields", List.of(Map.of("field", "name", "direction", "asc")));
        p.put(inputKey, inputValue);
        return p;
    }

    @Test
    @DisplayName("sort: 'list' alias is stored under params.input")
    void sortListAlias() {
        ToolExecutionResult r = creator.executeAddSort(session, baseSortParams("list", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("sort: 'inputExpression' alias is stored under params.input")
    void sortInputExpressionAlias() {
        ToolExecutionResult r = creator.executeAddSort(session, baseSortParams("inputExpression", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    // #X1: add_node(type='xml', preserveAttributes=true) used to silently drop the flag.
    // The creator now reads preserveAttributes (camelCase and snake_case) and forwards it
    // into the xmlConfig sub-object where XmlConfig.preserveAttributes() reads it.
    @SuppressWarnings("unchecked")
    private Map<String, Object> firstXmlConfig() {
        Map<String, Object> node = session.getCores().get(0);
        return (Map<String, Object>) node.get("xml");
    }

    private Map<String, Object> baseXmlParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "My Xml");
        p.put("connect_after", "Start");
        p.put("operation", "xmlToJson");
        p.put("value", "<root><order id='42'/></root>");
        return p;
    }

    @Test
    @DisplayName("xml: preserveAttributes=true (camelCase) is stored in xmlConfig")
    void xmlPreserveAttributesCamelCase() {
        Map<String, Object> p = baseXmlParams();
        p.put("preserveAttributes", true);
        ToolExecutionResult r = creator.executeAddXml(session, p);
        assertThat(r.success()).isTrue();
        assertThat(firstXmlConfig().get("preserveAttributes")).isEqualTo(true);
    }

    @Test
    @DisplayName("xml: preserve_attributes=true (snake_case) is stored in xmlConfig")
    void xmlPreserveAttributesSnakeCase() {
        Map<String, Object> p = baseXmlParams();
        p.put("preserve_attributes", true);
        ToolExecutionResult r = creator.executeAddXml(session, p);
        assertThat(r.success()).isTrue();
        assertThat(firstXmlConfig().get("preserveAttributes")).isEqualTo(true);
    }

    @Test
    @DisplayName("xml: preserveAttributes=false is preserved (not dropped)")
    void xmlPreserveAttributesFalse() {
        Map<String, Object> p = baseXmlParams();
        p.put("preserveAttributes", false);
        ToolExecutionResult r = creator.executeAddXml(session, p);
        assertThat(r.success()).isTrue();
        assertThat(firstXmlConfig().get("preserveAttributes")).isEqualTo(false);
    }

    @Test
    @DisplayName("xml: preserveAttributes absent → key omitted (record default of false applies)")
    void xmlPreserveAttributesAbsent() {
        ToolExecutionResult r = creator.executeAddXml(session, baseXmlParams());
        assertThat(r.success()).isTrue();
        assertThat(firstXmlConfig().containsKey("preserveAttributes")).isFalse();
    }

    // ---- public_link: add_node emits config under the generic 'params' key ----
    // Regression: the node's config MUST live in the nested 'params' map (where
    // CoreNodeBuilder.createPublicLinkNodes reads it), never at the node top level.

    private Map<String, Object> basePublicLinkParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Share Clip");
        p.put("connect_after", "Start");
        return p;
    }

    @Test
    @DisplayName("public_link: success emits a cores[] node of type public_link with file and ttl_minutes under 'params'")
    void publicLinkSuccessStoresConfigUnderParams() {
        Map<String, Object> p = basePublicLinkParams();
        p.put("file", "{{core:dl.output.file}}");
        p.put("ttl_minutes", 60);

        ToolExecutionResult r = creator.executeAddPublicLink(session, p);

        assertThat(r.success()).isTrue();
        Map<String, Object> node = session.getCores().get(0);
        assertThat(node.get("type")).isEqualTo("public_link");
        assertThat(firstCoreParams())
            .containsEntry("file", "{{core:dl.output.file}}")
            .containsEntry("ttl_minutes", 60);
        // No top-level orphans: the engine only reads the nested params map
        assertThat(node).doesNotContainKey("file").doesNotContainKey("ttl_minutes");
    }

    @Test
    @DisplayName("public_link: missing 'file' -> MISSING_PARAMETER failure mentioning 'file'")
    void publicLinkMissingFileIsMissingParameter() {
        ToolExecutionResult r = creator.executeAddPublicLink(session, basePublicLinkParams());

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(r.error()).contains("'file' is required");
    }

    @Test
    @DisplayName("public_link: 'file_ref' alias is stored under params.file")
    void publicLinkFileRefAlias() {
        Map<String, Object> p = basePublicLinkParams();
        p.put("file_ref", "{{core:dl.output.file}}");

        ToolExecutionResult r = creator.executeAddPublicLink(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("file")).isEqualTo("{{core:dl.output.file}}");
    }

    @Test
    @DisplayName("public_link: 'fileRef' camelCase alias is stored under params.file")
    void publicLinkFileRefCamelCaseAlias() {
        Map<String, Object> p = basePublicLinkParams();
        p.put("fileRef", "{{core:dl.output.file}}");

        ToolExecutionResult r = creator.executeAddPublicLink(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("file")).isEqualTo("{{core:dl.output.file}}");
    }

    // ---- media: add_node validation + config under the generic 'params' key ----
    // Regression guard: the node's config MUST live in the nested 'params' map
    // (where CoreNodeBuilder.createMediaNodes reads it), never at the node top level.

    private Map<String, Object> baseMediaParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Add Music");
        p.put("connect_after", "Start");
        return p;
    }

    @Test
    @DisplayName("media: mux_audio success stores operation/video/audio/options under 'params', type is media")
    void mediaMuxSuccessStoresConfigUnderParams() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "mux_audio");
        p.put("video", "{{interface:card.output.video}}");
        p.put("audio", "{{core:dl.output.file}}");
        p.put("volume", 80);
        p.put("fade_out_seconds", 2);

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isTrue();
        Map<String, Object> node = session.getCores().get(0);
        assertThat(node.get("type")).isEqualTo("media");
        assertThat(firstCoreParams())
            .containsEntry("operation", "mux_audio")
            .containsEntry("video", "{{interface:card.output.video}}")
            .containsEntry("audio", "{{core:dl.output.file}}")
            .containsEntry("volume", 80)
            .containsEntry("fade_out_seconds", 2);
        // No top-level orphans: the engine only reads the nested params map
        assertThat(node).doesNotContainKey("operation").doesNotContainKey("video").doesNotContainKey("volume");
    }

    @Test
    @DisplayName("media: missing 'operation' -> MISSING_PARAMETER failure listing the seven operations")
    void mediaMissingOperationListsOperations() {
        ToolExecutionResult r = creator.executeAddMedia(session, baseMediaParams());

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(r.error()).contains("probe").contains("mux_audio").contains("mix").contains("extract_audio");
    }

    @Test
    @DisplayName("media: unknown operation -> INVALID_ENUM_VALUE failure with the operations table")
    void mediaUnknownOperationFails() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "transcode");

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
        assertThat(r.error()).contains("transcode").contains("extract_audio");
    }

    @Test
    @DisplayName("media: probe without 'input' -> MISSING_PARAMETER naming input")
    void mediaProbeMissingInputFails() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "probe");

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(r.error()).contains("'input' is required");
    }

    @Test
    @DisplayName("media: mux_audio without 'audio' -> MISSING_PARAMETER naming the missing param")
    void mediaMuxMissingAudioFails() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "mux_audio");
        p.put("video", "{{interface:card.output.video}}");

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("'audio' is");
    }

    @Test
    @DisplayName("media: mix without tracks -> MISSING_PARAMETER explaining the 1-8 tracks array")
    void mediaMixMissingTracksFails() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "mix");

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("'tracks' is required");
    }

    @Test
    @DisplayName("media: mix with tracks stores the raw tracks array under params.tracks")
    void mediaMixStoresTracksUnderParams() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "mix");
        List<Map<String, Object>> tracks = List.of(
            Map.of("id", "voice", "source", "{{mcp:tts.output.file}}"),
            Map.of("id", "music", "source", "{{core:dl.output.file}}", "duck_under", "voice"));
        p.put("tracks", tracks);

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams()).containsEntry("operation", "mix").containsEntry("tracks", tracks);
    }

    @Test
    @DisplayName("media: top-level 'speed' is NOT copied into the node params (speed is a per-track mix option only)")
    void mediaTopLevelSpeedNotCopied() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "mux_audio");
        p.put("video", "{{interface:card.output.video}}");
        p.put("audio", "{{core:dl.output.file}}");
        p.put("volume", 80);
        p.put("speed", 1.5);

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams())
            .as("accepted keys must still be copied")
            .containsEntry("volume", 80);
        assertThat(firstCoreParams())
            .as("'speed' only exists inside mix tracks - a top-level copy would be dead config the engine ignores")
            .doesNotContainKey("speed");
    }

    // ---- media v2: concat / frame / overlay + operation aliases -----------

    @Test
    @DisplayName("media: concat without 'inputs' -> MISSING_PARAMETER explaining the 1-8 clips array")
    void mediaConcatMissingInputsFails() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "concat");

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(r.error()).contains("'inputs' is required");
    }

    @Test
    @DisplayName("media: concat with NINE clips -> INVALID_PARAMETER_VALUE naming the 8-clip cap")
    void mediaConcatNineClipsFails() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "concat");
        List<Map<String, Object>> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add(Map.of("source", "{{core:clip.output.file}}"));
        p.put("inputs", nine);

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(r.error()).contains("at most 8 clips (got 9)");
    }

    @Test
    @DisplayName("media: concat clip without a 'source' -> MISSING_PARAMETER naming the clip index")
    void mediaConcatClipMissingSourceFails() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "concat");
        p.put("inputs", List.of(
            Map.of("source", "{{core:clip_a.output.file}}"),
            Map.of("trim_end_seconds", 10)));

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("'inputs[1].source' is required");
    }

    @Test
    @DisplayName("media: concat success stores operation/inputs/transition options under 'params'")
    void mediaConcatSuccessStoresConfigUnderParams() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "concat");
        List<Map<String, Object>> clips = List.of(
            Map.of("source", "{{core:clip_a.output.file}}"),
            Map.of("source", "{{core:clip_b.output.file}}", "trim_end_seconds", 12));
        p.put("inputs", clips);
        p.put("transition", "crossfade");
        p.put("transition_seconds", 0.5);
        p.put("target_width", 1920);
        p.put("target_height", 1080);
        p.put("target_fps", 30);

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams())
            .containsEntry("operation", "concat")
            .containsEntry("inputs", clips)
            .containsEntry("transition", "crossfade")
            .containsEntry("transition_seconds", 0.5)
            .containsEntry("target_width", 1920)
            .containsEntry("target_height", 1080)
            .containsEntry("target_fps", 30);
    }

    @Test
    @DisplayName("media: concat clip with a literal FileRef OBJECT source is accepted")
    void mediaConcatLiteralFileRefSourceAccepted() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "concat");
        p.put("inputs", List.of(
            Map.of("source", Map.of("_type", "file", "path", "1/files/clip.mp4", "name", "clip.mp4"))));

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).as(r.error()).isTrue();
    }

    @Test
    @DisplayName("media: frame without 'input' -> MISSING_PARAMETER naming input")
    void mediaFrameMissingInputFails() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "frame");

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("'input' is required");
    }

    @Test
    @DisplayName("media: frame success stores input + at_seconds/image_format/width under 'params'")
    void mediaFrameSuccessStoresConfigUnderParams() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "frame");
        p.put("input", "{{core:reel.output.file}}");
        p.put("at_seconds", 3.5);
        p.put("image_format", "png");
        p.put("width", 1280);

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams())
            .containsEntry("operation", "frame")
            .containsEntry("input", "{{core:reel.output.file}}")
            .containsEntry("at_seconds", 3.5)
            .containsEntry("image_format", "png")
            .containsEntry("width", 1280);
    }

    @Test
    @DisplayName("media: overlay missing 'image' -> MISSING_PARAMETER naming the missing param")
    void mediaOverlayMissingImageFails() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "overlay");
        p.put("video", "{{core:reel.output.file}}");

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("'image' is");
    }

    @Test
    @DisplayName("media: overlay success stores video/image/position/margin/width%/opacity/window under 'params'")
    void mediaOverlaySuccessStoresConfigUnderParams() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "overlay");
        p.put("video", "{{core:reel.output.file}}");
        p.put("image", "{{core:logo.output.file}}");
        p.put("position", "bottom_right");
        p.put("margin_px", 24);
        p.put("width_percent", 12);
        p.put("opacity", 0.7);
        p.put("start_seconds", 2);
        p.put("end_seconds", 8);

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams())
            .containsEntry("operation", "overlay")
            .containsEntry("video", "{{core:reel.output.file}}")
            .containsEntry("image", "{{core:logo.output.file}}")
            .containsEntry("position", "bottom_right")
            .containsEntry("margin_px", 24)
            .containsEntry("width_percent", 12)
            .containsEntry("opacity", 0.7)
            .containsEntry("start_seconds", 2)
            .containsEntry("end_seconds", 8);
    }

    @Test
    @DisplayName("media: aliases stitch/join/join_videos resolve to the canonical 'concat' operation")
    void mediaConcatAliasesResolve() {
        for (String alias : List.of("stitch", "join", "join_videos")) {
            session.getCores().clear();
            Map<String, Object> p = baseMediaParams();
            p.put("operation", alias);
            p.put("inputs", List.of(Map.of("source", "{{core:clip.output.file}}")));

            ToolExecutionResult r = creator.executeAddMedia(session, p);

            assertThat(r.success()).as("alias '" + alias + "': " + r.error()).isTrue();
            assertThat(firstCoreParams().get("operation"))
                .as("alias '" + alias + "' must be stored as its canonical operation")
                .isEqualTo("concat");
        }
    }

    @Test
    @DisplayName("media: aliases thumbnail/cover resolve to 'frame' and watermark to 'overlay'")
    void mediaFrameAndOverlayAliasesResolve() {
        Map<String, Object> thumb = baseMediaParams();
        thumb.put("operation", "thumbnail");
        thumb.put("input", "{{core:reel.output.file}}");
        ToolExecutionResult r1 = creator.executeAddMedia(session, thumb);
        assertThat(r1.success()).as(r1.error()).isTrue();
        assertThat(firstCoreParams().get("operation")).isEqualTo("frame");

        session.getCores().clear();
        Map<String, Object> cover = baseMediaParams();
        cover.put("operation", "cover");
        cover.put("input", "{{core:reel.output.file}}");
        ToolExecutionResult r2 = creator.executeAddMedia(session, cover);
        assertThat(r2.success()).as(r2.error()).isTrue();
        assertThat(firstCoreParams().get("operation")).isEqualTo("frame");

        session.getCores().clear();
        Map<String, Object> watermark = baseMediaParams();
        watermark.put("operation", "watermark");
        watermark.put("video", "{{core:reel.output.file}}");
        watermark.put("image", "{{core:logo.output.file}}");
        ToolExecutionResult r3 = creator.executeAddMedia(session, watermark);
        assertThat(r3.success()).as(r3.error()).isTrue();
        assertThat(firstCoreParams().get("operation")).isEqualTo("overlay");
    }

    @Test
    @DisplayName("media: an aliased operation still enforces its canonical required params (watermark without image fails)")
    void mediaAliasStillValidatesRequiredParams() {
        Map<String, Object> p = baseMediaParams();
        p.put("operation", "watermark");
        p.put("video", "{{core:reel.output.file}}");

        ToolExecutionResult r = creator.executeAddMedia(session, p);

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(r.error()).contains("'image' is");
    }

    // ---- wait: duration validation (D5 audit split MISSING vs INVALID) -----

    @Test
    @DisplayName("wait: missing duration → MISSING_PARAMETER")
    void waitMissingDurationIsMissingParameter() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Wait5s");
        p.put("connect_after", "Start");
        ToolExecutionResult r = creator.executeAddWait(session, p);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
    }

    @Test
    @DisplayName("wait: negative duration → INVALID_PARAMETER_VALUE (not MISSING)")
    void waitNegativeDurationIsInvalidParameterValue() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Wait5s");
        p.put("connect_after", "Start");
        p.put("duration", -1000);
        ToolExecutionResult r = creator.executeAddWait(session, p);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
    }
}
