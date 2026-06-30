/**
 * External-proxy route handler (edition-agnostic - present in both CE and Cloud).
 *
 * The route is an authenticated, SSRF-guarded outbound proxy used by the "test this API" tool.
 * It must: (1) reject an unauthenticated caller (401) without fetching anything; (2) reject a
 * target URL the SSRF guard deems unsafe (400) without fetching anything; (3) when auth passes
 * and the URL is safe, fetch with `redirect: 'manual'` and return a SAME-ORIGIN JSON response
 * with NO wildcard CORS header.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest } from 'next/server';

import { isBackendAuthenticated } from '@/lib/security/edgeAuth';
import { assertOutboundUrlSafe } from '@/lib/security/ssrfGuard';

vi.mock('@/lib/security/edgeAuth', () => ({
  isBackendAuthenticated: vi.fn(),
}));
vi.mock('@/lib/security/ssrfGuard', () => ({
  assertOutboundUrlSafe: vi.fn(),
}));

import { POST } from '@/app/api/external-proxy/route';

const isBackendAuthenticatedMock = vi.mocked(isBackendAuthenticated);
const assertOutboundUrlSafeMock = vi.mocked(assertOutboundUrlSafe);

function proxyRequest(targetUrl = 'https://example.com'): NextRequest {
  return new NextRequest('http://localhost/api/external-proxy', {
    method: 'POST',
    headers: { authorization: 'Bearer x', 'content-type': 'application/json' },
    body: JSON.stringify({ url: targetUrl }),
  });
}

describe('POST /api/external-proxy', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.clearAllMocks();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('rejects an unauthenticated caller with 401 and does NOT fetch the target', async () => {
    isBackendAuthenticatedMock.mockResolvedValue(false);

    const response = await POST(proxyRequest());

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({ error: 'Unauthorized' });
    expect(assertOutboundUrlSafeMock).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('rejects an SSRF-unsafe target with 400 and does NOT fetch it', async () => {
    isBackendAuthenticatedMock.mockResolvedValue(true);
    assertOutboundUrlSafeMock.mockResolvedValue({
      safe: false,
      reason: 'Blocked private/loopback address',
    });

    const response = await POST(proxyRequest('http://169.254.169.254/latest/meta-data'));

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({
      error: 'Blocked private/loopback address',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('fetches a safe target with redirect:"manual" and returns same-origin JSON (no wildcard CORS)', async () => {
    isBackendAuthenticatedMock.mockResolvedValue(true);
    const safeUrl = new URL('https://example.com/');
    assertOutboundUrlSafeMock.mockResolvedValue({ safe: true, url: safeUrl });
    fetchMock.mockResolvedValue(
      new Response('{"ok":true}', {
        status: 200,
        statusText: 'OK',
        headers: { 'content-type': 'application/json' },
      }),
    );

    const response = await POST(proxyRequest());

    // The target WAS fetched, exactly once, with non-following redirects (SSRF defence in depth).
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [calledUrl, options] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(calledUrl).toBe(safeUrl);
    expect(options.redirect).toBe('manual');

    // Same-origin JSON: NO wildcard CORS header may be set on the response.
    expect(response.status).toBe(200);
    expect(response.headers.get('Access-Control-Allow-Origin')).toBeNull();
    await expect(response.json()).resolves.toMatchObject({
      status: 200,
      data: { ok: true },
      url: 'https://example.com/',
    });
  });
});
