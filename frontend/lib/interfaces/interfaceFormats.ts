/**
 * Interface display/capture formats - the frontend mirror of the backend's
 * `InterfaceFormat.java` (interface-client). Keep the preset table and the
 * parsing rules in sync between the two files.
 *
 * A format is either a named preset (e.g. `vertical`, `widescreen`) or a custom
 * `"<width>x<height>"` string (e.g. `"1080x1920"`). It belongs to the INTERFACE
 * ENTITY (field `format`), not to the workflow node that renders it: an interface's
 * HTML is authored for one fixed viewport width, so the format is intrinsic to its
 * design. It is shared by:
 *   - the screenshot capture (exact WxH frame)
 *   - the video recording (viewport, unless an explicit node videoPreset overrides)
 *   - every preview surface (canvas node, list card, side panel, shared app, marketplace)
 * The PDF output keeps its own paper-based pdfFormat and ignores this.
 *
 * Unset (null/undefined) is NOT the same as `classic`: unset means "no declared shape"
 * (screenshot captured full-page at a 1280x800 viewport), while `classic` declares an
 * exact 1280x800 frame that crops below the fold. Never coalesce unset to a preset.
 */

export interface FormatViewport {
  width: number;
  height: number;
}

/** Default viewport when no format is configured - the platform's historical 1280x800. */
export const DEFAULT_FORMAT_VIEWPORT: FormatViewport = { width: 1280, height: 800 };

/**
 * Dimension bounds for a custom WxH format. The upper bound mirrors the video renderer's
 * MAX_VIDEO_DIMENSION (2160) so one format stays valid for BOTH the screenshot and the
 * video pipeline.
 */
export const FORMAT_MIN_DIMENSION = 16;
export const FORMAT_MAX_DIMENSION = 2160;

/**
 * Named presets (canonical name -> dimensions). Order is the display order in the
 * inspector's Format select. Mirrors PRESETS in InterfaceFormat.java.
 */
export const INTERFACE_FORMAT_PRESETS: ReadonlyArray<{
  name: string;
  width: number;
  height: number;
}> = [
  { name: 'classic', width: 1280, height: 800 },        // 16:10 - historical default
  { name: 'widescreen', width: 1920, height: 1080 },    // 16:9 - YouTube / desktop video
  { name: 'vertical', width: 1080, height: 1920 },      // 9:16 - TikTok / Reels / Shorts
  { name: 'square', width: 1080, height: 1080 },        // 1:1 - feed posts
  { name: 'portrait', width: 1080, height: 1350 },      // 4:5 - Instagram portrait
  { name: 'mobile', width: 390, height: 844 },          // phone viewport
  { name: 'tablet', width: 820, height: 1180 },         // tablet portrait viewport
  { name: 'desktop', width: 1440, height: 900 },        // 16:10 desktop viewport
  { name: 'banner', width: 1500, height: 500 },         // 3:1 - X/Twitter header
  { name: 'social_card', width: 1200, height: 630 },    // 1.91:1 - OpenGraph card
  { name: 'a4_portrait', width: 794, height: 1123 },    // A4 at 96dpi CSS px
  { name: 'a4_landscape', width: 1123, height: 794 },   // A4 landscape at 96dpi CSS px
];

/** Accepted aliases -> canonical preset name. Mirrors ALIASES in InterfaceFormat.java. */
const FORMAT_ALIASES: Record<string, string> = {
  landscape: 'classic',
  horizontal: 'widescreen',
  story: 'vertical',
  reel: 'vertical',
  og: 'social_card',
  '16:9': 'widescreen',
  '9:16': 'vertical',
  '1:1': 'square',
  '4:5': 'portrait',
};

const CUSTOM_PATTERN = /^\s*(\d{2,4})\s*[xX×]\s*(\d{2,4})\s*$/;

const PRESET_MAP: ReadonlyMap<string, FormatViewport> = new Map(
  INTERFACE_FORMAT_PRESETS.map(p => [p.name, { width: p.width, height: p.height }]),
);

function parseCustom(candidate: string): FormatViewport | null {
  const match = CUSTOM_PATTERN.exec(candidate);
  if (!match) return null;
  const width = parseInt(match[1], 10);
  const height = parseInt(match[2], 10);
  if (
    !Number.isFinite(width) || !Number.isFinite(height) ||
    width < FORMAT_MIN_DIMENSION || width > FORMAT_MAX_DIMENSION ||
    height < FORMAT_MIN_DIMENSION || height > FORMAT_MAX_DIMENSION
  ) {
    return null;
  }
  // Floor custom dimensions to even, mirroring InterfaceFormat.java: the H.264 video
  // encoder requires even dimensions, and flooring at parse time keeps the screenshot
  // and the video pixel-identical for odd custom input like 1081x1921.
  return { width: width & ~1, height: height & ~1 };
}

/**
 * Resolve a format string (preset name, alias, or "WxH") to pixel dimensions.
 * Returns null for blank / unknown / out-of-range input - callers decide the
 * fallback (usually DEFAULT_FORMAT_VIEWPORT).
 */
export function resolveInterfaceFormat(format: string | null | undefined): FormatViewport | null {
  if (!format || !format.trim()) return null;
  const candidate = format.trim().toLowerCase();
  const canonical = FORMAT_ALIASES[candidate] ?? candidate;
  const preset = PRESET_MAP.get(canonical);
  if (preset) return preset;
  return parseCustom(candidate);
}

/** resolveInterfaceFormat with the classic 1280x800 fallback instead of null. */
export function resolveInterfaceFormatOrDefault(format: string | null | undefined): FormatViewport {
  return resolveInterfaceFormat(format) ?? DEFAULT_FORMAT_VIEWPORT;
}

/**
 * Normalise a caller-supplied format to its canonical stored form: canonical preset
 * name for presets/aliases, "WxH" for a valid custom pair, null otherwise.
 * Mirrors InterfaceFormat.normalize (backend).
 */
export function normalizeInterfaceFormat(format: string | null | undefined): string | null {
  if (!format || !format.trim()) return null;
  const candidate = format.trim().toLowerCase();
  const canonical = FORMAT_ALIASES[candidate] ?? candidate;
  if (PRESET_MAP.has(canonical)) return canonical;
  const custom = parseCustom(candidate);
  return custom ? `${custom.width}x${custom.height}` : null;
}

/** True when the (resolved) format is portrait-oriented (taller than wide). */
export function isPortraitFormat(format: string | null | undefined): boolean {
  const viewport = resolveInterfaceFormat(format);
  return !!viewport && viewport.height > viewport.width;
}
