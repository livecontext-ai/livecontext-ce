// @vitest-environment jsdom
/**
 * Orbi waves once after a sign-in. The flag is written by three sign-in paths in two editions
 * and read by the mascot, so the producers are tested here against the real consumer: a path
 * that forgets to mark would leave Orbi silent on that edition with nothing failing.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { consumeOrbiGreeting, markOrbiGreeting, ORBI_GREETING_KEY } from '../orbiGreeting';
import { oidcConfig } from '../../../../app/providers';
import { embeddedLogin, embeddedRegister } from '../../../../lib/providers/embedded-auth-provider';

const fetchMock = vi.fn();
const tokens = { accessToken: 'a', refreshToken: 'r', expiresIn: 300, user: { id: 1 } };

beforeEach(() => {
  sessionStorage.clear();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  sessionStorage.clear();
});

describe('orbiGreeting flag', () => {
  it('is consumed exactly once, so only the first Orbi waves', () => {
    expect(consumeOrbiGreeting()).toBe(false);
    markOrbiGreeting();
    expect(consumeOrbiGreeting()).toBe(true);
    expect(consumeOrbiGreeting()).toBe(false);
  });

  it('never throws when storage is unavailable: Orbi just does not wave', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(() => markOrbiGreeting()).not.toThrow();
    expect(consumeOrbiGreeting()).toBe(false);
  });
});

describe('every sign-in path marks the greeting', () => {
  it('cloud: the Keycloak sign-in callback', () => {
    oidcConfig.onSigninCallback();
    expect(consumeOrbiGreeting()).toBe(true);
  });

  it('CE: a successful embedded login, and not a refused one', async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 401, json: async () => ({ message: 'no' }) });
    await embeddedLogin('a@b.c', 'wrong');
    expect(sessionStorage.getItem(ORBI_GREETING_KEY)).toBeNull();

    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, json: async () => tokens });
    await embeddedLogin('a@b.c', 'right');
    expect(consumeOrbiGreeting()).toBe(true);
  });

  it('CE: a successful registration, which signs the new user in', async () => {
    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, json: async () => tokens });
    await embeddedRegister('a@b.c', 'pw', 'A', 'B');
    expect(consumeOrbiGreeting()).toBe(true);
  });
});
