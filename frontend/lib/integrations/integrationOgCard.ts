import { authTypeLabel, type PublicIntegration } from './integrations';

/**
 * The pure parts of the per-integration share card (`app/integrations/[slug]/opengraph-image.tsx`),
 * kept out of the route so they can be tested without rasterising anything.
 *
 * <p>The card mirrors the site-wide one (`public/og-image.jpg`), which is itself modelled on
 * GitHub's repository card: "LiveContext/<name>", a grey summary, the brand mark on the right,
 * a row of stats and a colour bar along the bottom edge.
 */

/** The site-wide card's bar: brand colours of the best-known integrations. */
export const DEFAULT_BAR_COLORS = [
  '#EA4335', '#4A154B', '#635BFF', '#FF7A59', '#0F9D58', '#95BF47', '#FCB400', '#0A66C2', '#111827',
] as const;

const MAX_BAR_COLORS = 4;

function expandHex(hex: string): string {
  const h = hex.toLowerCase();
  return h.length === 3 ? h.split('').map((c) => c + c).join('') : h;
}

/** HSL saturation and lightness of a 6-digit hex colour, both in [0, 1]. */
function saturationAndLightness(hex: string): { s: number; l: number } {
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  return { s, l };
}

/**
 * The colours of the integration's own mark, for the bar along the bottom of its card.
 *
 * <p>Read from the SVG rather than kept in a table: ~980 integrations each carry an icon, and a
 * hand-kept colour table would be both incomplete and a second source of truth for the same
 * brand. Greys, near-white and near-black are skipped because they are outlines and
 * backgrounds, not brand colours; a mono mark therefore yields nothing and falls back to the
 * site-wide bar, so no card ever renders an empty or all-grey bar.
 */
export function brandColors(svg: string | null): string[] {
  if (!svg) return [...DEFAULT_BAR_COLORS];
  const seen = new Set<string>();
  for (const match of svg.matchAll(/#([0-9a-f]{6}|[0-9a-f]{3})(?![0-9a-f])/gi)) {
    const hex = expandHex(match[1]);
    const { s, l } = saturationAndLightness(hex);
    if (s < 0.25 || l < 0.08 || l > 0.94) continue;
    seen.add(`#${hex.toUpperCase()}`);
    if (seen.size === MAX_BAR_COLORS) break;
  }
  return seen.size > 0 ? [...seen] : [...DEFAULT_BAR_COLORS];
}

/**
 * Title size for "LiveContext/<name>". The name must stay on the card at 1200 px without
 * running under the mark on the right, and catalogue names run from "Slack" to
 * "Google Analytics Admin API".
 */
export function titleFontSize(name: string): number {
  if (name.length <= 12) return 72;
  if (name.length <= 20) return 62;
  return 52;
}

/**
 * An icon key the renderer may turn into a file path. The key comes from the catalogue over
 * HTTP, so it is checked before it ever reaches `path.join`.
 */
export function isSafeIconSlug(iconSlug: string): boolean {
  return /^[a-z0-9][a-z0-9_-]*$/i.test(iconSlug);
}

export type OgStatIcon = 'bolt' | 'key' | 'bot' | 'gift';

export interface OgStat {
  icon: OgStatIcon;
  value: string;
  label: string;
}

/**
 * The stats row. Only what the catalogue states for THIS integration, plus two product facts
 * that hold for every one of them; an integration with no tools or no declared auth simply
 * shows fewer stats rather than a zero or a guess.
 */
export function integrationStats(integration: PublicIntegration): OgStat[] {
  const stats: OgStat[] = [];
  if (integration.toolCount > 0) {
    stats.push({
      icon: 'bolt',
      value: String(integration.toolCount),
      label: integration.toolCount === 1 ? 'Action' : 'Actions',
    });
  }
  const auth = authTypeLabel(integration.authType);
  if (auth) stats.push({ icon: 'key', value: auth, label: 'Authentication' });
  stats.push({ icon: 'bot', value: 'Agents', label: 'AI on a budget' });
  stats.push({ icon: 'gift', value: 'Free', label: 'to start' });
  return stats;
}
