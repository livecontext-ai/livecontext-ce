import { describe, expect, it } from 'vitest';
import {
  hasPersistedOidcUser,
  isPersistedOidcUserMissing,
  isRemovedOidcUserStorageEvent,
} from '../smart-providers';

function fakeStorage(values: Record<string, string>): Pick<Storage, 'getItem' | 'key' | 'length'> {
  const entries = Object.entries(values);
  return {
    length: entries.length,
    key: (index: number) => entries[index]?.[0] ?? null,
    getItem: (key: string) => values[key] ?? null,
  };
}

describe('isRemovedOidcUserStorageEvent', () => {
  const configuredKey = 'oidc.user:http://localhost:8180/realms/livecontext:livecontext-frontend';

  it('detects removal of the configured OIDC key', () => {
    expect(isRemovedOidcUserStorageEvent(configuredKey, '{"access_token":"old"}', null, configuredKey)).toBe(true);
  });

  it('detects removal of any persisted OIDC user key when the configured Keycloak URL drifted', () => {
    const actualStoredKey = 'oidc.user:http://127.0.0.1:8180/realms/livecontext:livecontext-frontend';

    expect(isRemovedOidcUserStorageEvent(actualStoredKey, '{"access_token":"old"}', null, configuredKey)).toBe(true);
  });

  it('treats storage.clear as logout when the OIDC entry existed', () => {
    expect(isRemovedOidcUserStorageEvent(null, null, null, configuredKey)).toBe(true);
  });

  it('ignores unrelated keys and OIDC writes', () => {
    expect(isRemovedOidcUserStorageEvent('lc.activeOrg', 'old', null, configuredKey)).toBe(false);
    expect(isRemovedOidcUserStorageEvent(configuredKey, null, '{"access_token":"new"}', configuredKey)).toBe(false);
  });
});

describe('hasPersistedOidcUser', () => {
  const configuredKey = 'oidc.user:http://localhost:8180/realms/livecontext:livecontext-frontend';

  it('detects the configured persisted OIDC user', () => {
    const storage = fakeStorage({ [configuredKey]: '{"access_token":"token"}' });

    expect(hasPersistedOidcUser(storage, configuredKey)).toBe(true);
  });

  it('detects any persisted OIDC user key when configuration drifted', () => {
    const storage = fakeStorage({
      'oidc.user:http://127.0.0.1:8180/realms/livecontext:livecontext-frontend': '{"access_token":"token"}',
    });

    expect(hasPersistedOidcUser(storage, configuredKey)).toBe(true);
  });

  it('returns false when no OIDC user remains in storage', () => {
    const storage = fakeStorage({ 'lc.activeOrg': '{"state":{}}' });

    expect(hasPersistedOidcUser(storage, configuredKey)).toBe(false);
  });

  it('fails open when browser storage cannot be read', () => {
    const storage = {
      length: 1,
      key: () => {
        throw new Error('blocked');
      },
      getItem: () => {
        throw new Error('blocked');
      },
    };

    expect(hasPersistedOidcUser(storage, configuredKey)).toBe(true);
  });
});

describe('isPersistedOidcUserMissing', () => {
  const configuredKey = 'oidc.user:http://localhost:8180/realms/livecontext:livecontext-frontend';

  it('detects an in-memory OIDC user after the persisted localStorage entry disappears', () => {
    const storage = fakeStorage({ 'lc.activeOrg': '{"state":{}}' });

    expect(isPersistedOidcUserMissing(false, { profile: { sub: 'user-1' } }, storage, configuredKey)).toBe(true);
  });

  it('does not expire embedded auth or an OIDC session with persisted storage', () => {
    const storage = fakeStorage({ [configuredKey]: '{"access_token":"token"}' });

    expect(isPersistedOidcUserMissing(true, { profile: { sub: 'user-1' } }, fakeStorage({}), configuredKey)).toBe(false);
    expect(isPersistedOidcUserMissing(false, { profile: { sub: 'user-1' } }, storage, configuredKey)).toBe(false);
  });

  it('is NOT missing on a cold first visit (no in-memory OIDC user)', () => {
    // The first-connect case driving the session-gate copy: with no user loaded
    // there is no session to be "missing", so this is false for both editions.
    // That keeps effectiveSessionExpired false on a fresh slot / cloud landing,
    // so the gate shows the neutral "sign in" copy, not "session expired".
    expect(isPersistedOidcUserMissing(false, null, fakeStorage({}), configuredKey)).toBe(false);
    expect(isPersistedOidcUserMissing(false, undefined, fakeStorage({}), configuredKey)).toBe(false);
    expect(isPersistedOidcUserMissing(true, null, fakeStorage({}), configuredKey)).toBe(false);
  });
});
