import { describe, expect, it } from 'vitest';
import { recencyKey, sortByRecency } from '../conversationRecency';

describe('recencyKey', () => {
  it('uses updatedAt when present', () => {
    expect(recencyKey({ updatedAt: '2026-06-03T10:00:00Z' })).toBe(Date.parse('2026-06-03T10:00:00Z'));
  });

  it('falls back to createdAt when updatedAt is missing', () => {
    expect(recencyKey({ createdAt: '2026-06-03T10:00:00Z' })).toBe(Date.parse('2026-06-03T10:00:00Z'));
  });

  it('prefers updatedAt over createdAt', () => {
    expect(recencyKey({ updatedAt: '2026-06-05T00:00:00Z', createdAt: '2026-06-01T00:00:00Z' })).toBe(
      Date.parse('2026-06-05T00:00:00Z'),
    );
  });

  it('returns 0 for missing or invalid timestamps', () => {
    expect(recencyKey({})).toBe(0);
    expect(recencyKey({ updatedAt: 'not-a-date' })).toBe(0);
  });
});

describe('sortByRecency', () => {
  it('orders items newest-first', () => {
    const out = sortByRecency([
      { id: 'a', updatedAt: '2026-06-01T00:00:00Z' },
      { id: 'b', updatedAt: '2026-06-03T00:00:00Z' },
      { id: 'c', updatedAt: '2026-06-02T00:00:00Z' },
    ]);
    expect(out.map((i) => i.id)).toEqual(['b', 'c', 'a']);
  });

  it('places missing/invalid timestamps last', () => {
    const out = sortByRecency([
      { id: 'bad', updatedAt: 'nope' },
      { id: 'good', updatedAt: '2026-06-04T00:00:00Z' },
      { id: 'none' },
    ]);
    expect(out[0].id).toBe('good');
    expect(out.map((i) => i.id).slice(1).sort()).toEqual(['bad', 'none']);
  });

  it('does not mutate the input array', () => {
    const input = [
      { id: 'a', updatedAt: '2026-06-01T00:00:00Z' },
      { id: 'b', updatedAt: '2026-06-03T00:00:00Z' },
    ];
    const snapshot = input.map((i) => i.id);
    sortByRecency(input);
    expect(input.map((i) => i.id)).toEqual(snapshot);
  });

  it('keeps equal-timestamp items in their original relative order (stable)', () => {
    const ts = '2026-06-02T00:00:00Z';
    const out = sortByRecency([
      { id: 'first', updatedAt: ts },
      { id: 'second', updatedAt: ts },
      { id: 'third', updatedAt: ts },
    ]);
    expect(out.map((i) => i.id)).toEqual(['first', 'second', 'third']);
  });
});
