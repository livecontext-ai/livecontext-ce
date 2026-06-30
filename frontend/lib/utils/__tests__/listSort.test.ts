/**
 * Pins the filter + sort rules shared by the resource listing pages (/app/workflow, /app/agent,
 * /app/interface, /app/tables): the binary published-vs-not visibility split (Globe / Lock), the
 * individual comparators, and processList() (filter-then-sort, stable, graceful when a surface
 * omits an accessor the chosen sort needs).
 */
import { describe, it, expect } from 'vitest';
import {
  matchesPublishedVisibility,
  compareName,
  compareDateDesc,
  compareNumberDesc,
  processList,
  type ListSortAccessors,
} from '../listSort';

describe('matchesPublishedVisibility', () => {
  it('all → every item passes regardless of published state', () => {
    expect(matchesPublishedVisibility(true, 'all')).toBe(true);
    expect(matchesPublishedVisibility(false, 'all')).toBe(true);
  });

  it('public → only shared (published) items', () => {
    expect(matchesPublishedVisibility(true, 'public')).toBe(true);
    expect(matchesPublishedVisibility(false, 'public')).toBe(false);
  });

  it('private → only NOT-shared items (drafts / in-review / rejected)', () => {
    expect(matchesPublishedVisibility(false, 'private')).toBe(true);
    expect(matchesPublishedVisibility(true, 'private')).toBe(false);
  });
});

describe('compareName', () => {
  it('orders alphabetically, treating missing names as empty (first)', () => {
    expect(compareName('apple', 'banana')).toBeLessThan(0);
    expect(compareName('banana', 'apple')).toBeGreaterThan(0);
    expect(compareName('apple', 'apple')).toBe(0);
    expect(compareName(null, 'apple')).toBeLessThan(0);
    expect(compareName(undefined, '')).toBe(0);
  });
});

describe('compareDateDesc', () => {
  it('orders most-recent-first', () => {
    expect(compareDateDesc('2026-06-18T00:00:00Z', '2026-06-17T00:00:00Z')).toBeLessThan(0);
    expect(compareDateDesc('2026-06-17T00:00:00Z', '2026-06-18T00:00:00Z')).toBeGreaterThan(0);
  });

  it('sorts missing / unparseable dates LAST (present beats absent)', () => {
    expect(compareDateDesc('2026-06-18T00:00:00Z', null)).toBeLessThan(0); // present before missing
    expect(compareDateDesc(null, '2026-06-18T00:00:00Z')).toBeGreaterThan(0);
    expect(compareDateDesc(null, undefined)).toBe(0);
    expect(compareDateDesc('not-a-date', null)).toBe(0); // both treated as -Infinity
  });
});

describe('compareNumberDesc', () => {
  it('orders largest-first, treating nullish as 0', () => {
    expect(compareNumberDesc(10, 2)).toBeLessThan(0);
    expect(compareNumberDesc(2, 10)).toBeGreaterThan(0);
    expect(compareNumberDesc(undefined, 5)).toBeGreaterThan(0); // 0 after 5
    expect(compareNumberDesc(5, null)).toBeLessThan(0);
  });
});

interface Row {
  id: string;
  title?: string | null;
  shared: boolean;
  modified?: string | null;
  executed?: string | null;
  runs?: number;
}

const ACC: ListSortAccessors<Row> = {
  isPublic: (r) => r.shared,
  name: (r) => r.title,
  updatedAt: (r) => r.modified,
  lastExecutedAt: (r) => r.executed,
  runCount: (r) => r.runs,
};

const rows: Row[] = [
  { id: 'a', title: 'Zebra',  shared: true,  modified: '2026-06-10T00:00:00Z', executed: '2026-06-01T00:00:00Z', runs: 3 },
  { id: 'b', title: 'apple',  shared: false, modified: '2026-06-18T00:00:00Z', executed: null,                   runs: 99 },
  { id: 'c', title: 'Mango',  shared: true,  modified: '2026-06-15T00:00:00Z', executed: '2026-06-17T00:00:00Z', runs: 0 },
];

describe('processList - visibility filter', () => {
  it('public keeps only shared rows', () => {
    expect(processList(rows, ACC, 'public', 'name').map(r => r.id).sort()).toEqual(['a', 'c']);
  });

  it('private keeps only non-shared rows', () => {
    expect(processList(rows, ACC, 'private', 'name').map(r => r.id)).toEqual(['b']);
  });

  it('all keeps every row', () => {
    expect(processList(rows, ACC, 'all', 'name')).toHaveLength(3);
  });
});

describe('processList - sorting', () => {
  it('name → case-insensitive alphabetical (localeCompare: apple, Mango, Zebra)', () => {
    expect(processList(rows, ACC, 'all', 'name').map(r => r.id)).toEqual(['b', 'c', 'a']);
  });

  it('lastModified → most-recently-modified first', () => {
    expect(processList(rows, ACC, 'all', 'lastModified').map(r => r.id)).toEqual(['b', 'c', 'a']);
  });

  it('lastExecuted → most-recent execution first, never-executed last', () => {
    expect(processList(rows, ACC, 'all', 'lastExecuted').map(r => r.id)).toEqual(['c', 'a', 'b']);
  });

  it('runCount → highest first', () => {
    expect(processList(rows, ACC, 'all', 'runCount').map(r => r.id)).toEqual(['b', 'a', 'c']);
  });

  it('does not mutate the input array', () => {
    const before = rows.map(r => r.id);
    processList(rows, ACC, 'all', 'name');
    expect(rows.map(r => r.id)).toEqual(before);
  });
});

describe('processList - graceful when an accessor is omitted', () => {
  it('a sort whose accessor the surface does not provide preserves input order', () => {
    const minimal: ListSortAccessors<Row> = { isPublic: (r) => r.shared, name: (r) => r.title };
    // No runCount accessor → all compare as 0 → stable input order kept.
    expect(processList(rows, minimal, 'all', 'runCount').map(r => r.id)).toEqual(['a', 'b', 'c']);
  });
});
