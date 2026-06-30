/**
 * Single source of truth for "mono-dark" brand icons - logos rendered in a
 * near-black brand color (e.g. GitHub `#161614`, OpenAI `#000000`, Anthropic
 * monochrome). Without help they vanish against a dark background.
 *
 * Both the credentials UI (`ServiceIcon`) and the landing trust strip read
 * from the same set so a brand only needs to be added once. Slugs use the
 * canonical form stored in `apis.icon_slug` - lowercase alphanumeric, no
 * separators (see `iconSlug.ts#normalizeIconSlug`).
 *
 * The CSS filter is `brightness(0) invert(1)` rather than plain `invert(1)`:
 * a near-black like `#161614` would invert to `#E9E9EB` and still read dim,
 * whereas `brightness(0)` first crushes the artwork to pure black so the
 * subsequent invert produces pure white regardless of the source color.
 */
export const MONO_DARK_ICON_SLUGS: ReadonlySet<string> = new Set([
  "github",
  "openai",
  "anthropic",
  "linear",
  "dropbox",
  "twitter",
]);

export function isMonoDarkIconSlug(slug: string | null | undefined): boolean {
  if (!slug) return false;
  return MONO_DARK_ICON_SLUGS.has(slug);
}

/**
 * Tailwind class that flips a mono-dark logo to white inside a `.dark`
 * ancestor. Returns an empty string for non-mono icons so callers can
 * concatenate unconditionally.
 */
export function monoDarkInvertClass(
  slug: string | null | undefined,
): string {
  return isMonoDarkIconSlug(slug) ? "dark:brightness-0 dark:invert" : "";
}

/**
 * Extract a slug from an `iconUrl` like `/icons/services/github.svg`. Named
 * `…FromUrl` rather than just `extractIconSlug` to avoid colliding with the
 * `CredentialWizard`-local helper of the same short name that resolves a slug
 * from a `CredentialTemplate` object (different domain, multi-fallback).
 */
export function extractIconSlugFromUrl(
  iconUrl: string | null | undefined,
): string | undefined {
  if (!iconUrl) return undefined;
  return iconUrl.match(/\/([^/]+)\.svg$/)?.[1];
}
