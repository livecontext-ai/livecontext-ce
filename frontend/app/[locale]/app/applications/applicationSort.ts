// Pure filter + sort helpers for the applications listing page.
// Kept out of the page component so the ordering rules are unit-testable.

export type AppSource = 'published' | 'acquired';

/** How the list is ordered. `execution` (default) = most recently run first. */
export type AppSortKey = 'execution' | 'name' | 'recent';

/** Which provenance bucket is shown. `installed` = acquired, `published` = own. */
export type AppSourceFilter = 'all' | 'installed' | 'published';

/**
 * Which visibility bucket of OWN published apps is shown. `all` = no
 * restriction. `public`/`private` narrow to published apps with that
 * marketplace visibility (acquired apps have no publisher visibility, so they
 * are excluded when this is anything but `all`).
 */
export type AppVisibilityFilter = 'all' | 'public' | 'private';

/** Minimal shape the ordering rules read - the page item is a superset. */
export interface SortableApp {
  pub: {
    id?: string;
    title?: string;
    publishedAt?: string;
    updatedAt?: string;
    visibility?: string;
    /**
     * Node-type tokens of the application's plan (`mcp:gmail`, `core:loop`, ...),
     * denormalized on the publication row so the list can filter on them without
     * loading a plan snapshot. Absent on rows published before V483.
     */
    nodeTypes?: string[];
  };
  source: AppSource;
  /** ISO timestamp the app was acquired (acquired apps only). */
  acquiredAt?: string;
  /** ISO timestamp of the app's last execution (lastFireAt ?? run startedAt). */
  lastExecutedAt?: string;
}

/** Epoch-ms of the last execution, or 0 when the app has never run. */
export function appExecutionTime(app: SortableApp): number {
  if (!app.lastExecutedAt) return 0;
  const t = Date.parse(app.lastExecutedAt);
  return Number.isNaN(t) ? 0 : t;
}

/** Epoch-ms of when the app entered the library (acquired → published → updated). */
export function appAddedTime(app: SortableApp): number {
  const candidate = app.acquiredAt ?? app.pub.publishedAt ?? app.pub.updatedAt;
  if (!candidate) return 0;
  const t = Date.parse(candidate);
  return Number.isNaN(t) ? 0 : t;
}

/** Keep only the apps matching the provenance filter. */
export function filterApps<T extends SortableApp>(apps: T[], filter: AppSourceFilter): T[] {
  if (filter === 'all') return apps;
  const wanted: AppSource = filter === 'installed' ? 'acquired' : 'published';
  return apps.filter((a) => a.source === wanted);
}

/**
 * Keep only OWN published apps matching the visibility filter. `public` =
 * marketplace-listed (`PUBLIC`); `private` = everything else a publisher can be
 * in (`PRIVATE` and legacy `UNLISTED`) so every published app falls into exactly
 * one of the two buckets. Acquired apps carry the publisher's visibility, not
 * the viewer's, so they are dropped whenever the filter is narrowed.
 */
export function filterByVisibility<T extends SortableApp>(apps: T[], filter: AppVisibilityFilter): T[] {
  if (filter === 'all') return apps;
  return apps.filter((a) => {
    if (a.source !== 'published') return false;
    const isPublic = (a.pub.visibility ?? '').toUpperCase() === 'PUBLIC';
    return filter === 'public' ? isPublic : !isPublic;
  });
}

/**
 * Keep only the apps containing one of the given node types.
 *
 * <p>ANY-of, exactly like the server-side filter on the workflows list: ticking
 * two integrations asks for the applications touching either. An empty
 * selection is no filter at all.
 */
export function filterByNodeTypes<T extends SortableApp>(apps: T[], nodeTypes: readonly string[]): T[] {
  if (!nodeTypes || nodeTypes.length === 0) return apps;
  const wanted = new Set(nodeTypes.map((token) => token.toLowerCase()));
  return apps.filter((app) => (app.pub.nodeTypes ?? []).some((token) => wanted.has(token.toLowerCase())));
}

/**
 * Count how many apps carry each node type, for the picker.
 *
 * <p>The applications page already holds its whole set in memory, so the
 * options are counted here rather than asked for: one source, and the counts
 * cannot describe a different set than the cards on screen. Ordered by
 * descending count then alphabetically, matching the server-side facets of the
 * workflows list so both pickers read the same way.
 */
export function nodeTypeFacets<T extends SortableApp>(apps: T[]): { value: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const app of apps) {
    // Distinct per app: the facet answers "how many applications would this
    // option show me", not how many nodes exist.
    for (const token of new Set((app.pub.nodeTypes ?? []).map((t) => t.toLowerCase()))) {
      if (!token) continue;
      counts.set(token, (counts.get(token) ?? 0) + 1);
    }
  }
  return [...counts.entries()]
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) => (b.count - a.count) || a.value.localeCompare(b.value));
}

/**
 * Return a new array sorted by the given key. Stable for equal keys (relies on
 * Array.prototype.sort stability) so the upstream dedup order is preserved as a
 * tie-breaker. Apps with no timestamp sort last for time-based keys.
 */
export function sortApps<T extends SortableApp>(apps: T[], sort: AppSortKey): T[] {
  const next = [...apps];
  if (sort === 'name') {
    next.sort((a, b) => (a.pub.title ?? '').localeCompare(b.pub.title ?? ''));
  } else if (sort === 'recent') {
    next.sort((a, b) => appAddedTime(b) - appAddedTime(a));
  } else {
    // 'execution' (default)
    next.sort((a, b) => appExecutionTime(b) - appExecutionTime(a));
  }
  return next;
}

/**
 * Stable partition floating favorited apps to the top, preserving the incoming
 * order within each group. A no-op when no favorites are supplied, so the chosen
 * sort is otherwise untouched. This is how the list honors "favorites first".
 */
export function favoritesFirst<T extends SortableApp>(apps: T[], favoriteIds?: ReadonlySet<string>): T[] {
  if (!favoriteIds || favoriteIds.size === 0) return apps;
  const favorites: T[] = [];
  const rest: T[] = [];
  for (const app of apps) {
    if (app.pub.id && favoriteIds.has(app.pub.id)) favorites.push(app);
    else rest.push(app);
  }
  return [...favorites, ...rest];
}

/**
 * Filter by provenance, then visibility, then node type, sort, then float
 * favorites to the top - the order the page renders. `favoriteIds` and
 * `nodeTypes` are optional; when omitted the result is exactly the sorted set
 * (back-compatible with callers that track neither).
 */
export function processApps<T extends SortableApp>(
  apps: T[],
  filter: AppSourceFilter,
  visibility: AppVisibilityFilter,
  sort: AppSortKey,
  favoriteIds?: ReadonlySet<string>,
  nodeTypes?: readonly string[],
): T[] {
  const narrowed = filterByNodeTypes(filterByVisibility(filterApps(apps, filter), visibility), nodeTypes ?? []);
  return favoritesFirst(sortApps(narrowed, sort), favoriteIds);
}
