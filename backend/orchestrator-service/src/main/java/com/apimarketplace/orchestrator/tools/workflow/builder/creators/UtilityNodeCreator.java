package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Creates utility nodes: transform, wait, download_file, http_request, stop, response, aggregate, split.
 * Interface node creation is handled by {@link InterfaceNodeCreator}.
 *
 * IMPORTANT: WorkflowBuilderProvider already merges all parameters into a flat map.
 * So we just read directly from parameters - no re-extraction needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UtilityNodeCreator extends CreatorBase {

    private final WorkflowBuilderSessionStore sessionStore;
    private final ResponseOptimizer responseOptimizer;
    private final NodeLibraryService nodeLibraryService;
    private final WorkflowRepository workflowRepository;

    // ==================== Transform ====================

    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddTransform(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "transform");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("transform");
        }

        // 2. Extract mappings - support multiple formats:
        //    - mappings: [{label, expression}]  (canonical)
        //    - outputs: {key: expression}       (object format - convert to array)
        //    - fields: [{label, expression}]    (alias)
        //    - transformations: [...]           (alias)
        List<Map<String, Object>> mappings = getMappings(parameters);
        if (mappings == null || mappings.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRANSFORM: 'mappings' is required.\n" +
                "Formats accepted:\n" +
                "  - params={mappings: [{label: 'x', expression: '{{...}}'}]}\n" +
                "  - params={outputs: {field_name: '{{...}}'}}");
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.TRANSFORM.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        // Build mappings list (normalize to {label, expression} format)
        List<Map<String, Object>> mappingsList = new ArrayList<>();
        for (Map<String, Object> m : mappings) {
            String mLabel = getString(m, "label", "name", "field", "key");
            String expr = getString(m, "expression", "value", "expr");
            if (mLabel != null && expr != null) {
                mappingsList.add(Map.of("label", mLabel, "expression", expr));
            }
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "transform");
        node.put("position", calculatePosition(session, NodeType.TRANSFORM));
        node.put("transform", Map.of("mappings", mappingsList));

        // 3. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.TRANSFORM, nodeId, node, connectAfter);

        return buildSuccessResponse("transform", nodeId, label, normalizedLabel, connectAfter,
            Map.of("mappings_count", mappingsList.size(),
                   "mappings", mappingsList.stream().map(m -> m.get("label") + " = " + m.get("expression")).toList(),
                   "access_pattern", "{{core:" + normalizedLabel + ".output.<field>}}"),
            Map.of("mappings", mappingsList));
    }

    // ==================== Wait ====================

    public ToolExecutionResult executeAddWait(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "wait");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("wait");
        }

        Integer duration = parseDuration(parameters.get("duration"));
        if (duration == null) duration = parseDuration(parameters.get("delay"));
        if (duration == null) {
            Integer seconds = getInt(parameters, "seconds");
            if (seconds != null) duration = seconds * 1000;
        }
        if (duration == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "WAIT: 'duration' is required. Formats: '30s', '5m', '1h', '1d', or milliseconds.\n" +
                "Example: params={duration: '30s'} or params={duration: 5000}");
        }
        if (duration < 0) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "WAIT: 'duration' must be non-negative (got " + duration + " ms).");
        }

        // 2. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.WAIT.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "wait");
        node.put("position", calculatePosition(session, NodeType.WAIT));
        node.put("wait", Map.of("duration", duration));

        // 3. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.WAIT, nodeId, node, connectAfter);

        return buildSuccessResponse("wait", nodeId, label, normalizedLabel, connectAfter,
            Map.of("duration_ms", duration, "duration_human", formatDuration(duration)),
            Map.of("duration_ms", duration));
    }

    // ==================== Download File ====================

    public ToolExecutionResult executeAddDownloadFile(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "download_file");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("download_file");
        }

        // Accept multiple aliases for url: url, source, link, file_url, href, src
        String url = getString(parameters, "url", "source", "link", "file_url", "href", "src");
        if (url == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "DOWNLOAD FILE: 'url' is required.\n" +
                "Example: params={url: '{{mcp:api.output.image_url}}'}\n" +
                "Supports: images, documents, media, data files, archives");
        }

        // 2. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.DOWNLOAD_FILE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        // Accept multiple aliases for filename: filename, name, file_name, output
        String filename = getString(parameters, "filename", "file_name", "output");

        // Optional headers for authentication
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) parameters.get("headers");

        // Optional timeout (default 30000ms)
        Integer timeout = getInt(parameters, "timeout");
        if (timeout == null) timeout = 30000;

        Map<String, Object> downloadConfig = new LinkedHashMap<>();
        downloadConfig.put("url", url);
        if (filename != null) downloadConfig.put("filename", filename);
        if (headers != null && !headers.isEmpty()) downloadConfig.put("headers", headers);
        downloadConfig.put("timeout", timeout);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "download_file");
        node.put("position", calculatePosition(session, NodeType.DOWNLOAD_FILE));
        node.put("download", downloadConfig);

        // 3. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.DOWNLOAD_FILE, nodeId, node, connectAfter);

        // Build saved params for download
        Map<String, Object> downloadSavedParams = new LinkedHashMap<>();
        downloadSavedParams.put("url", url);
        if (filename != null) downloadSavedParams.put("filename", filename);
        if (headers != null && !headers.isEmpty()) downloadSavedParams.put("headers", headers);
        downloadSavedParams.put("timeout", timeout);

        return buildSuccessResponse("download_file", nodeId, label, normalizedLabel, connectAfter,
            Map.of("url", url,
                   "filename", filename != null ? filename : "(auto-detected)",
                   "access_pattern", "{{core:" + normalizedLabel + ".output.file}}",
                   "interface_usage", "To display in an interface: variable_mapping: {'photo': '{{core:" + normalizedLabel + ".output.file}}'}, HTML: <img src=\"{{photo}}\"/>. The canonical FileRef is auto-rewritten to a tokenised URL (auth'd app) or HMAC-signed URL (marketplace + share preview)."),
            downloadSavedParams);
    }

    // ==================== Public Link ====================

    public ToolExecutionResult executeAddPublicLink(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "public_link");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("public_link");
        }

        String file = getString(parameters, "file", "file_ref", "fileRef");
        if (file == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "PUBLIC LINK: 'file' is required.\n" +
                "Reference the WHOLE FileRef output of an upstream node.\n" +
                "Example: params={file: '{{interface:card.output.video}}', ttl_minutes: 240}\n" +
                "Use the resulting {{core:<label>.output.url}} in URL-pull API params (Instagram video_url, TikTok PULL_FROM_URL, link).");
        }

        // 2. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.PUBLIC_LINK.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Integer ttlMinutes = getInt(parameters, "ttl_minutes", "ttlMinutes");
        String disposition = getString(parameters, "disposition");

        Map<String, Object> linkParams = new LinkedHashMap<>();
        linkParams.put("file", file);
        if (ttlMinutes != null) linkParams.put("ttl_minutes", ttlMinutes);
        if (disposition != null) linkParams.put("disposition", disposition);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "public_link");
        node.put("position", calculatePosition(session, NodeType.PUBLIC_LINK));
        node.put("params", linkParams);

        // 3. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.PUBLIC_LINK, nodeId, node, connectAfter);

        return buildSuccessResponse("public_link", nodeId, label, normalizedLabel, connectAfter,
            Map.of("file", file,
                   "ttl_minutes", ttlMinutes != null ? ttlMinutes : 240,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.url}}",
                   "usage", "The url output is PUBLIC until expires_at: feed it to API params that pull media from a URL (Instagram create_media_container video_url, TikTok source_info video_url, Facebook link). The link only works for files owned by this workflow's tenant."),
            new LinkedHashMap<>(linkParams));
    }

    // ==================== Media ====================

    /** The media operations and their agent-facing one-liners (shared by error + success responses). */
    private static final Map<String, String> MEDIA_OPERATIONS = createMediaOperations();

    private static Map<String, String> createMediaOperations() {
        Map<String, String> ops = new LinkedHashMap<>();
        ops.put("probe", "Read metadata of a video/audio file. Requires: input (whole FileRef expression). Outputs FLAT fields: duration_seconds, size_bytes, format_name, bit_rate, has_video, has_audio, video, audio.");
        ops.put("mux_audio", "Put ONE audio track onto ONE video. Requires: video + audio (whole FileRef expressions). Options: volume (0-400, default 100), offset_seconds, trim_start_seconds/trim_end_seconds, loop, fade_in_seconds (0)/fade_out_seconds (1.0), keep_original_audio (+original_volume), audio_fit (pad|shortest|loop), normalize, audio_bitrate ('192k'). Output: file (mp4) + duration_seconds.");
        ops.put("mix", "Mix 1-8 audio tracks, optionally onto a video. Requires: tracks=[{source, id?, volume?, offset_seconds?, trim_*?, loop?, fade_*?, speed (0.5-2.0)?, duck_under? (+duck_amount_db 12/duck_attack_ms 20/duck_release_ms 300)}]. Optional: video, keep_original_audio/original_volume (video only), audio_fit, normalize, audio_bitrate, output_format (mp3|wav|aac when no video; mp4 forced with video). Output: file + duration_seconds.");
        ops.put("extract_audio", "Pull the audio track out of a video. Requires: input (whole FileRef expression). Options: output_format (mp3|wav|aac, default mp3), audio_bitrate ('192k'), trim_start_seconds/trim_end_seconds. Output: file + duration_seconds.");
        ops.put("concat", "Glue 1-8 videos back to back into one mp4 (a SINGLE input = trim/speed edit). Requires: inputs=[{source (whole FileRef expression), trim_start_seconds?, trim_end_seconds?, speed (0.5-2.0)?}]. Options: transition (cut|crossfade, default cut; crossfade needs >= 2 inputs), transition_seconds (0.1-5.0, default 0.5), target_width+target_height (16-4096, BOTH or NEITHER; clips are scaled to fit and padded, never stretched), target_fps (1-60), fade_in_seconds (0)/fade_out_seconds (0), normalize (default FALSE: true evens out loudness between clips but forces re-encode), audio_bitrate ('192k'). Output: file (mp4) + duration_seconds.");
        ops.put("frame", "Extract ONE still image from a video (cover/thumbnail). Requires: input (whole FileRef expression). Options: at_seconds (>= 0; default = the MIDDLE of the video; clamped to the end, never an error), image_format (jpeg|png, default jpeg), width (16-4096, aspect ratio kept). Output: file (image) + timestamp_seconds (the ACTUAL timestamp used); duration_seconds is null.");
        ops.put("overlay", "Burn an image (logo, watermark, badge) onto a video. Requires: video + image (whole FileRef expressions; png alpha respected). Options: position (top_left|top_right|bottom_left|bottom_right|center, default bottom_right), margin_px (>= 0, default 24), width_percent (1-100, % of the video width, default 15), opacity (0-1, default 1), start_seconds/end_seconds (visibility window; absent = whole video). Output: file (mp4) + duration_seconds.");
        return ops;
    }

    /** Agent-friendly aliases resolved to the canonical operation before validation/storage. */
    private static final Map<String, String> MEDIA_OPERATION_ALIASES = Map.of(
        "stitch", "concat",
        "join", "concat",
        "join_videos", "concat",
        "thumbnail", "frame",
        "cover", "frame",
        "watermark", "overlay");

    /**
     * A usable media file param: a non-blank expression string ({@code {{...output.file}}})
     * OR a literal FileRef object (the builder's Files picker stores those) with a path.
     */
    private static boolean isFileParam(Object value) {
        if (value instanceof String s) {
            return !s.isBlank();
        }
        return value instanceof Map<?, ?> map && map.get("path") instanceof String p && !p.isBlank();
    }

    /** Every accepted media param key (besides operation and the framework keys) copied into the node's params map. */
    private static final List<String> MEDIA_PARAM_KEYS = List.of(
        "input", "video", "audio", "image", "tracks", "inputs",
        "volume", "offset_seconds", "trim_start_seconds", "trim_end_seconds", "loop",
        "fade_in_seconds", "fade_out_seconds", "keep_original_audio", "original_volume",
        "audio_fit", "normalize", "audio_bitrate", "output_format",
        "transition", "transition_seconds", "target_width", "target_height", "target_fps",
        "at_seconds", "image_format", "width",
        "position", "margin_px", "width_percent", "opacity", "start_seconds", "end_seconds");

    public ToolExecutionResult executeAddMedia(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "media");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("media");
        }

        String operation = getString(parameters, "operation", "op");
        if (operation == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "MEDIA: 'operation' is required.\n" +
                "Available operations:\n" + mediaOperationsTable() +
                "\nExample: params={operation: 'mux_audio', video: '{{interface:card.output.video}}', audio: '{{core:dl.output.file}}', volume: 80, fade_out_seconds: 2}");
        }
        operation = operation.trim().toLowerCase();
        operation = MEDIA_OPERATION_ALIASES.getOrDefault(operation, operation);
        if (!MEDIA_OPERATIONS.containsKey(operation)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "MEDIA: unknown operation '" + operation + "'.\n" +
                "Available operations:\n" + mediaOperationsTable());
        }

        Object tracks = parameters.get("tracks");
        switch (operation) {
            case "probe", "extract_audio", "frame" -> {
                if (!isFileParam(parameters.get("input")) && !isFileParam(parameters.get("file"))) {
                    return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "MEDIA " + operation + ": 'input' is required.\n" +
                        "Reference the WHOLE FileRef output of an upstream node (or a literal FileRef object).\n" +
                        "Example: params={operation: '" + operation + "', input: '{{core:dl.output.file}}'}");
                }
            }
            case "mux_audio" -> {
                boolean missingVideo = !isFileParam(parameters.get("video"));
                boolean missingAudio = !isFileParam(parameters.get("audio"));
                if (missingVideo || missingAudio) {
                    return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "MEDIA mux_audio: " +
                        (missingVideo && missingAudio ? "'video' and 'audio' are" : missingVideo ? "'video' is" : "'audio' is") +
                        " required (whole FileRef expressions or literal FileRef objects).\n" +
                        "Example: params={operation: 'mux_audio', video: '{{interface:card.output.video}}', audio: '{{core:music.output.file}}', volume: 80, fade_out_seconds: 2}");
                }
            }
            case "mix" -> {
                if (!(tracks instanceof List<?> trackList) || trackList.isEmpty()) {
                    return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "MEDIA mix: 'tracks' is required - a non-empty array of 1-8 tracks, each with a 'source' FileRef expression.\n" +
                        "Example: params={operation: 'mix', tracks: [{id: 'voice', source: '{{core:tts.output.file}}'}, {id: 'music', source: '{{core:dl.output.file}}', volume: 60, duck_under: 'voice'}]}");
                }
            }
            case "concat" -> {
                if (!(parameters.get("inputs") instanceof List<?> inputList) || inputList.isEmpty()) {
                    return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "MEDIA concat: 'inputs' is required - a non-empty array of 1-8 clips, each with a 'source' FileRef expression.\n" +
                        "Example: params={operation: 'concat', inputs: [{source: '{{core:clip_a.output.file}}'}, {source: '{{core:clip_b.output.file}}'}], transition: 'crossfade'}");
                } else if (inputList.size() > 8) {
                    return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "MEDIA concat: 'inputs' accepts at most 8 clips (got " + inputList.size() + ").");
                } else {
                    for (int i = 0; i < inputList.size(); i++) {
                        if (!(inputList.get(i) instanceof Map<?, ?> item) || !isFileParam(item.get("source"))) {
                            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "MEDIA concat: 'inputs[" + i + "].source' is required - " +
                                "the WHOLE FileRef expression of that clip (or a literal FileRef object).\n" +
                                "Example: inputs: [{source: '{{core:clip_a.output.file}}', trim_end_seconds: 10}]");
                        }
                    }
                }
            }
            case "overlay" -> {
                boolean missingVideo = !isFileParam(parameters.get("video"));
                boolean missingImage = !isFileParam(parameters.get("image"));
                if (missingVideo || missingImage) {
                    return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "MEDIA overlay: " +
                        (missingVideo && missingImage ? "'video' and 'image' are" : missingVideo ? "'video' is" : "'image' is") +
                        " required (whole FileRef expressions or literal FileRef objects).\n" +
                        "Example: params={operation: 'overlay', video: '{{core:clip.output.file}}', image: '{{core:logo.output.file}}', position: 'bottom_right', width_percent: 15}");
                }
            }
            default -> { /* unreachable - operation validated above */ }
        }

        // 2. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.MEDIA.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> mediaParams = new LinkedHashMap<>();
        mediaParams.put("operation", operation);
        if ("probe".equals(operation) || "extract_audio".equals(operation) || "frame".equals(operation)) {
            Object input = isFileParam(parameters.get("input")) ? parameters.get("input") : parameters.get("file");
            if (isFileParam(input)) mediaParams.put("input", input);
        }
        for (String key : MEDIA_PARAM_KEYS) {
            Object value = parameters.get(key);
            if (value != null && !mediaParams.containsKey(key)) {
                mediaParams.put(key, value);
            }
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "media");
        node.put("position", calculatePosition(session, NodeType.MEDIA));
        node.put("params", mediaParams);

        // 3. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.MEDIA, nodeId, node, connectAfter);

        String accessPattern = switch (operation) {
            case "probe" -> "{{core:" + normalizedLabel + ".output.duration_seconds}} (probe outputs FLAT metadata fields, no file)";
            case "frame" -> "{{core:" + normalizedLabel + ".output.file}} (an image; {{core:" + normalizedLabel
                + ".output.timestamp_seconds}} = the actual timestamp used, duration_seconds is null)";
            default -> "{{core:" + normalizedLabel + ".output.file}}";
        };
        return buildSuccessResponse("media", nodeId, label, normalizedLabel, connectAfter,
            Map.of("operation", operation,
                   "available_operations", MEDIA_OPERATIONS,
                   "access_pattern", accessPattern,
                   "usage", "Runs on the optional renderer component: when it is not enabled on this installation the node FAILS at run time (validate warns with MEDIA_RENDERER_UNAVAILABLE). File params take the WHOLE FileRef output of an upstream node ({{core:dl.output.file}}), never .path or a URL."),
            new LinkedHashMap<>(mediaParams));
    }

    private static String mediaOperationsTable() {
        StringBuilder sb = new StringBuilder();
        MEDIA_OPERATIONS.forEach((op, description) ->
            sb.append("- ").append(op).append(": ").append(description).append('\n'));
        return sb.toString();
    }

    // ==================== Exit ====================

    public ToolExecutionResult executeAddExit(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "exit");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("exit");
        }

        // 2. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.EXIT.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        // Optional params from docs
        String reason = getString(parameters, "reason");
        String status = getString(parameters, "status");
        if (status != null && !List.of("exited", "cancelled", "failed", "completed").contains(status.toLowerCase())) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "EXIT: 'status' must be one of: exited, cancelled, failed, completed. Got: '" + status + "'");
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "exit");
        if (reason != null) node.put("reason", reason);
        if (status != null) node.put("status", status.toLowerCase());
        node.put("position", calculatePosition(session, NodeType.EXIT));

        // 3. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.EXIT, nodeId, node, connectAfter);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("node_type", "exit");
        response.put("node_id", nodeId);
        response.put("label", label);
        response.put("note", "TERMINAL NODE: branch ends here, other parallel branches continue");
        response.put("connection", Map.of("status", connectAfter != null ? "connected" : "orphaned"));

        // Show saved params so LLM knows what was actually stored
        Map<String, Object> exitSavedParams = new LinkedHashMap<>();
        if (reason != null) exitSavedParams.put("reason", reason);
        if (status != null) exitSavedParams.put("status", status.toLowerCase());
        if (!exitSavedParams.isEmpty()) {
            response.put("saved_params", exitSavedParams);
        } else {
            response.put("saved_params", Map.of("note", "No optional params provided"));
        }

        return ToolExecutionResult.success(response);
    }

    // ==================== Response ====================

    public ToolExecutionResult executeAddResponse(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "response");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("response");
        }

        // Check if any trigger is a chat trigger - warn if not
        boolean hasChatTrigger = session.getTriggers().stream()
                .anyMatch(t -> "chat".equalsIgnoreCase((String) t.get("type")));

        // Accept multiple aliases for message: message, text, content, body, response
        String message = getString(parameters, "message", "text", "content", "body", "response");
        if (message == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "RESPONSE: 'message' is required.\n" +
                "Example: params={message: 'Processing {{mcp:api.output.count}} items...'}");
        }

        // 2. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.RESPONSE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> responseConfig = new LinkedHashMap<>();
        responseConfig.put("message", message);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "response");
        node.put("position", calculatePosition(session, NodeType.RESPONSE));
        node.put("response", responseConfig);

        // 3. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.RESPONSE, nodeId, node, connectAfter);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("message", message);
        if (!hasChatTrigger) {
            extras.put("warning", "Response node sends messages to the chat conversation. " +
                    "This workflow has no chat trigger - the response message will only appear " +
                    "in execution logs, not in any conversation.");
        }

        return buildSuccessResponse("response", nodeId, label, normalizedLabel, connectAfter,
            extras, Map.of("message", message));
    }

    // ==================== Aggregate ====================

    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddAggregate(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "aggregate");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("aggregate");
        }

        // 2. Extract fields - support multiple formats (same as transform)
        List<Map<String, Object>> fields = getMappings(parameters);
        // Also try 'fields' directly if getMappings didn't find anything
        if (fields == null) {
            fields = (List<Map<String, Object>>) parameters.get("fields");
        }
        if (fields == null || fields.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "AGGREGATE: 'fields' is required.\n" +
                "Formats accepted:\n" +
                "  - params={fields: [{label: 'total', expression: '{{#sum(...)}}'}]}\n" +
                "  - params={outputs: {total: '{{#sum(...)}}'}}\n" +
                "Functions: #collectList(), #sum(), #avg(), #max(), #min(), #size(), #join()");
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.AGGREGATE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        // Build fields list (normalize to {label, expression} format)
        List<Map<String, Object>> fieldsList = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String fLabel = getString(f, "label", "name", "field", "key");
            String expr = getString(f, "expression", "value", "expr");
            if (fLabel != null && expr != null) {
                fieldsList.add(Map.of("label", fLabel, "expression", expr));
            }
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "aggregate");
        node.put("position", calculatePosition(session, NodeType.AGGREGATE));
        node.put("aggregate", Map.of("fields", fieldsList));

        // 3. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.AGGREGATE, nodeId, node, connectAfter);

        return buildSuccessResponse("aggregate", nodeId, label, normalizedLabel, connectAfter,
            Map.of("fields_count", fieldsList.size(),
                   "fields", fieldsList.stream().map(f -> f.get("label") + " = " + f.get("expression")).toList(),
                   "access_pattern", "{{core:" + normalizedLabel + ".output.<field>}}"),
            Map.of("fields", fieldsList));
    }

    // ==================== Split ====================

    /**
     * Execute add_split action.
     * Creates a split node that iterates over a list with parallel execution.
     * All parameters are flat, no nested 'split' object.
     */
    public ToolExecutionResult executeAddSplit(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "split");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("split");
        }

        // 2. Parse items - accept common aliases LLMs use
        String items = getString(parameters, "items", "list", "input", "array", "data", "collection", "source");
        if (items == null || items.isBlank()) {
            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("'items' is REQUIRED for split.\n\n");
            errorMsg.append("FORMAT: items='{{mcp:STEP_NAME.output.array_field}}'\n");
            errorMsg.append("   - Must reference an array/list from a previous step's output\n");
            errorMsg.append("   - Use the step's label (normalized) as STEP_NAME\n\n");

            if (!session.getMcps().isEmpty()) {
                errorMsg.append("AVAILABLE STEPS you can reference:\n");
                for (Map<String, Object> step : session.getMcps()) {
                    String stepLabel = (String) step.get("label");
                    String normalizedStepLabel = WorkflowBuilderSession.normalizeLabel(stepLabel);
                    errorMsg.append("   - {{mcp:").append(normalizedStepLabel).append(".output.YOUR_ARRAY_FIELD}}\n");
                }
            } else {
                errorMsg.append("No steps found yet - add a step that returns a list first!\n");
            }

            errorMsg.append("\nEXAMPLE: workflow(action='add_node', type='split', label='Process Items', ");
            errorMsg.append("params={items: '{{mcp:get_users.output.users}}'}, connect_after='Get Users')");

            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, errorMsg.toString());
        }
        if (!items.contains("{{")) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "'items' must be a SpEL expression.\n\n" +
                "CORRECT FORMAT: items='{{mcp:STEP_NAME.output.array_field}}'\n" +
                "YOU PROVIDED: items='" + items + "'\n\n" +
                "The expression must start with {{ and reference a step's output array.");
        }

        // 3. Parse maxItems (default: 100, range: 1-1000)
        Integer maxItems = getInt(parameters, "maxItems", "max_items");
        if (maxItems == null) {
            maxItems = 100;
        }
        if (maxItems < 1 || maxItems > 1000) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "max_items must be between 1 and 1000");
        }

        // 4. Split strategy is always "continue-anyway" (not configurable by LLM)
        String splitStrategy = "continue-anyway";

        // 5. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.SPLIT.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        // 6. Build split node
        Map<String, Object> splitNode = new LinkedHashMap<>();
        splitNode.put("id", nodeId);
        splitNode.put("label", label);
        splitNode.put("type", "split");
        splitNode.put("list", items);
        splitNode.put("maxItems", maxItems);
        splitNode.put("splitStrategy", splitStrategy);
        splitNode.put("position", calculatePosition(session, NodeType.SPLIT));

        // 7. Add to session and create edge
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(splitNode));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.SPLIT, nodeId, splitNode, connectAfter);

        // 8. Build response with JIT variables and current_item/current_index guidance
        Map<String, Object> response = responseOptimizer.buildSplitResponse(session, nodeId, label, items, maxItems);

        response.put("saved_params", Map.of(
            "items", items,
            "max_items", maxItems
        ));

        // Progressive validation - check for other orphan nodes
        int totalNodes = session.getTriggers().size() + session.getMcps().size() + session.getCores().size();
        if (totalNodes >= 3) {
            List<String> orphans = session.findOrphanNodes().stream()
                .filter(id -> !id.equals(nodeId))
                .toList();
            if (!orphans.isEmpty()) {
                Map<String, Object> validation = new LinkedHashMap<>();
                validation.put("other_orphan_nodes", orphans.stream()
                    .map(id -> Map.of("id", id, "logical_id", session.getLogicalId(id)))
                    .toList());
                validation.put("hint", "Other nodes are also disconnected. Use workflow(action='connect', from='Source Label', to='Target Label')");
                response.put("progressive_validation", validation);
            }
        }

        return ToolExecutionResult.success(response);
    }

    // ==================== Loop ====================

    /**
     * Execute add_loop action.
     * Creates a loop node with body and exit ports.
     * Loop evaluates a condition each iteration; body executes while true, exit when false.
     */
    public ToolExecutionResult executeAddLoop(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "loop");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("loop");
        }

        // 2. Extract loop config
        String condition = getString(parameters, "condition", "loop_condition", "expression", "while");
        Integer maxIterations = getInt(parameters, "max_iterations", "maxIterations", "limit");
        if (maxIterations == null) maxIterations = 10;
        if (maxIterations < 1 || maxIterations > 10000) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "max_iterations must be between 1 and 10000. Got: " + maxIterations);
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.LOOP.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "loop");
        node.put("position", calculatePosition(session, NodeType.LOOP));
        if (condition != null) node.put("loopCondition", condition);
        node.put("maxIterations", maxIterations);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.LOOP, nodeId, node, connectAfter);

        // Build saved params
        Map<String, Object> savedParams = new LinkedHashMap<>();
        if (condition != null) savedParams.put("condition", condition);
        savedParams.put("max_iterations", maxIterations);

        Map<String, String> nextSteps = new LinkedHashMap<>();
        nextSteps.put("connect_body", "workflow(action='add_node', type='...', label='...', connect_after='core:" + normalizedLabel + ":body')");
        nextSteps.put("connect_iterate", "workflow(action='connect', from='<last body step label>', to='" + label + ":iterate')");
        nextSteps.put("connect_exit", "workflow(action='add_node', type='...', label='...', connect_after='core:" + normalizedLabel + ":exit')");

        return buildSuccessResponse("loop", nodeId, label, normalizedLabel, connectAfter,
            Map.of("ports", List.of("body", "iterate", "exit"),
                   "max_iterations", maxIterations,
                   "condition", condition != null ? condition : "(always enter body)",
                   "access_pattern", "{{core:" + normalizedLabel + ".output.iteration}}",
                   "NEXT_STEPS", nextSteps),
            savedParams);
    }

    // ==================== Data Input ====================

    /**
     * Execute add_data_input action.
     * Creates a data input node that provides text and/or file inputs to downstream nodes.
     * Each item has a label used for output access: {{core:label.output.<item_label>}}
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddDataInput(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "data_input");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("data_input");
        }

        // 2. Extract items
        List<Map<String, Object>> items = null;
        for (String key : List.of("items", "inputs", "fields", "data")) {
            Object value = parameters.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                items = (List<Map<String, Object>>) list;
                break;
            }
        }

        if (items == null || items.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "DATA INPUT: 'items' is required.\n" +
                "Format: params={items: [{label: 'prompt', type: 'text', text: 'Analyze {{mcp:fetch.output.data}}'}, " +
                "{label: 'document', type: 'file', file: {url: '...', name: 'doc.pdf'}}]}\n" +
                "Types: 'text' (default) or 'file'");
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.DATA_INPUT.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        // Normalize items
        List<Map<String, Object>> normalizedItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            Map<String, Object> normalizedItem = new LinkedHashMap<>();
            String itemId = safeString(item.get("id"));
            if (itemId == null) itemId = "item_" + i;
            normalizedItem.put("id", itemId);

            String itemLabel = safeString(item.get("label"));
            if (itemLabel == null) itemLabel = safeString(item.get("name"));
            if (itemLabel == null) itemLabel = "input_" + i;
            normalizedItem.put("label", itemLabel);

            String itemType = safeString(item.get("type"));
            if (itemType == null) itemType = "text";
            normalizedItem.put("type", itemType);

            if ("text".equals(itemType)) {
                String text = safeString(item.get("text"));
                if (text == null) text = safeString(item.get("value"));
                if (text == null) text = safeString(item.get("content"));
                normalizedItem.put("text", text != null ? text : "");
            } else if ("file".equals(itemType)) {
                Object file = item.get("file");
                if (file instanceof Map) {
                    normalizedItem.put("file", file);
                }
            }

            normalizedItems.add(normalizedItem);
        }

        Map<String, Object> dataInputConfig = new LinkedHashMap<>();
        dataInputConfig.put("items", normalizedItems);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "data_input");
        node.put("position", calculatePosition(session, NodeType.DATA_INPUT));
        node.put("dataInput", dataInputConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.DATA_INPUT, nodeId, node, connectAfter);

        // Build access patterns
        List<String> accessPatterns = normalizedItems.stream()
            .map(item -> "{{core:" + normalizedLabel + ".output." + item.get("label") + "}}")
            .toList();

        return buildSuccessResponse("data_input", nodeId, label, normalizedLabel, connectAfter,
            Map.of("items_count", normalizedItems.size(),
                   "access_patterns", accessPatterns,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.<item_label>}}"),
            Map.of("items", normalizedItems));
    }

    // ==================== HTTP Request ====================

    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddHttpRequest(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "http_request");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("http_request");
        }

        // URL is required
        String url = getString(parameters, "url", "endpoint", "uri");
        if (url == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "HTTP REQUEST: 'url' is required.\n" +
                "Example: params={url: 'https://api.example.com/users/{{trigger:webhook.output.user_id}}'}\n" +
                "Supports SpEL templates for dynamic URLs.");
        }

        // 2. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.HTTP_REQUEST.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        // Method (default: GET)
        String method = getString(parameters, "method");
        if (method == null) method = "GET";
        method = method.toUpperCase();
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "HTTP REQUEST: 'method' must be one of: GET, POST, PUT, PATCH, DELETE. Got: '" + method + "'");
        }

        // Auth configuration
        String authType = getString(parameters, "authType", "auth_type");
        if (authType == null) authType = "none";
        authType = authType.toLowerCase();
        if (!List.of("none", "bearer", "basic", "apikey").contains(authType)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "HTTP REQUEST: 'authType' must be one of: none, bearer, basic, apiKey. Got: '" + authType + "'");
        }

        Map<String, Object> authConfig = (Map<String, Object>) parameters.get("authConfig");
        if (authConfig == null) authConfig = (Map<String, Object>) parameters.get("auth_config");

        // Validate auth config based on type
        if ("bearer".equals(authType)) {
            if (authConfig == null || authConfig.get("token") == null) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "HTTP REQUEST: authType='bearer' requires authConfig.token.\n" +
                    "Example: params={authType: 'bearer', authConfig: {token: '{{mcp:auth.output.token}}'}}");
            }
        } else if ("basic".equals(authType)) {
            if (authConfig == null || authConfig.get("username") == null || authConfig.get("password") == null) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "HTTP REQUEST: authType='basic' requires authConfig.username and authConfig.password.\n" +
                    "Example: params={authType: 'basic', authConfig: {username: 'user', password: '{{mcp:secret.output.pass}}'}}");
            }
        } else if ("apikey".equals(authType)) {
            if (authConfig == null || authConfig.get("headerName") == null || authConfig.get("apiKey") == null) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "HTTP REQUEST: authType='apiKey' requires authConfig.headerName and authConfig.apiKey.\n" +
                    "Example: params={authType: 'apiKey', authConfig: {headerName: 'X-API-Key', apiKey: '{{mcp:secret.output.key}}'}}");
            }
        }

        // Query parameters (array of {key, value})
        List<Map<String, Object>> queryParams = (List<Map<String, Object>>) parameters.get("queryParams");
        if (queryParams == null) queryParams = (List<Map<String, Object>>) parameters.get("query_params");

        // Headers (array of {key, value})
        List<Map<String, Object>> headers = (List<Map<String, Object>>) parameters.get("headers");

        // Body configuration
        String bodyType = getString(parameters, "bodyType", "body_type");
        if (bodyType == null) bodyType = "json";
        bodyType = bodyType.toLowerCase();
        if (!List.of("json", "form", "raw").contains(bodyType)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "HTTP REQUEST: 'bodyType' must be one of: json, form, raw. Got: '" + bodyType + "'");
        }

        Object body = parameters.get("body");

        // Timeout (default 30000ms)
        Integer timeout = getInt(parameters, "timeout");
        if (timeout == null) timeout = 30000;
        if (timeout < 1000 || timeout > 300000) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "HTTP REQUEST: 'timeout' must be between 1000 and 300000 ms. Got: " + timeout);
        }

        // Build HTTP request configuration
        Map<String, Object> httpConfig = new LinkedHashMap<>();
        httpConfig.put("method", method);
        httpConfig.put("url", url);
        httpConfig.put("authType", authType);
        if (authConfig != null && !authConfig.isEmpty()) httpConfig.put("authConfig", authConfig);
        if (queryParams != null && !queryParams.isEmpty()) httpConfig.put("queryParams", queryParams);
        if (headers != null && !headers.isEmpty()) httpConfig.put("headers", headers);
        httpConfig.put("bodyType", bodyType);
        if (body != null) httpConfig.put("body", body);
        httpConfig.put("timeout", timeout);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "http_request");
        node.put("position", calculatePosition(session, NodeType.HTTP_REQUEST));
        node.put("httpRequest", httpConfig);

        // 3. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.HTTP_REQUEST, nodeId, node, connectAfter);

        // Build saved params for response
        Map<String, Object> savedParams = new LinkedHashMap<>();
        savedParams.put("method", method);
        savedParams.put("url", url);
        savedParams.put("authType", authType);
        if (authConfig != null && !authConfig.isEmpty()) savedParams.put("authConfig", authConfig);
        if (queryParams != null && !queryParams.isEmpty()) savedParams.put("queryParams", queryParams);
        if (headers != null && !headers.isEmpty()) savedParams.put("headers", headers);
        savedParams.put("bodyType", bodyType);
        if (body != null) savedParams.put("body", body);
        savedParams.put("timeout", timeout);

        return buildSuccessResponse("http_request", nodeId, label, normalizedLabel, connectAfter,
            Map.of("method", method,
                   "url", url,
                   "authType", authType,
                   "outputs", List.of("success", "status", "statusText", "data", "headers", "error"),
                   "access_pattern", "{{core:" + normalizedLabel + ".output.<field>}}"),
            savedParams);
    }

    // ==================== Filter ====================

    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddFilter(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "filter");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("filter");
        }

        // 2. Extract conditions - support multiple formats:
        //    - conditions: [{field, operator, value}]  (canonical)
        //    - filters: [{field, operator, value}]     (alias)
        List<Map<String, Object>> conditions = null;
        for (String key : List.of("conditions", "filters", "rules")) {
            Object value = parameters.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                conditions = (List<Map<String, Object>>) list;
                break;
            }
        }

        if (conditions == null || conditions.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "FILTER: 'conditions' is required.\n" +
                "Format: params={conditions: [{field: 'status', operator: 'equals', value: 'active'}], mode: 'and'}\n" +
                "Operators: equals, notEquals, contains, notContains, greaterThan, lessThan, greaterOrEqual, lessOrEqual, startsWith, endsWith, isEmpty, isNotEmpty");
        }

        // Extract mode (default: "and")
        String mode = getString(parameters, "mode");
        if (mode == null) mode = "and";
        if (!"and".equalsIgnoreCase(mode) && !"or".equalsIgnoreCase(mode)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "FILTER: 'mode' must be 'and' or 'or'. Got: '" + mode + "'");
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.FILTER.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        // Build conditions list (normalize to {field, operator, value} format)
        List<Map<String, Object>> conditionsList = new ArrayList<>();
        for (Map<String, Object> c : conditions) {
            String field = getString(c, "field", "column", "key");
            String operator = getString(c, "operator", "op", "comparator");
            String value = getString(c, "value", "expected", "compare_to");
            if (field != null && operator != null) {
                Map<String, Object> cond = new LinkedHashMap<>();
                cond.put("field", field);
                cond.put("operator", operator);
                if (value != null) cond.put("value", value);
                conditionsList.add(cond);
            }
        }

        String input = getString(parameters, "input", "inputExpression", "items", "list");

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "filter");
        node.put("position", calculatePosition(session, NodeType.FILTER));
        node.put("filter", Map.of("conditions", conditionsList, "mode", mode));
        if (input != null && !input.isBlank()) {
            Map<String, Object> filterParams = new LinkedHashMap<>();
            filterParams.put("input", input);
            node.put("params", filterParams);
        }

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.FILTER, nodeId, node, connectAfter);

        return buildSuccessResponse("filter", nodeId, label, normalizedLabel, connectAfter,
            Map.of("conditions_count", conditionsList.size(),
                   "mode", mode,
                   "conditions", conditionsList.stream()
                       .map(c -> c.get("field") + " " + c.get("operator") + " " + (c.containsKey("value") ? c.get("value") : ""))
                       .toList(),
                   "access_pattern", "{{core:" + normalizedLabel + ".output.<field>}}"),
            Map.of("conditions", conditionsList, "mode", mode));
    }

    // ==================== Sort ====================

    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddSort(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "sort");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("sort");
        }

        // 2. Extract sort fields - support multiple formats
        List<Map<String, Object>> sortFields = null;
        for (String key : List.of("fields", "sort_by", "sort", "sort_fields", "order_by")) {
            Object value = parameters.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                sortFields = (List<Map<String, Object>>) list;
                break;
            }
        }
        if (sortFields == null || sortFields.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "SORT: 'fields' is required.\n" +
                "Format: params={fields: [{field: 'price', direction: 'asc'}, {field: 'name', direction: 'desc'}]}\n" +
                "Direction values: 'asc' (default) or 'desc'");
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.SORT.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        List<Map<String, Object>> fieldsList = new ArrayList<>();
        for (Map<String, Object> f : sortFields) {
            String field = getString(f, "field", "name", "column", "key");
            String direction = getString(f, "direction", "order", "dir");
            if (direction == null) direction = "asc";
            if (field != null) {
                fieldsList.add(Map.of("field", field, "direction", direction));
            }
        }

        String input = getString(parameters, "input", "inputExpression", "items", "list");

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "sort");
        node.put("position", calculatePosition(session, NodeType.SORT));
        node.put("sort", Map.of("fields", fieldsList));
        if (input != null && !input.isBlank()) {
            Map<String, Object> sortParams = new LinkedHashMap<>();
            sortParams.put("input", input);
            node.put("params", sortParams);
        }

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.SORT, nodeId, node, connectAfter);

        return buildSuccessResponse("sort", nodeId, label, normalizedLabel, connectAfter,
            Map.of("fields_count", fieldsList.size(),
                   "fields", fieldsList.stream().map(f -> f.get("field") + " " + f.get("direction")).toList(),
                   "access_pattern", "{{core:" + normalizedLabel + ".output.sorted_items}}"),
            Map.of("fields", fieldsList));
    }

    // ==================== Limit ====================

    public ToolExecutionResult executeAddLimit(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "limit");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("limit");
        }

        // 2. Extract limit config
        Integer count = getInt(parameters, "count", "limit", "n", "size");
        if (count == null) count = 10;

        String from = getString(parameters, "from", "direction", "side");
        if (from == null) from = "first";
        if (!"first".equalsIgnoreCase(from) && !"last".equalsIgnoreCase(from)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "LIMIT: 'from' must be 'first' or 'last'. Got: '" + from + "'.\n" +
                "Example: params={count: 5, from: 'first'} or params={count: 10, from: 'last'}");
        }
        from = from.toLowerCase();

        Integer offset = getInt(parameters, "offset", "skip");
        if (offset == null) offset = 0;

        // input: the collection to trim. LimitNode requires it at runtime (fails with
        // "Input expression is required" when absent), so a limit node built without it
        // would always fail. Mirrors the sibling remove_duplicates node.
        String input = getString(parameters, "input", "inputExpression", "items", "list");

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.LIMIT.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> limitConfig = new LinkedHashMap<>();
        limitConfig.put("count", count);
        limitConfig.put("from", from);
        limitConfig.put("offset", offset);
        if (input != null && !input.isBlank()) limitConfig.put("input", input);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "limit");
        node.put("position", calculatePosition(session, NodeType.LIMIT));
        node.put("limit", limitConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.LIMIT, nodeId, node, connectAfter);

        return buildSuccessResponse("limit", nodeId, label, normalizedLabel, connectAfter,
            Map.of("count", count, "from", from, "offset", offset,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.items}}"),
            Map.of("count", count, "from", from, "offset", offset));
    }

    // ==================== Set ====================

    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddSet(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "set");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("set");
        }

        // 2. Extract assignments - accept multiple aliases
        List<Map<String, Object>> assignments = null;
        for (String key : List.of("assignments", "fields", "values", "set")) {
            Object value = parameters.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                assignments = (List<Map<String, Object>>) list;
                break;
            }
        }
        if (assignments == null || assignments.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "SET: 'assignments' is required.\n" +
                "Format: params={assignments: [{name: 'status', value: 'active', type: 'string'}, " +
                "{name: 'count', value: '{{mcp:fetch.output.total}}', type: 'number'}], keepOnlySet: false, input: '{{...}}'}\n" +
                "Types: string | number | boolean | json | auto");
        }

        // Normalize each assignment to {name, value, type}
        List<Map<String, Object>> assignmentsList = new ArrayList<>();
        for (Map<String, Object> a : assignments) {
            String name = getString(a, "name", "key", "field", "label");
            String value = getString(a, "value", "expression", "v");
            String type = getString(a, "type", "as");
            if (type == null) type = "auto";
            if (name != null && !name.isBlank()) {
                Map<String, Object> assignment = new LinkedHashMap<>();
                assignment.put("name", name);
                assignment.put("value", value);
                assignment.put("type", type);
                assignmentsList.add(assignment);
            }
        }
        if (assignmentsList.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "SET: at least one assignment with a 'name' is required");
        }

        Boolean keepOnlySet = getBoolean(parameters, "keepOnlySet", "keep_only_set", "only_set");
        if (keepOnlySet == null) keepOnlySet = false;
        String input = getString(parameters, "input", "inputExpression", "items", "list");

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.SET.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> setConfig = new LinkedHashMap<>();
        setConfig.put("assignments", assignmentsList);
        setConfig.put("keepOnlySet", keepOnlySet);
        if (input != null && !input.isBlank()) setConfig.put("input", input);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "set");
        node.put("position", calculatePosition(session, NodeType.SET));
        node.put("set", setConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.SET, nodeId, node, connectAfter);

        return buildSuccessResponse("set", nodeId, label, normalizedLabel, connectAfter,
            Map.of("assignments_count", assignmentsList.size(),
                   "keepOnlySet", keepOnlySet,
                   "fields", assignmentsList.stream().map(a -> a.get("name") + ":" + a.get("type")).toList(),
                   "access_pattern", "{{core:" + normalizedLabel + ".output.<field>}}"),
            setConfig);
    }

    // ==================== HtmlExtract ====================

    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddHtmlExtract(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "html_extract");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("html_extract");
        }

        // 2. Extract config
        String sourceHtml = getString(parameters, "sourceHtml", "source_html", "html", "input", "source");
        if (sourceHtml == null || sourceHtml.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "HTML_EXTRACT: 'sourceHtml' is required.\n" +
                "Format: params={sourceHtml: '{{mcp:fetch.output.body}}', extractionMode: 'multiple', " +
                "rootSelector: 'div.product', fields: [{name: 'title', selector: 'h2', attribute: 'text'}]}");
        }

        List<Map<String, Object>> fields = null;
        for (String key : List.of("fields", "selectors", "extract")) {
            Object value = parameters.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                fields = (List<Map<String, Object>>) list;
                break;
            }
        }
        if (fields == null || fields.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "HTML_EXTRACT: 'fields' is required (at least one).\n" +
                "Each field: {name, selector, attribute (text|html|<attrName>), transform (none|trim|lowercase|uppercase|number), required, default}");
        }

        // Normalize fields
        List<Map<String, Object>> fieldsList = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String name = getString(f, "name", "key", "label");
            String selector = getString(f, "selector", "css", "query");
            if (name == null || selector == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("selector", selector);
            String attribute = getString(f, "attribute", "attr");
            entry.put("attribute", attribute == null ? "text" : attribute);
            String transform = getString(f, "transform");
            entry.put("transform", transform == null ? "none" : transform);
            Boolean required = getBoolean(f, "required");
            entry.put("required", required != null && required);
            String defaultValue = getString(f, "default", "defaultValue");
            if (defaultValue != null) entry.put("default", defaultValue);
            fieldsList.add(entry);
        }
        if (fieldsList.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "HTML_EXTRACT: each field requires a 'name' and a 'selector'");
        }

        String extractionMode = getString(parameters, "extractionMode", "extraction_mode", "mode");
        if (extractionMode == null) extractionMode = "single";
        if (!"single".equalsIgnoreCase(extractionMode) && !"multiple".equalsIgnoreCase(extractionMode)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "HTML_EXTRACT: 'extractionMode' must be 'single' or 'multiple'");
        }
        String rootSelector = getString(parameters, "rootSelector", "root_selector", "root");
        Boolean cleanWhitespace = getBoolean(parameters, "cleanWhitespace", "clean_whitespace");
        if (cleanWhitespace == null) cleanWhitespace = true;

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.HTML_EXTRACT.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> htmlExtractConfig = new LinkedHashMap<>();
        htmlExtractConfig.put("sourceHtml", sourceHtml);
        htmlExtractConfig.put("extractionMode", extractionMode.toLowerCase());
        if (rootSelector != null && !rootSelector.isBlank()) htmlExtractConfig.put("rootSelector", rootSelector);
        htmlExtractConfig.put("fields", fieldsList);
        htmlExtractConfig.put("cleanWhitespace", cleanWhitespace);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "html_extract");
        node.put("position", calculatePosition(session, NodeType.HTML_EXTRACT));
        node.put("htmlExtract", htmlExtractConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.HTML_EXTRACT, nodeId, node, connectAfter);

        return buildSuccessResponse("html_extract", nodeId, label, normalizedLabel, connectAfter,
            Map.of("fields_count", fieldsList.size(),
                   "extractionMode", extractionMode.toLowerCase(),
                   "fields", fieldsList.stream().map(f -> f.get("name") + " <- " + f.get("selector")).toList(),
                   "access_pattern", "{{core:" + normalizedLabel + ".output.items}}"),
            htmlExtractConfig);
    }

    // ==================== Task CRUD ====================

    public ToolExecutionResult executeAddTask(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "task");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("task");
        }

        // 2. Extract operation
        String operation = getString(parameters, "operation", "op");
        if (operation == null || operation.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TASK: 'operation' is required.\n" +
                "Valid: create_task, get_task, update_task, delete_task, list_tasks\n" +
                "Format: params={operation: 'create_task', title: 'My Task', priority: 'normal'}");
        }
        if (!List.of("create_task", "get_task", "update_task", "delete_task", "list_tasks").contains(operation)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "TASK: unknown operation '" + operation + "'. Valid: create_task, get_task, update_task, delete_task, list_tasks");
        }

        // Operation-specific validation
        if ("create_task".equals(operation)) {
            String title = getString(parameters, "title");
            if (title == null || title.isBlank()) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TASK: 'title' is required for create_task operation.");
            }
        }
        if ("get_task".equals(operation) || "update_task".equals(operation) || "delete_task".equals(operation)) {
            String taskId = getString(parameters, "taskId", "task_id");
            if (taskId == null || taskId.isBlank()) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TASK: 'taskId' is required for " + operation + " operation.");
            }
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.TASK.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> taskConfig = new LinkedHashMap<>();
        taskConfig.put("operation", operation);
        putIfPresent(taskConfig, "taskId", getString(parameters, "taskId", "task_id"));
        putIfPresent(taskConfig, "title", getString(parameters, "title"));
        putIfPresent(taskConfig, "instructions", getString(parameters, "instructions", "description"));
        putIfPresent(taskConfig, "priority", getString(parameters, "priority"));
        putIfPresent(taskConfig, "agentId", getString(parameters, "agentId", "agent_id", "assignee"));
        putIfPresent(taskConfig, "reviewerAgentId", getString(parameters, "reviewerAgentId", "reviewer_agent_id", "reviewer"));
        putIfPresent(taskConfig, "status", getString(parameters, "status"));
        putIfPresent(taskConfig, "search", getString(parameters, "search"));
        Object limitObj = parameters.get("limit");
        if (limitObj instanceof Number n) taskConfig.put("limit", n.intValue());
        Object taskContextObj = parameters.get("taskContext");
        if (taskContextObj == null) taskContextObj = parameters.get("task_context");
        if (taskContextObj instanceof Map<?, ?> ctxMap && !ctxMap.isEmpty()) {
            taskConfig.put("taskContext", ctxMap);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "task");
        node.put("position", calculatePosition(session, NodeType.TASK));
        node.put("task", taskConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.TASK, nodeId, node, connectAfter);

        return buildSuccessResponse("task", nodeId, label, normalizedLabel, connectAfter,
            Map.of("operation", operation,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.task}}"),
            taskConfig);
    }

    private void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }

    // ==================== StopOnError ====================

    public ToolExecutionResult executeAddStopOnError(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "stop_on_error");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("stop_on_error");
        }

        // 2. Extract config
        String errorMessage = getString(parameters, "errorMessage", "error_message", "message");
        if (errorMessage == null || errorMessage.isBlank()) {
            errorMessage = "Workflow stopped due to error";
        }
        String errorCode = getString(parameters, "errorCode", "error_code", "code");

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.STOP_ON_ERROR.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> stopOnErrorConfig = new LinkedHashMap<>();
        stopOnErrorConfig.put("errorMessage", errorMessage);
        if (errorCode != null && !errorCode.isBlank()) {
            stopOnErrorConfig.put("errorCode", errorCode);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "stop_on_error");
        node.put("position", calculatePosition(session, NodeType.STOP_ON_ERROR));
        node.put("stopOnError", stopOnErrorConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.STOP_ON_ERROR, nodeId, node, connectAfter);

        return buildSuccessResponse("stop_on_error", nodeId, label, normalizedLabel, connectAfter,
            Map.of("error_message", errorMessage),
            stopOnErrorConfig);
    }

    // ==================== SSH ====================

    public ToolExecutionResult executeAddSsh(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "ssh");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("ssh");
        }

        // 2. Extract and validate config
        String host = getString(parameters, "host");
        String command = getString(parameters, "command", "cmd");
        if (host == null || host.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "SSH: 'host' is required.\n" +
                "Format: params={host: 'server.example.com', command: 'ls -la', username: 'user'}");
        }
        if (command == null || command.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "SSH: 'command' is required.\n" +
                "Format: params={host: 'server.example.com', command: 'ls -la', username: 'user'}");
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.SSH.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> sshConfig = new LinkedHashMap<>();
        sshConfig.put("host", host);
        sshConfig.put("command", command);
        putIfPresent(sshConfig, "username", getString(parameters, "username", "user"));
        putIfPresent(sshConfig, "authMethod", getString(parameters, "authMethod", "auth_method", "auth"));
        putIfPresent(sshConfig, "password", getString(parameters, "password"));
        putIfPresent(sshConfig, "privateKey", getString(parameters, "privateKey", "private_key", "key"));
        // port/timeout: coerce numeric strings too (LLMs routinely quote "22"); getInt
        // handles both Number and numeric String, unlike the old instanceof Number check
        // which silently dropped a quoted value.
        Integer port = getInt(parameters, "port");
        if (port != null) sshConfig.put("port", port);
        Integer timeout = getInt(parameters, "timeout");
        if (timeout != null) sshConfig.put("timeout", timeout);
        // credentialId: pin a stored SSH credential (runtime falls back when absent).
        // Coerced from a numeric string. A NON-numeric value is dropped here rather than
        // preserved: unlike the approval delegation (parsed field-by-field), this config is
        // deserialized whole by Jackson (parseConfigSafe), which hard-fails on a non-numeric
        // Long and would drop the ENTIRE node config, strictly worse than a per-field drop.
        Long credentialId = getLong(parameters, "credentialId", "credential_id");
        if (credentialId != null) sshConfig.put("credentialId", credentialId);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "ssh");
        node.put("position", calculatePosition(session, NodeType.SSH));
        node.put("ssh", sshConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.SSH, nodeId, node, connectAfter);

        return buildSuccessResponse("ssh", nodeId, label, normalizedLabel, connectAfter,
            Map.of("host", host, "command", command,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.stdout}}"),
            sshConfig);
    }

    // ==================== SFTP ====================

    public ToolExecutionResult executeAddSftp(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "sftp");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("sftp");
        }

        // 2. Extract and validate config
        String host = getString(parameters, "host");
        String operation = getString(parameters, "operation", "op");
        if (host == null || host.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "SFTP: 'host' is required.\n" +
                "Format: params={host: 'server.example.com', operation: 'list', remotePath: '/home/user'}");
        }
        if (operation == null || operation.isBlank()) {
            operation = "list";
        }
        if (!List.of("upload", "download", "list", "delete", "rename", "mkdir").contains(operation)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "SFTP: unknown operation '" + operation + "'. Valid: upload, download, list, delete, rename, mkdir");
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.SFTP.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> sftpConfig = new LinkedHashMap<>();
        sftpConfig.put("host", host);
        sftpConfig.put("operation", operation);
        putIfPresent(sftpConfig, "remotePath", getString(parameters, "remotePath", "remote_path", "path"));
        putIfPresent(sftpConfig, "username", getString(parameters, "username", "user"));
        putIfPresent(sftpConfig, "authMethod", getString(parameters, "authMethod", "auth_method", "auth"));
        putIfPresent(sftpConfig, "password", getString(parameters, "password"));
        putIfPresent(sftpConfig, "privateKey", getString(parameters, "privateKey", "private_key", "key"));
        putIfPresent(sftpConfig, "localContent", getString(parameters, "localContent", "local_content", "content"));
        putIfPresent(sftpConfig, "newPath", getString(parameters, "newPath", "new_path"));
        // port/timeout: coerce numeric strings too (see executeAddSsh).
        Integer port = getInt(parameters, "port");
        if (port != null) sftpConfig.put("port", port);
        Integer timeout = getInt(parameters, "timeout");
        if (timeout != null) sftpConfig.put("timeout", timeout);
        // credentialId: pin a stored SFTP credential; numeric-string coerced, non-numeric
        // dropped (see executeAddSsh for the Jackson whole-config rationale).
        Long credentialId = getLong(parameters, "credentialId", "credential_id");
        if (credentialId != null) sftpConfig.put("credentialId", credentialId);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "sftp");
        node.put("position", calculatePosition(session, NodeType.SFTP));
        node.put("sftp", sftpConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.SFTP, nodeId, node, connectAfter);

        // access_pattern is operation-specific:
        // - download → canonical FileRef under `output.file`
        // - list → array under `output.files`
        // - upload → byte count under `output.uploaded_size`
        // - rename → renamed path under `output.new_path`
        // - delete/mkdir → `output.success` is the only useful field
        // PR2 contract: the download path is a FileRef object (NOT a flat URL).
        String accessPattern = switch (operation) {
            case "download" -> "{{core:" + normalizedLabel + ".output.file}}";
            case "list" -> "{{core:" + normalizedLabel + ".output.files}}";
            case "upload" -> "{{core:" + normalizedLabel + ".output.uploaded_size}}";
            case "rename" -> "{{core:" + normalizedLabel + ".output.new_path}}";
            default -> "{{core:" + normalizedLabel + ".output.success}}";
        };
        return buildSuccessResponse("sftp", nodeId, label, normalizedLabel, connectAfter,
            Map.of("operation", operation,
                   "access_pattern", accessPattern),
            sftpConfig);
    }

    // ==================== Database ====================

    public ToolExecutionResult executeAddDatabase(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "database");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("database");
        }

        // 2. Extract and validate config
        String host = getString(parameters, "host");
        String databaseName = getString(parameters, "databaseName", "database_name", "database", "db_name");
        String query = getString(parameters, "query", "sql");
        if (host == null || host.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "DATABASE: 'host' is required.\n" +
                "Format: params={host: 'db.example.com', databaseName: 'mydb', query: 'SELECT * FROM users', operation: 'select'}");
        }
        if (databaseName == null || databaseName.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "DATABASE: 'databaseName' is required.\n" +
                "Format: params={host: 'db.example.com', databaseName: 'mydb', query: 'SELECT * FROM users'}");
        }
        if (query == null || query.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "DATABASE: 'query' is required.\n" +
                "Format: params={host: 'db.example.com', databaseName: 'mydb', query: 'SELECT * FROM users WHERE id = ?', queryParams: ['123']}");
        }

        String operation = getString(parameters, "operation", "op");
        if (operation == null || operation.isBlank()) {
            operation = "select";
        }
        if (!List.of("select", "insert", "update", "delete", "execute").contains(operation)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "DATABASE: unknown operation '" + operation + "'. Valid: select, insert, update, delete, execute");
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.DATABASE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> dbConfig = new LinkedHashMap<>();
        dbConfig.put("host", host);
        dbConfig.put("databaseName", databaseName);
        dbConfig.put("query", query);
        dbConfig.put("operation", operation);
        putIfPresent(dbConfig, "dbType", getString(parameters, "dbType", "db_type", "type"));
        putIfPresent(dbConfig, "username", getString(parameters, "username", "user"));
        putIfPresent(dbConfig, "password", getString(parameters, "password"));
        // port/timeout: coerce numeric strings too (see executeAddSsh).
        Integer port = getInt(parameters, "port");
        if (port != null) dbConfig.put("port", port);
        // sslEnabled: coerce "true"/"false" strings via getBoolean, not just Boolean.
        Boolean sslEnabled = getBoolean(parameters, "sslEnabled", "ssl_enabled", "ssl");
        if (sslEnabled != null) dbConfig.put("sslEnabled", sslEnabled);
        Integer timeout = getInt(parameters, "timeout");
        if (timeout != null) dbConfig.put("timeout", timeout);
        // credentialId: pin a stored database credential; numeric-string coerced, non-numeric
        // dropped (see executeAddSsh for the Jackson whole-config rationale).
        Long credentialId = getLong(parameters, "credentialId", "credential_id");
        if (credentialId != null) dbConfig.put("credentialId", credentialId);
        Object paramsObj = parameters.get("queryParams");
        if (paramsObj == null) paramsObj = parameters.get("query_params");
        if (paramsObj instanceof List<?> paramsList) {
            dbConfig.put("queryParams", paramsList);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "database");
        node.put("position", calculatePosition(session, NodeType.DATABASE));
        node.put("database", dbConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.DATABASE, nodeId, node, connectAfter);

        return buildSuccessResponse("database", nodeId, label, normalizedLabel, connectAfter,
            Map.of("operation", operation,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.rows}}"),
            dbConfig);
    }

    // ==================== RemoveDuplicates ====================

    public ToolExecutionResult executeAddRemoveDuplicates(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "remove_duplicates");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("remove_duplicates");
        }

        // 2. Extract fields and keep strategy
        List<String> fields = new ArrayList<>();
        Object fieldsObj = parameters.get("fields");
        if (fieldsObj instanceof List<?> fieldsList) {
            for (Object f : fieldsList) {
                if (f instanceof String s && !s.isBlank()) {
                    fields.add(s);
                }
            }
        }

        String keep = getString(parameters, "keep");
        if (keep == null || (!keep.equalsIgnoreCase("first") && !keep.equalsIgnoreCase("last"))) {
            keep = "first";
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.REMOVE_DUPLICATES.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> removeDuplicatesConfig = new LinkedHashMap<>();
        removeDuplicatesConfig.put("fields", fields);
        removeDuplicatesConfig.put("keep", keep);
        String inputExpr = getString(parameters, "input");
        if (inputExpr != null && !inputExpr.isBlank()) {
            removeDuplicatesConfig.put("input", inputExpr);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "remove_duplicates");
        node.put("position", calculatePosition(session, NodeType.REMOVE_DUPLICATES));
        node.put("removeDuplicates", removeDuplicatesConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.REMOVE_DUPLICATES, nodeId, node, connectAfter);

        return buildSuccessResponse("remove_duplicates", nodeId, label, normalizedLabel, connectAfter,
            Map.of("fields", fields,
                   "keep", keep,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.items}}"),
            Map.of("fields", fields, "keep", keep));
    }

    // ==================== Summarize ====================

    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddSummarize(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "summarize");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("summarize");
        }

        // 2. Extract aggregations and groupBy
        List<Map<String, Object>> aggregations = new ArrayList<>();
        Object aggObj = parameters.get("aggregations");
        if (aggObj instanceof List<?> aggList) {
            for (Object a : aggList) {
                if (a instanceof Map<?, ?> m) {
                    aggregations.add((Map<String, Object>) m);
                }
            }
        }

        List<String> groupBy = new ArrayList<>();
        Object groupByObj = parameters.get("groupBy");
        if (groupByObj == null) groupByObj = parameters.get("group_by");
        if (groupByObj instanceof List<?> groupByList) {
            for (Object g : groupByList) {
                if (g instanceof String s && !s.isBlank()) {
                    groupBy.add(s);
                }
            }
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.SUMMARIZE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> summarizeConfig = new LinkedHashMap<>();
        summarizeConfig.put("aggregations", aggregations);
        summarizeConfig.put("groupBy", groupBy);
        String summarizeInputExpr = getString(parameters, "input");
        if (summarizeInputExpr != null && !summarizeInputExpr.isBlank()) {
            summarizeConfig.put("input", summarizeInputExpr);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "summarize");
        node.put("position", calculatePosition(session, NodeType.SUMMARIZE));
        node.put("summarize", summarizeConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.SUMMARIZE, nodeId, node, connectAfter);

        return buildSuccessResponse("summarize", nodeId, label, normalizedLabel, connectAfter,
            Map.of("aggregations", aggregations,
                   "groupBy", groupBy,
                   "access_pattern", "{{core:" + normalizedLabel + ".output}}"),
            Map.of("aggregations", aggregations, "groupBy", groupBy));
    }

    // ==================== DateTime ====================

    public ToolExecutionResult executeAddDateTime(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "date_time");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("date_time");
        }

        // 2. Extract config
        String operation = getString(parameters, "operation");
        if (operation == null) operation = "format";

        String value = getString(parameters, "value");
        String inputFormat = getString(parameters, "inputFormat", "input_format");
        String outputFormat = getString(parameters, "outputFormat", "output_format");
        String timezone = getString(parameters, "timezone");
        String targetTimezone = getString(parameters, "targetTimezone", "target_timezone");
        String durationUnit = getString(parameters, "durationUnit", "duration_unit");
        Integer durationAmount = getInt(parameters, "durationAmount", "duration_amount");
        String secondValue = getString(parameters, "secondValue", "second_value");
        String extractPart = getString(parameters, "extractPart", "extract_part");

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.DATE_TIME.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> dateTimeConfig = new LinkedHashMap<>();
        dateTimeConfig.put("operation", operation);
        if (value != null) dateTimeConfig.put("value", value);
        if (inputFormat != null) dateTimeConfig.put("inputFormat", inputFormat);
        if (outputFormat != null) dateTimeConfig.put("outputFormat", outputFormat);
        if (timezone != null) dateTimeConfig.put("timezone", timezone);
        if (targetTimezone != null) dateTimeConfig.put("targetTimezone", targetTimezone);
        if (durationUnit != null) dateTimeConfig.put("durationUnit", durationUnit);
        if (durationAmount != null) dateTimeConfig.put("durationAmount", durationAmount);
        if (secondValue != null) dateTimeConfig.put("secondValue", secondValue);
        if (extractPart != null) dateTimeConfig.put("extractPart", extractPart);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "date_time");
        node.put("position", calculatePosition(session, NodeType.DATE_TIME));
        node.put("dateTime", dateTimeConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.DATE_TIME, nodeId, node, connectAfter);

        return buildSuccessResponse("date_time", nodeId, label, normalizedLabel, connectAfter,
            Map.of("operation", operation,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.result}}"),
            dateTimeConfig);
    }

    // ==================== CryptoJWT ====================

    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddCryptoJwt(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "crypto_jwt");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return triggerRequiredError("crypto_jwt");
        }

        // 2. Extract config
        String operation = getString(parameters, "operation");
        if (operation == null) operation = "hash";

        String algorithm = getString(parameters, "algorithm");
        if (algorithm == null) algorithm = "SHA-256";

        String value = getString(parameters, "value");
        String key = getString(parameters, "key");
        String secret = getString(parameters, "secret");
        String token = getString(parameters, "token");
        String encoding = getString(parameters, "encoding");

        Map<String, Object> payload = null;
        Object payloadObj = parameters.get("payload");
        if (payloadObj instanceof Map<?, ?> m) {
            payload = (Map<String, Object>) m;
        }

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.CRYPTO_JWT.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> cryptoConfig = new LinkedHashMap<>();
        cryptoConfig.put("operation", operation);
        cryptoConfig.put("algorithm", algorithm);
        if (value != null) cryptoConfig.put("value", value);
        if (key != null) cryptoConfig.put("key", key);
        if (secret != null) cryptoConfig.put("secret", secret);
        if (token != null) cryptoConfig.put("token", token);
        if (payload != null) cryptoConfig.put("payload", payload);
        if (encoding != null) cryptoConfig.put("encoding", encoding);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "crypto_jwt");
        node.put("position", calculatePosition(session, NodeType.CRYPTO_JWT));
        node.put("cryptoJwt", cryptoConfig);

        // 4. Add and finalize
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.CRYPTO_JWT, nodeId, node, connectAfter);

        return buildSuccessResponse("crypto_jwt", nodeId, label, normalizedLabel, connectAfter,
            Map.of("operation", operation,
                   "algorithm", algorithm,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.result}}"),
            cryptoConfig);
    }

    // ==================== XML ====================

    public ToolExecutionResult executeAddXml(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "xml");
        if (labelError != null) return labelError;
        if (session.getTriggers().isEmpty()) return triggerRequiredError("xml");

        String operation = getString(parameters, "operation");
        if (operation == null) operation = "xmlToJson";
        String value = getString(parameters, "value");
        String rootElement = getString(parameters, "rootElement", "root_element");
        // #X1: propagate preserveAttributes (and camelCase/snake_case aliases)
        // through to XmlConfig - otherwise user-supplied flag is silently dropped.
        Boolean preserveAttributes = getBoolean(parameters, "preserveAttributes", "preserve_attributes");

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.XML.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> xmlConfig = new LinkedHashMap<>();
        xmlConfig.put("operation", operation);
        if (value != null) xmlConfig.put("value", value);
        if (rootElement != null) xmlConfig.put("rootElement", rootElement);
        if (preserveAttributes != null) xmlConfig.put("preserveAttributes", preserveAttributes);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "xml");
        node.put("position", calculatePosition(session, NodeType.XML));
        node.put("xml", xmlConfig);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.XML, nodeId, node, connectAfter);

        return buildSuccessResponse("xml", nodeId, label, normalizedLabel, connectAfter,
            Map.of("operation", operation, "access_pattern", "{{core:" + normalizedLabel + ".output.result}}"),
            xmlConfig);
    }

    // ==================== Compression ====================

    public ToolExecutionResult executeAddCompression(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "compression");
        if (labelError != null) return labelError;
        if (session.getTriggers().isEmpty()) return triggerRequiredError("compression");

        String operation = getString(parameters, "operation");
        if (operation == null) operation = "compress";
        String format = getString(parameters, "format");
        if (format == null) format = "gzip";
        String value = getString(parameters, "value");
        String filename = getString(parameters, "filename");

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.COMPRESSION.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> compressionConfig = new LinkedHashMap<>();
        compressionConfig.put("operation", operation);
        compressionConfig.put("format", format);
        if (value != null) compressionConfig.put("value", value);
        if (filename != null) compressionConfig.put("filename", filename);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "compression");
        node.put("position", calculatePosition(session, NodeType.COMPRESSION));
        node.put("compression", compressionConfig);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.COMPRESSION, nodeId, node, connectAfter);

        // access_pattern points to the canonical FileRef under `output.file`
        // (compress operation only; decompress + failures resolve to null). This is
        // the wiring the LLM agent should copy into interface variable_mapping so the
        // renderer auto-converts it to a proxy URL / HMAC-signed URL. `output.result`
        // also exists (inline base64 archive bytes for compress / plain string for
        // decompress) but is NOT the right field for `<img src>` / `<a href>` -
        // PR2 contract.
        return buildSuccessResponse("compression", nodeId, label, normalizedLabel, connectAfter,
            Map.of("operation", operation, "format", format,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.file}}"),
            compressionConfig);
    }

    // ==================== RSS ====================

    public ToolExecutionResult executeAddRss(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "rss");
        if (labelError != null) return labelError;
        if (session.getTriggers().isEmpty()) return triggerRequiredError("rss");

        String url = getString(parameters, "url");
        Integer maxItems = getInt(parameters, "maxItems", "max_items");
        if (maxItems == null) maxItems = 20;

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.RSS.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> rssConfig = new LinkedHashMap<>();
        if (url != null) rssConfig.put("url", url);
        rssConfig.put("maxItems", maxItems);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "rss");
        node.put("position", calculatePosition(session, NodeType.RSS));
        node.put("rss", rssConfig);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.RSS, nodeId, node, connectAfter);

        return buildSuccessResponse("rss", nodeId, label, normalizedLabel, connectAfter,
            Map.of("url", url != null ? url : "",
                   "maxItems", maxItems,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.items}}"),
            rssConfig);
    }

    // ==================== ConvertToFile ====================

    public ToolExecutionResult executeAddConvertToFile(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "convert_to_file");
        if (labelError != null) return labelError;
        if (session.getTriggers().isEmpty()) return triggerRequiredError("convert_to_file");

        String format = getString(parameters, "format");
        if (format == null) format = "csv";
        String value = getString(parameters, "value");
        String filename = getString(parameters, "filename");
        if (filename == null) filename = "export";
        String delimiter = getString(parameters, "delimiter");
        if (delimiter == null) delimiter = ",";
        Boolean includeHeadersBool = getBoolean(parameters, "includeHeaders", "include_headers");
        String includeHeaders = (includeHeadersBool == null || includeHeadersBool) ? "yes" : "no";

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.CONVERT_TO_FILE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("format", format);
        if (value != null) config.put("value", value);
        config.put("filename", filename);
        config.put("delimiter", delimiter);
        config.put("includeHeaders", includeHeaders);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "convert_to_file");
        node.put("position", calculatePosition(session, NodeType.CONVERT_TO_FILE));
        node.put("convertToFile", config);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.CONVERT_TO_FILE, nodeId, node, connectAfter);

        // access_pattern points to the canonical FileRef under `output.file`.
        // `output.result` (inline xlsx-base64 / plain csv/json/txt text) exists too
        // but is NOT the right field for `<img src>` / `<a href>` in interfaces -
        // the auto-rewriter only converts FileRef objects to proxy URLs. PR2 contract.
        return buildSuccessResponse("convert_to_file", nodeId, label, normalizedLabel, connectAfter,
            Map.of("format", format,
                   "filename", filename,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.file}}"),
            config);
    }

    // ==================== ExtractFromFile ====================

    public ToolExecutionResult executeAddExtractFromFile(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "extract_from_file");
        if (labelError != null) return labelError;
        if (session.getTriggers().isEmpty()) return triggerRequiredError("extract_from_file");

        String format = getString(parameters, "format");
        if (format == null) format = "csv";
        String value = getString(parameters, "value");
        String delimiter = getString(parameters, "delimiter");
        if (delimiter == null) delimiter = ",";
        String sheetName = getString(parameters, "sheetName", "sheet_name");
        Boolean hasHeadersBool = getBoolean(parameters, "hasHeaders", "has_headers");
        String hasHeaders = (hasHeadersBool == null || hasHeadersBool) ? "yes" : "no";

        // Text mode parameters
        String mode = getString(parameters, "mode");
        if (mode == null) mode = "structured";
        Boolean chunking = getBoolean(parameters, "chunking");
        Integer chunkSize = parseInteger(getString(parameters, "chunkSize", "chunk_size"));
        Integer overlap = parseInteger(getString(parameters, "overlap"));
        String chunkingStrategy = getString(parameters, "chunkingStrategy", "chunking_strategy");
        String chunkUnit = getString(parameters, "chunkUnit", "chunk_unit");
        String separator = getString(parameters, "separator");

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.EXTRACT_FROM_FILE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("format", format);
        if (value != null) config.put("value", value);
        config.put("mode", mode);
        if ("text".equals(mode)) {
            if (chunking != null) config.put("chunking", chunking);
            if (chunkSize != null) config.put("chunkSize", chunkSize);
            if (overlap != null) config.put("overlap", overlap);
            if (chunkingStrategy != null) config.put("chunkingStrategy", chunkingStrategy);
            if (chunkUnit != null) config.put("chunkUnit", chunkUnit);
            if (separator != null) config.put("separator", separator);
        } else {
            config.put("delimiter", delimiter);
            if (sheetName != null) config.put("sheetName", sheetName);
            config.put("hasHeaders", hasHeaders);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "extract_from_file");
        node.put("position", calculatePosition(session, NodeType.EXTRACT_FROM_FILE));
        node.put("extractFromFile", config);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.EXTRACT_FROM_FILE, nodeId, node, connectAfter);

        return buildSuccessResponse("extract_from_file", nodeId, label, normalizedLabel, connectAfter,
            Map.of("format", format,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.items}}"),
            config);
    }

    // ==================== CompareDatasets ====================

    public ToolExecutionResult executeAddCompareDatasets(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "compare_datasets");
        if (labelError != null) return labelError;
        if (session.getTriggers().isEmpty()) return triggerRequiredError("compare_datasets");

        String inputA = getString(parameters, "inputA", "input_a");
        String inputB = getString(parameters, "inputB", "input_b");
        @SuppressWarnings("unchecked")
        List<String> matchFields = (List<String>) parameters.get("matchFields");
        if (matchFields == null) {
            @SuppressWarnings("unchecked")
            List<String> altFields = (List<String>) parameters.get("match_fields");
            matchFields = altFields != null ? altFields : List.of();
        }
        Boolean returnMatched = getBoolean(parameters, "returnMatched", "return_matched");
        if (returnMatched == null) returnMatched = true;
        Boolean returnOnlyA = getBoolean(parameters, "returnOnlyA", "return_only_a");
        if (returnOnlyA == null) returnOnlyA = true;
        Boolean returnOnlyB = getBoolean(parameters, "returnOnlyB", "return_only_b");
        if (returnOnlyB == null) returnOnlyB = true;

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.COMPARE_DATASETS.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> config = new LinkedHashMap<>();
        if (inputA != null) config.put("inputA", inputA);
        if (inputB != null) config.put("inputB", inputB);
        config.put("matchFields", matchFields);
        config.put("returnMatched", returnMatched);
        config.put("returnOnlyA", returnOnlyA);
        config.put("returnOnlyB", returnOnlyB);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "compare_datasets");
        node.put("position", calculatePosition(session, NodeType.COMPARE_DATASETS));
        node.put("compareDatasets", config);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.COMPARE_DATASETS, nodeId, node, connectAfter);

        return buildSuccessResponse("compare_datasets", nodeId, label, normalizedLabel, connectAfter,
            Map.of("matchFields", matchFields,
                   "access_matched", "{{core:" + normalizedLabel + ".output.matched}}",
                   "access_onlyA", "{{core:" + normalizedLabel + ".output.onlyInA}}",
                   "access_onlyB", "{{core:" + normalizedLabel + ".output.onlyInB}}"),
            config);
    }

    // ==================== SubWorkflow ====================

    public ToolExecutionResult executeAddSubWorkflow(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "sub_workflow");
        if (labelError != null) return labelError;

        String workflowId = getString(parameters, "workflowId", "workflow_id", "target_workflow");
        String inputMapping = getString(parameters, "inputMapping", "input_mapping", "input");
        Integer timeoutSeconds = getInt(parameters, "timeoutSeconds", "timeout_seconds", "timeout");
        if (timeoutSeconds == null) timeoutSeconds = 300;
        Integer maxDepth = getInt(parameters, "maxDepth", "max_depth");
        if (maxDepth == null) maxDepth = 5;

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.SUB_WORKFLOW.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> config = new LinkedHashMap<>();
        if (workflowId != null) config.put("workflowId", workflowId);
        if (inputMapping != null) config.put("inputMapping", inputMapping);
        config.put("timeoutSeconds", timeoutSeconds);
        config.put("maxDepth", maxDepth);

        // Resolve workflow name for frontend display
        if (workflowId != null) {
            try {
                UUID wfUuid = UUID.fromString(workflowId);
                workflowRepository.findById(wfUuid)
                    .ifPresent(wf -> config.put("workflowName", wf.getName()));
            } catch (IllegalArgumentException e) {
                // Invalid UUID - skip name resolution
            }
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "sub_workflow");
        node.put("position", calculatePosition(session, NodeType.SUB_WORKFLOW));
        node.put("subWorkflow", config);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.SUB_WORKFLOW, nodeId, node, connectAfter);

        return buildSuccessResponse("sub_workflow", nodeId, label, normalizedLabel, connectAfter,
            Map.of("workflowId", workflowId != null ? workflowId : "not_set",
                   "timeoutSeconds", timeoutSeconds,
                   "access_result", "{{core:" + normalizedLabel + ".output.result}}"),
            config);
    }

    // ==================== RespondToWebhook ====================

    public ToolExecutionResult executeAddRespondToWebhook(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "respond_to_webhook");
        if (labelError != null) return labelError;

        Integer statusCode = getInt(parameters, "statusCode", "status_code", "status");
        if (statusCode == null) statusCode = 200;
        String body = getString(parameters, "body", "response_body", "response");
        String contentType = getString(parameters, "contentType", "content_type");
        if (contentType == null) contentType = "application/json";

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.RESPOND_TO_WEBHOOK.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("statusCode", statusCode);
        if (body != null) config.put("body", body);
        config.put("contentType", contentType);
        // headers: RespondToWebhookNode applies these custom response headers; forward them
        // so the documented `headers` param is not silently dropped by the builder.
        Object headers = parameters.get("headers");
        if (headers instanceof Map<?, ?> headerMap && !headerMap.isEmpty()) {
            config.put("headers", headerMap);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "respond_to_webhook");
        node.put("position", calculatePosition(session, NodeType.RESPOND_TO_WEBHOOK));
        node.put("respondToWebhook", config);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.RESPOND_TO_WEBHOOK, nodeId, node, connectAfter);

        return buildSuccessResponse("respond_to_webhook", nodeId, label, normalizedLabel, connectAfter,
            Map.of("statusCode", statusCode,
                   "contentType", contentType,
                   "note", "This node sends the HTTP response to the webhook caller. Must be in a webhook-triggered workflow."),
            config);
    }

    // ==================== Send Email ====================

    public ToolExecutionResult executeAddSendEmail(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "send_email");
        if (labelError != null) return labelError;
        if (session.getTriggers().isEmpty()) return triggerRequiredError("send_email");

        // Per-email fields only - SMTP credentials come from Settings > Credentials
        String toEmail = getString(parameters, "toEmail", "to_email", "to");
        String ccEmail = getString(parameters, "ccEmail", "cc_email", "cc");
        String bccEmail = getString(parameters, "bccEmail", "bcc_email", "bcc");
        String fromName = getString(parameters, "fromName", "from_name");
        String subject = getString(parameters, "subject");
        String body = getString(parameters, "body", "content", "message");
        Boolean isHtml = getBoolean(parameters, "isHtml", "is_html", "html");
        if (isHtml == null) isHtml = false;
        String fromEmail = getString(parameters, "fromEmail", "from_email");
        String replyTo = getString(parameters, "replyTo", "reply_to");
        // Reply threading - pass the original message's messageId (from email_inbox output)
        String inReplyTo = getString(parameters, "inReplyTo", "in_reply_to", "replyToMessageId");
        String references = getString(parameters, "references");
        // credentialId: pin a specific SMTP credential (runtime falls back to the default
        // when absent). Numeric strings are coerced (LLMs routinely quote numbers); a
        // non-numeric value is dropped rather than preserved, because this config is
        // deserialized whole by Jackson (parseConfigSafe) which hard-fails on a non-numeric
        // Long and would drop the ENTIRE node config.
        Long credentialId = getLong(parameters, "credentialId", "credential_id");

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.SEND_EMAIL.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> config = new LinkedHashMap<>();
        if (toEmail != null) config.put("toEmail", toEmail);
        if (ccEmail != null) config.put("ccEmail", ccEmail);
        if (bccEmail != null) config.put("bccEmail", bccEmail);
        if (fromName != null) config.put("fromName", fromName);
        if (fromEmail != null) config.put("fromEmail", fromEmail);
        if (replyTo != null) config.put("replyTo", replyTo);
        if (subject != null) config.put("subject", subject);
        if (body != null) config.put("body", body);
        config.put("isHtml", isHtml);
        if (inReplyTo != null) config.put("inReplyTo", inReplyTo);
        if (references != null) config.put("references", references);
        if (credentialId != null) config.put("credentialId", credentialId);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "send_email");
        node.put("position", calculatePosition(session, NodeType.SEND_EMAIL));
        node.put("sendEmail", config);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.SEND_EMAIL, nodeId, node, connectAfter);

        return buildSuccessResponse("send_email", nodeId, label, normalizedLabel, connectAfter,
            Map.of("toEmail", toEmail != null ? toEmail : "",
                   "subject", subject != null ? subject : "",
                   "note", "SMTP credentials are loaded from Settings > Credentials (integration: smtp)",
                   "access_pattern", "{{core:" + normalizedLabel + ".output.sent}}"),
            config);
    }

    // ==================== Email Inbox ====================

    public ToolExecutionResult executeAddEmailInbox(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "email_inbox");
        if (labelError != null) return labelError;
        if (session.getTriggers().isEmpty()) return triggerRequiredError("email_inbox");

        // IMAP credentials come from Settings > Credentials. Per-node fields only.
        String folder = getString(parameters, "folder", "mailbox");
        Boolean unreadOnly = getBoolean(parameters, "unreadOnly", "unread_only", "unread");
        Integer limit = getInt(parameters, "limit", "max", "count");
        Boolean markSeen = getBoolean(parameters, "markSeen", "mark_seen");
        Integer sinceDays = getInt(parameters, "sinceDays", "since_days");
        String action = getString(parameters, "action");
        String messageUid = getString(parameters, "messageUid", "message_uid", "uid");
        String targetFolder = getString(parameters, "targetFolder", "target_folder", "destination");
        Boolean createTargetIfMissing = getBoolean(parameters, "createTargetIfMissing", "create_target_if_missing");
        // READ-mode search filters + attachments
        String fromContains = getString(parameters, "fromContains", "from_contains", "from");
        String subjectContains = getString(parameters, "subjectContains", "subject_contains", "subject");
        String bodyContains = getString(parameters, "bodyContains", "body_contains");
        Boolean flaggedOnly = getBoolean(parameters, "flaggedOnly", "flagged_only", "flagged");
        Integer beforeDays = getInt(parameters, "beforeDays", "before_days");
        Boolean downloadAttachments = getBoolean(parameters, "downloadAttachments", "download_attachments", "attachments");
        // credentialId: pin a specific IMAP credential (runtime falls back to the default
        // when absent). Numeric strings are coerced; a non-numeric value is dropped rather
        // than preserved (see executeAddSendEmail for the Jackson whole-config rationale).
        Long credentialId = getLong(parameters, "credentialId", "credential_id");

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.EMAIL_INBOX.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> config = new LinkedHashMap<>();
        if (folder != null) config.put("folder", folder);
        if (unreadOnly != null) config.put("unreadOnly", unreadOnly);
        if (limit != null) config.put("limit", limit);
        if (markSeen != null) config.put("markSeen", markSeen);
        if (sinceDays != null) config.put("sinceDays", sinceDays);
        if (action != null) config.put("action", action);
        if (messageUid != null) config.put("messageUid", messageUid);
        if (targetFolder != null) config.put("targetFolder", targetFolder);
        if (createTargetIfMissing != null) config.put("createTargetIfMissing", createTargetIfMissing);
        if (fromContains != null) config.put("fromContains", fromContains);
        if (subjectContains != null) config.put("subjectContains", subjectContains);
        if (bodyContains != null) config.put("bodyContains", bodyContains);
        if (flaggedOnly != null) config.put("flaggedOnly", flaggedOnly);
        if (beforeDays != null) config.put("beforeDays", beforeDays);
        if (downloadAttachments != null) config.put("downloadAttachments", downloadAttachments);
        if (credentialId != null) config.put("credentialId", credentialId);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "email_inbox");
        node.put("position", calculatePosition(session, NodeType.EMAIL_INBOX));
        node.put("emailInbox", config);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.EMAIL_INBOX, nodeId, node, connectAfter);

        String resolvedAction = (action == null || action.isBlank()) ? "none" : action;
        return buildSuccessResponse("email_inbox", nodeId, label, normalizedLabel, connectAfter,
            Map.of("folder", folder != null ? folder : "INBOX",
                   "action", resolvedAction,
                   "note", "IMAP credentials are loaded from Settings > Credentials (integration: imap). IMAP reads/acts on a mailbox; use send_email to SEND mail.",
                   "access_pattern", "none".equals(resolvedAction)
                       ? "{{core:" + normalizedLabel + ".output.messages}}"
                       : "{{core:" + normalizedLabel + ".output.success}}"),
            config);
    }

    // ==================== Code ====================

    public ToolExecutionResult executeAddCode(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String label = getString(parameters, "label", "name");
        var labelError = validateLabel(label, "code");
        if (labelError != null) return labelError;
        if (session.getTriggers().isEmpty()) return triggerRequiredError("code");

        String language = getString(parameters, "language", "lang");
        if (language == null) language = "javascript";
        String code = getString(parameters, "code", "script", "source");
        Integer timeoutSeconds = getInt(parameters, "timeoutSeconds", "timeout_seconds", "timeout");
        if (timeoutSeconds == null) timeoutSeconds = 10;

        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.CODE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("language", language);
        if (code != null) config.put("code", code);
        config.put("timeoutSeconds", timeoutSeconds);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("type", "code");
        node.put("position", calculatePosition(session, NodeType.CODE));
        node.put("code", config);

        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(node));
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.CODE, nodeId, node, connectAfter);

        return buildSuccessResponse("code", nodeId, label, normalizedLabel, connectAfter,
            Map.of("language", language,
                   "timeoutSeconds", timeoutSeconds,
                   "access_pattern", "{{core:" + normalizedLabel + ".output.result}}"),
            config);
    }

    // ==================== Helpers ====================

    private ToolExecutionResult triggerRequiredError(String nodeType) {
        return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Cannot add " + nodeType + " without a trigger. " +
            "Create a trigger first: workflow(action='add_node', type='form', label='...', params={...})");
    }

    private ToolExecutionResult buildSuccessResponse(String nodeType, String nodeId, String label,
                                                      String normalizedLabel, String connectAfter,
                                                      Map<String, Object> extras) {
        return buildSuccessResponse(nodeType, nodeId, label, normalizedLabel, connectAfter, extras, null);
    }

    private ToolExecutionResult buildSuccessResponse(String nodeType, String nodeId, String label,
                                                      String normalizedLabel, String connectAfter,
                                                      Map<String, Object> extras, Map<String, Object> savedParams) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("node_type", nodeType);
        response.put("node_id", nodeId);
        response.put("label", label);
        response.putAll(extras);

        // Enrich with outputs from node_type_documentation (authoritative source)
        nodeLibraryService.findByType(nodeType)
            .map(NodeTypeDocumentationEntity::getOutputs)
            .filter(outputs -> outputs != null && !outputs.isEmpty())
            .ifPresent(outputs -> {
                Map<String, String> outputRefs = new LinkedHashMap<>();
                for (String field : outputs.keySet()) {
                    outputRefs.put(field, "{{" + nodeId + ".output." + field + "}}");
                }
                response.put("outputs", outputRefs);
            });

        // Show saved params so LLM knows what was actually stored
        if (savedParams != null && !savedParams.isEmpty()) {
            response.put("saved_params", savedParams);
        }

        response.put("connection", Map.of(
            "status", connectAfter != null ? "connected" : "orphaned",
            "connected_after", connectAfter != null ? connectAfter : "none"
        ));

        response.put("NEXT", Map.of(
            "pattern", "workflow(action='add_node', type='...', label='...', connect_after='" + label + "')"
        ));

        return ToolExecutionResult.success(response);
    }

    private String formatDuration(int ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%.1f min", ms / 60000.0);
    }

    /**
     * Parse duration from string ("30s", "5m", "1h", "1d") or integer (milliseconds).
     */
    private Integer parseDuration(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            s = s.trim().toLowerCase();
            if (s.isEmpty()) return null;
            try {
                // Try parsing as plain integer (milliseconds)
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {}
            // Parse duration string: 30s, 5m, 1h, 1d
            try {
                if (s.endsWith("ms")) {
                    return Integer.parseInt(s.substring(0, s.length() - 2).trim());
                } else if (s.endsWith("s")) {
                    return (int) (Double.parseDouble(s.substring(0, s.length() - 1).trim()) * 1000);
                } else if (s.endsWith("m")) {
                    return (int) (Double.parseDouble(s.substring(0, s.length() - 1).trim()) * 60 * 1000);
                } else if (s.endsWith("h")) {
                    return (int) (Double.parseDouble(s.substring(0, s.length() - 1).trim()) * 60 * 60 * 1000);
                } else if (s.endsWith("d")) {
                    return (int) (Double.parseDouble(s.substring(0, s.length() - 1).trim()) * 24 * 60 * 60 * 1000);
                }
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    // getString inherited from CreatorBase

    private Integer getInt(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof Number n) return n.intValue();
            if (value instanceof String s) {
                try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private Long getLong(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Long value = toLongOrNull(params.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private Boolean getBoolean(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof Boolean b) return b;
            if (value instanceof String s) {
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
            }
        }
        return null;
    }

    private Integer parseInteger(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extract mappings from parameters, supporting multiple formats:
     * - mappings: [{label, expression}]           (canonical array format)
     * - outputs: {fieldName: expression}          (object format - LLM friendly)
     * - fields: [{label, expression}]             (alias)
     * - transformations: [{label, expression}]    (alias)
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getMappings(Map<String, Object> parameters) {
        // Try array formats first: mappings, fields, transformations + LLM aliases
        for (String key : List.of("mappings", "fields", "transformations", "values", "aggregations", "collect")) {
            Object value = parameters.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return (List<Map<String, Object>>) list;
            }
        }

        // Try object format: outputs = {key: expression} -> convert to [{label, expression}]
        Object outputs = parameters.get("outputs");
        if (outputs instanceof Map<?, ?> outputsMap && !outputsMap.isEmpty()) {
            List<Map<String, Object>> converted = new ArrayList<>();
            for (Map.Entry<?, ?> entry : outputsMap.entrySet()) {
                String fieldLabel = String.valueOf(entry.getKey());
                String expression = String.valueOf(entry.getValue());
                converted.add(Map.of("label", fieldLabel, "expression", expression));
            }
            log.info("Converted outputs object format to mappings array: {} fields", converted.size());
            return converted;
        }

        return null;
    }
}
