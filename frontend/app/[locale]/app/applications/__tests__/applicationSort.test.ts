import { describe, it, expect } from 'vitest';
import {
  filterApps,
  filterByVisibility,
  sortApps,
  processApps,
  favoritesFirst,
  appExecutionTime,
  appAddedTime,
  type SortableApp,
} from '../applicationSort';

/**
 * Ordering + provenance rules for the /app/applications listing.
 * Default sort = most recently executed first; provenance filter splits
 * acquired ("installed") from own ("published").
 */

function mk(over: Partial<SortableApp> & { title: string }): SortableApp {
  const { title, ...rest } = over;
  return {
    pub: { title, publishedAt: rest.pub?.publishedAt, updatedAt: rest.pub?.updatedAt, visibility: rest.pub?.visibility },
    source: rest.source ?? 'published',
    acquiredAt: rest.acquiredAt,
    lastExecutedAt: rest.lastExecutedAt,
  };
}

describe('appExecutionTime', () => {
  it('returns 0 when never executed', () => {
    expect(appExecutionTime(mk({ title: 'a' }))).toBe(0);
  });
  it('returns 0 for an unparseable timestamp', () => {
    expect(appExecutionTime(mk({ title: 'a', lastExecutedAt: 'not-a-date' }))).toBe(0);
  });
  it('parses an ISO timestamp', () => {
    expect(appExecutionTime(mk({ title: 'a', lastExecutedAt: '2026-01-01T00:00:00Z' })))
      .toBe(Date.parse('2026-01-01T00:00:00Z'));
  });
});

describe('appAddedTime', () => {
  it('prefers acquiredAt, then publishedAt, then updatedAt', () => {
    expect(appAddedTime(mk({ title: 'a', acquiredAt: '2026-03-01T00:00:00Z', pub: { title: 'a', publishedAt: '2025-01-01T00:00:00Z' } as any })))
      .toBe(Date.parse('2026-03-01T00:00:00Z'));
    expect(appAddedTime({ pub: { publishedAt: '2025-05-05T00:00:00Z' }, source: 'published' }))
      .toBe(Date.parse('2025-05-05T00:00:00Z'));
    expect(appAddedTime({ pub: { updatedAt: '2024-02-02T00:00:00Z' }, source: 'published' }))
      .toBe(Date.parse('2024-02-02T00:00:00Z'));
  });
});

describe('filterApps', () => {
  const apps = [
    mk({ title: 'own', source: 'published' }),
    mk({ title: 'installed', source: 'acquired' }),
  ];
  it('all → returns everything', () => {
    expect(filterApps(apps, 'all')).toHaveLength(2);
  });
  it('installed → only acquired', () => {
    expect(filterApps(apps, 'installed').map((a) => a.source)).toEqual(['acquired']);
  });
  it('published → only own', () => {
    expect(filterApps(apps, 'published').map((a) => a.source)).toEqual(['published']);
  });
});

describe('filterByVisibility', () => {
  const apps = [
    mk({ title: 'pub-public', source: 'published', pub: { title: 'pub-public', visibility: 'PUBLIC' } as any }),
    mk({ title: 'pub-private', source: 'published', pub: { title: 'pub-private', visibility: 'PRIVATE' } as any }),
    mk({ title: 'pub-unlisted', source: 'published', pub: { title: 'pub-unlisted', visibility: 'UNLISTED' } as any }),
    mk({ title: 'pub-missing', source: 'published', pub: { title: 'pub-missing' } as any }),
    mk({ title: 'acquired-public', source: 'acquired', pub: { title: 'acquired-public', visibility: 'PUBLIC' } as any }),
  ];

  it('all → returns everything untouched', () => {
    expect(filterByVisibility(apps, 'all')).toHaveLength(5);
  });

  it('public → only OWN published apps with PUBLIC visibility (acquired excluded)', () => {
    expect(filterByVisibility(apps, 'public').map((a) => a.pub.title)).toEqual(['pub-public']);
  });

  it('private → every published app that is NOT public (PRIVATE, UNLISTED, missing)', () => {
    expect(filterByVisibility(apps, 'private').map((a) => a.pub.title))
      .toEqual(['pub-private', 'pub-unlisted', 'pub-missing']);
  });

  it('is case-insensitive on the stored visibility value', () => {
    const lower = [mk({ title: 'lc', source: 'published', pub: { title: 'lc', visibility: 'public' } as any })];
    expect(filterByVisibility(lower, 'public').map((a) => a.pub.title)).toEqual(['lc']);
  });
});

