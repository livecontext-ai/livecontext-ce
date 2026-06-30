// @vitest-environment node
import { describe, it, expect, vi } from 'vitest';
import { NextRequest } from 'next/server';
import type { NextResponse } from 'next/server';

// proxy.ts imports next-intl/middleware (which pulls next/server in a way vitest can't
// resolve). The /api/proxy/* branch under test returns BEFORE intlMiddleware runs, so a
// no-op mock is safe and keeps the module loadable.
vi.mock('next-intl/middleware', () => ({ default: () => () => undefined }));

import { proxy } from '../proxy';

// The `proxy.ts` middleware is the ACTIVE proxy for /api/proxy/* (it rewrites straight to
// the gateway, bypassing the /api/proxy route handler). It used to strip ANY `?token=`
// before rewriting, which deleted the invitation token on the unauthenticated
// `/organizations/invitations/info?token=` lookup -> backend saw none -> valid:false ->
// "Invitation not available". The fix only strips a JWT-shaped token.

function rewriteTarget(res: NextResponse): string | null {
  return res.headers.get('x-middleware-rewrite');
}

describe('proxy.ts middleware - ?token query handling', () => {
  it('forwards a non-JWT resource ?token to the gateway (invitation-accept lookup)', () => {
    const uuid = '8e8caa84-1f01-4c4e-bd88-7efe872aee91';
    const res = proxy(
      new NextRequest(`http://localhost:3000/api/proxy/organizations/invitations/info?token=${uuid}`),
    ) as NextResponse;

    expect(rewriteTarget(res)).toBe(`http://localhost:8080/api/organizations/invitations/info?token=${uuid}`);
  });

  it('strips a JWT-shaped ?token and promotes it to the forwarded Authorization bearer', () => {
    const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig';
    const res = proxy(
      new NextRequest(`http://localhost:3000/api/proxy/files/x?token=${jwt}`),
    ) as NextResponse;

    expect(rewriteTarget(res)).toBe('http://localhost:8080/api/files/x'); // token removed from query
    expect(res.headers.get('x-middleware-request-authorization')).toBe(`Bearer ${jwt}`);
  });

  it('leaves a resource ?token in place when an Authorization header is already present', () => {
    const uuid = 'abc-123-resource';
    const res = proxy(
      new NextRequest(`http://localhost:3000/api/proxy/organizations/invitations/info?token=${uuid}`, {
        headers: { authorization: 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.sig' },
      }),
    ) as NextResponse;

    expect(rewriteTarget(res)).toContain(`token=${uuid}`);
  });
});
