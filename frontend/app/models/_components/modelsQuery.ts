import { CATALOG_MODELS } from './modelsData';

/**
 * The /models URL contract: `?provider=<key>` opens the page already filtered.
 *
 * This exists because the public footer's Models column names model families and
 * every one of them used to land on the same unfiltered page. Clicking "Grok"
 * should show Grok. The key is the catalogue's own provider key (`xai`, not
 * "Grok"), so the link and the filter chip agree by construction.
 *
 * Kept in its own module rather than inside the page or the catalogue component
 * because three places have to agree on it: the server page reads it, the client
 * component writes it back on a chip click, and the footer builds it.
 */
export const PROVIDER_PARAM = 'provider';

/**
 * Every provider that actually has a row on the page.
 *
 * Derived from the generated dataset, never a hand-written list: a provider that
 * leaves the catalogue leaves this set in the same pass, so a link can never point
 * at a filter that would match nothing.
 */
export const PROVIDER_KEYS: readonly string[] = [
  ...new Set(CATALOG_MODELS.map((model) => model.provider)),
].sort();

/**
 * Read the provider filter off a query string.
 *
 * An unknown key resolves to `null`, i.e. the FULL page, deliberately: the value
 * arrives from a URL, so it can be stale (a provider we dropped), hand-typed, or
 * a crawler's invention. Showing everything is the answer a visitor can use;
 * showing an empty list, or a 404, punishes them for a link we changed.
 */
export function resolveProviderParam(raw: string | string[] | undefined): string | null {
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (!value) return null;
  const key = value.trim().toLowerCase();
  return PROVIDER_KEYS.includes(key) ? key : null;
}

/** The canonical link to the page filtered on one provider. */
export function providerHref(provider: string): string {
  return `/models?${PROVIDER_PARAM}=${encodeURIComponent(provider)}`;
}
