/**
 * LC-027 CASA E3: `/ce-setup/*` (the CE first-run wizard, already `force-dynamic` for an
 * unrelated reason - app/[locale]/ce-setup/layout.tsx), `/app/*` and the auth/onboarding areas
 * (`/login`, `/register`, `/onboarding`, `/forgot-password`, `/reset-password`, `/invitations`,
 * `/auth`) all get a per-request nonce + strict-dynamic ENFORCED Content-Security-Policy from the
 * middleware (a static next.config.mjs rule cannot generate a fresh value per request - see
 * lib/security/securityHeaders.mjs's module header and Next's own CSP guide).
 *
 * Round 2 of this batch moved `/app/*` and the auth/onboarding areas into this class: `/app`'s
 * layout WAS a Client Component (cannot itself force dynamic rendering), and the build's own
 * route table proved `/app/chat` and friends were prerendered STATIC (`●`) - shipping a
 * nonce-only CSP there without first making it genuinely dynamic would have baked a stale nonce
 * into the static HTML that never matches the per-request header, silently blocking the app's own
 * hydration scripts in production. `app/[locale]/app/layout.tsx` is now a thin, always-dynamic
 * Server Component wrapping the unchanged client layout (`AppLayoutClient.tsx`), which is what
 * makes this class change safe; the auth/onboarding areas each got their own equivalent
 * `force-dynamic` `layout.tsx`. This is the request/response wiring; the header CONTENT itself is
 * covered by lib/security/__tests__/securityHeaders.test.ts (pure function, no request needed).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest } from 'next/server';

const editionMock = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => editionMock);

// next-intl's ESM middleware build does not resolve under the vitest node environment; the
// paths under test never reach it.
vi.mock('next-intl/middleware', () => ({
  default: () => () => undefined,
}));

import { proxy, generateCspNonce } from '@/proxy';

function request(path: string, headers: Record<string, string> = {}): NextRequest {
  return new NextRequest(`https://livecontext.ai${path}`, { headers });
}

describe('proxy nonce CSP (LC-027 CASA E3)', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  it('generateCspNonce produces a fresh, non-empty value every call', () => {
    const a = generateCspNonce();
    const b = generateCspNonce();
    expect(a).toBeTruthy();
    expect(b).toBeTruthy();
    expect(a).not.toEqual(b);
  });

  it('sends a nonce + strict-dynamic CSP for /ce-setup (locale-prefixed)', () => {
    const response = proxy(request('/en/ce-setup')) as Response;
    const csp = response.headers.get('Content-Security-Policy');
    expect(csp).toBeTruthy();
    expect(csp).toMatch(/script-src 'self' 'nonce-[^']+' 'strict-dynamic'/);
    expect(csp).toContain("frame-ancestors 'self'");
  });

  it('two consecutive requests never reuse the same nonce', () => {
    const cspA = (proxy(request('/en/ce-setup')) as Response).headers.get('Content-Security-Policy');
    const cspB = (proxy(request('/en/ce-setup')) as Response).headers.get('Content-Security-Policy');
    const nonceOf = (csp: string | null) => csp?.match(/'nonce-([^']+)'/)?.[1];
    expect(nonceOf(cspA)).toBeTruthy();
    expect(nonceOf(cspA)).not.toEqual(nonceOf(cspB));
  });

  it('never sends a Content-Security-Policy for a non-nonce document route (next.config.mjs owns that one)', () => {
    expect((proxy(request('/about')) as Response).headers.has('Content-Security-Policy')).toBe(false);
  });

  it('/app is now in the nonce class (round 2 - see the module header)', () => {
    const response = proxy(request('/en/app/chat')) as Response;
    const csp = response.headers.get('Content-Security-Policy');
    expect(csp).toBeTruthy();
    expect(csp).toMatch(/script-src 'self' 'nonce-[^']+' 'strict-dynamic'/);
    expect(csp).toContain("frame-ancestors 'self'");
  });

  it('two consecutive /app requests never reuse the same nonce', () => {
    const cspA = (proxy(request('/en/app/chat')) as Response).headers.get('Content-Security-Policy');
    const cspB = (proxy(request('/en/app/chat')) as Response).headers.get('Content-Security-Policy');
    const nonceOf = (csp: string | null) => csp?.match(/'nonce-([^']+)'/)?.[1];
    expect(nonceOf(cspA)).toBeTruthy();
    expect(nonceOf(cspA)).not.toEqual(nonceOf(cspB));
  });

  it.each(['/en/login', '/en/register', '/en/onboarding', '/en/forgot-password', '/en/reset-password', '/en/invitations/accept', '/en/auth/sso'])(
    'sends a nonce + strict-dynamic CSP for %s (auth/onboarding areas)',
    (path) => {
      const csp = (proxy(request(path)) as Response).headers.get('Content-Security-Policy');
      expect(csp).toBeTruthy();
      expect(csp).toMatch(/script-src 'self' 'nonce-[^']+' 'strict-dynamic'/);
    },
  );


  // CASA round 2: the public share/form links joined the nonce class. The nonce must reach Next's
  // SSR as a REQUEST header (x-middleware-request-*), not only the response CSP: a response CSP
  // naming a nonce the page never rendered blocks the page's own hydration scripts. Dotted paths
  // (`/s/abc.def`, `/en/app/u/j.doe`) render through a different branch and were sent the nonce
  // CSP without the request header before (whole-class fix).
  it.each(['/s/tok123', '/f/tok123', '/s/tok.with.dot', '/en/app/u/j.doe', '/en/app/chat'])(
    '%s renders with the same nonce on the request as in the response CSP',
    (path) => {
      const response = proxy(request(path)) as Response;
      const csp = response.headers.get('Content-Security-Policy');
      expect(csp).toMatch(/script-src 'self' 'nonce-[^']+' 'strict-dynamic'/);
      const nonce = csp?.match(/'nonce-([^']+)'/)?.[1];
      expect(response.headers.get('x-middleware-request-x-nonce')).toBe(nonce);
      expect(response.headers.get('x-middleware-request-content-security-policy')).toBe(csp);
    },
  );

  it.each(['/about', '/w/embed/tok123', '/workflows/builder'])(
    '%s (outside the nonce class) gets no nonce request header',
    (path) => {
      const response = proxy(request(path)) as Response;
      expect(response.headers.has('x-middleware-request-x-nonce')).toBe(false);
    },
  );

  it('edition drives connect-src through process.env.NEXT_PUBLIC_APP_EDITION (the build-time flag next.config.mjs already reads for HSTS), not a hardcoded host', () => {
    const original = process.env.NEXT_PUBLIC_APP_EDITION;
    try {
      process.env.NEXT_PUBLIC_APP_EDITION = 'ce';
      const ceCsp = (proxy(request('/en/ce-setup')) as Response).headers.get('Content-Security-Policy');
      expect(ceCsp).toMatch(/connect-src[^;]*\bhttp:/);

      process.env.NEXT_PUBLIC_APP_EDITION = 'cloud';
      const cloudCsp = (proxy(request('/en/ce-setup')) as Response).headers.get('Content-Security-Policy');
      expect(cloudCsp).not.toMatch(/connect-src[^;]*\bhttp:/);
    } finally {
      if (original === undefined) delete process.env.NEXT_PUBLIC_APP_EDITION;
      else process.env.NEXT_PUBLIC_APP_EDITION = original;
    }
  });
});
