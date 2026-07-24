package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.media.MediaRenderService;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.FileResult;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.MediaInput;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.MediaRenderException;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.ProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MediaNode}: per-operation validation (required params, numeric
 * bounds, duck_under referential integrity), the exact spec (options + inputs) handed to
 * {@link MediaRenderService}, the per-operation output shapes (probe FLAT fields vs
 * file + duration_seconds), and the explicit renderer-absent failure.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MediaNode")
class MediaNodeTest {

    private static final String VIDEO_KEY = "tenant-1/wf/run/node/clip.mp4";
    private static final String AUDIO_KEY = "tenant-1/wf/run/node/music.mp3";

    @Mock private WorkflowPlan mockPlan;
    @Mock private V2TemplateAdapter templateAdapter;
    @Mock private MediaRenderService mediaRenderService;

    @Captor private ArgumentCaptor<Map<String, Object>> optionsCaptor;
    @Captor private ArgumentCaptor<List<MediaInput>> inputsCaptor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
        when(mediaRenderService.isEnabled()).thenReturn(true);
        // Identity resolution: params carry literal values (incl. raw FileRef maps).
        when(templateAdapter.resolveTemplates(anyMap(), any()))
            .thenAnswer(inv -> new LinkedHashMap<>((Map<String, Object>) inv.getArgument(0)));
    }

    private MediaNode node(Map<String, Object> params) {
        MediaNode node = new MediaNode("core:media_test", params);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.getMediaRenderService()).thenReturn(mediaRenderService);
        when(registry.getTemplateAdapter()).thenReturn(templateAdapter);
        node.acceptServices(registry);
        return node;
    }

    private static Map<String, Object> fileRef(String key) {
        Map<String, Object> ref = new HashMap<>();
        ref.put("_type", "file");
        ref.put("path", key);
        ref.put("name", key.substring(key.lastIndexOf('/') + 1));
        ref.put("mimeType", key.endsWith(".mp4") ? "video/mp4" : "audio/mpeg");
        return ref;
    }

    private static FileResult stubFileResult() {
        return new FileResult(FileRef.of("tenant-1/wf/run/core:media_test/out.mp4", "out.mp4", "video/mp4", 42), 20.5);
    }

    // ==================== Renderer availability ====================

    @Nested
    @DisplayName("renderer availability")
    class RendererAvailability {

        @Test
        @DisplayName("renderer component absent -> node FAILS with the agent-actionable enablement message")
        void rendererAbsentFailsExplicitly() {
            when(mediaRenderService.isEnabled()).thenReturn(false);
            NodeExecutionResult result = node(Map.of("operation", "probe")).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("renderer component"),
                "failure must name the renderer component, got: " + message);
            assertTrue(message.contains("administrator"),
                "failure must say who can enable it, got: " + message);
        }

        @Test
        @DisplayName("no MediaRenderService wired at all -> same explicit failure, never an NPE")
        void nullServiceFailsExplicitly() {
            MediaNode bare = new MediaNode("core:media_test", Map.of("operation", "probe"));
            NodeExecutionResult result = bare.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("renderer component"));
        }
    }

    // ==================== Operation validation ====================

    @Nested
    @DisplayName("operation validation")
    class OperationValidation {

        @Test
        @DisplayName("missing operation -> failure listing the four accepted values")
        void missingOperationFails() {
            NodeExecutionResult result = node(Map.of()).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("probe, mux_audio, mix, extract_audio"));
        }

        @Test
        @DisplayName("unknown operation -> failure naming the bad value and the accepted ones")
        void unknownOperationFails() {
            NodeExecutionResult result = node(Map.of("operation", "transcode")).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("transcode"));
            assertTrue(message.contains("probe, mux_audio, mix, extract_audio"));
        }
    }

    // ==================== probe ====================

    @Nested
    @DisplayName("probe")
    class Probe {

        @Test
        @DisplayName("missing input -> failure mentioning input and the whole-FileRef mapping")
        void missingInputFails() {
            NodeExecutionResult result = node(Map.of("operation", "probe")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("input is required"));
        }

        @Test
        @DisplayName("input resolving to a plain string (not a FileRef) -> failure with the WHOLE-FileRef hint")
        void plainStringInputFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "probe", "input", "https://example.com/x.mp4")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("WHOLE FileRef"));
        }

        @Test
        @DisplayName("happy path -> spec has role 'input'/empty options; probe JSON fields land FLAT in the output")
        void happyPathEmitsFlatFields() {
            Map<String, Object> probeFields = new LinkedHashMap<>();
            probeFields.put("duration_seconds", 12.5);
            probeFields.put("size_bytes", 1024);
            probeFields.put("format_name", "mp3");
            probeFields.put("bit_rate", 192000);
            probeFields.put("has_video", false);
            probeFields.put("has_audio", true);
            probeFields.put("video", null);
            probeFields.put("audio", Map.of("codec", "mp3", "sample_rate", 44100, "channels", 2));
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(new ProbeResult(probeFields));

            NodeExecutionResult result = node(Map.of(
                "operation", "probe", "input", fileRef(AUDIO_KEY))).execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            assertEquals(12.5, result.output().get("duration_seconds"));
            assertEquals(1024, result.output().get("size_bytes"));
            assertEquals("mp3", result.output().get("format_name"));
            assertEquals(true, result.output().get("has_audio"));
            assertEquals(false, result.output().get("has_video"));
            assertNull(result.output().get("file"), "probe emits no file");

            verify(mediaRenderService).render(eq("tenant-1"), any(), eq("run-1"), eq("core:media_test"),
                anyInt(), anyInt(), any(), eq("probe"), optionsCaptor.capture(), inputsCaptor.capture());
            assertTrue(optionsCaptor.getValue().isEmpty(), "probe sends no options");
            assertEquals(1, inputsCaptor.getValue().size());
            assertEquals("input0", inputsCaptor.getValue().get(0).name());
            assertEquals("input", inputsCaptor.getValue().get(0).role());
        }
    }

    // ==================== mux_audio ====================

    @Nested
    @DisplayName("mux_audio")
    class MuxAudio {

        @Test
        @DisplayName("missing video AND audio -> failure listing both required params")
        void missingBothFails() {
            NodeExecutionResult result = node(Map.of("operation", "mux_audio")).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("video is required"), "must flag video, got: " + message);
            assertTrue(message.contains("audio is required"), "must flag audio, got: " + message);
        }

        @Test
        @DisplayName("video NEVER mapped (no key in params) -> 'is required' with the whole-FileRef mapping hint")
        void unmappedVideoReportsIsRequired() {
            NodeExecutionResult result = node(Map.of(
                "operation", "mux_audio", "audio", fileRef(AUDIO_KEY))).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("video is required"), "got: " + message);
            assertTrue(message.contains("map the WHOLE FileRef"), "got: " + message);
            assertFalse(message.contains("resolved to nothing"),
                "a never-mapped param must NOT be reported as an upstream resolution problem, got: " + message);
        }

        @Test
        @DisplayName("video MAPPED as a template that resolves to null -> 'resolved to nothing' error carrying the raw template")
        void mappedVideoResolvingNullReportsUpstreamProblem() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("operation", "mux_audio");
            params.put("video", "{{core:x.output.file}}");
            params.put("audio", fileRef(AUDIO_KEY));
            when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
                Map<String, Object> resolved = new LinkedHashMap<>((Map<String, Object>) inv.getArgument(0));
                resolved.put("video", null);
                return resolved;
            });

            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("{{core:x.output.file}}"),
                "the failure must show the raw template the agent configured, got: " + message);
            assertTrue(message.contains("resolved to nothing - the upstream node"), "got: " + message);
            assertFalse(message.contains("video is required"),
                "a mapped param must NOT be reported as missing, got: " + message);
        }

        @Test
        @DisplayName("volume above 400 -> failure naming the 0-400 percent bound")
        void volumeOutOfBoundsFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "mux_audio", "video", fileRef(VIDEO_KEY), "audio", fileRef(AUDIO_KEY),
                "volume", 500)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("between 0 and 400"));
        }

        @Test
        @DisplayName("negative offset_seconds -> failure naming the >= 0 bound")
        void negativeOffsetFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "mux_audio", "video", fileRef(VIDEO_KEY), "audio", fileRef(AUDIO_KEY),
                "offset_seconds", -1)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains(">= 0"));
        }

        @Test
        @DisplayName("normalize number outside [-70,-5] -> failure naming the LUFS range")
        void normalizeOutOfRangeFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "mux_audio", "video", fileRef(VIDEO_KEY), "audio", fileRef(AUDIO_KEY),
                "normalize", -80)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("-70"));
        }

        @Test
        @DisplayName("invalid audio_fit -> failure listing pad, shortest, loop")
        void invalidAudioFitFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "mux_audio", "video", fileRef(VIDEO_KEY), "audio", fileRef(AUDIO_KEY),
                "audio_fit", "stretch")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("pad, shortest, loop"));
        }

        @Test
        @DisplayName("loop:true combined with a trim on the same audio -> explicit failure (renderer contract)")
        void loopWithTrimFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "mux_audio", "video", fileRef(VIDEO_KEY), "audio", fileRef(AUDIO_KEY),
                "loop", true, "trim_start_seconds", 3)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("loop cannot be combined with trim"));
        }

        @Test
        @DisplayName("happy path defaults -> spec options carry the contract defaults, inputs are input0=video input1=audio")
        void happyPathSendsContractDefaults() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(Map.of(
                "operation", "mux_audio", "video", fileRef(VIDEO_KEY), "audio", fileRef(AUDIO_KEY)))
                .execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            assertNotNull(result.output().get("file"));
            assertEquals(20.5, result.output().get("duration_seconds"));

            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("mux_audio"), optionsCaptor.capture(), inputsCaptor.capture());
            Map<String, Object> options = optionsCaptor.getValue();
            assertEquals(100d, options.get("volume"));
            assertEquals(0d, options.get("offset_seconds"));
            assertEquals(false, options.get("loop"));
            assertEquals(0d, options.get("fade_in_seconds"));
            assertEquals(1.0d, options.get("fade_out_seconds"));
            assertEquals(false, options.get("keep_original_audio"));
            assertFalse(options.containsKey("original_volume"), "original_volume only rides keep_original_audio=true");
            assertEquals("pad", options.get("audio_fit"));
            assertEquals(Boolean.TRUE, options.get("normalize"));
            assertEquals("192k", options.get("audio_bitrate"));

            List<MediaInput> inputs = inputsCaptor.getValue();
            assertEquals(2, inputs.size());
            assertEquals("input0", inputs.get(0).name());
            assertEquals("video", inputs.get(0).role());
            assertEquals(VIDEO_KEY, inputs.get(0).fileRef().get("path"));
            assertEquals("input1", inputs.get(1).name());
            assertEquals("audio", inputs.get(1).role());
            assertEquals(AUDIO_KEY, inputs.get(1).fileRef().get("path"));
        }

        @Test
        @DisplayName("keep_original_audio=true -> original_volume rides along (default 100)")
        void keepOriginalIncludesOriginalVolume() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(Map.of(
                "operation", "mux_audio", "video", fileRef(VIDEO_KEY), "audio", fileRef(AUDIO_KEY),
                "keep_original_audio", true, "original_volume", 40)).execute(context);

            assertTrue(result.isSuccess());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), optionsCaptor.capture(), anyList());
            assertEquals(true, optionsCaptor.getValue().get("keep_original_audio"));
            assertEquals(40d, optionsCaptor.getValue().get("original_volume"));
        }

        @Test
        @DisplayName("normalize as an in-range LUFS number is forwarded as a number")
        void normalizeNumberForwarded() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(Map.of(
                "operation", "mux_audio", "video", fileRef(VIDEO_KEY), "audio", fileRef(AUDIO_KEY),
                "normalize", -14)).execute(context);

            assertTrue(result.isSuccess());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), optionsCaptor.capture(), anyList());
            assertEquals(-14d, optionsCaptor.getValue().get("normalize"));
        }
    }

    // ==================== mix ====================

    @Nested
    @DisplayName("mix")
    class Mix {

        private Map<String, Object> mixParams(List<Map<String, Object>> tracks) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("operation", "mix");
            params.put("tracks", tracks);
            return params;
        }

        @Test
        @DisplayName("missing tracks -> failure explaining the 1-8 array with source")
        void missingTracksFails() {
            NodeExecutionResult result = node(Map.of("operation", "mix")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("tracks is required"));
        }

        @Test
        @DisplayName("more than 8 tracks -> failure naming the cap")
        void tooManyTracksFails() {
            List<Map<String, Object>> tracks = new java.util.ArrayList<>();
            for (int i = 0; i < 9; i++) {
                tracks.add(new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY))));
            }
            NodeExecutionResult result = node(mixParams(tracks)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("at most 8"));
        }

        @Test
        @DisplayName("track without source -> failure naming tracks[i].source")
        void trackMissingSourceFails() {
            NodeExecutionResult result = node(mixParams(
                List.of(new LinkedHashMap<>(Map.of("volume", 80))))).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("tracks[0].source is required"));
        }

        @Test
        @DisplayName("duck_under referencing the track's OWN id -> failure (self-reference)")
        void duckUnderSelfReferenceFails() {
            NodeExecutionResult result = node(mixParams(List.of(
                new LinkedHashMap<>(Map.of("id", "music", "source", fileRef(AUDIO_KEY), "duck_under", "music")))))
                .execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("not its own"));
        }

        @Test
        @DisplayName("duck_under referencing an UNKNOWN track id -> failure listing the known ids")
        void duckUnderUnknownIdFails() {
            NodeExecutionResult result = node(mixParams(List.of(
                new LinkedHashMap<>(Map.of("id", "music", "source", fileRef(AUDIO_KEY), "duck_under", "voice")))))
                .execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("unknown track id 'voice'"), "got: " + message);
            assertTrue(message.contains("music"), "must list known ids, got: " + message);
        }

        @Test
        @DisplayName("track speed outside 0.5-2.0 -> failure naming the bound")
        void speedOutOfBoundsFails() {
            NodeExecutionResult result = node(mixParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY), "speed", 3.0)))))
                .execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("between 0.5 and 2.0"));
        }

        @Test
        @DisplayName("track with loop:true AND a trim -> explicit per-track failure (renderer contract)")
        void trackLoopWithTrimFails() {
            NodeExecutionResult result = node(mixParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY), "loop", true, "trim_end_seconds", 10)))))
                .execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("loop cannot be combined with trim"));
        }

        @Test
        @DisplayName("audio-only mix where EVERY track loops -> explicit failure (no length anchor)")
        void audioOnlyMixAllLoopingFails() {
            NodeExecutionResult result = node(mixParams(List.of(
                new LinkedHashMap<>(Map.of("id", "a", "source", fileRef(AUDIO_KEY), "loop", true)),
                new LinkedHashMap<>(Map.of("id", "b", "source", fileRef(AUDIO_KEY), "loop", true)))))
                .execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("EVERY track"));
        }

        @Test
        @DisplayName("all-looping tracks WITH a video are fine (the video anchors the length)")
        void allLoopingTracksWithVideoAllowed() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            Map<String, Object> params = mixParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY), "loop", true))));
            params.put("video", fileRef(VIDEO_KEY));
            NodeExecutionResult result = node(params).execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
        }

        @Test
        @DisplayName("keep_original_audio without a video -> explicit failure (no original track to keep)")
        void keepOriginalWithoutVideoFails() {
            Map<String, Object> params = mixParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY)))));
            params.put("keep_original_audio", true);
            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("keep_original_audio is only valid"));
        }

        @Test
        @DisplayName("explicit non-mp4 output_format with a video -> explicit failure (mp4 is forced)")
        void nonMp4WithVideoFails() {
            Map<String, Object> params = mixParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY)))));
            params.put("video", fileRef(VIDEO_KEY));
            params.put("output_format", "mp3");
            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("output_format must be mp4"));
        }

        @Test
        @DisplayName("ducked voice+music happy path -> tracks carry source_part refs, default ids, duck defaults 12/20/300")
        void duckedMixSendsExactTrackSpec() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(mixParams(List.of(
                new LinkedHashMap<>(Map.of("id", "voice", "source", fileRef(AUDIO_KEY))),
                new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY), "volume", 60, "duck_under", "voice")))))
                .execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("mix"), optionsCaptor.capture(), inputsCaptor.capture());

            Map<String, Object> options = optionsCaptor.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tracks = (List<Map<String, Object>>) options.get("tracks");
            assertEquals(2, tracks.size());
            assertEquals("voice", tracks.get(0).get("id"));
            assertEquals("input0", tracks.get(0).get("source_part"));
            assertEquals(1.0d, tracks.get(0).get("speed"));
            assertEquals("track_2", tracks.get(1).get("id"), "unnamed tracks default to track_N (1-based array order)");
            assertEquals("input1", tracks.get(1).get("source_part"));
            assertEquals(60d, tracks.get(1).get("volume"));
            assertEquals("voice", tracks.get(1).get("duck_under"));
            assertEquals(12d, tracks.get(1).get("duck_amount_db"));
            assertEquals(20d, tracks.get(1).get("duck_attack_ms"));
            assertEquals(300d, tracks.get(1).get("duck_release_ms"));
            assertEquals("mp3", options.get("output_format"), "audio-only mix defaults to mp3");
            assertFalse(options.containsKey("keep_original_audio"), "no video -> no original-audio option");

            List<MediaInput> inputs = inputsCaptor.getValue();
            assertEquals(2, inputs.size());
            assertEquals("audio", inputs.get(0).role());
            assertEquals(0, inputs.get(0).trackIndex());
            assertEquals(1, inputs.get(1).trackIndex());
        }

        @Test
        @DisplayName("BLANK literal video + valid track -> proceeds as an audio-only mix (no video input, mp3 default)")
        void blankLiteralVideoIsAudioOnlyMix() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            Map<String, Object> params = mixParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY)))));
            params.put("video", "   ");
            NodeExecutionResult result = node(params).execute(context);

            assertTrue(result.isSuccess(), "expected audio-only success, got: " + result.errorMessage());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("mix"), optionsCaptor.capture(), inputsCaptor.capture());
            List<MediaInput> inputs = inputsCaptor.getValue();
            assertEquals(1, inputs.size(), "no video input part may be sent for a blank literal video");
            assertEquals("audio", inputs.get(0).role());
            assertEquals("mp3", optionsCaptor.getValue().get("output_format"),
                "audio-only mix defaults its output_format to mp3");
        }

        @Test
        @DisplayName("video TEMPLATE resolving blank -> 'resolved to nothing' error, never a silent audio-only downgrade")
        void videoTemplateResolvingBlankFails() {
            Map<String, Object> params = mixParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY)))));
            params.put("video", "{{core:clip.output.file}}");
            when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
                Map<String, Object> resolved = new LinkedHashMap<>((Map<String, Object>) inv.getArgument(0));
                resolved.put("video", "");
                return resolved;
            });

            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess(), "a mapped video resolving blank must FAIL, not degrade to audio-only");
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("{{core:clip.output.file}}"), "got: " + message);
            assertTrue(message.contains("resolved to nothing"), "got: " + message);
            verify(mediaRenderService, never()).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList());
        }

        @Test
        @DisplayName("track source TEMPLATE resolving blank -> per-track 'resolved to nothing' error carrying the template")
        void trackSourceTemplateResolvingBlankFails() {
            Map<String, Object> params = mixParams(List.of(
                new LinkedHashMap<>(Map.of("source", "{{core:tts.output.file}}"))));
            when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
                Map<String, Object> resolved = new LinkedHashMap<>((Map<String, Object>) inv.getArgument(0));
                resolved.put("tracks", List.of(new LinkedHashMap<>(Map.of("source", ""))));
                return resolved;
            });

            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("tracks[0].source"), "got: " + message);
            assertTrue(message.contains("{{core:tts.output.file}}"),
                "the per-track failure must show the raw source template, got: " + message);
            assertTrue(message.contains("resolved to nothing"), "got: " + message);
        }

        @Test
        @DisplayName("mix with a video -> input0 is the video, output_format forced to mp4")
        void mixWithVideoForcesMp4() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            Map<String, Object> params = mixParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(AUDIO_KEY)))));
            params.put("video", fileRef(VIDEO_KEY));
            NodeExecutionResult result = node(params).execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("mix"), optionsCaptor.capture(), inputsCaptor.capture());
            assertEquals("mp4", optionsCaptor.getValue().get("output_format"));
            List<MediaInput> inputs = inputsCaptor.getValue();
            assertEquals("video", inputs.get(0).role());
            assertNull(inputs.get(0).trackIndex());
            assertEquals("input1", inputs.get(1).name());
        }
    }

    // ==================== extract_audio ====================

    @Nested
    @DisplayName("extract_audio")
    class ExtractAudio {

        @Test
        @DisplayName("missing input -> failure")
        void missingInputFails() {
            NodeExecutionResult result = node(Map.of("operation", "extract_audio")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("input is required"));
        }

        @Test
        @DisplayName("invalid output_format -> failure listing mp3, wav, aac")
        void invalidFormatFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "extract_audio", "input", fileRef(VIDEO_KEY), "output_format", "flac"))
                .execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("mp3, wav, aac"));
        }

        @Test
        @DisplayName("happy path defaults -> options carry mp3/192k, input role 'input'")
        void happyPathDefaults() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(Map.of(
                "operation", "extract_audio", "input", fileRef(VIDEO_KEY))).execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("extract_audio"), optionsCaptor.capture(), inputsCaptor.capture());
            assertEquals("mp3", optionsCaptor.getValue().get("output_format"));
            assertEquals("192k", optionsCaptor.getValue().get("audio_bitrate"));
            assertEquals("input", inputsCaptor.getValue().get(0).role());
        }
    }

    // ==================== concat ====================

    @Nested
    @DisplayName("concat")
    class Concat {

        private Map<String, Object> concatParams(List<Map<String, Object>> inputs) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("operation", "concat");
            params.put("inputs", inputs);
            return params;
        }

        private List<Map<String, Object>> twoClips() {
            return List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY))),
                new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY))));
        }

        @Test
        @DisplayName("missing inputs -> failure explaining the 1-8 clips array with source")
        void missingInputsFails() {
            NodeExecutionResult result = node(Map.of("operation", "concat")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("inputs is required"));
        }

        @Test
        @DisplayName("more than 8 inputs -> failure naming the cap")
        void tooManyInputsFails() {
            List<Map<String, Object>> nine = new java.util.ArrayList<>();
            for (int i = 0; i < 9; i++) {
                nine.add(new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY))));
            }
            NodeExecutionResult result = node(concatParams(nine)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("at most 8"));
        }

        @Test
        @DisplayName("clip without source -> failure naming inputs[i].source")
        void clipMissingSourceFails() {
            NodeExecutionResult result = node(concatParams(
                List.of(new LinkedHashMap<>(Map.of("speed", 1.5))))).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("inputs[0].source is required"));
        }

        @Test
        @DisplayName("clip source TEMPLATE resolving blank -> 'resolved to nothing' error naming the clip index and the raw template")
        void clipSourceTemplateResolvingBlankFails() {
            Map<String, Object> params = concatParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY))),
                new LinkedHashMap<>(Map.of("source", "{{core:clip_b.output.file}}"))));
            when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
                Map<String, Object> resolved = new LinkedHashMap<>((Map<String, Object>) inv.getArgument(0));
                resolved.put("inputs", List.of(
                    new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY))),
                    new LinkedHashMap<>(Map.of("source", ""))));
                return resolved;
            });

            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("inputs[1].source"), "must name the clip index, got: " + message);
            assertTrue(message.contains("{{core:clip_b.output.file}}"),
                "must show the raw template the agent configured, got: " + message);
            assertTrue(message.contains("resolved to nothing"), "got: " + message);
        }

        @Test
        @DisplayName("literal FileRef map as inputs[].source is accepted (Files picker contract)")
        void literalFileRefSourceAccepted() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(concatParams(
                List.of(new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY)))))).execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("concat"), anyMap(), inputsCaptor.capture());
            assertEquals(VIDEO_KEY, inputsCaptor.getValue().get(0).fileRef().get("path"));
        }

        @Test
        @DisplayName("a SINGLE input is allowed (the trim/speed-edit use case)")
        void singleInputAllowed() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(concatParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY),
                    "trim_start_seconds", 2, "trim_end_seconds", 10, "speed", 1.5)))))
                .execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
        }

        @Test
        @DisplayName("trim_end_seconds <= trim_start_seconds on a clip -> failure naming the clip")
        void trimEndNotAfterTrimStartFails() {
            NodeExecutionResult result = node(concatParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY),
                    "trim_start_seconds", 10, "trim_end_seconds", 10)))))
                .execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("inputs[0].trim_end_seconds must be greater than trim_start_seconds"),
                "got: " + message);
        }

        @Test
        @DisplayName("clip speed outside 0.5-2.0 -> failure naming the bound")
        void speedOutOfBoundsFails() {
            NodeExecutionResult result = node(concatParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY), "speed", 0.1)))))
                .execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("between 0.5 and 2.0"));
        }

        @Test
        @DisplayName("crossfade with a SINGLE input -> explicit failure (needs >= 2 inputs)")
        void crossfadeWithOneInputFails() {
            Map<String, Object> params = concatParams(List.of(
                new LinkedHashMap<>(Map.of("source", fileRef(VIDEO_KEY)))));
            params.put("transition", "crossfade");
            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("at least 2 inputs"));
        }

        @Test
        @DisplayName("unknown transition -> failure listing cut, crossfade")
        void unknownTransitionFails() {
            Map<String, Object> params = concatParams(twoClips());
            params.put("transition", "wipe");
            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("cut, crossfade"));
        }

        @Test
        @DisplayName("transition_seconds outside 0.1-5.0 -> failure naming the bound")
        void transitionSecondsOutOfBoundsFails() {
            Map<String, Object> params = concatParams(twoClips());
            params.put("transition", "crossfade");
            params.put("transition_seconds", 6);
            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("between 0.1 and 5.0"));
        }

        @Test
        @DisplayName("target_width without target_height -> explicit BOTH-or-NEITHER failure")
        void targetWidthAloneFails() {
            Map<String, Object> params = concatParams(twoClips());
            params.put("target_width", 1920);
            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("BOTH or NEITHER"));
        }

        @Test
        @DisplayName("target_height without target_width -> explicit BOTH-or-NEITHER failure")
        void targetHeightAloneFails() {
            Map<String, Object> params = concatParams(twoClips());
            params.put("target_height", 1080);
            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("BOTH or NEITHER"));
        }

        @Test
        @DisplayName("target_width outside 16-4096 -> failure naming the bound")
        void targetWidthOutOfBoundsFails() {
            Map<String, Object> params = concatParams(twoClips());
            params.put("target_width", 8000);
            params.put("target_height", 1080);
            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("between 16 and 4096"));
        }

        @Test
        @DisplayName("target_fps outside 1-60 -> failure naming the bound")
        void targetFpsOutOfBoundsFails() {
            Map<String, Object> params = concatParams(twoClips());
            params.put("target_fps", 120);
            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("between 1 and 60"));
        }

        @Test
        @DisplayName("happy path defaults -> options carry cut/0.5/fades 0/normalize FALSE, clips carry source_part input{i}")
        void happyPathSendsContractDefaults() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(concatParams(twoClips())).execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            assertNotNull(result.output().get("file"));
            assertEquals(20.5, result.output().get("duration_seconds"));
            assertFalse(result.output().containsKey("timestamp_seconds"),
                "timestamp_seconds is a frame-only output and must be absent for concat");

            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("concat"), optionsCaptor.capture(), inputsCaptor.capture());
            Map<String, Object> options = optionsCaptor.getValue();
            assertEquals("cut", options.get("transition"));
            assertEquals(0.5d, options.get("transition_seconds"));
            assertEquals(0d, options.get("fade_in_seconds"));
            assertEquals(0d, options.get("fade_out_seconds"),
                "concat's fade_out default is 0, NOT mux_audio's 1.0");
            assertEquals(Boolean.FALSE, options.get("normalize"),
                "concat defaults normalize to FALSE (it would force the re-encode path)");
            assertEquals("192k", options.get("audio_bitrate"));
            assertFalse(options.containsKey("target_width"), "no target dims unless provided");
            assertFalse(options.containsKey("target_fps"), "no target fps unless provided");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> clipOptions = (List<Map<String, Object>>) options.get("inputs");
            assertEquals(2, clipOptions.size());
            assertEquals("input0", clipOptions.get(0).get("source_part"));
            assertEquals("input1", clipOptions.get(1).get("source_part"));
            assertEquals(1.0d, clipOptions.get(0).get("speed"));

            List<MediaInput> inputs = inputsCaptor.getValue();
            assertEquals(2, inputs.size());
            assertEquals("input0", inputs.get(0).name());
            assertEquals("video", inputs.get(0).role());
            assertEquals(0, inputs.get(0).trackIndex());
            assertEquals("input1", inputs.get(1).name());
            assertEquals(1, inputs.get(1).trackIndex());
        }

        @Test
        @DisplayName("crossfade with 2 inputs + target canvas -> options carry the crossfade config and BOTH dims")
        void crossfadeWithCanvasSendsFullSpec() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            Map<String, Object> params = concatParams(twoClips());
            params.put("transition", "crossfade");
            params.put("transition_seconds", 1.0);
            params.put("target_width", 1920);
            params.put("target_height", 1080);
            params.put("target_fps", 30);
            params.put("normalize", true);
            NodeExecutionResult result = node(params).execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("concat"), optionsCaptor.capture(), anyList());
            Map<String, Object> options = optionsCaptor.getValue();
            assertEquals("crossfade", options.get("transition"));
            assertEquals(1.0d, options.get("transition_seconds"));
            assertEquals(1920d, options.get("target_width"));
            assertEquals(1080d, options.get("target_height"));
            assertEquals(30d, options.get("target_fps"));
            assertEquals(Boolean.TRUE, options.get("normalize"));
        }
    }

    // ==================== frame ====================

    @Nested
    @DisplayName("frame")
    class Frame {

        private FileResult stubFrameResult() {
            return new FileResult(
                FileRef.of("tenant-1/wf/run/core:media_test/out.jpg", "out.jpg", "image/jpeg", 7),
                null, 7.5);
        }

        @Test
        @DisplayName("missing input -> failure mentioning input")
        void missingInputFails() {
            NodeExecutionResult result = node(Map.of("operation", "frame")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("input is required"));
        }

        @Test
        @DisplayName("input MAPPED as a template that resolves to null -> 'resolved to nothing' error carrying the raw template")
        void mappedInputResolvingNullReportsUpstreamProblem() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("operation", "frame");
            params.put("input", "{{core:clip.output.file}}");
            when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
                Map<String, Object> resolved = new LinkedHashMap<>((Map<String, Object>) inv.getArgument(0));
                resolved.put("input", null);
                return resolved;
            });

            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("{{core:clip.output.file}}"), "got: " + message);
            assertTrue(message.contains("resolved to nothing"), "got: " + message);
        }

        @Test
        @DisplayName("negative at_seconds -> failure naming the >= 0 bound")
        void negativeAtSecondsFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "frame", "input", fileRef(VIDEO_KEY), "at_seconds", -3)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains(">= 0"));
        }

        @Test
        @DisplayName("invalid image_format -> failure listing jpeg, png")
        void invalidImageFormatFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "frame", "input", fileRef(VIDEO_KEY), "image_format", "webp")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("jpeg, png"));
        }

        @Test
        @DisplayName("width outside 16-4096 -> failure naming the bound")
        void widthOutOfBoundsFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "frame", "input", fileRef(VIDEO_KEY), "width", 8)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("between 16 and 4096"));
        }

        @Test
        @DisplayName("happy path without at_seconds -> no at_seconds in the spec (renderer defaults to the middle), jpeg default")
        void happyPathDefaultsToMiddle() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFrameResult());

            NodeExecutionResult result = node(Map.of(
                "operation", "frame", "input", fileRef(VIDEO_KEY))).execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("frame"), optionsCaptor.capture(), inputsCaptor.capture());
            Map<String, Object> options = optionsCaptor.getValue();
            assertFalse(options.containsKey("at_seconds"),
                "absent at_seconds must NOT be sent - the renderer applies the middle-of-video default");
            assertEquals("jpeg", options.get("image_format"));
            assertFalse(options.containsKey("width"), "no width unless provided");
            assertEquals(1, inputsCaptor.getValue().size());
            assertEquals("input0", inputsCaptor.getValue().get(0).name());
            assertEquals("input", inputsCaptor.getValue().get(0).role());
        }

        @Test
        @DisplayName("frame output carries file + timestamp_seconds with a NULL duration_seconds")
        void frameOutputCarriesTimestampSeconds() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFrameResult());

            NodeExecutionResult result = node(Map.of(
                "operation", "frame", "input", fileRef(VIDEO_KEY), "at_seconds", 7.5, "width", 640))
                .execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            assertNotNull(result.output().get("file"));
            assertNull(result.output().get("duration_seconds"), "a still image has no duration");
            assertEquals(7.5, result.output().get("timestamp_seconds"),
                "frame must expose the ACTUAL timestamp reported by the renderer");

            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("frame"), optionsCaptor.capture(), anyList());
            assertEquals(7.5d, optionsCaptor.getValue().get("at_seconds"));
            assertEquals(640d, optionsCaptor.getValue().get("width"));
        }
    }

    // ==================== overlay ====================

    @Nested
    @DisplayName("overlay")
    class Overlay {

        private static final String IMAGE_KEY = "tenant-1/wf/run/node/logo.png";

        private Map<String, Object> imageRef() {
            Map<String, Object> ref = new HashMap<>();
            ref.put("_type", "file");
            ref.put("path", IMAGE_KEY);
            ref.put("name", "logo.png");
            ref.put("mimeType", "image/png");
            return ref;
        }

        @Test
        @DisplayName("missing video AND image -> failure listing both required params")
        void missingBothFails() {
            NodeExecutionResult result = node(Map.of("operation", "overlay")).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("video is required"), "must flag video, got: " + message);
            assertTrue(message.contains("image is required"), "must flag image, got: " + message);
        }

        @Test
        @DisplayName("image MAPPED as a template that resolves to null -> 'resolved to nothing' error carrying the raw template")
        void mappedImageResolvingNullReportsUpstreamProblem() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("operation", "overlay");
            params.put("video", fileRef(VIDEO_KEY));
            params.put("image", "{{core:logo.output.file}}");
            when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
                Map<String, Object> resolved = new LinkedHashMap<>((Map<String, Object>) inv.getArgument(0));
                resolved.put("image", "");
                return resolved;
            });

            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("{{core:logo.output.file}}"), "got: " + message);
            assertTrue(message.contains("resolved to nothing"), "got: " + message);
            assertFalse(message.contains("image is required"),
                "a mapped param must NOT be reported as missing, got: " + message);
        }

        @Test
        @DisplayName("literal FileRef map as image is accepted (Files picker contract)")
        void literalFileRefImageAccepted() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(Map.of(
                "operation", "overlay", "video", fileRef(VIDEO_KEY), "image", imageRef()))
                .execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("overlay"), anyMap(), inputsCaptor.capture());
            assertEquals(IMAGE_KEY, inputsCaptor.getValue().get(1).fileRef().get("path"));
        }

        @Test
        @DisplayName("invalid position -> failure listing the five anchors")
        void invalidPositionFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "overlay", "video", fileRef(VIDEO_KEY), "image", imageRef(),
                "position", "middle_left")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("")
                .contains("top_left, top_right, bottom_left, bottom_right, center"));
        }

        @Test
        @DisplayName("width_percent outside 1-100 -> failure naming the bound")
        void widthPercentOutOfBoundsFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "overlay", "video", fileRef(VIDEO_KEY), "image", imageRef(),
                "width_percent", 0)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("between 1 and 100"));
        }

        @Test
        @DisplayName("opacity outside 0-1 -> failure naming the bound")
        void opacityOutOfBoundsFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "overlay", "video", fileRef(VIDEO_KEY), "image", imageRef(),
                "opacity", 1.5)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("between 0 and 1"));
        }

        @Test
        @DisplayName("negative margin_px -> failure naming the >= 0 bound")
        void negativeMarginFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "overlay", "video", fileRef(VIDEO_KEY), "image", imageRef(),
                "margin_px", -5)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains(">= 0"));
        }

        @Test
        @DisplayName("end_seconds <= start_seconds -> explicit failure")
        void endNotAfterStartFails() {
            NodeExecutionResult result = node(Map.of(
                "operation", "overlay", "video", fileRef(VIDEO_KEY), "image", imageRef(),
                "start_seconds", 5, "end_seconds", 5)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("")
                .contains("end_seconds must be greater than start_seconds"));
        }

        @Test
        @DisplayName("end_seconds without start_seconds must still be > 0 (start defaults to 0)")
        void endAloneMustBePositive() {
            NodeExecutionResult result = node(Map.of(
                "operation", "overlay", "video", fileRef(VIDEO_KEY), "image", imageRef(),
                "end_seconds", 0)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("")
                .contains("end_seconds must be greater than start_seconds"));
        }

        @Test
        @DisplayName("happy path defaults -> options carry bottom_right/24/15/1.0, inputs are input0=video input1=image")
        void happyPathSendsContractDefaults() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(Map.of(
                "operation", "overlay", "video", fileRef(VIDEO_KEY), "image", imageRef()))
                .execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            assertNotNull(result.output().get("file"));
            assertEquals(20.5, result.output().get("duration_seconds"));
            assertFalse(result.output().containsKey("timestamp_seconds"),
                "timestamp_seconds is a frame-only output and must be absent for overlay");

            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("overlay"), optionsCaptor.capture(), inputsCaptor.capture());
            Map<String, Object> options = optionsCaptor.getValue();
            assertEquals("bottom_right", options.get("position"));
            assertEquals(24d, options.get("margin_px"));
            assertEquals(15d, options.get("width_percent"));
            assertEquals(1.0d, options.get("opacity"));
            assertFalse(options.containsKey("start_seconds"), "no timing window unless provided");
            assertFalse(options.containsKey("end_seconds"), "no timing window unless provided");

            List<MediaInput> inputs = inputsCaptor.getValue();
            assertEquals(2, inputs.size());
            assertEquals("input0", inputs.get(0).name());
            assertEquals("video", inputs.get(0).role());
            assertEquals(VIDEO_KEY, inputs.get(0).fileRef().get("path"));
            assertEquals("input1", inputs.get(1).name());
            assertEquals("image", inputs.get(1).role());
            assertEquals(IMAGE_KEY, inputs.get(1).fileRef().get("path"));
        }

        @Test
        @DisplayName("timing window start/end is forwarded when valid")
        void timingWindowForwarded() {
            when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
                .thenReturn(stubFileResult());

            NodeExecutionResult result = node(Map.of(
                "operation", "overlay", "video", fileRef(VIDEO_KEY), "image", imageRef(),
                "start_seconds", 2, "end_seconds", 8, "opacity", 0.5)).execute(context);

            assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
            verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), eq("overlay"), optionsCaptor.capture(), anyList());
            assertEquals(2d, optionsCaptor.getValue().get("start_seconds"));
            assertEquals(8d, optionsCaptor.getValue().get("end_seconds"));
            assertEquals(0.5d, optionsCaptor.getValue().get("opacity"));
        }
    }

    // ==================== Failure passthrough + template resolution ====================

    @Test
    @DisplayName("MediaRenderException from the service surfaces as the node's failure message verbatim")
    void renderExceptionSurfacesVerbatim() {
        when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
            anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
            .thenThrow(new MediaRenderException("Media operation 'probe' failed: unreadable input"));

        NodeExecutionResult result = node(Map.of(
            "operation", "probe", "input", fileRef(AUDIO_KEY))).execute(context);

        assertFalse(result.isSuccess());
        assertEquals("Media operation 'probe' failed: unreadable input", result.errorMessage().orElse(""));
        assertNull(result.output().get("file"), "failure output keeps the documented shape with null file");
    }

    @Test
    @DisplayName("whole-value template resolving to a RAW FileRef map is accepted (public_link resolution contract)")
    void wholeValueTemplateYieldsRawFileRef() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "probe");
        params.put("input", "{{core:dl.output.file}}");
        when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
            Map<String, Object> resolved = new LinkedHashMap<>((Map<String, Object>) inv.getArgument(0));
            resolved.put("input", fileRef(AUDIO_KEY));
            return resolved;
        });
        when(mediaRenderService.render(anyString(), any(), anyString(), anyString(),
            anyInt(), anyInt(), any(), anyString(), anyMap(), anyList()))
            .thenReturn(new ProbeResult(Map.of("duration_seconds", 3.0)));

        NodeExecutionResult result = node(params).execute(context);

        assertTrue(result.isSuccess(), "expected success, got: " + result.errorMessage());
        verify(mediaRenderService).render(anyString(), any(), anyString(), anyString(),
            anyInt(), anyInt(), any(), eq("probe"), anyMap(), inputsCaptor.capture());
        assertEquals(AUDIO_KEY, inputsCaptor.getValue().get(0).fileRef().get("path"));
    }
}
