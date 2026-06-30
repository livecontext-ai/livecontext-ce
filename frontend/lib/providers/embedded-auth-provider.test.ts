// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { embeddedChangePassword } from './embedded-auth-provider';

const ACCESS_TOKEN_KEY = 'ce_access_token';

describe('embeddedChangePassword', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('posts to the change-password endpoint with the bearer token and body, then returns success', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'tok-123');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ success: true }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('oldpass', 'newpassword1');

    expect(result).toEqual({ success: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/proxy/auth/change-password');
    expect(opts.method).toBe('POST');
    expect(opts.headers.Authorization).toBe('Bearer tok-123');
    expect(opts.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(opts.body)).toEqual({ currentPassword: 'oldpass', newPassword: 'newpassword1' });
  });

  it('fails without a status (generic error, not a 401) and skips the network when no token is stored', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('whatever', 'newpassword1');

    expect(result.success).toBe(false);
    // A missing local token must NOT be mislabeled as "wrong current password" (401).
    expect(result.status).toBeUndefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces the backend status and message when the current password is wrong (401)', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'tok-123');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ message: 'Current password is incorrect' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('wrong', 'newpassword1');

    expect(result.success).toBe(false);
    expect(result.status).toBe(401);
    expect(result.error).toBe('Current password is incorrect');
  });

  it('surfaces a 400 (e.g. password too short) from the backend', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'tok-123');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ message: 'New password must be at least 8 characters' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('oldpass', 'short');

    expect(result.success).toBe(false);
    expect(result.status).toBe(400);
    expect(result.error).toBe('New password must be at least 8 characters');
  });

  it('returns a failure (never throws) on a network error', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'tok-123');
    const fetchMock = vi.fn().mockRejectedValue(new Error('boom'));
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('a', 'newpassword1');

    expect(result.success).toBe(false);
    expect(result.error).toBe('boom');
  });
});
