// Pure, dependency-free filter + sort helpers shared by the resource listing pages
// (`/app/workflow`, `/app/agent`, `/app/interface`, `/app/tables`). They mirror the
// Applications page (`applicationSort.ts`): the visible set is filtered by visibility,
// then ordered, then the page paginates client-side. Kept free of React / next-intl so
// the ordering rules are unit-testable in isolation.
//
// "Public" here is the binary published-vs-not split that drives each card's Globe / Lock
// marker: an item is public when it has an ACTIVE (shared) publication, private otherwise
// (drafts, in-review, rejected). This is intentionally coarser than the marketplace
// PUBLIC / UNLISTED distinction the board's `VisibilityBadge` draws - these lists only know
// whether a resource is shared, not the publication's fine-grained visibility.

import type { VisibilityFilter } from './visibility';

export type { VisibilityFilter } from './visibility';

/**
 * Sort keys a listing can expose. Each surface advertises only the subset that applies
 * (e.g. agents/interfaces/tables have no run count or last-execution timestamp, so they
 * offer just `name` + `lastModified`).
 */
export type ListSortKey = 'lastExecuted' | 'lastModified' | 'name' | 'runCount';

/**
 * Whether an item passes the visibility filter, using the binary published split.
 * `public` keeps shared items; `private` keeps everything else; `all` keeps everything.
 */
export function matchesPublishedVisibility(isPublic: boolean, filter: VisibilityFilter): boolean {
  if (filter === 'all') return true;
  return filter === 'public' ? isPublic : !isPublic;
}

/** Case/locale-aware name compare; missing names sort as empty (first). */
export function compareName(a?: string | null, b?: string | null): number {
  return (a ?? '').localeCompare(b ?? '');
}

/**
 * Most-recent-first by ISO timestamp; missing / unparseable dates sort LAST. Returns a clean
 * sign (never NaN) so it is a valid Array.prototype.sort comparator even when both dates are
 * absent.
 */
export function compareDateDesc(a?: string | null, b?: string | null): number {
  const ta = a ? Date.parse(a) : NaN;
  const tb = b ? Date.parse(b) : NaN;
  const va = Number.isNaN(ta) ? null : ta;
  const vb = Number.isNaN(tb) ? null : tb;
  if (va === null && vb === null) return 0;
  if (va === null) return 1;  // a missing → a sorts after b
  if (vb === null) return -1; // b missing → a sorts before b
  return vb - va;
}

/** Largest-first numeric compare (e.g. run count); nullish counts as 0. */
export function compareNumberDesc(a?: number | null, b?: number | null): number {
  return (b ?? 0) - (a ?? 0);
}

/** Field accessors a surface supplies so {@link processList} can read its rows generically. */
export interface ListSortAccessors<T> {
  /** True when the item is shared (drives the public bucket + Globe marker). */
  isPublic: (item: T) => boolean;
  name: (item: T) => string | undefined | null;
  /** ISO last-modified timestamp (used by the default `lastModified` sort). */
  updatedAt?: (item: T) => string | undefined | null;
  /** ISO last-execution timestamp (used by the `lastExecuted` sort). */
  lastExecutedAt?: (item: T) => string | undefined | null;
  /** Run count (used by the `runCount` sort). */
  runCount?: (item: T) => number | undefined | null;
}

/**
 * Filter by visibility, then return a NEW array ordered by the sort key. Sort is stable for
 * equal keys (relies on Array.prototype.sort stability), so the caller's upstream order is
 * preserved as a tie-breaker. An accessor a sort needs but the surface omits resolves to a
 * neutral value (empty / 0 / undefined), so an unsupported key degrades gracefully rather
 * than throwing.
 */
export function processList<T>(
  items: T[],
  acc: ListSortAccessors<T>,
  visibility: VisibilityFilter,
  sort: ListSortKey,
): T[] {
  const filtered = visibility === 'all'
    ? items
    : items.filter((i) => matchesPublishedVisibility(acc.isPublic(i), visibility));
  const next = [...filtered];
  switch (sort) {
    case 'name':
      next.sort((a, b) => compareName(acc.name(a), acc.name(b)));
      break;
    case 'runCount':
      next.sort((a, b) => compareNumberDesc(acc.runCount?.(a), acc.runCount?.(b)));
      break;
    case 'lastExecuted':
      next.sort((a, b) => compareDateDesc(acc.lastExecutedAt?.(a), acc.lastExecutedAt?.(b)));
      break;
    case 'lastModified':
    default:
      next.sort((a, b) => compareDateDesc(acc.updatedAt?.(a), acc.updatedAt?.(b)));
      break;
  }
  return next;
}

/**
 * Stable partition floating favorited items to the top, preserving the incoming order within
 * each group. A no-op when no favorites are supplied (or none match), so the chosen sort is
 * otherwise untouched. This is how a list honors "favorites first" - mirrors
 * `applicationSort.favoritesFirst`, generalized over an id accessor so it works for any resource.
 */
export function favoritesFirst<T>(
  items: T[],
  getId: (item: T) => string | undefined | null,
  favoriteIds?: ReadonlySet<string>,
): T[] {
  if (!favoriteIds || favoriteIds.size === 0) return items;
  const favorites: T[] = [];
  const rest: T[] = [];
  for (const item of items) {
    const id = getId(item);
    if (id != null && favoriteIds.has(id)) favorites.push(item);
    else rest.push(item);
  }
  return [...favorites, ...rest];
}
