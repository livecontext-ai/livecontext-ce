/**
 * LC-027 CASA E3 (round 2). `NonceLocaleLayoutBody` is the shared body every auth/onboarding area
 * layout (`/login`, `/register`, `/onboarding`, `/forgot-password`, `/reset-password`,
 * `/invitations`, `/auth`) and `/ce-setup` delegate to. It is the ONE place that reads the
 * per-request nonce proxy.ts stamped on the request (`x-nonce`) and threads it into
 * `HtmlLangSync`, so a regression here silently breaks the html-lang script on every one of those
 * routes at once - exactly the kind of shared-body bug `documentLanguage.test.ts`'s own comment
 * warns is "easy to delete while every rendering test stays green" if nothing pins it directly.
 *
 * `@vitest-environment jsdom` is NOT needed: this calls the async Server Component function
 * directly and inspects the returned React element tree, without going through ReactDOM.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as React from 'react';

const headerGet = vi.fn();
vi.mock('next/headers', () => ({
  headers: async () => ({ get: headerGet }),
}));

import NonceLocaleLayoutBody from '../NonceLocaleLayoutBody';
import HtmlLangSync from '../HtmlLangSync';

describe('NonceLocaleLayoutBody', () => {
  beforeEach(() => {
    headerGet.mockReset();
  });

  it('reads x-nonce from headers() and threads it into HtmlLangSync alongside the resolved locale', async () => {
    headerGet.mockReturnValue('nonce-value-1');
    const children = <div>page content</div>;
    const element = (await NonceLocaleLayoutBody({
      children,
      params: Promise.resolve({ locale: 'es' }),
    })) as React.ReactElement<{ children: React.ReactNode[] }>;

    const [langSync, passedChildren] = element.props.children as [
      React.ReactElement<{ locale: string; nonce: string | null }>,
      React.ReactNode,
    ];
    expect(langSync.type).toBe(HtmlLangSync);
    expect(langSync.props.locale).toBe('es');
    expect(langSync.props.nonce).toBe('nonce-value-1');
    expect(passedChildren).toBe(children);
  });

  it('asks headers() for the x-nonce key specifically, not a different header', async () => {
    headerGet.mockReturnValue('n');
    await NonceLocaleLayoutBody({ children: null, params: Promise.resolve({ locale: 'en' }) });
    expect(headerGet).toHaveBeenCalledWith('x-nonce');
  });

  it('passes null through when the route somehow has no nonce (defensive - should not happen on a nonce-class route)', async () => {
    headerGet.mockReturnValue(null);
    const element = (await NonceLocaleLayoutBody({
      children: <div />,
      params: Promise.resolve({ locale: 'en' }),
    })) as React.ReactElement<{ children: React.ReactNode[] }>;
    const [langSync] = element.props.children as [React.ReactElement<{ nonce: string | null }>];
    expect(langSync.props.nonce).toBeNull();
  });
});
