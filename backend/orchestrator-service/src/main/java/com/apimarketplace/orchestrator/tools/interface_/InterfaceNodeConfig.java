package com.apimarketplace.orchestrator.tools.interface_;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Type-safe configuration for interface nodes in workflow builder.
 * Centralizes all string key access for interface node parameters,
 * eliminating scattered Map<String, Object> key lookups.
 *
 * Used by:
 * - UtilityNodeCreator (or future InterfaceNodeCreator) when adding interface nodes
 * - WorkflowBuilderProvider when modifying interface nodes
 * - NodeParamsValidator when validating interface node params
 *
 * Supported keys extracted from raw parameters:
 * - interface_id / id: UUID of the interface entity
 * - variable_mapping: generic template var -> workflow expression
 * - action_mapping: CSS selector -> trigger binding (trigger:label:actiontype)
 * - is_entry_interface / isEntryInterface: boolean - show this interface first
 * - generate_screenshot / generateScreenshot: boolean - emit `screenshot` FileRef output
 * - expose_rendered_source / exposeRenderedSource: boolean - emit `rendered_html`/`rendered_css`/`rendered_js`
 * - generate_pdf / generatePdf: boolean - emit `pdf` FileRef output (PDF of the rendered interface)
 * - pdf_format / pdfFormat: string - page size for the PDF (A4, Letter, Legal); default A4
 * - pdf_landscape / pdfLandscape: boolean - landscape orientation for the PDF; default false
 * - generate_video / generateVideo: boolean - emit `video` FileRef output (MP4 recording of the interface)
 * - video_preset / videoPreset: string - capture format (vertical, horizontal, square); default vertical
 * - video_max_duration_seconds / videoMaxDurationSeconds: integer - recording ceiling in seconds (5-120); default 30
 * - video_mode / videoMode: string - smooth (offline frame-by-frame, fluid, default) or live (real-time fallback)
 * - video_fps / videoFps: integer - output frame rate (10-60); default 30
 *
 * The display/capture format is NOT a node param: it belongs to the interface itself (an
 * interface's HTML is authored for one fixed viewport width). Plans written before that move may
 * still carry `format` / `interface_format` / `interfaceFormat` on the node; those keys are
 * tolerated and ignored (see KNOWN_PARAMS + NodeParamsValidator).
 */
public record InterfaceNodeConfig(
    String interfaceId,
    Map<String, String> variableMapping,
    Map<String, String> actionMapping,
    Boolean isEntryInterface,
    Boolean generateScreenshot,
    Boolean exposeRenderedSource,
    Boolean generatePdf,
    String pdfFormat,
    Boolean pdfLandscape,
    Boolean generateVideo,
    String videoPreset,
    Integer videoMaxDurationSeconds,
    String videoMode,
    Integer videoFps
) {

    // ==================== Known Parameter Keys ====================

    /** All known parameter keys for interface nodes (for validation). */
    public static final java.util.Set<String> KNOWN_PARAMS = java.util.Set.of(
        "interface_id", "id", "variable_mapping", "action_mapping",
        "is_entry_interface", "isEntryInterface",
        "generate_screenshot", "generateScreenshot",
        "expose_rendered_source", "exposeRenderedSource",
        "generate_pdf", "generatePdf",
        "pdf_format", "pdfFormat",
        "pdf_landscape", "pdfLandscape",
        "generate_video", "generateVideo",
        "video_preset", "videoPreset",
        "video_max_duration_seconds", "videoMaxDurationSeconds",
        "video_mode", "videoMode",
        "video_fps", "videoFps",
        // Deprecated and ignored: the format moved to the interface entity. Kept here so a plan
        // written before the move still validates instead of erroring on an unknown param.
        "format", "interface_format", "interfaceFormat"
    );

    /**
     * Backward-compatible constructor without video mode/fps: both default to null (= omitted
     * from the plan; the render falls back to smooth / 30fps).
     */
    public InterfaceNodeConfig(
        String interfaceId,
        Map<String, String> variableMapping,
        Map<String, String> actionMapping,
        Boolean isEntryInterface,
        Boolean generateScreenshot,
        Boolean exposeRenderedSource,
        Boolean generatePdf,
        String pdfFormat,
        Boolean pdfLandscape,
        Boolean generateVideo,
        String videoPreset,
        Integer videoMaxDurationSeconds
    ) {
        this(interfaceId, variableMapping, actionMapping, isEntryInterface,
            generateScreenshot, exposeRenderedSource, generatePdf, pdfFormat, pdfLandscape,
            generateVideo, videoPreset, videoMaxDurationSeconds, null, null);
    }

    /**
     * Backward-compatible constructor without video options: {@code generateVideo} /
     * {@code videoPreset} / {@code videoMaxDurationSeconds} default to null (= omitted from
     * the plan, so no video output).
     */
    public InterfaceNodeConfig(
        String interfaceId,
        Map<String, String> variableMapping,
        Map<String, String> actionMapping,
        Boolean isEntryInterface,
        Boolean generateScreenshot,
        Boolean exposeRenderedSource,
        Boolean generatePdf,
        String pdfFormat,
        Boolean pdfLandscape
    ) {
        this(interfaceId, variableMapping, actionMapping, isEntryInterface,
            generateScreenshot, exposeRenderedSource, generatePdf, pdfFormat, pdfLandscape,
            null, null, null);
    }

    /**
     * Backward-compatible constructor without PDF options: {@code generatePdf} / {@code pdfFormat}
     * / {@code pdfLandscape} default to null (= omitted from the plan, so no PDF output).
     */
    public InterfaceNodeConfig(
        String interfaceId,
        Map<String, String> variableMapping,
        Map<String, String> actionMapping,
        Boolean isEntryInterface,
        Boolean generateScreenshot,
        Boolean exposeRenderedSource
    ) {
        this(interfaceId, variableMapping, actionMapping, isEntryInterface,
            generateScreenshot, exposeRenderedSource, null, null, null);
    }

    // ==================== Factory ====================

    /**
     * Extract InterfaceNodeConfig from raw parameters map.
     * Supports multiple key aliases (interface_id / id) and snake_case / camelCase
     * pairs for boolean toggles (so MCP agents can use either convention).
     */
    @SuppressWarnings("unchecked")
    public static InterfaceNodeConfig fromParams(Map<String, Object> params) {
        // Extract interface_id with alias support
        String interfaceId = getFirstString(params, "interface_id", "id");

        // Extract variable_mapping
        Map<String, String> variableMapping = extractStringMap(params, "variable_mapping");

        // Extract action_mapping
        Map<String, String> actionMapping = extractStringMap(params, "action_mapping");

        // Extract isEntryInterface (snake_case and camelCase)
        Boolean isEntryInterface = getFirstBoolean(params, "is_entry_interface", "isEntryInterface");

        // Extract generateScreenshot toggle (snake_case + camelCase). Default null → omitted from
        // the plan, parser will default to false. Setting true emits a `screenshot` FileRef output.
        Boolean generateScreenshot = getFirstBoolean(params, "generate_screenshot", "generateScreenshot");

        // Extract exposeRenderedSource toggle (snake_case + camelCase). Default null → omitted,
        // parser defaults to false. Setting true emits rendered_html/rendered_css/rendered_js.
        Boolean exposeRenderedSource = getFirstBoolean(params, "expose_rendered_source", "exposeRenderedSource");

        // Extract generatePdf toggle (snake_case + camelCase). Default null → omitted, parser
        // defaults to false. Setting true emits a `pdf` FileRef output (PDF of the interface).
        Boolean generatePdf = getFirstBoolean(params, "generate_pdf", "generatePdf");

        // Extract PDF page options. pdfFormat is normalised to a supported page size (A4 default);
        // pdfLandscape defaults to false. Both are only meaningful when generatePdf=true.
        String pdfFormat = normalizePdfFormat(getFirstString(params, "pdf_format", "pdfFormat"));
        Boolean pdfLandscape = getFirstBoolean(params, "pdf_landscape", "pdfLandscape");

        // Extract generateVideo toggle (snake_case + camelCase). Default null → omitted, parser
        // defaults to false. Setting true emits a `video` FileRef output (MP4 of the interface).
        Boolean generateVideo = getFirstBoolean(params, "generate_video", "generateVideo");

        // Extract video options. videoPreset is normalised to a supported capture format
        // (vertical default); videoMaxDurationSeconds is clamped to 5-120 (30 default). Both are
        // only meaningful when generateVideo=true.
        String videoPreset = normalizeVideoPreset(getFirstString(params, "video_preset", "videoPreset"));
        Integer videoMaxDurationSeconds = normalizeVideoDuration(
            getFirstInteger(params, "video_max_duration_seconds", "videoMaxDurationSeconds"));

        // Extract video mode + fps. Mode is normalised to smooth|live (unknown → null → smooth
        // default at render); fps is clamped to 10-60 (null → 30 default at render).
        String videoMode = normalizeVideoMode(getFirstString(params, "video_mode", "videoMode"));
        Integer videoFps = normalizeVideoFps(getFirstInteger(params, "video_fps", "videoFps"));

        // A legacy `format` / `interface_format` / `interfaceFormat` key is deliberately NOT
        // read: the format belongs to the interface entity now. Dropping it silently keeps
        // pre-refactor plans running (set it with interface update instead).

        return new InterfaceNodeConfig(interfaceId, variableMapping, actionMapping,
            isEntryInterface, generateScreenshot, exposeRenderedSource,
            generatePdf, pdfFormat, pdfLandscape,
            generateVideo, videoPreset, videoMaxDurationSeconds, videoMode, videoFps);
    }

    /** Supported PDF page sizes (matched case-insensitively). */
    public static final java.util.Set<String> SUPPORTED_PDF_FORMATS =
        java.util.Set.of("A4", "Letter", "Legal");

    /** Supported video capture presets (matched case-insensitively, stored lowercase). */
    public static final java.util.Set<String> SUPPORTED_VIDEO_PRESETS =
        java.util.Set.of("vertical", "horizontal", "square");

    /** Bounds for the video recording ceiling, mirroring the renderer's clamps. */
    public static final int MIN_VIDEO_DURATION_SECONDS = 5;
    public static final int MAX_VIDEO_DURATION_SECONDS = 120;

    /**
     * Normalise a caller-supplied PDF page size to one of {@link #SUPPORTED_PDF_FORMATS}.
     * Blank / unknown values return null so the node falls back to its A4 default rather than
     * forwarding an unsupported format the renderer would reject.
     */
    static String normalizePdfFormat(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (String supported : SUPPORTED_PDF_FORMATS) {
            if (supported.equalsIgnoreCase(raw.trim())) return supported;
        }
        return null;
    }

    /**
     * Normalise a caller-supplied video preset to one of {@link #SUPPORTED_VIDEO_PRESETS}
     * (lowercase). Blank / unknown values return null so the node falls back to its vertical
     * default rather than forwarding a preset the renderer would reject.
     */
    static String normalizeVideoPreset(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String candidate = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return SUPPORTED_VIDEO_PRESETS.contains(candidate) ? candidate : null;
    }

    /**
     * Clamp a caller-supplied recording ceiling to [{@link #MIN_VIDEO_DURATION_SECONDS},
     * {@link #MAX_VIDEO_DURATION_SECONDS}]. Null / non-positive values return null so the node
     * falls back to its 30s default.
     */
    static Integer normalizeVideoDuration(Integer raw) {
        if (raw == null || raw <= 0) return null;
        return Math.max(MIN_VIDEO_DURATION_SECONDS, Math.min(raw, MAX_VIDEO_DURATION_SECONDS));
    }

    /** Supported video render modes (matched case-insensitively, stored lowercase). */
    public static final java.util.Set<String> SUPPORTED_VIDEO_MODES =
        java.util.Set.of("smooth", "live");

    /** Bounds for the output frame rate, mirroring the renderer's clamps. */
    public static final int MIN_VIDEO_FPS = 10;
    public static final int MAX_VIDEO_FPS = 60;

    /**
     * Normalise a caller-supplied render mode to smooth|live (lowercase). Blank / unknown
     * values return null so the render falls back to its smooth default.
     */
    static String normalizeVideoMode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String candidate = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return SUPPORTED_VIDEO_MODES.contains(candidate) ? candidate : null;
    }

    /**
     * Clamp a caller-supplied frame rate to [{@link #MIN_VIDEO_FPS}, {@link #MAX_VIDEO_FPS}].
     * Null / non-positive values return null so the render falls back to its 30fps default.
     */
    static Integer normalizeVideoFps(Integer raw) {
        if (raw == null || raw <= 0) return null;
        return Math.max(MIN_VIDEO_FPS, Math.min(raw, MAX_VIDEO_FPS));
    }

    // ==================== Conversion ====================

    /**
     * Build the node map stored in WorkflowBuilderSession.interfaces.
     * Boolean toggles are emitted as camelCase keys to match {@code WorkflowPlanParser.parseInterfaces}.
     */
    public Map<String, Object> toNodeMap(String label, Map<String, Integer> position) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", interfaceId);
        node.put("label", label);
        node.put("type", "interface");
        if (isEntryInterface != null) {
            node.put("isEntryInterface", isEntryInterface);
        }
        if (generateScreenshot != null) {
            node.put("generateScreenshot", generateScreenshot);
        }
        if (exposeRenderedSource != null) {
            node.put("exposeRenderedSource", exposeRenderedSource);
        }
        if (generatePdf != null) {
            node.put("generatePdf", generatePdf);
        }
        if (pdfFormat != null) {
            node.put("pdfFormat", pdfFormat);
        }
        if (pdfLandscape != null) {
            node.put("pdfLandscape", pdfLandscape);
        }
        if (generateVideo != null) {
            node.put("generateVideo", generateVideo);
        }
        if (videoPreset != null) {
            node.put("videoPreset", videoPreset);
        }
        if (videoMaxDurationSeconds != null) {
            node.put("videoMaxDurationSeconds", videoMaxDurationSeconds);
        }
        if (videoMode != null) {
            node.put("videoMode", videoMode);
        }
        if (videoFps != null) {
            node.put("videoFps", videoFps);
        }
        if (variableMapping != null && !variableMapping.isEmpty()) {
            node.put("variableMapping", variableMapping);
        }
        if (actionMapping != null && !actionMapping.isEmpty()) {
            node.put("actionMapping", actionMapping);
        }
        node.put("position", position);
        return node;
    }

    /**
     * Build the savedParams map returned in the success response.
     */
    public Map<String, Object> toSavedParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("interface_id", interfaceId);
        if (isEntryInterface != null) {
            params.put("is_entry_interface", isEntryInterface);
        }
        if (generateScreenshot != null) {
            params.put("generate_screenshot", generateScreenshot);
        }
        if (exposeRenderedSource != null) {
            params.put("expose_rendered_source", exposeRenderedSource);
        }
        if (generatePdf != null) {
            params.put("generate_pdf", generatePdf);
        }
        if (pdfFormat != null) {
            params.put("pdf_format", pdfFormat);
        }
        if (pdfLandscape != null) {
            params.put("pdf_landscape", pdfLandscape);
        }
        if (generateVideo != null) {
            params.put("generate_video", generateVideo);
        }
        if (videoPreset != null) {
            params.put("video_preset", videoPreset);
        }
        if (videoMaxDurationSeconds != null) {
            params.put("video_max_duration_seconds", videoMaxDurationSeconds);
        }
        if (videoMode != null) {
            params.put("video_mode", videoMode);
        }
        if (videoFps != null) {
            params.put("video_fps", videoFps);
        }
        if (variableMapping != null && !variableMapping.isEmpty()) {
            params.put("variable_mapping", variableMapping);
        }
        if (actionMapping != null && !actionMapping.isEmpty()) {
            params.put("action_mapping", actionMapping);
        }
        return params;
    }

    /**
     * Build the extras map for the success response (interface_id + mappings).
     */
    public Map<String, Object> toExtras() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("interface_id", interfaceId);
        if (isEntryInterface != null) {
            extras.put("is_entry_interface", isEntryInterface);
        }
        if (generateScreenshot != null) {
            extras.put("generate_screenshot", generateScreenshot);
        }
        if (exposeRenderedSource != null) {
            extras.put("expose_rendered_source", exposeRenderedSource);
        }
        if (generatePdf != null) {
            extras.put("generate_pdf", generatePdf);
        }
        if (pdfFormat != null) {
            extras.put("pdf_format", pdfFormat);
        }
        if (pdfLandscape != null) {
            extras.put("pdf_landscape", pdfLandscape);
        }
        if (generateVideo != null) {
            extras.put("generate_video", generateVideo);
        }
        if (videoPreset != null) {
            extras.put("video_preset", videoPreset);
        }
        if (videoMaxDurationSeconds != null) {
            extras.put("video_max_duration_seconds", videoMaxDurationSeconds);
        }
        if (videoMode != null) {
            extras.put("video_mode", videoMode);
        }
        if (videoFps != null) {
            extras.put("video_fps", videoFps);
        }
        if (variableMapping != null && !variableMapping.isEmpty()) {
            extras.put("variable_mapping", variableMapping);
        }
        if (actionMapping != null && !actionMapping.isEmpty()) {
            extras.put("action_mapping", actionMapping);
        }
        return extras;
    }

    // ==================== Helpers ====================

    private static String getFirstString(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    private static Boolean getFirstBoolean(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof Boolean b) return b;
            if (value instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s);
        }
        return null;
    }

    private static Integer getFirstInteger(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof Number n) return n.intValue();
            if (value instanceof String s && !s.isBlank()) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the next alias / null
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractStringMap(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (raw instanceof Map<?, ?> map) {
            boolean isVariableMapping = "variable_mapping".equals(key);
            boolean isActionMapping = "action_mapping".equals(key);
            return ((Map<String, Object>) map).entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(
                    e -> isActionMapping ? stripSurroundingQuotes(e.getKey()) : e.getKey(),
                    e -> {
                        Object rawValue = e.getValue();
                        if (isActionMapping) {
                            assertActionMappingValueIsString(e.getKey(), rawValue);
                        }
                        String val = rawValue.toString();
                        // Normalize variable references in variable_mapping values
                        // e.g., {{mcp:Fetch Profile.output.data}} → {{mcp:fetch_profile.output.data}}
                        return isVariableMapping ? LabelNormalizer.normalizeVariableReferences(val) : val;
                    },
                    (a, b) -> b,
                    LinkedHashMap::new
                ));
        }
        return null;
    }

    /**
     * Reject non-String values in {@code action_mapping}.
     *
     * <p>An object value (Map / List) is the canonical sign that the agent invented a
     * non-existent field-renaming feature. We surface a loud, actionable error instead of
     * silently swallowing the value via {@code Map.toString()} - the latter has historically
     * stored {@code "{trigger=…, mapping={…}}"} strings in DB that the runtime later treats
     * as junk and silently ignores.
     *
     * <p>Public+static so the same contract is enforced from both code paths that accept
     * {@code action_mapping} input ({@link #fromParams} for add_node, and the modify-flow
     * harmoniser).
     *
     * @throws IllegalArgumentException with a self-contained, agent-actionable message when
     *         the value is not a String.
     */
    public static void assertActionMappingValueIsString(Object selectorKey, Object rawValue) {
        if (rawValue instanceof String) return;
        throw new IllegalArgumentException(
            "action_mapping value for key '" + selectorKey +
            "' must be a single string token (got " +
            (rawValue == null ? "null" : rawValue.getClass().getSimpleName()) +
            "). Allowed: 'trigger:<label>:submit|click|message', " +
            "'interface:<label>:navigate', '__continue', " +
            "'__pagination:next|prev|first|last'. " +
            "Field renaming inside action_mapping is NOT supported - either align " +
            "<input name=...> in the HTML to the trigger field name, or remap " +
            "downstream in a code node. " +
            "See workflow(action='help', topics=['interface']) for the full grammar."
        );
    }

    /**
     * Apply {@link #assertActionMappingValueIsString} to every entry of a candidate
     * action_mapping map. Convenience for callers that already hold the Map (e.g.
     * the modify-flow harmoniser, before {@code NodeFieldMerger} mutates the node).
     *
     * @param actionMapping the candidate map (possibly null or non-Map - both no-op)
     * @throws IllegalArgumentException if any value is not a String
     */
    public static void assertActionMappingValuesAreStrings(Object actionMapping) {
        if (!(actionMapping instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getValue() == null) continue;
            assertActionMappingValueIsString(e.getKey(), e.getValue());
        }
    }

    /**
     * Strip surrounding single or double quotes from a string.
     * LLMs sometimes wrap CSS selectors in quotes: '#btn' → #btn
     */
    private static String stripSurroundingQuotes(String value) {
        if (value == null || value.length() < 2) return value;
        if ((value.startsWith("'") && value.endsWith("'")) ||
            (value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
