import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { NONCE_CSP_PREFIXES } from '@/lib/security/securityHeaders.mjs';

/**
 * LC-027 CASA E3 (round 2). Every area in `NONCE_CSP_PREFIXES` gets its per-request nonce CSP
 * ONLY because its route is genuinely dynamic - `isNonceCspPath`/`nonceDocumentHeaders` (pinned by
 * securityHeaders.test.ts and proxy.nonceCsp.test.ts) assume that holds and have no way to verify
 * it themselves. This is the one test that closes the gap between "the header SAYS nonce" and
 * "the route actually re-renders per request": it reads each area's `layout.tsx` from disk and
 * asserts it exports `dynamic = 'force-dynamic'` and delegates rendering to
 * `NonceLocaleLayoutBody` (the one place that reads `x-nonce` off the request - see that
 * component's own test). A layout that silently lost either one would still pass every other CSP
 * test in the repo (they all operate on the HEADER, never the actual Next route table) while
 * shipping a real production regression: a stale, non-matching nonce baked into static HTML - the
 * exact failure mode `/app` was deliberately kept OFF this list for, in round 1, until it got this
 * same treatment.
 *
 * Source-level on purpose, matching `documentLanguage.test.ts`'s own reasoning: actually rendering
 * a Next route table entry needs a running `next build`, which this suite cannot do; the thing
 * worth pinning is the one line that is easy to delete while every other rendering test stays
 * green.
 */

const APP_ROOT = path.resolve(__dirname, '..', 'app', '[locale]');

// The simple areas: one `layout.tsx` delegating straight to NonceLocaleLayoutBody, no bespoke
// body of their own. `/app` and `/ce-setup` are checked separately below - `/app` wraps
// AppLayoutClient instead (see AppLayout.nonceSplit.test.tsx), and `/ce-setup` re-exports the
// shared body via `export { default } from ...` rather than delegating through a JSX call.
const SIMPLE_NONCE_AREAS = ['login', 'register', 'onboarding', 'forgot-password', 'reset-password', 'invitations', 'auth'];

describe('nonce-class area layouts are genuinely dynamic (LC-027 CASA E3)', () => {
  it('NONCE_CSP_PREFIXES names every simple area plus app and ce-setup - keeps this test and the CSP class from drifting apart', () => {
    for (const area of [...SIMPLE_NONCE_AREAS, 'app', 'ce-setup']) {
      expect(NONCE_CSP_PREFIXES).toContain(area);
    }
  });

  it.each(SIMPLE_NONCE_AREAS)('/%s/layout.tsx forces dynamic rendering and delegates to NonceLocaleLayoutBody', (area) => {
    const source = readFileSync(path.join(APP_ROOT, area, 'layout.tsx'), 'utf8');
    expect(source).toContain("export const dynamic = 'force-dynamic'");
    expect(source).toContain('NonceLocaleLayoutBody');
  });

  it('/app/layout.tsx forces dynamic rendering (its own bespoke body is covered by AppLayout.nonceSplit.test.tsx)', () => {
    const source = readFileSync(path.join(APP_ROOT, 'app', 'layout.tsx'), 'utf8');
    expect(source).toContain("export const dynamic = 'force-dynamic'");
  });

  it('/ce-setup/layout.tsx forces dynamic rendering and delegates to NonceLocaleLayoutBody', () => {
    const source = readFileSync(path.join(APP_ROOT, 'ce-setup', 'layout.tsx'), 'utf8');
    expect(source).toContain("export const dynamic = 'force-dynamic'");
    expect(source).toContain('NonceLocaleLayoutBody');
  });
});
