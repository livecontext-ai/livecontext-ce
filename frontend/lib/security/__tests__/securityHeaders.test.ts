/**
 * LC-027: the Next server sent no HSTS, CSP, X-Frame-Options, nosniff, Referrer-Policy or
 * Permissions-Policy on any route (measured on https://livecontext.ai/: only `Server`).
 *
 * The rules are compiled with Next's OWN route compiler (the one `next build` uses to write the
 * routes manifest), so these tests assert which headers a real request path receives, not how
 * the config object happens to look.
 */
import { describe, it, expect } from 'vitest';
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { buildCustomRoute } = require('next/dist/lib/build-custom-route') as {
  buildCustomRoute: (type: 'header', route: unknown) => { regex: string };
};
import {
  securityHeaderRules,
  strictTransportSecurity,
  PERMISSIONS_POLICY,
  isNonceCspPath,
  nonceDocumentHeaders,
  interfaceFrameHeaders,
  INTERFACE_FRAME_PATH,
} from '../securityHeaders.mjs';

type Rule = { source: string; headers: { key: string; value: string }[] };

/** Every header (key -> list of values) a request to `path` receives, as Next would apply them. */
function headersFor(path: string, edition = 'cloud', isDev = false): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const rule of securityHeaderRules({ edition, isDev }) as Rule[]) {
    const { regex } = buildCustomRoute('header', rule);
    if (!new RegExp(regex).test(path)) continue;
    for (const { key, value } of rule.headers) {
      out.set(key, [...(out.get(key) ?? []), value]);
    }
  }
  return out;
}

// /f and /s (public form / application links) are NOT embeddable: no embed snippet exists for them.
// /app, /ce-setup, the auth/onboarding areas and the public /s and /f links are deliberately
// ABSENT: they are the nonce class (proxy.ts middleware owns their CSP per request, not this
// static rule table) - see the isNonceCspPath describe block.
const DOCUMENT_PATHS = ['/', '/en', '/marketplace', '/workflows/builder', '/favicon.ico', '/shared/x', '/apiary', '/support', '/faq', '/fx/tok', '/sx/tok', '/w/other', '/w/embedded'];
const EMBEDDABLE_PATHS = ['/w/embed', '/w/embed/tok123', '/w/embed/tok123/sub'];
const API_PATHS = ['/api/proxy/files/by-id/1/raw', '/api/status', '/api'];
const NONCE_PATHS = [
  '/app',
  '/app/chat',
  '/fr/app/chat',
  '/ce-setup',
  '/en/ce-setup/step1',
  '/login',
  '/en/login',
  '/register',
  '/onboarding',
  '/forgot-password',
  '/reset-password',
  '/invitations',
  '/invitations/accept',
  '/auth',
  '/auth/sso',
  '/fr/auth/sso',
  // CASA round 2: the public share/form links serve user content to signed-in users.
  '/s/tok123',
  '/s/tok.with.dot',
  '/f/tok123',
];
const NOT_NONCE_PATHS = ['/', '/apparel', '/application', '/fr/apparel', '/marketplace/app', '/appx', '/ce-setupx', '/ce-set', '/loginx', '/registerx', '/onboardingx', '/authx', '/invitationsx', '/forgot-passwordx', '/reset-passwordx', '/support', '/faq', '/sx/tok', '/fx/tok', '/w/embed/tok123'];

