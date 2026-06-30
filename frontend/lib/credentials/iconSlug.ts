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
