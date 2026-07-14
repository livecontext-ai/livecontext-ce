// Avatar color model shared by AvatarPicker / AvatarDisplay / marketplace heroes.
//
// An agent avatarUrl is one of:
//   - 'preset:<name>'                          -> static SVG under /avatars/
//   - 'preset:<name>?c1=RRGGBB&c2=RRGGBB'      -> same SVG with the gradient stops recolored
//   - 'preset:<name>?...&tool=<id>'            -> plus a tool badge (see avatarTools.ts);
//                                                 tool combines with or without colors
//   - an http(s) or /api/proxy/files/... URL   -> uploaded or AI-generated image
//
// The customized form keeps the 'preset:' prefix on purpose: every backend snapshot
// filter (publication/clone/moderation) keeps values starting with 'preset:' verbatim,
// so a recolored avatar survives publish/acquire without any backend change.

import { AVATAR_TOOL_IDS } from './avatarTools';

// Gradient stop colors for each preset, extracted from public/avatars/avatar-N.svg.
// Order matches AVATAR_PRESETS in AvatarPicker.tsx.
export const PRESET_GRADIENTS: Record<string, [string, string]> = {
  'preset:purple': ['#7C3AED', '#4338CA'],
  'preset:blue': ['#3B82F6', '#1D4ED8'],
  'preset:green': ['#10B981', '#047857'],
  'preset:orange': ['#F97316', '#C2410C'],
  'preset:pink': ['#EC4899', '#BE185D'],
  'preset:yellow': ['#EAB308', '#A16207'],
  'preset:teal': ['#14B8A6', '#0F766E'],
  'preset:indigo': ['#4F46E5', '#3730A3'],
  'preset:slate': ['#78716C', '#57534E'],
  'preset:red': ['#EF4444', '#B91C1C'],
  'preset:emerald': ['#F43F5E', '#E11D48'],
  'preset:coral': ['#FF7F7F', '#E05555'],
  'preset:gold': ['#D4A51A', '#A88315'],
  'preset:cyan': ['#06B6D4', '#0E7490'],
  'preset:lavender': ['#B8A9E8', '#9484D1'],
  'preset:burgundy': ['#831843', '#5C0F2E'],
  'preset:fuchsia': ['#D946EF', '#A21CAF'],
  'preset:lime': ['#84CC16', '#4D7C0F'],
  'preset:sand': ['#C2A878', '#A68B5B'],
  'preset:mint': ['#4AEDC4', '#2BC9A4'],
  'preset:olive': ['#8B9D4A', '#6B7A30'],
  'preset:periwinkle': ['#6C7EB7', '#4E60A0'],
  'preset:peach': ['#FFAB91', '#FF8A65'],
  'preset:navy': ['#1E3A5F', '#142840'],
  'preset:wine': ['#9F1239', '#881337'],
  'preset:charcoal': ['#374151', '#1F2937'],
  'preset:forest': ['#166534', '#14532D'],
  'preset:bubblegum': ['#F472B6', '#DB2777'],
  'preset:arctic': ['#7DD3FC', '#0EA5E9'],
  'preset:sunshine': ['#FDE047', '#EAB308'],
};

const HEX_RE = /^[0-9a-fA-F]{6}$/;

export interface AvatarCustomColors {
  c1: string; // '#RRGGBB'
  c2: string; // '#RRGGBB'
}

/**
 * Split an avatar value into its base preset id, optional custom colors, and
 * optional tool badge. Returns null when the value is not a preset (uploaded/
 * generated URL or empty). Unknown tool ids parse to null (nothing rendered).
 */
export function parsePresetValue(
  value?: string,
): { presetId: string; colors: AvatarCustomColors | null; tool: string | null } | null {
  if (!value || !value.startsWith('preset:')) return null;
  const qIndex = value.indexOf('?');
  if (qIndex < 0) return { presetId: value, colors: null, tool: null };
  const presetId = value.slice(0, qIndex);
  const params = new URLSearchParams(value.slice(qIndex + 1));
  const c1 = params.get('c1');
  const c2 = params.get('c2');
  const toolParam = params.get('tool');
  const tool = toolParam && AVATAR_TOOL_IDS.has(toolParam) ? toolParam : null;
  if (c1 && c2 && HEX_RE.test(c1) && HEX_RE.test(c2)) {
    return { presetId, colors: { c1: `#${c1.toUpperCase()}`, c2: `#${c2.toUpperCase()}` }, tool };
  }
  return { presetId, colors: null, tool };
}