describe('LC-027 security headers', () => {
  it.each([...DOCUMENT_PATHS, ...EMBEDDABLE_PATHS, ...API_PATHS])('%s gets HSTS and nosniff exactly once', (path) => {
    const h = headersFor(path);
    expect(h.get('Strict-Transport-Security')).toEqual(['max-age=31536000; includeSubDomains']);
    expect(h.get('X-Content-Type-Options')).toEqual(['nosniff']);
  });

  it.each(DOCUMENT_PATHS)('%s is a first-party document: frame-ancestors self, SAMEORIGIN, full policy set', (path) => {
    const h = headersFor(path);
    const csp = h.get('Content-Security-Policy');
    expect(csp).toHaveLength(1);
    expect(csp![0]).toContain("frame-ancestors 'self'");
    expect(csp![0]).toContain("object-src 'none'");
    expect(csp![0]).toContain("base-uri 'self'");
    expect(h.get('X-Frame-Options')).toEqual(['SAMEORIGIN']);
    expect(h.get('Referrer-Policy')).toEqual(['strict-origin-when-cross-origin']);
    expect(h.get('Permissions-Policy')).toEqual([PERMISSIONS_POLICY]);
    expect(h.get('Cross-Origin-Opener-Policy')).toEqual(['same-origin-allow-popups']);
    expect(h.get('Content-Security-Policy-Report-Only')).toHaveLength(1);
    // Violations are measurable: legacy report-uri AND Reporting API report-to, same origin.
    expect(h.get('Content-Security-Policy-Report-Only')![0]).toContain('report-uri /api/csp-report');
    expect(h.get('Content-Security-Policy-Report-Only')![0]).toContain('report-to csp-endpoint');
    expect(h.get('Reporting-Endpoints')).toEqual(['csp-endpoint="/api/csp-report"']);
  });

  it.each(EMBEDDABLE_PATHS)('%s stays framable by customer sites: one CSP with frame-ancestors *, no X-Frame-Options', (path) => {
    const h = headersFor(path);
    // Exactly one CSP: a second (strict) one would be intersected by the browser and win.
    expect(h.get('Content-Security-Policy')).toHaveLength(1);
    expect(h.get('Content-Security-Policy')![0]).toContain('frame-ancestors *');
    expect(h.get('Content-Security-Policy')![0]).not.toContain("frame-ancestors 'self'");
    expect(h.has('X-Frame-Options')).toBe(false);
  });

  it.each(API_PATHS)('%s carries no document policy that could override the backend per-file headers', (path) => {
    const h = headersFor(path);
    expect(h.has('Content-Security-Policy')).toBe(false);
    expect(h.has('Referrer-Policy')).toBe(false);
    expect(h.has('X-Frame-Options')).toBe(false);
  });

  it('the enforced CSP restricts script-src to self/https (CASA E3): no wildcard host, no eval in prod', () => {
    const csp = headersFor('/marketplace').get('Content-Security-Policy')![0];
    expect(csp).toContain("script-src 'self' 'unsafe-inline' https:");
    expect(csp.match(/script-src[^;]*/)![0]).not.toContain('unsafe-eval');
    expect(csp).not.toContain('script-src *');
  });

  it('allows unsafe-eval in script-src only in dev (React dev-mode error reconstruction)', () => {
    const prod = headersFor('/marketplace', 'cloud', false).get('Content-Security-Policy')![0];
    const dev = headersFor('/marketplace', 'cloud', true).get('Content-Security-Policy')![0];
    expect(prod).not.toContain("'unsafe-eval'");
    expect(dev).toContain("'unsafe-eval'");
  });

  it('object-src stays none and base-uri/form-action stay self (unchanged by CASA E3)', () => {
    const csp = headersFor('/marketplace').get('Content-Security-Policy')![0];
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("base-uri 'self'");
    expect(csp).toContain("form-action 'self' https:");
  });

  it('CE additionally allows plain http/ws in connect-src (LAN, no hardcoded host); cloud does not', () => {
    const ce = headersFor('/marketplace', 'ce').get('Content-Security-Policy')![0];
    const cloud = headersFor('/marketplace', 'cloud').get('Content-Security-Policy')![0];
    expect(ce).toMatch(/connect-src[^;]*\bhttp:/);
    expect(ce).toMatch(/connect-src[^;]*\bws:/);
    expect(cloud).not.toMatch(/connect-src[^;]*\bhttp:/);
    // Neither ever names a literal host: CE's Keycloak/gateway host is operator-configured.
    expect(ce).not.toContain('livecontext.ai');
    expect(cloud).not.toContain('livecontext.ai');
  });

  describe('cloud connect-src and the local services the browser calls directly (Keycloak via oidc-client, the realtime gateway)', () => {
    const KC = 'http://localhost:8180';
    const GW = 'http://localhost:8080';
    const connectSrcOf = (csp: string) => csp.split('; ').find((d) => d.startsWith('connect-src '));
    const staticCsp = (serviceUrls?: (string | undefined)[]) =>
      (securityHeaderRules({ edition: 'cloud', isDev: false, serviceUrls }) as Rule[])
        .find((r) => r.source.startsWith('/:path((?!'))!
        .headers.find((h) => h.key === 'Content-Security-Policy')!.value;
    const nonceCsp = (edition: string, serviceUrls?: (string | undefined)[]) =>
      (nonceDocumentHeaders({ nonce: 'abc123', edition, isDev: false, serviceUrls }) as { key: string; value: string }[])
        .find((h) => h.key === 'Content-Security-Policy')!.value;

    it('a local plain-http Keycloak and gateway (cloud e2e stack, next dev) are allowed, else Sign in never leaves the app', () => {
      // Regression: the cloud e2e stack's AUTH-002..006 stayed on the app's "Sign in" gate because
      // the cloud CSP refused oidc-client's fetch of http://localhost:8180's discovery document.
      const expected = `connect-src 'self' https: wss: ${KC} ${GW}`;
      expect(connectSrcOf(nonceCsp('cloud', [KC, GW]))).toBe(expected);
      expect(connectSrcOf(staticCsp([KC, GW]))).toBe(expected);
      expect(connectSrcOf(nonceCsp('cloud', ['http://127.0.0.1:8180/realms/livecontext', undefined])))
        .toBe("connect-src 'self' https: wss: http://127.0.0.1:8180");
      expect(connectSrcOf(nonceCsp('cloud', [KC, `${KC}/`]))).toBe(`connect-src 'self' https: wss: ${KC}`);
    });

    it('the production build values (https Keycloak, empty gateway) leave the cloud header byte-identical', () => {
      const prod = ['https://auth.livecontext.ai', ''];
      expect(nonceCsp('cloud', prod)).toBe(nonceCsp('cloud', undefined));
      expect(staticCsp(prod)).toBe(staticCsp(undefined));
    });

    it('never widens cloud to a non-loopback plain-http host, nor to a malformed value', () => {
      const urls = ['http://auth.example.com', 'http://10.0.0.5:8180', 'http://localhost.example.com', 'not a url', 'localhost:8080'];
      expect(connectSrcOf(nonceCsp('cloud', urls))).toBe("connect-src 'self' https: wss:");
    });

    it('CE is unchanged by the service URLs', () => {
      expect(nonceCsp('ce', [KC, GW])).toBe(nonceCsp('ce', undefined));
    });
  });

  describe('nonce class (/app, /ce-setup, login/register/onboarding/etc - proxy.ts middleware owns their CSP, not this table)', () => {
    it.each(NONCE_PATHS)('%s is classified as a nonce path', (path) => {
      expect(isNonceCspPath(path)).toBe(true);
    });

    it.each(NOT_NONCE_PATHS)('%s is NOT classified as a nonce path (no false positive on a shared prefix)', (path) => {
      expect(isNonceCspPath(path)).toBe(false);
    });

    it.each(NONCE_PATHS)('%s gets no CSP from the static rule table (only proxy.ts may set one, or two CSPs would intersect)', (path) => {
      const h = headersFor(path);
      expect(h.has('Content-Security-Policy')).toBe(false);
      expect(h.has('Content-Security-Policy-Report-Only')).toBe(false);
    });

    it.each(NONCE_PATHS)('%s still gets HSTS and nosniff from the universal rule', (path) => {
      const h = headersFor(path);
      expect(h.get('Strict-Transport-Security')).toEqual(['max-age=31536000; includeSubDomains']);
      expect(h.get('X-Content-Type-Options')).toEqual(['nosniff']);
    });

    it('nonceDocumentHeaders requires a non-empty nonce (a blank one is not a nonce)', () => {
      // @ts-expect-error - deliberately omitting the required field
      expect(() => nonceDocumentHeaders({})).toThrow();
      expect(() => nonceDocumentHeaders({ nonce: '' })).toThrow();
    });

    it('builds a strict-dynamic, nonce-scoped script-src with the same base directives', () => {
      const rules = nonceDocumentHeaders({ nonce: 'abc123', edition: 'cloud', isDev: false });
      const csp = rules.find((r) => r.key === 'Content-Security-Policy')!.value;
      expect(csp).toContain("script-src 'self' 'nonce-abc123' 'strict-dynamic'");
      expect(csp).not.toContain("'unsafe-eval'");
      expect(csp).toContain("object-src 'none'");
      expect(csp).toContain("frame-ancestors 'self'");
      expect(rules.some((r) => r.key === 'X-Frame-Options' && r.value === 'SAMEORIGIN')).toBe(true);
    });

    it('two calls with different nonces never collide (per-request freshness)', () => {
      const a = nonceDocumentHeaders({ nonce: 'nonce-a', edition: 'cloud' });
      const b = nonceDocumentHeaders({ nonce: 'nonce-b', edition: 'cloud' });
      const cspA = a.find((r) => r.key === 'Content-Security-Policy')!.value;
      const cspB = b.find((r) => r.key === 'Content-Security-Policy')!.value;
      expect(cspA).not.toEqual(cspB);
      expect(cspA).toContain('nonce-a');
      expect(cspB).toContain('nonce-b');
    });
  });

  describe('/interface-frame shell (LC-027 CASA E3 point 1: publisher JS moves off srcDoc)', () => {
    it('is excluded from the default document rule (permissive script-src must not intersect with the strict one)', () => {
      const h = headersFor(`/${INTERFACE_FRAME_PATH}`);
      const csp = h.get('Content-Security-Policy');
      expect(csp).toHaveLength(1);
      expect(csp![0]).toContain('script-src *');
    });

    it('keeps base-uri/object-src/form-action identical to the values interface content already had', () => {
      const rules = interfaceFrameHeaders();
      const csp = rules.find((r) => r.key === 'Content-Security-Policy')!.value;
      expect(csp).toContain("base-uri 'self'");
      expect(csp).toContain("object-src 'none'");
      expect(csp).toContain("form-action 'self' https:");
      // Never embeddable by a third party: only this app frames it.
      expect(csp).toContain("frame-ancestors 'self'");
    });

    it('never sends a Content-Security-Policy-Report-Only header (it is not the reporting surface)', () => {
      const h = headersFor(`/${INTERFACE_FRAME_PATH}`);
      expect(h.has('Content-Security-Policy-Report-Only')).toBe(false);
    });
  });

  it('keeps the microphone for voice input and never denies features the YouTube embed delegates', () => {
    expect(PERMISSIONS_POLICY).toContain('microphone=(self)');
    expect(PERMISSIONS_POLICY).toContain('camera=()');
    for (const delegated of ['autoplay', 'encrypted-media', 'picture-in-picture', 'clipboard-write', 'accelerometer', 'gyroscope', 'web-share', 'fullscreen']) {
      expect(PERMISSIONS_POLICY).not.toContain(`${delegated}=`);
    }
  });

  it('CE builds send host-only HSTS (no includeSubDomains pinning a self-hoster apex domain)', () => {
    expect(strictTransportSecurity('ce')).toBe('max-age=31536000');
    expect(headersFor('/', 'ce').get('Strict-Transport-Security')).toEqual(['max-age=31536000']);
    expect(strictTransportSecurity(undefined)).toBe('max-age=31536000; includeSubDomains');
  });

  it('is actually wired into next.config.mjs headers() (a rule module nothing calls protects nothing)', async () => {
    const { default: config } = (await import('../../../next.config.mjs')) as {
      default: { headers: () => Promise<Rule[]> };
    };
    const all = await config.headers();
    const expected = securityHeaderRules({
      edition: process.env.NEXT_PUBLIC_APP_EDITION,
      serviceUrls: [process.env.NEXT_PUBLIC_KEYCLOAK_URL, process.env.NEXT_PUBLIC_GATEWAY_WS_URL],
    }) as Rule[];
    for (const rule of expected) {
      expect(all).toContainEqual(rule);
    }
  });

  it('both CSP call sites pass the browser-called service URLs by their real env names (a typo would silently drop them)', () => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { readFileSync } = require('node:fs') as typeof import('node:fs');
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { resolve } = require('node:path') as typeof import('node:path');
    const wiring = 'serviceUrls: [process.env.NEXT_PUBLIC_KEYCLOAK_URL, process.env.NEXT_PUBLIC_GATEWAY_WS_URL]';
    for (const file of ['../../../proxy.ts', '../../../next.config.mjs']) {
      expect(readFileSync(resolve(__dirname, file), 'utf8'), file).toContain(wiring);
    }
  });

  it('is accepted by Next config validation (a bad header key or source fails next build)', async () => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { default: loadCustomRoutes } = require('next/dist/lib/load-custom-routes') as {
      default: (config: unknown) => Promise<{ headers: unknown[] }>;
    };
    const rules = securityHeaderRules({ edition: 'cloud' });
    const loaded = await loadCustomRoutes({ headers: async () => rules, rewrites: async () => [], redirects: async () => [] });
    expect(loaded.headers).toHaveLength(rules.length);
  });
});
