package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.media.MediaRenderService;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.FileResult;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.MediaInput;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.MediaResult;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.ProbeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Media node - audio/video processing on the optional renderer component.
 *
 * <p>Operations (param {@code operation}): {@code probe} (read metadata),
 * {@code mux_audio} (put ONE audio track onto ONE video), {@code mix} (mix 1-8 audio
 * tracks, optionally onto a video), {@code extract_audio} (pull the audio track out of
 * a video), {@code concat} (glue 1-8 videos back to back, with optional per-clip
 * trim/speed and cut/crossfade transitions), {@code frame} (extract ONE still image,
 * default the middle of the video), {@code overlay} (burn an image onto a video),
 * {@code subtitles} (burn timed captions into the picture).</p>
 *
 * <p>Config lives in Core's generic {@code params} map. Every param accepts
 * {@code {{...}}} template expressions resolved at run time; file params
 * ({@code input}/{@code video}/{@code audio}/{@code image}/{@code tracks[].source}/
 * {@code inputs[].source}) must be whole-value templates that resolve to RAW FileRef
 * maps (same resolution contract as the public_link node's {@code file} param) or
 * literal FileRef objects.</p>
 *
 * <p>Unlike the best-effort interface screenshot, this node FAILS when the renderer
 * component is absent: producing the media output IS its purpose.</p>
 */
public class MediaNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(MediaNode.class);

    static final Set<String> OPERATIONS = Set.of(
        "probe", "mux_audio", "mix", "extract_audio", "concat", "frame", "overlay", "subtitles");
    static final String OPERATIONS_LIST =
        "probe, mux_audio, mix, extract_audio, concat, frame, overlay, subtitles";
    static final Set<String> AUDIO_FITS = Set.of("pad", "shortest", "loop");
    static final Set<String> AUDIO_FORMATS = Set.of("mp3", "wav", "aac");
    static final Set<String> TRANSITIONS = Set.of("cut", "crossfade");
    static final Set<String> IMAGE_FORMATS = Set.of("jpeg", "png");
    static final Set<String> OVERLAY_POSITIONS = Set.of(
        "top_left", "top_right", "bottom_left", "bottom_right", "center");
    /** PUBLIC so the build-time layers refuse the same values the run refuses. */
    public static final Set<String> SUBTITLE_STYLES = Set.of("tiktok", "classic");
    static final int MAX_TRACKS = 8;
    static final int MAX_CONCAT_INPUTS = 8;
    /**
     * PUBLIC because the build-time layers (node creator, set_plan validation, error
     * checker) enforce the same cap and must not each carry their own copy of the
     * number: the run-time refusal and the build-time refusal have to agree.
     */
    public static final int MAX_SUBTITLE_CUES = 600;
    /** PUBLIC for the same reason as {@link #MAX_SUBTITLE_CUES}: the build-time layers enforce it too. */
    public static final int MAX_SUBTITLE_TEXT_CHARS = 240;
    /**
     * The whole caption track, not one line. A per-line cap is not enough: 600 legal
     * lines in a script where each character costs 3 UTF-8 bytes serialise past the
     * renderer's request cap, where the document is TRUNCATED in transit rather than
     * refused, and the caller is told their JSON is malformed.
     */
    public static final int MAX_SUBTITLE_TOTAL_CHARS = 40000;
    /** Letters, digits, spaces, dot, underscore, dash - what a font family name can be. */
    public static final Pattern SUBTITLE_FONT_PATTERN = Pattern.compile("^[A-Za-z0-9 ._-]{1,64}$");
    public static final Pattern SUBTITLE_COLOUR_PATTERN = Pattern.compile("^#?[0-9A-Fa-f]{6}$");

    static final String RENDERER_UNAVAILABLE_MESSAGE =
        "This media operation cannot run: the media renderer component is not enabled on this "
            + "installation, and producing the media output is this node's whole purpose. Only the "
            + "user or an administrator can enable the optional renderer component - tell them this "
            + "node needs it, or remove the node. It will fail on every run here until the component "
            + "is enabled.";

    private final Map<String, Object> params;

    // Injected via ServiceRegistry
    private MediaRenderService mediaRenderService;

    public MediaNode(String nodeId, Map<String, Object> params) {
        super(nodeId, NodeType.MEDIA);
        this.params = params != null ? params : Map.of();
    }

    public void setMediaRenderService(MediaRenderService mediaRenderService) {
        this.mediaRenderService = mediaRenderService;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.mediaRenderService = registry.getMediaRenderService();
    }

    public Map<String, Object> getParams() {
        return params;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> resolvedParams = new LinkedHashMap<>();

        try {
            if (mediaRenderService == null || !mediaRenderService.isEnabled()) {
                return failure(context, resolvedParams, startTime, RENDERER_UNAVAILABLE_MESSAGE);
            }

            // Resolve the WHOLE params map first: every param accepts templates, and
            // whole-value templates keep their RAW type (FileRef maps stay maps).
            Map<String, Object> resolved = resolveParams(context);
            String operation = stringValue(resolved.get("operation"));
            operation = operation != null ? operation.trim().toLowerCase(Locale.ROOT) : null;
            resolvedParams.put("operation", operation);

            if (operation == null || operation.isBlank()) {
                return failure(context, resolvedParams, startTime,
                    "operation is required. Accepted values: " + OPERATIONS_LIST);
            }
            if (!OPERATIONS.contains(operation)) {
                return failure(context, resolvedParams, startTime,
                    "Unknown operation '" + operation + "'. Accepted values: " + OPERATIONS_LIST);
            }

            List<String> errors = new ArrayList<>();
            RenderPlan plan = switch (operation) {
                case "probe" -> buildProbePlan(resolved, errors);
                case "mux_audio" -> buildMuxPlan(resolved, errors);
                case "mix" -> buildMixPlan(resolved, errors);
                case "extract_audio" -> buildExtractPlan(resolved, errors);
                case "concat" -> buildConcatPlan(resolved, errors);
                case "frame" -> buildFramePlan(resolved, errors);
                case "overlay" -> buildOverlayPlan(resolved, errors);
                case "subtitles" -> buildSubtitlesPlan(resolved, errors);
                default -> null; // unreachable - operation validated above
            };
            if (!errors.isEmpty()) {
                return failure(context, resolvedParams, startTime,
                    "Invalid media configuration: " + String.join("; ", errors));
            }

            // The INPUTS as well as the options. For probe and concat the options map
            // is empty, so resolved_params used to be literally {operation}: the file
            // being probed, or the clips being joined, never appeared at all.
            if (!plan.inputs().isEmpty()) {
                resolvedParams.put("inputs", plan.inputs());
            }
            resolvedParams.putAll(plan.options());
            // A caption track carries up to MAX_SUBTITLE_CUES entries. Echoing every one of
            // them into the run's stored output would multiply the step data by the length of
            // the script while telling the reader nothing the node's own params do not already
            // say, so the echo is a count. Every OTHER option stays verbatim.
            if (resolvedParams.get("cues") instanceof List<?> cueList) {
                resolvedParams.remove("cues");
                resolvedParams.put("cue_count", cueList.size());
            }

            String workflowId = context.plan() != null ? context.plan().getId() : null;
            MediaResult result = mediaRenderService.render(
                context.tenantId(), workflowId, context.runId(), nodeId,
                resolveStorageEpoch(context), context.spawn(), context.itemIndex(),
                operation, plan.options(), plan.inputs());

            Map<String, Object> output = new LinkedHashMap<>();
            if (result instanceof ProbeResult probe) {
                // Probe answers FLAT fields (duration_seconds, size_bytes, format_name,
                // bit_rate, has_video, has_audio, video, audio) - pass them through verbatim.
                output.putAll(probe.fields());
                output.putIfAbsent("file", null);
            } else if (result instanceof FileResult file) {
                output.put("file", file.file());
                output.put("duration_seconds", file.durationSeconds());
                if ("frame".equals(operation)) {
                    // frame is the only operation with timestamp_seconds: the ACTUAL
                    // timestamp used after the default (middle) and end-clamp. Its
                    // duration_seconds stays null (a still image has no duration).
                    output.put("timestamp_seconds", file.timestampSeconds());
                }
            }
            output.put("node_type", "MEDIA");
            output.put("resolved_params", resolvedParams);
            return NodeExecutionResult.success(nodeId, output);

        } catch (MediaRenderService.MediaRenderException e) {
            logger.error("Media node failed: nodeId={}, error={}", nodeId, e.getMessage());
            return failure(context, resolvedParams, startTime, e.getMessage());
        } catch (Exception e) {
            logger.error("Media node failed unexpectedly: nodeId={}, error={}", nodeId, e.getMessage(), e);
            return failure(context, resolvedParams, startTime, "Media processing failed: " + e.getMessage());
        }
    }

    // ==================== Per-operation plans ====================

    /** Options for the spec's {@code options} member + the ordered binary inputs. */
    private record RenderPlan(Map<String, Object> options, List<MediaInput> inputs) { }

    private RenderPlan buildProbePlan(Map<String, Object> resolved, List<String> errors) {
        List<MediaInput> inputs = new ArrayList<>();
        Map<String, Object> fileRef = requireFileRef(resolved, "input", errors);
        if (fileRef != null) {
            inputs.add(new MediaInput("input0", "input", null, fileRef));
        }
        return new RenderPlan(new LinkedHashMap<>(), inputs);
    }

    private RenderPlan buildMuxPlan(Map<String, Object> resolved, List<String> errors) {
        List<MediaInput> inputs = new ArrayList<>();
        Map<String, Object> video = requireFileRef(resolved, "video", errors);
        Map<String, Object> audio = requireFileRef(resolved, "audio", errors);
        if (video != null) {
            inputs.add(new MediaInput("input0", "video", null, video));
        }
        if (audio != null) {
            inputs.add(new MediaInput("input" + inputs.size(), "audio", null, audio));
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("volume", numberOrDefault(resolved, "volume", 100d, 0d, 400d,
            "volume must be a number between 0 and 400 (percent)", errors));
        options.put("offset_seconds", numberOrDefault(resolved, "offset_seconds", 0d, 0d, null,
            "offset_seconds must be a number >= 0", errors));
        putOptionalNonNegative(options, resolved, "trim_start_seconds", errors);
        putOptionalNonNegative(options, resolved, "trim_end_seconds", errors);
        options.put("loop", booleanOrDefault(resolved, "loop", false, errors));
        options.put("fade_in_seconds", numberOrDefault(resolved, "fade_in_seconds", 0d, 0d, null,
            "fade_in_seconds must be a number >= 0", errors));
        options.put("fade_out_seconds", numberOrDefault(resolved, "fade_out_seconds", 1.0d, 0d, null,
            "fade_out_seconds must be a number >= 0", errors));
        boolean keepOriginal = booleanOrDefault(resolved, "keep_original_audio", false, errors);
        options.put("keep_original_audio", keepOriginal);
        if (keepOriginal) {
            options.put("original_volume", numberOrDefault(resolved, "original_volume", 100d, 0d, 400d,
                "original_volume must be a number between 0 and 400 (percent)", errors));
        }
        options.put("audio_fit", enumOrDefault(resolved, "audio_fit", "pad", AUDIO_FITS,
            "audio_fit must be one of: pad, shortest, loop", errors));
        options.put("normalize", normalizeValue(resolved, errors));
        options.put("audio_bitrate", stringOrDefault(resolved, "audio_bitrate", "192k"));
        rejectLoopWithTrim(options, "the audio", errors);
        return new RenderPlan(options, inputs);
    }

    /**
     * loop:true combined with trim_start_seconds/trim_end_seconds on the same audio is
     * INVALID (renderer contract): trimming defines a segment, looping repeats the whole
     * source, and combining them is ambiguous. Trim in a separate media node first, or
     * drop one of the two.
     */
    private static void rejectLoopWithTrim(Map<String, Object> options, String what, List<String> errors) {
        boolean loops = Boolean.TRUE.equals(options.get("loop"));
        boolean trims = options.containsKey("trim_start_seconds") || options.containsKey("trim_end_seconds");
        if (loops && trims) {
            errors.add("loop cannot be combined with trim_start_seconds/trim_end_seconds on " + what
                + ": extract the trimmed segment with a separate media node first, or drop loop/the trims");
        }
    }

    private RenderPlan buildMixPlan(Map<String, Object> resolved, List<String> errors) {
        List<MediaInput> inputs = new ArrayList<>();
        // A blank literal video is ABSENT (audio-only mix); a mapped template that resolved
        // blank is an ERROR (requireFileRef reports the upstream problem), never a silent
        // downgrade to audio-only.
        Object videoValue = resolved.get("video");
        boolean resolvedPresent = videoValue != null && !(videoValue instanceof String s && s.isBlank());
        boolean hasVideo = resolvedPresent || rawTemplateFor("video") != null;
        if (hasVideo) {
            Map<String, Object> video = requireFileRef(resolved, "video", errors);
            if (video != null) {
                inputs.add(new MediaInput("input0", "video", null, video));
            }
        }

        Object tracksValue = resolved.get("tracks");
        if (!(tracksValue instanceof List<?> trackList) || trackList.isEmpty()) {
            errors.add("tracks is required for mix: a non-empty array of 1-" + MAX_TRACKS
                + " tracks, each with a source FileRef expression "
                + "(e.g. tracks: [{source: '{{agent:voice.output.file}}'}])");
            return new RenderPlan(new LinkedHashMap<>(), inputs);
        }
        if (trackList.size() > MAX_TRACKS) {
            errors.add("tracks accepts at most " + MAX_TRACKS + " tracks (got " + trackList.size() + ")");
            return new RenderPlan(new LinkedHashMap<>(), inputs);
        }

        List<Map<String, Object>> trackOptions = new ArrayList<>();
        List<String> trackIds = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < trackList.size(); i++) {
            Object rawTrack = trackList.get(i);
            if (!(rawTrack instanceof Map<?, ?> trackMapRaw)) {
                errors.add("tracks[" + i + "] must be an object with at least a source FileRef expression");
                trackIds.add(null);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> track = (Map<String, Object>) trackMapRaw;

            String id = stringValue(track.get("id"));
            if (id == null || id.isBlank()) {
                id = "track_" + (i + 1);
            }
            if (!seenIds.add(id)) {
                errors.add("tracks[" + i + "]: duplicate track id '" + id
                    + "' - each track id must be unique so duck_under references are unambiguous");
            }
            trackIds.add(id);

            Map<String, Object> fileRef = requireFileRef(track, "source", errors, "tracks[" + i + "].source",
                rawTrackSourceTemplate(i));
            if (fileRef == null) {
                continue;
            }
            String partName = "input" + inputs.size();
            inputs.add(new MediaInput(partName, "audio", i, fileRef));

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("id", id);
            options.put("source_part", partName);
            options.put("volume", numberOrDefault(track, "volume", 100d, 0d, 400d,
                "tracks[" + i + "].volume must be a number between 0 and 400 (percent)", errors));
            options.put("offset_seconds", numberOrDefault(track, "offset_seconds", 0d, 0d, null,
                "tracks[" + i + "].offset_seconds must be a number >= 0", errors));
            putOptionalNonNegative(options, track, "trim_start_seconds", errors, "tracks[" + i + "].trim_start_seconds");
            putOptionalNonNegative(options, track, "trim_end_seconds", errors, "tracks[" + i + "].trim_end_seconds");
            options.put("loop", booleanOrDefault(track, "loop", false, errors));
            options.put("fade_in_seconds", numberOrDefault(track, "fade_in_seconds", 0d, 0d, null,
                "tracks[" + i + "].fade_in_seconds must be a number >= 0", errors));
            options.put("fade_out_seconds", numberOrDefault(track, "fade_out_seconds", 0d, 0d, null,
                "tracks[" + i + "].fade_out_seconds must be a number >= 0", errors));
            options.put("speed", numberOrDefault(track, "speed", 1.0d, 0.5d, 2.0d,
                "tracks[" + i + "].speed must be a number between 0.5 and 2.0", errors));
            String duckUnder = stringValue(track.get("duck_under"));
            if (duckUnder != null && !duckUnder.isBlank()) {
                options.put("duck_under", duckUnder);
                options.put("duck_amount_db", numberOrDefault(track, "duck_amount_db", 12d, null, null,
                    "tracks[" + i + "].duck_amount_db must be a number", errors));
                options.put("duck_attack_ms", numberOrDefault(track, "duck_attack_ms", 20d, null, null,
                    "tracks[" + i + "].duck_attack_ms must be a number", errors));
                options.put("duck_release_ms", numberOrDefault(track, "duck_release_ms", 300d, null, null,
                    "tracks[" + i + "].duck_release_ms must be a number", errors));
            }
            rejectLoopWithTrim(options, "tracks[" + i + "]", errors);
            trackOptions.add(options);
        }

        // Audio-only mix needs a length anchor: when EVERY track loops there is nothing
        // that defines the output duration (renderer contract: INVALID).
        if (!hasVideo && !trackOptions.isEmpty()
                && trackOptions.stream().allMatch(t -> Boolean.TRUE.equals(t.get("loop")))) {
            errors.add("an audio-only mix cannot have loop:true on EVERY track (nothing anchors the output length): "
                + "set loop:false on at least one track, or provide a video");
        }

        // duck_under must reference an EXISTING track id that is not the track's own.
        for (Map<String, Object> track : trackOptions) {
            Object duckUnder = track.get("duck_under");
            if (duckUnder == null) {
                continue;
            }
            String ownId = String.valueOf(track.get("id"));
            if (ownId.equals(duckUnder)) {
                errors.add("track '" + ownId + "': duck_under must reference ANOTHER track's id, not its own");
            } else if (!trackIds.contains(String.valueOf(duckUnder))) {
                errors.add("track '" + ownId + "': duck_under references unknown track id '" + duckUnder
                    + "'. Known track ids: " + String.join(", ",
                        trackIds.stream().filter(t -> t != null).toList()));
            }
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("tracks", trackOptions);
        boolean keepOriginal = booleanOrDefault(resolved, "keep_original_audio", false, errors);
        if (keepOriginal && !hasVideo) {
            errors.add("keep_original_audio is only valid when a video is provided (there is no original track without one)");
        }
        if (hasVideo) {
            options.put("keep_original_audio", keepOriginal);
            if (keepOriginal) {
                options.put("original_volume", numberOrDefault(resolved, "original_volume", 100d, 0d, 400d,
                    "original_volume must be a number between 0 and 400 (percent)", errors));
            }
        }
        options.put("audio_fit", enumOrDefault(resolved, "audio_fit", "pad", AUDIO_FITS,
            "audio_fit must be one of: pad, shortest, loop", errors));
        options.put("normalize", normalizeValue(resolved, errors));
        options.put("audio_bitrate", stringOrDefault(resolved, "audio_bitrate", "192k"));

        String requestedFormat = stringValue(resolved.get("output_format"));
        if (hasVideo) {
            if (requestedFormat != null && !"mp4".equalsIgnoreCase(requestedFormat.trim())) {
                errors.add("output_format must be mp4 when a video is provided (the video stream is copied into an mp4 container)");
            }
            options.put("output_format", "mp4");
        } else {
            options.put("output_format", enumOrDefault(resolved, "output_format", "mp3", AUDIO_FORMATS,
                "output_format must be one of: mp3, wav, aac (audio-only mix)", errors));
        }
        return new RenderPlan(options, inputs);
    }

    private RenderPlan buildExtractPlan(Map<String, Object> resolved, List<String> errors) {
        List<MediaInput> inputs = new ArrayList<>();
        Map<String, Object> fileRef = requireFileRef(resolved, "input", errors);
        if (fileRef != null) {
            inputs.add(new MediaInput("input0", "input", null, fileRef));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("output_format", enumOrDefault(resolved, "output_format", "mp3", AUDIO_FORMATS,
            "output_format must be one of: mp3, wav, aac", errors));
        options.put("audio_bitrate", stringOrDefault(resolved, "audio_bitrate", "192k"));
        putOptionalNonNegative(options, resolved, "trim_start_seconds", errors);
        putOptionalNonNegative(options, resolved, "trim_end_seconds", errors);
        return new RenderPlan(options, inputs);
    }

    /**
     * concat: glue 1-8 videos back to back (a SINGLE input is the trim/speed-edit use
     * case). Per-item trims/speed, cut or crossfade transitions, optional target
     * canvas/fps, global fades and loudness normalisation (default OFF here: it forces
     * the re-encode path). The renderer owns the copy-vs-re-encode decision and the
     * effective-duration crossfade check (it probes every input first).
     */
    private RenderPlan buildConcatPlan(Map<String, Object> resolved, List<String> errors) {
        List<MediaInput> inputs = new ArrayList<>();

        Object inputsValue = resolved.get("inputs");
        if (!(inputsValue instanceof List<?> inputList) || inputList.isEmpty()) {
            errors.add("inputs is required for concat: a non-empty array of 1-" + MAX_CONCAT_INPUTS
                + " clips, each with a source FileRef expression "
                + "(e.g. inputs: [{source: '{{core:clip_a.output.file}}'}, {source: '{{core:clip_b.output.file}}'}])");
            return new RenderPlan(new LinkedHashMap<>(), inputs);
        }
        if (inputList.size() > MAX_CONCAT_INPUTS) {
            errors.add("inputs accepts at most " + MAX_CONCAT_INPUTS + " clips (got " + inputList.size() + ")");
            return new RenderPlan(new LinkedHashMap<>(), inputs);
        }

        List<Map<String, Object>> itemOptions = new ArrayList<>();
        for (int i = 0; i < inputList.size(); i++) {
            Object rawItem = inputList.get(i);
            if (!(rawItem instanceof Map<?, ?> itemMapRaw)) {
                errors.add("inputs[" + i + "] must be an object with at least a source FileRef expression");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) itemMapRaw;

            Map<String, Object> fileRef = requireFileRef(item, "source", errors, "inputs[" + i + "].source",
                rawConcatSourceTemplate(i));
            if (fileRef == null) {
                continue;
            }
            String partName = "input" + inputs.size();
            inputs.add(new MediaInput(partName, "video", i, fileRef));

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("source_part", partName);
            putOptionalNonNegative(options, item, "trim_start_seconds", errors, "inputs[" + i + "].trim_start_seconds");
            putOptionalNonNegative(options, item, "trim_end_seconds", errors, "inputs[" + i + "].trim_end_seconds");
            Object trimStart = options.get("trim_start_seconds");
            Object trimEnd = options.get("trim_end_seconds");
            if (trimStart instanceof Double s && trimEnd instanceof Double e && e <= s) {
                errors.add("inputs[" + i + "].trim_end_seconds must be greater than trim_start_seconds (got "
                    + trimEnd + " <= " + trimStart + ")");
            }
            options.put("speed", numberOrDefault(item, "speed", 1.0d, 0.5d, 2.0d,
                "inputs[" + i + "].speed must be a number between 0.5 and 2.0", errors));
            itemOptions.add(options);
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("inputs", itemOptions);
        String transition = enumOrDefault(resolved, "transition", "cut", TRANSITIONS,
            "transition must be one of: cut, crossfade", errors);
        options.put("transition", transition);
        options.put("transition_seconds", numberOrDefault(resolved, "transition_seconds", 0.5d, 0.1d, 5.0d,
            "transition_seconds must be a number between 0.1 and 5.0", errors));
        if ("crossfade".equals(transition) && inputList.size() < 2) {
            errors.add("transition 'crossfade' requires at least 2 inputs (got " + inputList.size()
                + "): add a second clip, or use transition 'cut'");
        }

        Double targetWidth = optionalNumber(resolved, "target_width", 16d, 4096d,
            "target_width must be a number between 16 and 4096", errors);
        Double targetHeight = optionalNumber(resolved, "target_height", 16d, 4096d,
            "target_height must be a number between 16 and 4096", errors);
        boolean widthPresent = isPresent(resolved.get("target_width"));
        boolean heightPresent = isPresent(resolved.get("target_height"));
        if (widthPresent != heightPresent) {
            errors.add("target_width and target_height must be provided together (BOTH or NEITHER) - got only "
                + (widthPresent ? "target_width" : "target_height"));
        } else if (targetWidth != null && targetHeight != null) {
            options.put("target_width", targetWidth);
            options.put("target_height", targetHeight);
        }
        Double targetFps = optionalNumber(resolved, "target_fps", 1d, 60d,
            "target_fps must be a number between 1 and 60", errors);
        if (targetFps != null) {
            options.put("target_fps", targetFps);
        }

        options.put("fade_in_seconds", numberOrDefault(resolved, "fade_in_seconds", 0d, 0d, null,
            "fade_in_seconds must be a number >= 0", errors));
        options.put("fade_out_seconds", numberOrDefault(resolved, "fade_out_seconds", 0d, 0d, null,
            "fade_out_seconds must be a number >= 0", errors));
        // concat defaults normalize to FALSE (unlike mux/mix): normalisation forces the
        // re-encode path, defeating the lossless fast copy of homogeneous clips.
        options.put("normalize", normalizeValue(resolved, errors, Boolean.FALSE));
        options.put("audio_bitrate", stringOrDefault(resolved, "audio_bitrate", "192k"));
        return new RenderPlan(options, inputs);
    }

    /**
     * frame: extract ONE still image (cover/thumbnail). at_seconds defaults to the
     * MIDDLE of the video and is clamped to the end renderer-side (never an error).
     */
    private RenderPlan buildFramePlan(Map<String, Object> resolved, List<String> errors) {
        List<MediaInput> inputs = new ArrayList<>();
        Map<String, Object> fileRef = requireFileRef(resolved, "input", errors);
        if (fileRef != null) {
            inputs.add(new MediaInput("input0", "input", null, fileRef));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        // Absent = renderer default (the middle of the video, duration/2).
        putOptionalNonNegative(options, resolved, "at_seconds", errors);
        options.put("image_format", enumOrDefault(resolved, "image_format", "jpeg", IMAGE_FORMATS,
            "image_format must be one of: jpeg, png", errors));
        Double width = optionalNumber(resolved, "width", 16d, 4096d,
            "width must be a number between 16 and 4096", errors);
        if (width != null) {
            options.put("width", width);
        }
        return new RenderPlan(options, inputs);
    }

    /**
     * overlay: burn an image (logo, watermark, badge) onto a video. Video re-encodes;
     * audio is stream-copied when present (renderer contract).
     */
    private RenderPlan buildOverlayPlan(Map<String, Object> resolved, List<String> errors) {
        List<MediaInput> inputs = new ArrayList<>();
        Map<String, Object> video = requireFileRef(resolved, "video", errors);
        Map<String, Object> image = requireFileRef(resolved, "image", errors);
        if (video != null) {
            inputs.add(new MediaInput("input0", "video", null, video));
        }
        if (image != null) {
            inputs.add(new MediaInput("input" + inputs.size(), "image", null, image));
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("position", enumOrDefault(resolved, "position", "bottom_right", OVERLAY_POSITIONS,
            "position must be one of: top_left, top_right, bottom_left, bottom_right, center", errors));
        options.put("margin_px", numberOrDefault(resolved, "margin_px", 24d, 0d, null,
            "margin_px must be a number >= 0", errors));
        options.put("width_percent", numberOrDefault(resolved, "width_percent", 15d, 1d, 100d,
            "width_percent must be a number between 1 and 100 (percent of the video width)", errors));
        options.put("opacity", numberOrDefault(resolved, "opacity", 1.0d, 0d, 1.0d,
            "opacity must be a number between 0 and 1", errors));
        putOptionalNonNegative(options, resolved, "start_seconds", errors);
        putOptionalNonNegative(options, resolved, "end_seconds", errors);
        Object start = options.get("start_seconds");
        Object end = options.get("end_seconds");
        double effectiveStart = start instanceof Double s ? s : 0d;
        if (end instanceof Double e && e <= effectiveStart) {
            errors.add("end_seconds must be greater than start_seconds (got " + end
                + " <= " + effectiveStart + ")");
        }
        return new RenderPlan(options, inputs);
    }

    /**
     * subtitles: burn timed captions into the picture of a video.
     *
     * <p>The caller hands over timed TEXT and picks a look from a preset; it never
     * authors a subtitle file. That is deliberate: a subtitle style block the renderer
     * does not understand is ignored SILENTLY, so a caller-authored file would fail by
     * producing a video captioned in the wrong face rather than by reporting an error.</p>
     */
    private RenderPlan buildSubtitlesPlan(Map<String, Object> resolved, List<String> errors) {
        List<MediaInput> inputs = new ArrayList<>();
        Map<String, Object> video = requireFileRef(resolved, "video", errors);
        if (video != null) {
            inputs.add(new MediaInput("input0", "video", null, video));
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("cues", buildSubtitleCues(resolved.get("cues"), rawTemplateFor("cues"), errors));
        options.put("style", enumOrDefault(resolved, "style", "tiktok", SUBTITLE_STYLES,
            "style must be one of: tiktok, classic", errors));
        // font_family, font_size_percent, position_percent and the two colours are OMITTED
        // when absent instead of being defaulted here: the preset is what defines the look,
        // and a default copied into this layer would silently drift from the one that renders.
        putPatternOption(options, resolved, "font_family", SUBTITLE_FONT_PATTERN,
            "font_family must be a font family name (letters, digits, spaces, dot, underscore or dash)", errors);
        Double fontSizePercent = optionalNumber(resolved, "font_size_percent", 1d, 20d,
            "font_size_percent must be a number between 1 and 20 (percent of the video HEIGHT)", errors);
        if (fontSizePercent != null) {
            options.put("font_size_percent", fontSizePercent);
        }
        Double positionPercent = optionalNumber(resolved, "position_percent", 0d, 100d,
            "position_percent must be a number between 0 and 100 (percent from the TOP of the video)", errors);
        if (positionPercent != null) {
            options.put("position_percent", positionPercent);
        }
        putPatternOption(options, resolved, "text_color", SUBTITLE_COLOUR_PATTERN,
            "text_color must be a hex colour like '#FFFFFF'", errors);
        putPatternOption(options, resolved, "outline_color", SUBTITLE_COLOUR_PATTERN,
            "outline_color must be a hex colour like '#000000'", errors);
        return new RenderPlan(options, inputs);
    }

    /**
     * Validate and normalise the caption track. Every cue must carry when it appears,
     * when it disappears, and what it says; the track must run forward without two cues
     * sharing an instant.
     */
    private static List<Map<String, Object>> buildSubtitleCues(Object cuesValue, String rawTemplate,
                                                              List<String> errors) {
        List<Map<String, Object>> cues = new ArrayList<>();
        if (!(cuesValue instanceof List<?> cueList) || cueList.isEmpty()) {
            if (rawTemplate != null) {
                // The param WAS mapped - "is required" would send the agent to fix the
                // wrong thing. The expression resolved to no caption track, so the
                // upstream node that was meant to produce one is the problem.
                errors.add("cues ('" + rawTemplate + "') resolved to no caption track - the upstream node it "
                    + "references may have failed, been skipped, or produced something other than an array of "
                    + "{start_seconds, end_seconds, text}. Check that node's run output, fix it, then run again");
            } else {
                errors.add("cues is required for subtitles: a non-empty array of {start_seconds, end_seconds, text}, "
                    + "e.g. cues: [{start_seconds: 0, end_seconds: 2.4, text: 'Ninety metres below the surface'}]");
            }
            return cues;
        }
        if (cueList.size() > MAX_SUBTITLE_CUES) {
            errors.add("cues accepts at most " + MAX_SUBTITLE_CUES + " entries (got " + cueList.size()
                + ") - caption a shorter section, or split the video and caption each part");
            return cues;
        }

        Double previousEnd = null;
        int totalChars = 0;
        for (int i = 0; i < cueList.size(); i++) {
            String label = "cues[" + i + "]";
            if (!(cueList.get(i) instanceof Map<?, ?> rawCue)) {
                errors.add(label + " must be an object with start_seconds, end_seconds and text");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cue = (Map<String, Object>) rawCue;

            Double start = optionalNumber(cue, "start_seconds", 0d, null,
                label + ".start_seconds must be a number >= 0", errors);
            Double end = optionalNumber(cue, "end_seconds", 0d, null,
                label + ".end_seconds must be a number >= 0", errors);
            if (!isPresent(cue.get("start_seconds")) || !isPresent(cue.get("end_seconds"))) {
                errors.add(label + " needs both start_seconds and end_seconds: when the caption appears and "
                    + "when it disappears, in seconds from the start of the video");
                continue;
            }
            if (start == null || end == null) {
                continue; // optionalNumber already reported what is wrong with the value
            }
            if (end <= start) {
                errors.add(label + ".end_seconds must be greater than start_seconds (got " + end
                    + " <= " + start + ")");
                // previousEnd deliberately NOT advanced: this cue's end is already known
                // to be wrong, so ordering the next cue against it would report a second
                // error derived from the first one.
                continue;
            }
            // Ascending and non-overlapping is REQUIRED, not repaired: two cues covering the
            // same instant are drawn ON TOP of each other, so an overlap is always a timing
            // bug in the caller. Sorting it away here would ship that bug as a finished video.
            if (previousEnd != null && start < previousEnd) {
                errors.add(label + ".start_seconds (" + start + ") overlaps the previous cue, which ends at "
                    + previousEnd + " - give the cues in ascending, non-overlapping order");
                previousEnd = end;
                continue;
            }
            previousEnd = end;

            String text = stringValue(cue.get("text"));
            if (text == null || text.isBlank()) {
                errors.add(label + ".text is required: the line shown between start_seconds and end_seconds");
                continue;
            }
            // Measured on the TRIMMED text, because that is the value actually sent:
            // checking the raw one would refuse a line that trailing spaces pushed over
            // the cap, which is a rejection the caller cannot see the reason for.
            String trimmed = text.trim();
            if (trimmed.length() > MAX_SUBTITLE_TEXT_CHARS) {
                errors.add(label + ".text is longer than " + MAX_SUBTITLE_TEXT_CHARS + " characters (got "
                    + trimmed.length() + ") - split it across consecutive cues so each one stays readable");
                continue;
            }

            totalChars += trimmed.length();
            if (totalChars > MAX_SUBTITLE_TOTAL_CHARS) {
                errors.add("the caption track is longer than " + MAX_SUBTITLE_TOTAL_CHARS
                    + " characters in total - caption a shorter section, or split the video and "
                    + "caption each part");
                return cues;
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("start_seconds", start);
            normalized.put("end_seconds", end);
            normalized.put("text", trimmed);
            cues.add(normalized);
        }
        return cues;
    }

    // ==================== Value helpers ====================

    /** The list params a template can fill: a reference may hand them over as a JSON string. */
    private static final List<String> LIST_PARAMS = List.of("tracks", "inputs", "cues");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Resolve the params map, keeping RAW object types for whole-value templates, and FAIL if
     * that cannot be done.
     *
     * <p>It used to swallow the failure and render with the RAW params, so a source of
     * {@code {{core:clip.output.file}}} reached the renderer as that text. GenerateNode stopped
     * doing this for the same reason: the literal template is not a value, and the node that
     * receives it can only produce something wrong from it.
     *
     * <p>A list param (tracks, inputs, cues) that resolves to a JSON STRING, which is what a code
     * node or an LLM step usually returns, is parsed into the list it describes; it used to be
     * refused as "not an array" although it was one.
     */
    private Map<String, Object> resolveParams(ExecutionContext context) {
        if (templateAdapter == null || params.isEmpty()) {
            return new LinkedHashMap<>(params);
        }
        Map<String, Object> resolved = templateAdapter.resolveTemplates(params, context);
        Map<String, Object> out = resolved != null ? new LinkedHashMap<>(resolved) : new LinkedHashMap<>(params);
        for (String key : LIST_PARAMS) {
            if (out.get(key) instanceof String text && text.trim().startsWith("[")) {
                try {
                    out.put(key, JSON.readValue(text, List.class));
                } catch (Exception e) {
                    // Left as the string: the operation's own check names the param and says
                    // an array was expected, which is the right message for malformed JSON too.
                    logger.debug("Media param '{}' is not a JSON array: {}", key, e.getMessage());
                }
            }
        }
        return out;
    }

    private Map<String, Object> requireFileRef(Map<String, Object> source, String key, List<String> errors) {
        return requireFileRef(source, key, errors, key, rawTemplateFor(key));
    }

    private Map<String, Object> requireFileRef(Map<String, Object> source, String key,
                                               List<String> errors, String label) {
        return requireFileRef(source, key, errors, label, rawTemplateFor(key));
    }

    /** The raw (unresolved) template configured for a top-level param, or null. */
    private String rawTemplateFor(String key) {
        String raw = stringValue(params.get(key));
        return raw != null && !raw.isBlank() ? raw : null;
    }

    /** The raw (unresolved) template configured for tracks[i].source, or null. */
    private String rawTrackSourceTemplate(int index) {
        return rawListItemSourceTemplate("tracks", index);
    }

    /** The raw (unresolved) template configured for inputs[i].source (concat), or null. */
    private String rawConcatSourceTemplate(int index) {
        return rawListItemSourceTemplate("inputs", index);
    }

    private String rawListItemSourceTemplate(String listKey, int index) {
        if (params.get(listKey) instanceof List<?> rawItems && index < rawItems.size()
                && rawItems.get(index) instanceof Map<?, ?> rawItem) {
            String raw = stringValue(rawItem.get("source"));
            return raw != null && !raw.isBlank() ? raw : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireFileRef(Map<String, Object> source, String key,
                                               List<String> errors, String label, String rawTemplate) {
        Object value = source.get(key);
        if (value == null || (value instanceof String s && s.isBlank())) {
            if (rawTemplate != null) {
                // The param WAS mapped - "is required" would send the agent fixing the wrong
                // thing. The template resolved to nothing: the referenced node is the problem.
                errors.add(label + " ('" + rawTemplate + "') resolved to nothing - the upstream node it "
                    + "references may have failed, been skipped, or produced no file. Check that node's "
                    + "run output, fix it, then run again");
            } else {
                errors.add(label + " is required: map the WHOLE FileRef output of an upstream node, "
                    + "e.g. " + label + ": '{{core:download.output.file}}'");
            }
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            // Blank is not a path: it passes validation here and then fails much later, in the
            // renderer, with the very message this branch exists to replace.
            if (map.get("path") instanceof String path && !path.isBlank()) {
                return (Map<String, Object>) map;
            }
            // A file-shaped value with no usable path is a DIFFERENT problem from a wrong mapping,
            // and saying "map the WHOLE FileRef output" to someone who already did sends them to
            // re-map a correct expression. A table media cell reaches here exactly like this when
            // the file it names is known only by id.
            if (com.apimarketplace.orchestrator.domain.file.FileRef.TYPE_FILE.equals(map.get("_type"))) {
                errors.add(label + " " + com.apimarketplace.orchestrator.domain.file.FileRefMessages.NO_STORAGE_PATH);
                return null;
            }
        }
        errors.add(label + " did not resolve to a file reference (got "
            + (value instanceof String ? "a plain string" : value.getClass().getSimpleName())
            + ") - map the WHOLE FileRef output, e.g. {{core:download.output.file}}, never .path or a URL");
        return null;
    }

    private static String stringValue(Object value) {
        if (value instanceof String s) {
            return s;
        }
        return value != null && !(value instanceof Map) && !(value instanceof List) ? String.valueOf(value) : null;
    }

    private static String stringOrDefault(Map<String, Object> source, String key, String defaultValue) {
        String value = stringValue(source.get(key));
        return value != null && !value.isBlank() ? value.trim() : defaultValue;
    }

    private static String enumOrDefault(Map<String, Object> source, String key, String defaultValue,
                                        Set<String> accepted, String errorMessage, List<String> errors) {
        Object raw = source.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return defaultValue;
        }
        String value = stringValue(raw);
        if (value == null) {
            errors.add(errorMessage);
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!accepted.contains(normalized)) {
            errors.add(errorMessage + " (got '" + value + "')");
            return defaultValue;
        }
        return normalized;
    }

    private static Double asNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static double numberOrDefault(Map<String, Object> source, String key, double defaultValue,
                                          Double min, Double max, String errorMessage, List<String> errors) {
        Object raw = source.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return defaultValue;
        }
        Double value = asNumber(raw);
        if (value == null) {
            errors.add(errorMessage + " (got '" + raw + "')");
            return defaultValue;
        }
        if ((min != null && value < min) || (max != null && value > max)) {
            errors.add(errorMessage + " (got " + raw + ")");
            return defaultValue;
        }
        return value;
    }

    /** Non-blank literal or resolved value present (used for the BOTH-or-NEITHER dims check). */
    private static boolean isPresent(Object value) {
        return value != null && !(value instanceof String s && s.isBlank());
    }

    /**
     * An optional bounded number: null when absent (no default applies), the value when
     * valid, null + an error when non-numeric or out of bounds.
     */
    private static Double optionalNumber(Map<String, Object> source, String key,
                                         Double min, Double max, String errorMessage, List<String> errors) {
        Object raw = source.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return null;
        }
        Double value = asNumber(raw);
        if (value == null || (min != null && value < min) || (max != null && value > max)) {
            errors.add(errorMessage + " (got '" + raw + "')");
            return null;
        }
        return value;
    }

    private static void putOptionalNonNegative(Map<String, Object> options, Map<String, Object> source,
                                               String key, List<String> errors) {
        putOptionalNonNegative(options, source, key, errors, key);
    }

    private static void putOptionalNonNegative(Map<String, Object> options, Map<String, Object> source,
                                               String key, List<String> errors, String label) {
        Object raw = source.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return;
        }
        Double value = asNumber(raw);
        if (value == null || value < 0) {
            errors.add(label + " must be a number >= 0 (got '" + raw + "')");
            return;
        }
        options.put(key, value);
    }

    /**
     * A pattern-checked optional string option: absent leaves the option OUT entirely (so
     * the renderer's own default applies and there is one definition of it), while a value
     * that is present but malformed is an error rather than a value quietly dropped.
     */
    private static void putPatternOption(Map<String, Object> options, Map<String, Object> source, String key,
                                         Pattern pattern, String errorMessage, List<String> errors) {
        Object raw = source.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return;
        }
        String value = stringValue(raw);
        if (value == null || !pattern.matcher(value.trim()).matches()) {
            errors.add(errorMessage + " (got '" + raw + "')");
            return;
        }
        options.put(key, value.trim());
    }

    private static boolean booleanOrDefault(Map<String, Object> source, String key,
                                            boolean defaultValue, List<String> errors) {
        Object raw = source.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return defaultValue;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String normalized = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) return true;
            if ("false".equals(normalized)) return false;
        }
        errors.add(key + " must be true or false (got '" + raw + "')");
        return defaultValue;
    }

    /**
     * normalize accepts a boolean (true = loudnorm at -16 LUFS) or a LUFS target number
     * in [-70, -5]; false disables loudness normalisation. Default true for mux/mix.
     */
    private static Object normalizeValue(Map<String, Object> source, List<String> errors) {
        return normalizeValue(source, errors, Boolean.TRUE);
    }

    /**
     * normalize with a per-operation default: concat defaults to FALSE (normalisation
     * forces the re-encode path, defeating the lossless fast copy of homogeneous clips).
     */
    private static Object normalizeValue(Map<String, Object> source, List<String> errors, Object defaultValue) {
        Object raw = source.get("normalize");
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return defaultValue;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String normalized = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) return Boolean.TRUE;
            if ("false".equals(normalized)) return Boolean.FALSE;
        }
        Double value = asNumber(raw);
        if (value == null) {
            errors.add("normalize must be true, false, or a LUFS target number between -70 and -5 (got '" + raw + "')");
            return defaultValue;
        }
        if (value < -70 || value > -5) {
            errors.add("normalize as a number is a LUFS target and must be between -70 and -5 (got " + raw + ")");
            return defaultValue;
        }
        return value;
    }

    private NodeExecutionResult failure(ExecutionContext context, Map<String, Object> resolvedParams,
                                        long startTime, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", null);
        out.put("duration_seconds", null);
        out.put("resolved_params", resolvedParams);
        return NodeExecutionResult.failureWithOutput(nodeId, message,
            enrichWithMetadata(out, context), System.currentTimeMillis() - startTime);
    }
}
