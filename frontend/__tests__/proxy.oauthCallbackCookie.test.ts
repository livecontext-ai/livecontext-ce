// @vitest-environment node
import { describe, it, expect, vi } from 'vitest';
import { NextRequest } from 'next/server';
import type { NextResponse } from 'next/server';

// Same isolation as proxy.token.test.ts: the /api/proxy/* branch returns before intlMiddleware.
vi.mock('next-intl/middleware', () => ({ default: () => () => undefined }));

import { proxy } from '../proxy';

// proxy.ts, not app/api/proxy/[...path]/route.ts, is the live API proxy: it REWRITES
// /api/proxy/* to the gateway, and Next relays the gateway's response (Set-Cookie included)
// as-is. The OAuth browser binding (LC-005) depends on both directions of that hop:
//  - initiate: the binding cookie the gateway sets must not be dropped or rewritten here;
//  - split-host callback (app.example.com / api.example.com): auth-service bounces the browser
//    to <app>/api/proxy/credentials/oauth2/callback?...&hop=1, and THIS rewrite must carry the
//    app-host Cookie header to the gateway, or the flow can never complete.

describe('proxy.ts - OAuth callback hop forwards the app-host cookie', () => {
  it('rewrites the hop to the gateway callback with code, state and hop intact', () => {
    const res = proxy(
      new NextRequest('https://app.example.com/api/proxy/credentials/oauth2/callback?code=c1&state=s1&hop=1', {
        headers: { cookie: '__Host-lc_oauth_0123456789ab=binding-value; other=1' },
      }),
    ) as NextResponse;

    expect(res.headers.get('x-middleware-rewrite'))
      .toBe('http://localhost:8080/api/credentials/oauth2/callback?code=c1&state=s1&hop=1');
  });

  it('forwards the Cookie header unchanged to the gateway', () => {
    const cookie = '__Host-lc_oauth_0123456789ab=binding-value; other=1';
    const res = proxy(
      new NextRequest('https://app.example.com/api/proxy/credentials/oauth2/callback?code=c1&state=s1&hop=1', {
        headers: { cookie },
      }),
    ) as NextResponse;

    const overridden = (res.headers.get('x-middleware-override-headers') ?? '').split(',');
    expect(overridden).toContain('cookie');
    expect(res.headers.get('x-middleware-request-cookie')).toBe(cookie);
  });

  it('sets no Set-Cookie of its own on the rewrite, so the gateway one is the only one relayed', () => {
    const res = proxy(
      new NextRequest('https://app.example.com/api/proxy/credentials/oauth2/initiate', { method: 'POST' }),
    ) as NextResponse;

    expect(res.headers.get('set-cookie')).toBeNull();
    expect(res.headers.get('x-middleware-rewrite')).toBe('http://localhost:8080/api/credentials/oauth2/initiate');
  });
});
