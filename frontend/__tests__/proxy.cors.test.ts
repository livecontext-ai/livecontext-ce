// @vitest-environment node
import { describe, it, expect, vi, afterEach } from 'vitest';
import { NextRequest } from 'next/server';
import type { NextResponse } from 'next/server';

// The /api/proxy/* branch under test returns BEFORE intlMiddleware runs (see proxy.token.test.ts).
vi.mock('next-intl/middleware', () => ({ default: () => () => undefined }));

import { proxy, parseApiProxyAllowedOrigins } from '../proxy';

/**
 * LC-033 regression. The API proxy reflected ANY request Origin back in
 * Access-Control-Allow-Origin (and sent `*` when there was none) together with
 * Access-Control-Allow-Credentials: true. Production answered `access-control-allow-origin: *`
 * with credentials true. The app calls the proxy same-origin and needs no CORS header, so the
 * default is now to emit none, credentials are never advertised, and only origins listed in
 * API_PROXY_CORS_ALLOWED_ORIGINS are answered.
 */
const EVIL = 'https://evil.example';
const PARTNER = 'https://partner.example';

function call(method: string, origin?: string): NextResponse {
  const headers: Record<string, string> = {};
  if (origin) headers.origin = origin;
  return proxy(
    new NextRequest('http://localhost:3000/api/proxy/users/status', { method, headers }),
  ) as NextResponse;
}

describe('proxy.ts - API proxy CORS (LC-033)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it.each(['GET', 'OPTIONS'])('does not reflect a foreign Origin on %s by default', (method) => {
    const res = call(method, EVIL);

    expect(res.headers.get('access-control-allow-origin')).toBeNull();
    expect(res.headers.get('access-control-allow-credentials')).toBeNull();
  });

  it('never answers with a wildcard origin when the request has no Origin', () => {
    const res = call('GET');

    expect(res.headers.get('access-control-allow-origin')).toBeNull();
    expect(res.headers.get('access-control-allow-credentials')).toBeNull();
  });

  it('still answers an OPTIONS preflight with 204', () => {
    expect(call('OPTIONS', EVIL).status).toBe(204);
  });

  it('answers an explicitly allow-listed origin, without credentials', () => {
    vi.stubEnv('API_PROXY_CORS_ALLOWED_ORIGINS', `${PARTNER}, https://other.example`);

    const res = call('OPTIONS', PARTNER);

    expect(res.headers.get('access-control-allow-origin')).toBe(PARTNER);
    expect(res.headers.get('access-control-allow-methods')).toContain('PATCH');
    expect(res.headers.get('access-control-allow-headers')).toContain('Authorization');
    expect(res.headers.get('access-control-allow-credentials')).toBeNull();
    expect(res.headers.get('vary')).toContain('Origin');
  });

  it('refuses an origin that is not on a configured allow-list', () => {
    vi.stubEnv('API_PROXY_CORS_ALLOWED_ORIGINS', PARTNER);

    const res = call('GET', EVIL);

    expect(res.headers.get('access-control-allow-origin')).toBeNull();
  });

  it('ignores a wildcard or the opaque null origin even when configured', () => {
    vi.stubEnv('API_PROXY_CORS_ALLOWED_ORIGINS', '*, null, https://*.example');

    expect(call('GET', EVIL).headers.get('access-control-allow-origin')).toBeNull();
    expect(call('GET', 'null').headers.get('access-control-allow-origin')).toBeNull();
  });

  // Regression: the rewrite forwarded the browser Origin, and the CE backend (allow-list =
  // APP_PUBLIC_URL/APP_BASE_URL) answered "403 Invalid CORS request" to every browser POST from
  // an install reached on another address, e.g. the default http://localhost:8870.
  it.each(['GET', 'POST'])('does not forward the browser Origin upstream on %s', (method) => {
    const res = call(method, 'http://localhost:8870');

    expect(res.headers.get('x-middleware-rewrite')).toBe('http://localhost:8080/api/users/status');
    expect(res.headers.get('x-middleware-request-origin')).toBeNull();
  });

  it('still forwards the other request headers upstream', () => {
    const res = proxy(
      new NextRequest('http://localhost:3000/api/proxy/users/status', {
        method: 'POST',
        headers: { origin: EVIL, authorization: 'Bearer abc', 'x-active-organization-id': 'org-1' },
      }),
    ) as NextResponse;

    expect(res.headers.get('x-middleware-request-authorization')).toBe('Bearer abc');
    expect(res.headers.get('x-middleware-request-x-active-organization-id')).toBe('org-1');
    expect(res.headers.get('x-middleware-request-origin')).toBeNull();
  });

  it('parses the allow-list strictly', () => {
    expect([...parseApiProxyAllowedOrigins(undefined)]).toEqual([]);
    expect([...parseApiProxyAllowedOrigins(' https://a.example ,, *,null,NULL ')]).toEqual(['https://a.example']);
  });
});