describe('sortApps', () => {
  it('execution (default) → most recently executed first; never-run sort last', () => {
    const apps = [
      mk({ title: 'old', lastExecutedAt: '2026-01-01T00:00:00Z' }),
      mk({ title: 'never' }),
      mk({ title: 'recent', lastExecutedAt: '2026-06-01T00:00:00Z' }),
    ];
    expect(sortApps(apps, 'execution').map((a) => a.pub.title)).toEqual(['recent', 'old', 'never']);
  });

  it('name → alphabetical by title', () => {
    const apps = [mk({ title: 'Charlie' }), mk({ title: 'alpha' }), mk({ title: 'Bravo' })];
    expect(sortApps(apps, 'name').map((a) => a.pub.title)).toEqual(['alpha', 'Bravo', 'Charlie']);
  });

  it('recent → most recently added first', () => {
    const apps = [
      mk({ title: 'mid', acquiredAt: '2026-03-01T00:00:00Z', source: 'acquired' }),
      mk({ title: 'newest', acquiredAt: '2026-06-01T00:00:00Z', source: 'acquired' }),
      mk({ title: 'oldest', acquiredAt: '2026-01-01T00:00:00Z', source: 'acquired' }),
    ];
    expect(sortApps(apps, 'recent').map((a) => a.pub.title)).toEqual(['newest', 'mid', 'oldest']);
  });

  it('does not mutate the input array', () => {
    const apps = [mk({ title: 'b', lastExecutedAt: '2026-01-01T00:00:00Z' }), mk({ title: 'a', lastExecutedAt: '2026-02-01T00:00:00Z' })];
    const snapshot = apps.map((a) => a.pub.title);
    sortApps(apps, 'execution');
    expect(apps.map((a) => a.pub.title)).toEqual(snapshot);
  });
});

describe('processApps', () => {
  it('filters provenance THEN sorts (default execution, no visibility restriction)', () => {
    const apps = [
      mk({ title: 'ownRecent', source: 'published', lastExecutedAt: '2026-06-01T00:00:00Z' }),
      mk({ title: 'installedOld', source: 'acquired', lastExecutedAt: '2026-01-01T00:00:00Z' }),
      mk({ title: 'installedRecent', source: 'acquired', lastExecutedAt: '2026-05-01T00:00:00Z' }),
    ];
    const result = processApps(apps, 'installed', 'all', 'execution');
    expect(result.map((a) => a.pub.title)).toEqual(['installedRecent', 'installedOld']);
  });

  it('applies the visibility filter on top of provenance, then sorts', () => {
    const apps = [
      mk({ title: 'pubPublicRecent', source: 'published', lastExecutedAt: '2026-06-01T00:00:00Z', pub: { title: 'pubPublicRecent', visibility: 'PUBLIC' } as any }),
      mk({ title: 'pubPublicOld', source: 'published', lastExecutedAt: '2026-01-01T00:00:00Z', pub: { title: 'pubPublicOld', visibility: 'PUBLIC' } as any }),
      mk({ title: 'pubPrivate', source: 'published', lastExecutedAt: '2026-05-01T00:00:00Z', pub: { title: 'pubPrivate', visibility: 'PRIVATE' } as any }),
    ];
    const result = processApps(apps, 'all', 'public', 'execution');
    expect(result.map((a) => a.pub.title)).toEqual(['pubPublicRecent', 'pubPublicOld']);
  });

  it('private visibility + installed provenance yields nothing (acquired apps are never "your published")', () => {
    const apps = [
      mk({ title: 'installed', source: 'acquired', pub: { title: 'installed', visibility: 'PRIVATE' } as any }),
    ];
    expect(processApps(apps, 'installed', 'private', 'execution')).toEqual([]);
  });

  it('floats favorites to the top AFTER sorting (favorites-first overrides the chosen order)', () => {
    const apps: SortableApp[] = [
      { pub: { id: 'recent', title: 'recent' }, source: 'published', lastExecutedAt: '2026-06-01T00:00:00Z' },
      { pub: { id: 'old', title: 'old' }, source: 'published', lastExecutedAt: '2026-01-01T00:00:00Z' },
    ];
    // No favorites → plain execution sort (recent first).
    expect(processApps(apps, 'all', 'all', 'execution').map((a) => a.pub.id)).toEqual(['recent', 'old']);
    // 'old' favorited → it floats above 'recent' despite being the less-recently-run app.
    expect(processApps(apps, 'all', 'all', 'execution', new Set(['old'])).map((a) => a.pub.id))
      .toEqual(['old', 'recent']);
  });
});

describe('favoritesFirst', () => {
  const withId = (id: string, title: string): SortableApp => ({ pub: { id, title }, source: 'published' });

  it('returns the list unchanged when no favorite ids are supplied', () => {
    const apps = [withId('a', 'A'), withId('b', 'B')];
    expect(favoritesFirst(apps).map((a) => a.pub.id)).toEqual(['a', 'b']);
    expect(favoritesFirst(apps, new Set()).map((a) => a.pub.id)).toEqual(['a', 'b']);
  });

  it('floats favorited apps to the top, preserving the incoming order within each group', () => {
    const apps = [withId('a', 'A'), withId('b', 'B'), withId('c', 'C'), withId('d', 'D')];
    expect(favoritesFirst(apps, new Set(['b', 'd'])).map((a) => a.pub.id)).toEqual(['b', 'd', 'a', 'c']);
  });

  it('is a stable partition - it does not reorder the favorites by the favorite-set order', () => {
    const apps = [withId('1', 'one'), withId('2', 'two'), withId('3', 'three')];
    // favorites set lists 3 before 1, but the rendered order follows the LIST order (1 then 3).
    expect(favoritesFirst(apps, new Set(['3', '1'])).map((a) => a.pub.id)).toEqual(['1', '3', '2']);
  });

  it('ignores apps with no id (cannot be matched against the favorites set)', () => {
    const apps: SortableApp[] = [{ pub: { title: 'no-id' }, source: 'published' }, withId('b', 'B')];
    expect(favoritesFirst(apps, new Set(['b'])).map((a) => a.pub.id)).toEqual(['b', undefined]);
  });
});
