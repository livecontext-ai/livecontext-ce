/**
 * LC-027 CASA E3 (round 2). `/app` moved into the per-request nonce CSP class
 * (lib/security/securityHeaders.mjs's NONCE_CSP_PREFIXES) by splitting the old Client Component
 * `layout.tsx` into this thin, always-dynamic Server Component wrapper + the unchanged
 * `AppLayoutClient.tsx`. Three regressions this pins, each of which would silently break the app
 * shell in production with every OTHER test in the repo staying green:
 *
 *  - `export const dynamic` reverting to something other than `'force-dynamic'` (or being
 *    deleted) would make `/app` eligible for static generation again, baking a stale nonce into
 *    prerendered HTML that never matches the per-request CSP header - see proxy.nonceCsp.test.ts
 *    for why that specific failure mode was the reason round 1 deferred this exact change.
 *  - `AppLayoutClient` no longer receiving `children` (a copy/paste split mistake) would render
 *    every `/app` route blank.
 *  - The html-lang script losing its nonce would silently stop executing under the strict
 *    script-src this route now sends (see HtmlLangSync's own comment for the full trade-off).
 *
 * `AppLayoutClient` is mocked to a plain passthrough: it owns the full provider/AppShell/modal
 * tree and has its own tests (AppShell.test.tsx and friends) - this suite's job is only the thin
 * wrapper's own logic (dynamic export, nonce plumbing, children pass-through).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as React from 'react';

const headerGet = vi.fn();
vi.mock('next/headers', () => ({
  headers: async () => ({ get: headerGet }),
}));

const AppLayoutClientMock = vi.hoisted(() =>
  vi.fn(({ children }: { children: React.ReactNode }) => <div data-testid="app-layout-client">{children}</div>),
);
vi.mock('../AppLayoutClient', () => ({ default: AppLayoutClientMock }));

import AppLayout, { dynamic } from '../layout';
import HtmlLangSync from '@/components/security/HtmlLangSync';

describe('/app layout (nonce split)', () => {
  beforeEach(() => {
    headerGet.mockReset();
    AppLayoutClientMock.mockClear();
  });

  it('forces dynamic rendering - a static /app would bake a stale nonce into the HTML', () => {
    expect(dynamic).toBe('force-dynamic');
  });

  it('reads x-nonce and threads it into HtmlLangSync alongside the resolved locale', async () => {
    headerGet.mockReturnValue('the-nonce');
    const element = (await AppLayout({
      children: <div>chat</div>,
      params: Promise.resolve({ locale: 'pt' }),
    })) as React.ReactElement<{ children: React.ReactNode[] }>;

    const [langSync] = element.props.children as [React.ReactElement<{ locale: string; nonce: string | null }>];
    expect(langSync.type).toBe(HtmlLangSync);
    expect(langSync.props.locale).toBe('pt');
    expect(langSync.props.nonce).toBe('the-nonce');
  });

  it('still wraps children in AppLayoutClient, unchanged - the split must not drop the app shell', async () => {
    headerGet.mockReturnValue('n');
    const children = <div data-testid="page-content">hi</div>;
    const element = (await AppLayout({
      children,
      params: Promise.resolve({ locale: 'en' }),
    })) as React.ReactElement<{ children: React.ReactNode[] }>;

    // React only invokes a component function during actual rendering/reconciliation, not while
    // constructing the element - assert the ELEMENT TREE `AppLayout` built, the same way the
    // nonce assertion above inspects `HtmlLangSync`'s element rather than rendering it.
    const [, appLayoutClientElement] = element.props.children as [unknown, React.ReactElement<{ children: React.ReactNode }>];
    expect(appLayoutClientElement.type).toBe(AppLayoutClientMock);
    expect(appLayoutClientElement.props.children).toBe(children);
  });
});
