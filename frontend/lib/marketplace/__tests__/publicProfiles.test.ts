import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('server-only', () => ({}));

import { fetchPublicProfile, fetchPublicationsByPublisher, mapProfile } from '../publicProfiles';

const ORIGINAL_ENV = { ...process.env };

function rawProfile(overrides: Record<string, unknown> = {}) {
  return {
    userId: 42,
    displayName: 'John Doe',
    handle: 'john-doe',
    avatarUrl: 'avatar-uuid',
    bio: 'Builds invoice bots.',
    joinedAt: '2026-01-15T09:00:00',
    searchIndexable: true,
    verified: true,
    ...overrides,
  };
}

describe('mapProfile', () => {
  it('maps a complete profile', () => {
    expect(mapProfile(rawProfile())).toEqual({
      userId: 42,
      displayName: 'John Doe',
      handle: 'john-doe',
      avatarUrl: 'avatar-uuid',
      bio: 'Builds invoice bots.',
      joinedAt: '2026-01-15T09:00:00',
      searchIndexable: true,
      verified: true,
    });
  });

  it.each([
    ['the field is absent', undefined],
    ['it is the string "true"', 'true'],
    ['it is the number 1', 1],
    ['it is null', null],
    ['it is explicitly false', false],
  ])('is not verified when %s', (_label, verified) => {
    // Strict equality with `true`, same reasoning as searchIndexable: a badge is a
    // public claim about who an account belongs to, and a malformed payload must
    // never be able to grant one.
    expect(mapProfile(rawProfile({ verified }))?.verified).toBe(false);
  });

  it.each([
    ['the field is absent', undefined],
    ['it is the string "true"', 'true'],
    ['it is the number 1', 1],
    ['it is null', null],
    ['it is explicitly false', false],
  ])('is not search-indexable when %s', (_label, searchIndexable) => {
    // Strict equality with `true` on purpose: defaulting the other way, or
    // accepting anything truthy, would index a profile whose owner never opted
    // in. UNLISTED is the default state, so most rows arrive here as false.
    expect(mapProfile(rawProfile({ searchIndexable }))?.searchIndexable).toBe(false);
  });

  it('returns null without a handle: there would be no canonical URL', () => {
    expect(mapProfile(rawProfile({ handle: null }))).toBeNull();
  });

  it('returns null without a user id: the listings grid could not be fetched', () => {
    expect(mapProfile(rawProfile({ userId: undefined }))).toBeNull();
  });

  it('keeps an absent display name as null rather than the string "null"', () => {
    expect(mapProfile(rawProfile({ displayName: null }))?.displayName).toBeNull();
  });

  it.each([null, undefined, 'nope', 7])('returns null for a non-object payload (%s)', (input) => {
    expect(mapProfile(input)).toBeNull();
  });
});

describe('fetchPublicProfile', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
    vi.unstubAllGlobals();
  });

  it('reads the by-handle endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => rawProfile() });
    vi.stubGlobal('fetch', fetchMock);

    const profile = await fetchPublicProfile('john-doe');

    expect(profile?.handle).toBe('john-doe');
    expect(fetchMock.mock.calls[0][0]).toBe('http://gw:8080/api/users/public/by-handle/john-doe');
  });

  it('never calls the by-id endpoint, which stays gated against id enumeration', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => rawProfile() });
    vi.stubGlobal('fetch', fetchMock);

    await fetchPublicProfile('john-doe');

    expect(fetchMock.mock.calls[0][0]).not.toContain('/by-id/');
  });

  it.each([
    ['a path traversal attempt', '../../internal/secrets'],
    ['an uppercase handle', 'JohnDoe'],
    ['a handle with a slash', 'john/doe'],
    ['a one-character handle', 'a'],
    ['an over-long handle', 'a'.repeat(33)],
    ['an empty handle', ''],
  ])('rejects %s before any network call', async (_label, handle) => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPublicProfile(handle)).resolves.toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('returns null on 404, so a private profile is indistinguishable from a missing one', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({}) }));

    await expect(fetchPublicProfile('john-doe')).resolves.toBeNull();
  });

  it('returns null when the gateway is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

    await expect(fetchPublicProfile('john-doe')).resolves.toBeNull();
  });
});

describe('fetchPublicationsByPublisher', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
    vi.unstubAllGlobals();
  });

  it('reads the public by-publisher listing', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ publications: [{ id: 'pub-1', title: 'Invoice Bot' }] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const items = await fetchPublicationsByPublisher(42);

    expect(items).toHaveLength(1);
    expect(fetchMock.mock.calls[0][0]).toBe('http://gw:8080/api/publications/by-publisher/42');
  });

  it('returns an empty list on failure so the profile page still renders', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('boom')));

    await expect(fetchPublicationsByPublisher(42)).resolves.toEqual([]);
  });
});
