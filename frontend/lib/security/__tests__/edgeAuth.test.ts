import { describe, it, expect, vi } from 'vitest';
import { isBackendAuthenticated } from '@/lib/security/edgeAuth';

/**
 * Regression: the external-proxy route previously accepted any request whose Authorization header
 * merely started with "Bearer " (no validation) - an effectively unauthenticated open proxy. The
 * token must be validated by the backend (the auth authority); the gate is fail-closed.
 */
describe('isBackendAuthenticated', () => {
  const baseUrl = 'http://backend:8080';
  const okFetch = vi.fn(async () => new Response('[]', { status: 200 }));

  it('rejects a missing / malformed / empty Bearer header WITHOUT calling the backend', async () => {
    const fetcher = vi.fn();
    for (const header of [null, '', 'Bearer', 'Bearer ', 'Basic abc', 'token']) {
      expect(await isBackendAuthenticated(header, baseUrl, fetcher as unknown as typeof fetch)).toBe(false);
    }
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('accepts a token the backend validates (2xx) and probes the authenticated endpoint with it', async () => {
    const fetcher = vi.fn(async () => new Response('[]', { status: 200 }));
    const result = await isBackendAuthenticated('Bearer good.jwt.token', baseUrl, fetcher as unknown as typeof fetch);

    expect(result).toBe(true);
    expect(fetcher).toHaveBeenCalledTimes(1);
    const [url, init] = fetcher.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://backend:8080/api/organizations/me');
    expect(init.headers).toMatchObject({ Authorization: 'Bearer good.jwt.token' });
  });

  it('rejects a token the backend rejects (401/403)', async () => {
    for (const status of [401, 403, 400, 500]) {
      const fetcher = vi.fn(async () => new Response('', { status }));
      expect(
        await isBackendAuthenticated('Bearer bad', baseUrl, fetcher as unknown as typeof fetch),
      ).toBe(false);
    }
  });

  it('fails closed when the backend is unreachable (fetch throws)', async () => {
    const fetcher = vi.fn(async () => {
      throw new Error('ECONNREFUSED');
    });
    expect(await isBackendAuthenticated('Bearer good', baseUrl, fetcher as unknown as typeof fetch)).toBe(false);
  });

  it('uses okFetch helper shape (smoke: 2xx → true)', async () => {
    expect(await isBackendAuthenticated('Bearer good', baseUrl, okFetch as unknown as typeof fetch)).toBe(true);
  });
});
