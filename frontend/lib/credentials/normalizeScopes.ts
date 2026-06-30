/**
 * Flatten a scope array that may contain embedded delimiters (comma, whitespace)
 * inside individual elements. Returns a clean {@code string[]} where each entry
 * is exactly one scope.
 *
 * <p>Why this exists: not every OAuth provider returns scopes in the RFC 6749
 * space-separated form. Slack returns commas in its `authed_user.scope` field,
 * some providers concatenate with `+`, and a few legacy callers store the raw
 * `scope` query value as a single array element. When that single-element
 * array reaches the UI:
 * <ul>
 *   <li>{@code ScopeStatusIndicator} renders one bullet containing the whole
 *     comma-blob, so the user sees "1 scope" with content like
 *     {@code "channels:read,channels:history,..."}.</li>
 *   <li>{@code MissingScopesBanner} builds a {@code Set} of granted scopes for
 *     a {@code Set.has(scope)} check; the comma-blob never matches any
 *     individual required scope, so every workflow shows false-positive
 *     "missing scope" warnings.</li>
 * </ul>
 *
 * <p>Treating this at the display layer is intentionally defensive: even
 * after the auth-service is fixed to always store one-scope-per-row, old
 * credential rows may still carry the legacy shape, and we don't want a
 * one-time migration to be on the critical path.
 *
 * <p>Splitting rule: comma {@code ,} OR any whitespace run. Empty fragments
 * dropped. Each fragment is trimmed. Order preserved (so the tooltip lists
 * scopes in the same order the provider returned them). Duplicates kept -
 * dedup is the caller's choice (a Set wraps the result for matching, the
 * tooltip prefers showing duplicates over hiding them).
 */
export function normalizeScopes(input: readonly string[] | undefined | null): string[] {
  if (!input || input.length === 0) return [];
  const out: string[] = [];
  for (const raw of input) {
    if (typeof raw !== 'string') continue;
    for (const part of raw.split(/[,\s]+/)) {
      const trimmed = part.trim();
      if (trimmed) out.push(trimmed);
    }
  }
  return out;
}
