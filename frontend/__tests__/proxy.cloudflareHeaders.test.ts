// @vitest-environment node
import { describe, it, expect, vi } from 'vitest';
import { NextRequest } from 'next/server';
import type { NextResponse } from 'next/server';

// Same isolation as proxy.token.test.ts: the /api/proxy/* branch returns before the intl
// middleware runs.
vi.mock('next-intl/middleware', () => ({ default: () => () => undefined }));

import { proxy } from '../proxy';

// Cloudflare fronts livecontext.ai and sets CF-IPCountry / CF-Connecting-IP. auth-service reads
// them on PUT /users/profile/context (signup country for the lifecycle e-mails, abuse-only
// signup IP), so the ACTIVE proxy (this middleware, not the /api/proxy route handler) must hand
// them to the gateway unchanged.
describe('proxy.ts middleware - Cloudflare headers', () => {
  it('forwards CF-IPCountry and CF-Connecting-IP to the gateway rewrite', () => {
    const res = proxy(
      new NextRequest('http://localhost:3000/api/proxy/users/profile/context', {
        method: 'PUT',
        headers: { 'CF-IPCountry': 'FR', 'CF-Connecting-IP': '203.0.113.7' },
      }),
    ) as NextResponse;

    expect(res.headers.get('x-middleware-rewrite')).toBe('http://localhost:8080/api/users/profile/context');
    expect(res.headers.get('x-middleware-request-cf-ipcountry')).toBe('FR');
    expect(res.headers.get('x-middleware-request-cf-connecting-ip')).toBe('203.0.113.7');
  });
});
