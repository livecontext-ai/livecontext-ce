package com.apimarketplace.interfaces.client;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Display/capture format of an interface - the single source of truth for the pixel dimensions
 * shared by the rendered screenshot, the recorded video and every frontend preview surface
 * (canvas node, list card, side panel, shared application, marketplace).
 *
 * <p>The format belongs to the INTERFACE ENTITY, not to the workflow node that renders it: an
 * interface's HTML is authored for one fixed viewport width (the iframe does not apply
 * device-width), so the format is intrinsic to the interface's design. A workflow node resolves
 * the format from the interface it references.
 *
 * <p>A format is either a named preset (e.g. {@code vertical}, {@code widescreen}) or a custom
 * {@code "<width>x<height>"} string (e.g. {@code "1080x1920"}). Unknown / out-of-range values
 * resolve to null so callers fall back to the classic 1280x800 default.
 *
 * <p><b>Unset (null) is NOT the same as {@code classic}.</b> Unset means "no declared shape": the
 * screenshot is captured full-page (the whole scrollable document, whatever its height) at a
 * 1280x800 viewport. {@code classic} declares an exact 1280x800 frame, cropping anything below
 * the fold. Never coalesce null to a preset - it would silently crop every legacy interface.
 *
 * <p>The PDF output deliberately does NOT follow this format: PDFs are paper-based and keep
 * their own {@code pdfFormat} (A4 / Letter / Legal) + {@code pdfLandscape} options.
 *
 * <p>The preset table is mirrored in the frontend ({@code frontend/lib/interfaces/interfaceFormats.ts})
 * - keep both in sync.
 */
public final class InterfaceFormat {

    /** Default viewport when no format is configured - the platform's historical 1280x800. */
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 800;

    /**
     * Dimension bounds for a custom {@code WxH} format. The upper bound mirrors the video
     * renderer's MAX_VIDEO_DIMENSION (2160) so one format stays valid for BOTH the screenshot
     * and the video pipeline - a format only the screenshot could render would silently drop
     * the video output.
     */
    public static final int MIN_DIMENSION = 16;
    public static final int MAX_DIMENSION = 2160;

    private static final Pattern CUSTOM_PATTERN =
        Pattern.compile("^\\s*(\\d{2,4})\\s*[xX×]\\s*(\\d{2,4})\\s*$");

    /** Resolved pixel dimensions of a format. */
    public record Viewport(int width, int height) { }

    /**
     * Named presets (canonical name, lowercase) - insertion order is the display order.
     * Mirrored by PRESETS in interfaceFormats.ts (frontend).
     */
    private static final Map<String, Viewport> PRESETS;
    static {
        Map<String, Viewport> presets = new LinkedHashMap<>();
        presets.put("classic", new Viewport(1280, 800));        // 16:10 - historical default
        presets.put("widescreen", new Viewport(1920, 1080));    // 16:9 - YouTube / desktop video
        presets.put("vertical", new Viewport(1080, 1920));      // 9:16 - TikTok / Reels / Shorts
        presets.put("square", new Viewport(1080, 1080));        // 1:1 - feed posts
        presets.put("portrait", new Viewport(1080, 1350));      // 4:5 - Instagram portrait
        presets.put("mobile", new Viewport(390, 844));          // phone viewport
        presets.put("tablet", new Viewport(820, 1180));         // tablet portrait viewport
        presets.put("desktop", new Viewport(1440, 900));        // 16:10 desktop viewport
        presets.put("banner", new Viewport(1500, 500));         // 3:1 - X/Twitter header
        presets.put("social_card", new Viewport(1200, 630));    // 1.91:1 - OpenGraph card
        presets.put("a4_portrait", new Viewport(794, 1123));    // A4 at 96dpi CSS px
        presets.put("a4_landscape", new Viewport(1123, 794));   // A4 landscape at 96dpi CSS px
        PRESETS = java.util.Collections.unmodifiableMap(presets);
    }

    /** Accepted aliases -> canonical preset name (agent-friendly synonyms + aspect ratios). */
    private static final Map<String, String> ALIASES = Map.of(
        "landscape", "classic",
        "horizontal", "widescreen",   // matches the videoPreset name for 1920x1080
        "story", "vertical",
        "reel", "vertical",
        "og", "social_card",
        "16:9", "widescreen",
        "9:16", "vertical",
        "1:1", "square",
        "4:5", "portrait"
    );

    private InterfaceFormat() { }

    /** Read-only view of the canonical preset table (name -> dimensions, display order). */
    public static Map<String, Viewport> presets() {
        return PRESETS;
    }

    /** Read-only view of the accepted aliases (alias -> canonical preset name). */
    public static Map<String, String> aliases() {
        return ALIASES;
    }

    /**
     * Normalise a caller-supplied format to its canonical stored form: a canonical preset name
     * for presets/aliases, {@code "<w>x<h>"} for a valid custom dimension pair, or null for
     * blank / unknown / out-of-range input (callers fall back to the classic default).
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String candidate = raw.trim().toLowerCase(Locale.ROOT);
        String canonical = ALIASES.getOrDefault(candidate, candidate);
        if (PRESETS.containsKey(canonical)) return canonical;
        Viewport custom = parseCustom(candidate);
        return custom != null ? custom.width() + "x" + custom.height() : null;
    }

    /**
     * True when {@code raw} is a non-blank value that {@link #normalize(String)} rejects. Callers
     * that must not silently drop bad input (the interface tool, the REST layer) use this to tell
     * "no format supplied" apart from "a format was supplied but is invalid".
     */
    public static boolean isInvalid(String raw) {
        return raw != null && !raw.isBlank() && normalize(raw) == null;
    }

    /**
     * Resolve a (raw or normalised) format string to pixel dimensions. Null / blank / unknown
     * input resolves to null - callers decide the fallback (renderer default 1280x800,
     * frontend virtual viewport, ...).
     */
    public static Viewport resolve(String format) {
        if (format == null || format.isBlank()) return null;
        String candidate = format.trim().toLowerCase(Locale.ROOT);
        String canonical = ALIASES.getOrDefault(candidate, candidate);
        Viewport preset = PRESETS.get(canonical);
        if (preset != null) return preset;
        return parseCustom(candidate);
    }

    /** {@link #resolve(String)} with the classic 1280x800 fallback instead of null. */
    public static Viewport resolveOrDefault(String format) {
        Viewport viewport = resolve(format);
        return viewport != null ? viewport : new Viewport(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    private static Viewport parseCustom(String candidate) {
        Matcher matcher = CUSTOM_PATTERN.matcher(candidate);
        if (!matcher.matches()) return null;
        int width;
        int height;
        try {
            width = Integer.parseInt(matcher.group(1));
            height = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException overflow) {
            return null;
        }
        if (width < MIN_DIMENSION || width > MAX_DIMENSION
            || height < MIN_DIMENSION || height > MAX_DIMENSION) {
            return null;
        }
        // Floor custom dimensions to even: the H.264 yuv420p video encoder requires even
        // dimensions (the renderer floors them anyway), so flooring HERE keeps the screenshot
        // and the video pixel-identical for odd custom input like 1081x1921.
        return new Viewport(width & ~1, height & ~1);
    }
}