/**
 * Build a customized preset value, or the bare preset id when there is nothing
 * to encode (colors matching the defaults are dropped; tool is optional).
 */
export function buildPresetValue(
  presetId: string,
  colors?: AvatarCustomColors | null,
  tool?: string | null,
): string {
  const parts: string[] = [];
  const defaults = PRESET_GRADIENTS[presetId];
  const isDefaultColors = !!colors && !!defaults
    && defaults[0].toUpperCase() === colors.c1.toUpperCase()
    && defaults[1].toUpperCase() === colors.c2.toUpperCase();
  if (colors && !isDefaultColors) {
    parts.push(`c1=${colors.c1.replace('#', '').toUpperCase()}`);
    parts.push(`c2=${colors.c2.replace('#', '').toUpperCase()}`);
  }
  if (tool) parts.push(`tool=${tool}`);
  return parts.length > 0 ? `${presetId}?${parts.join('&')}` : presetId;
}

/**
 * Effective gradient of an avatar value: custom colors when present, else the
 * preset's default stops. Null for uploaded/generated URLs (unknown palette).
 */
export function getAvatarGradient(value?: string): [string, string] | null {
  const parsed = parsePresetValue(value);
  if (!parsed) return null;
  if (parsed.colors) return [parsed.colors.c1, parsed.colors.c2];
  return PRESET_GRADIENTS[parsed.presetId] ?? null;
}

/**
 * CSS background for the agent "identity hero" (marketplace card / publish preview):
 * a soft translucent wash of the avatar's own gradient, so the tile matches the agent
 * without competing with the avatar circle. Uploaded/AI avatars (unknown palette) get
 * a neutral slate wash that works on both themes.
 */
export function heroGradientCss(avatarUrl?: string): string {
  const gradient = getAvatarGradient(avatarUrl);
  if (!gradient) {
    return 'linear-gradient(135deg, rgba(148,163,184,0.18), rgba(100,116,139,0.32))';
  }
  return `linear-gradient(135deg, ${gradient[0]}2E, ${gradient[1]}52)`;
}

// ---------------------------------------------------------------------------
// Recolored preset SVG rendering
// ---------------------------------------------------------------------------

// Raw SVG markup cache: one fetch per preset image for the whole session.
const svgTextCache = new Map<string, Promise<string>>();

function fetchSvgText(image: string): Promise<string> {
  let cached = svgTextCache.get(image);
  if (!cached) {
    cached = fetch(image).then((res) => {
      if (!res.ok) throw new Error(`Failed to load ${image}: ${res.status}`);
      return res.text();
    });
    cached.catch(() => svgTextCache.delete(image));
    svgTextCache.set(image, cached);
  }
  return cached;
}

// '#RRGGBB' contains no regex metacharacters, so the literal is safe as a pattern.
function replaceAllHex(svg: string, from: string, to: string): string {
  return svg.replace(new RegExp(from, 'gi'), to);
}

/**
 * Produce a data-URI of the preset SVG with its two gradient stop colors swapped
 * for the custom pair. Accent shapes keep their original hues (low-opacity details).
 */
export async function buildRecoloredPresetDataUri(
  presetId: string,
  image: string,
  colors: AvatarCustomColors,
): Promise<string | null> {
  const defaults = PRESET_GRADIENTS[presetId];
  if (!defaults) return null;
  try {
    let svg = await fetchSvgText(image);
    // Two-phase swap so c1->c2 followed by c2->x can never chain.
    svg = replaceAllHex(svg, defaults[0], '#__AVATAR_C1__');
    svg = replaceAllHex(svg, defaults[1], '#__AVATAR_C2__');
    svg = svg.split('#__AVATAR_C1__').join(colors.c1).split('#__AVATAR_C2__').join(colors.c2);
    return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  } catch {
    return null;
  }
}
