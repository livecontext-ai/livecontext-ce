/**
 * Pins the marketplace-visibility bucketing shared by the "My Shared" tab, /app/applications, and
 * the workflow + application boards: PUBLIC vs everything-else for the footer indicator, and the
 * three filter buckets - crucially, narrowing DROPS items with no visibility (unpublished workflows,
 * acquired apps) rather than lumping them into "private". Kept in lockstep with
 * applicationSort.filterByVisibility.
 */
import { describe, it, expect } from 'vitest';
import { isPublicVisibility, matchesVisibilityFilter } from '../visibility';

describe('isPublicVisibility', () => {
  it('only PUBLIC (any case) is public; everything else is private', () => {
    expect(isPublicVisibility('PUBLIC')).toBe(true);
    expect(isPublicVisibility('public')).toBe(true);
    expect(isPublicVisibility('PRIVATE')).toBe(false);
    expect(isPublicVisibility('UNLISTED')).toBe(false);
    expect(isPublicVisibility('')).toBe(false);
    expect(isPublicVisibility(null)).toBe(false);
    expect(isPublicVisibility(undefined)).toBe(false);
  });
});

describe('matchesVisibilityFilter', () => {
  it('all → every item passes, including items with no visibility', () => {
    expect(matchesVisibilityFilter('PUBLIC', 'all')).toBe(true);
    expect(matchesVisibilityFilter('PRIVATE', 'all')).toBe(true);
    expect(matchesVisibilityFilter(null, 'all')).toBe(true);
    expect(matchesVisibilityFilter(undefined, 'all')).toBe(true);
  });

  it('public → only PUBLIC', () => {
    expect(matchesVisibilityFilter('PUBLIC', 'public')).toBe(true);
    expect(matchesVisibilityFilter('PRIVATE', 'public')).toBe(false);
    expect(matchesVisibilityFilter('UNLISTED', 'public')).toBe(false);
    expect(matchesVisibilityFilter(null, 'public')).toBe(false);
    expect(matchesVisibilityFilter(undefined, 'public')).toBe(false);
  });

  it('private → PRIVATE / UNLISTED, but NOT items with no visibility', () => {
    expect(matchesVisibilityFilter('PRIVATE', 'private')).toBe(true);
    expect(matchesVisibilityFilter('UNLISTED', 'private')).toBe(true);
    expect(matchesVisibilityFilter('PUBLIC', 'private')).toBe(false);
    // The load-bearing nuance: an unpublished workflow / acquired app (no visibility - null,
    // undefined, or blank) is excluded when narrowing, never lumped into "private".
    expect(matchesVisibilityFilter(null, 'private')).toBe(false);
    expect(matchesVisibilityFilter(undefined, 'private')).toBe(false);
    expect(matchesVisibilityFilter('', 'private')).toBe(false);
  });
});
