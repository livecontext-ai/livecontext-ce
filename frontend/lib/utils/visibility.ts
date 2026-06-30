// Pure, dependency-free helpers for the marketplace-visibility (Public / Private) indicator + filter
// shared by the "My Shared" tab, /app/applications, and the workflow + application boards. Kept free
// of React / next-intl imports so the bucketing rules are unit-testable in isolation and the boards
// can import them without pulling in client-only modules.

export type VisibilityFilter = 'all' | 'public' | 'private';

/**
 * True when the publication is marketplace-listed (`PUBLIC`). Everything else - `PRIVATE`, legacy
 * `UNLISTED`, or missing - is treated as private, the SAME single-bucket split
 * `applicationSort.filterByVisibility` uses. Keep the two in lockstep so the footer indicator and
 * the visibility filter never disagree.
 */
export function isPublicVisibility(visibility: string | null | undefined): boolean {
  return (visibility ?? '').toString().toUpperCase() === 'PUBLIC';
}

/**
 * Whether a card/publication passes the visibility filter. `public` = `PUBLIC`; `private` = any OTHER
 * KNOWN visibility (`PRIVATE` / legacy `UNLISTED`). Items with NO visibility (unpublished workflows,
 * acquired apps - the publisher's visibility isn't the viewer's) are dropped whenever the filter is
 * narrowed, never lumped into "private".
 */
export function matchesVisibilityFilter(visibility: string | null | undefined, filter: VisibilityFilter): boolean {
  if (filter === 'all') return true;
  if (filter === 'public') return isPublicVisibility(visibility);
  // `!!visibility` drops null / undefined / "" (no visibility) so they are never lumped into
  // "private" - only a KNOWN non-public visibility (PRIVATE / UNLISTED) matches.
  return !!visibility && !isPublicVisibility(visibility);
}
