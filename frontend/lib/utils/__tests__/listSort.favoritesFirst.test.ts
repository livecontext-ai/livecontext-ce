// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { favoritesFirst } from '../listSort';

const item = (id: string | null) => ({ id });

describe('favoritesFirst', () => {
  it('returns the SAME array reference when no favorites set is supplied (no-op)', () => {
    const items = [item('a'), item('b')];
    expect(favoritesFirst(items, (i) => i.id)).toBe(items);
  });

  it('returns the SAME array reference when the favorites set is empty (no-op)', () => {
    const items = [item('a'), item('b')];
    expect(favoritesFirst(items, (i) => i.id, new Set())).toBe(items);
  });

  it('floats favorited items to the front, preserving the incoming order within each group', () => {
    const items = ['a', 'b', 'c', 'd'].map(item);
    const out = favoritesFirst(items, (i) => i.id, new Set(['b', 'd']));
    expect(out.map((i) => i.id)).toEqual(['b', 'd', 'a', 'c']);
  });

  it('skips items whose id accessor returns null / undefined (they stay in the rest group)', () => {
    const items = [item(null), item('x'), item('y')];
    const out = favoritesFirst(items, (i) => i.id, new Set(['x']));
    expect(out.map((i) => i.id)).toEqual(['x', null, 'y']);
  });

  it('does not mutate the input array when favorites match', () => {
    const items = ['a', 'b'].map(item);
    const out = favoritesFirst(items, (i) => i.id, new Set(['b']));
    expect(out).not.toBe(items);
    expect(items.map((i) => i.id)).toEqual(['a', 'b']);
  });

  it('keeps everything (order unchanged) when a favorite id matches nothing in the list', () => {
    const items = ['a', 'b'].map(item);
    const out = favoritesFirst(items, (i) => i.id, new Set(['zzz']));
    expect(out.map((i) => i.id)).toEqual(['a', 'b']);
  });
});
