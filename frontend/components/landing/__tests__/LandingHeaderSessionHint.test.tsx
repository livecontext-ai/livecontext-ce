// @vitest-environment jsdom
/**
 * The public header as the server sends it: the session hint script must run before the
 * visitor end is parsed, or a browser holding a session paints "Get started" until the page's
 * scripts take over. Off the main host (the docs subdomain) it reads the app's signed-in cookie.
 */
import React from 'react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/providers/smart-providers', () => ({
  useOptionalAuth: () => ({ isAuthenticated: false, isLoading: true }),
  useAuth: () => ({ isAuthenticated: false, isLoading: true, loginWithRedirect: vi.fn() }),
}));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }), usePathname: () => '/en' }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn(), setLandingIntent: vi.fn() }));

import { DEFAULT_SHELL_LABELS, LandingFooter, LandingHeader } from '../LandingShell';
import { SESSION_HINT_SCRIPT } from '../sessionHint';

describe('LandingHeader: the session hint', () => {
  it('regression: the server sends the hint script before the visitor end, so it runs first', () => {
    const html = renderToString(<LandingHeader />);

    // Inlined as is (dangerouslySetInnerHTML is not escaped).
    const script = html.indexOf(`<script>${SESSION_HINT_SCRIPT}</script>`);
    const visitorEnd = html.indexOf('data-testid="landing-visitor-end"');

    expect(script, 'the hint script is missing from the header').toBeGreaterThan(-1);
    expect(visitorEnd).toBeGreaterThan(-1);
    expect(script, 'the hint script must come before the visitor end').toBeLessThan(visitorEnd);
  });

  it('off the main host (docs subdomain) the header carries the script too, before the visitor end: it reads the app\'s signed-in cookie there', () => {
    const html = renderToString(<LandingHeader siteBaseUrl="https://livecontext.ai" />);

    const script = html.indexOf(`<script>${SESSION_HINT_SCRIPT}</script>`);
    expect(script).toBeGreaterThan(-1);
    expect(script).toBeLessThan(html.indexOf('data-testid="landing-visitor-end"'));
  });
});

describe('LandingFooter: the partner link', () => {
  it("leads to the partner page in the page's own language (the bare URL is English)", () => {
    const french = renderToString(<LandingFooter labels={{ ...DEFAULT_SHELL_LABELS, locale: 'fr' }} />);
    const english = renderToString(<LandingFooter labels={DEFAULT_SHELL_LABELS} />);

    expect(french).toContain('href="/fr/partners"');
    expect(english).toContain('href="/partners"');
  });
});
