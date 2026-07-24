/**
 * Canonical icon-slug normalizer - frontend mirror of the backend
 * `IconSlugNormalizer.normalize` (`backend/catalog-service/.../util/IconSlugNormalizer.java`).
 *
 * The catalog stores `apis.icon_slug` and `credentials.icon_slug` in a single
 * canonical form: lowercase, alphanumeric only, no separators. Anything that
 * needs to look up a credential template by slug must collapse its input to
 * the same shape, otherwise an apiSlug like "google-gemini" never resolves
 * the template registered under "googlegemini" (real bug observed in the
 * multi-credential wizard - Connect OpenAI succeeds, Connect Gemini hits
 * "Service configuration not found").
 *
 * Mapping table (`KNOWN_ICON_MAPPINGS` in the Java side) is intentionally NOT
 * mirrored here: that table collapses brand families ("googlecloudstorage" →
 * "googlecloud") for icon paths, but credential lookup needs the per-API key.
 * Frontend callers either look up icons (handled by the backend response that
 * already carries the right `icon_slug`) or look up credential templates
 * (which are keyed by `normalizeForKey`, equivalent to what we do here).
 */

// `\p{M}` matches every combining mark (Mn/Mc/Me) - full parity with the Java
// side's `\p{M}`. The earlier U+0300-U+036F literal range covered only Latin /
// Greek / Cyrillic; non-Latin scripts (Devanagari, Hebrew niqqud) need the
// broader class. The `u` flag is required for Unicode property escapes.
const DIACRITICS = /\p{M}/gu;
const TRAILING_API_SUFFIX = /-api$/i;
const NON_ALNUM = /[^a-z0-9]/g;

export function normalizeIconSlug(input: string | null | undefined): string {
  if (!input) return '';
  return input
    .normalize('NFD')
    .replace(DIACRITICS, '')
    .replace(TRAILING_API_SUFFIX, '')
    .toLowerCase()
    .replace(NON_ALNUM, '');
}

/**
 * Sentinel the catalog substitutes when an API has no icon of its own: every
 * `WorkflowInspectorService` query selects `COALESCE(a.icon_slug, 'mcp')`.
 *
 * It is a *truthy* placeholder, so a naive `data.iconSlug || data.apiSlug`
 * chain never falls through to the real slug and the node ends up rendering
 * `/icons/services/mcp.svg` (a generic "API" glyph) instead of the brand logo.
 * Treat it as "unresolved" at every point of consumption rather than changing
 * the SQL, which other callers rely on.
 */
export const UNRESOLVED_ICON_SLUG = 'mcp';

/** True for the `mcp` sentinel AND for a blank slug: neither names a real icon. */
export function isUnresolvedIconSlug(input: string | null | undefined): boolean {
  const normalized = normalizeIconSlug(input);
  return normalized === '' || normalized === UNRESOLVED_ICON_SLUG;
}

/**
 * First candidate that is a real, resolvable icon slug - skipping blanks and
 * the catalog's `mcp` sentinel. Returns `undefined` when nothing resolves, so
 * callers can fall back to their own default (the MCP logo, a lucide glyph).
 */
export function resolveIconSlug(...candidates: (string | null | undefined)[]): string | undefined {
  for (const candidate of candidates) {
    if (candidate && candidate.trim() && !isUnresolvedIconSlug(candidate)) return candidate;
  }
  return undefined;
}
