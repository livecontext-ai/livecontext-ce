import { describe, it, expect } from 'vitest';
import { normalizeScopes } from '../normalizeScopes';

describe('normalizeScopes', () => {
  it('returns [] for null / undefined / empty input - must not throw before the auth callback populates the array', () => {
    expect(normalizeScopes(null)).toEqual([]);
    expect(normalizeScopes(undefined)).toEqual([]);
    expect(normalizeScopes([])).toEqual([]);
  });

  it('passes through a clean array unchanged - the well-formed RFC 6749 case (Google, GitHub, …) costs no transformation', () => {
    expect(normalizeScopes(['channels:read', 'channels:write'])).toEqual([
      'channels:read', 'channels:write',
    ]);
  });

  it('regression - the Slack single-element comma-blob: when the auth callback stored the entire `authed_user.scope` string as scopes[0], split it into N individual scopes so the credentials list shows them as N separate badges, not 1 mega-scope', () => {
    const slackBlob = [
      'channels:read,channels:history,files:read,reactions:read,pins:read',
    ];
    expect(normalizeScopes(slackBlob)).toEqual([
      'channels:read', 'channels:history', 'files:read', 'reactions:read', 'pins:read',
    ]);
  });

  it('regression - MissingScopesBanner false positives: with the comma-blob shape, `Set.has(individualScope)` never matches. After normalize, every required scope can be looked up correctly. This pins the contract that powers the missing-scope membership check', () => {
    const granted = new Set(normalizeScopes(['a:read,b:write,c:admin']));
    expect(granted.has('a:read')).toBe(true);
    expect(granted.has('b:write')).toBe(true);
    expect(granted.has('c:admin')).toBe(true);
    expect(granted.has('a')).toBe(false); // exact-match boundary
  });

  it('handles whitespace-separated input - covers the (rarer) case where space concatenation reaches the array as one element', () => {
    expect(normalizeScopes(['scope.a scope.b   scope.c'])).toEqual([
      'scope.a', 'scope.b', 'scope.c',
    ]);
  });

  it('handles mixed delimiters in the same element - `,` and whitespace interleaved, no surprise on real-world quirks', () => {
    expect(normalizeScopes(['a:read, b:write,  c:admin\td:owner'])).toEqual([
      'a:read', 'b:write', 'c:admin', 'd:owner',
    ]);
  });

  it('drops empty fragments - stray double commas / leading-trailing separators must not produce empty bullets', () => {
    expect(normalizeScopes([',a:read,,b:write,'])).toEqual(['a:read', 'b:write']);
    expect(normalizeScopes(['  a  '])).toEqual(['a']);
  });

  it('preserves order - the tooltip lists scopes in provider-returned order so users can match against the consent screen they just saw', () => {
    expect(normalizeScopes(['z,y,x', 'w'])).toEqual(['z', 'y', 'x', 'w']);
  });

  it('keeps duplicates - dedup is the caller decision (Set wraps for matching, tooltip prefers showing duplicates over hiding them)', () => {
    expect(normalizeScopes(['a,b,a'])).toEqual(['a', 'b', 'a']);
  });

  it('skips non-string array elements defensively - bad data must not crash the render', () => {
    const dirty = ['a', null as any, 42 as any, 'b,c', undefined as any];
    expect(normalizeScopes(dirty)).toEqual(['a', 'b', 'c']);
  });
});
