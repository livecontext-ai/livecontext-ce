// @vitest-environment jsdom
/**
 * The right-hand end of the public header: a visitor is offered to sign in and to get started; a
 * signed-in reader sees their account and the way back into the app, never "Get started" with an
 * account they already have, and not even for the moment before the page's scripts run.
 */
import React from 'react';
import { renderToString } from 'react-dom/server';
import { hydrateRoot } from 'react-dom/client';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The auth context's real shape: `user` IS the OIDC profile (smart-providers: oidc.user?.profile).
type FakeAuth = { isAuthenticated: boolean; isLoading: boolean; user?: Record<string, unknown> | null; avatarUrl?: string | null };
const state = vi.hoisted(() => ({ auth: undefined as FakeAuth | undefined }));
const analytics = vi.hoisted(() => ({ track: vi.fn(), setLandingIntent: vi.fn() }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useOptionalAuth: () => state.auth,
  useAuth: () => ({ ...(state.auth ?? { isAuthenticated: false, isLoading: true }), loginWithRedirect: vi.fn() }),
}));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => analytics);

import LandingAccount, { initials } from '../LandingAccount';
import { SESSION_HINT_ATTR, SESSION_HINT_SCRIPT } from '../sessionHint';

const LABELS = { signIn: 'Sign in', getStarted: 'Get started free', openApp: 'Open the app', account: 'Your account' };
const renderIt = (baseUrl?: string) => render(<LandingAccount {...LABELS} baseUrl={baseUrl} />);
const hint = (on: boolean) => (on
  ? document.documentElement.setAttribute(SESSION_HINT_ATTR, '')
  : document.documentElement.removeAttribute(SESSION_HINT_ATTR));
const runHintScript = () => new Function(SESSION_HINT_SCRIPT)();

beforeEach(() => {
  window.localStorage.clear();
  hint(false);
  analytics.setLandingIntent.mockClear();
  analytics.track.mockClear();
  state.auth = { isAuthenticated: false, isLoading: false };
});
afterEach(() => cleanup());

describe('LandingAccount', () => {
  it('a visitor is offered to sign in and to get started, shown at once', () => {
    renderIt();

    expect(screen.getByText('Sign in')).toBeTruthy();
    expect(screen.getByText('Get started free')).toBeTruthy();
    expect(screen.getByTestId('landing-visitor-end').dataset.state).toBe('ready');
    expect(screen.queryByTestId('landing-account')).toBeNull();
  });

  it('regression: a signed-in reader sees their initials and name (the context user is the profile), not "Get started"', () => {
    state.auth = { isAuthenticated: true, isLoading: false, user: { name: 'Lucas Martin', email: 'lucas@acme.io' }, avatarUrl: null };
    renderIt();

    expect(screen.getByTestId('landing-open-app').getAttribute('href')).toBe('/app/chat');
    const avatar = screen.getByTestId('landing-account-avatar');
    expect(avatar.getAttribute('href')).toBe('/app/settings');
    expect(avatar.getAttribute('aria-label')).toBe('Your account (Lucas Martin)');
    expect(avatar.textContent).toBe('LM');
    expect(screen.queryByText('Get started free')).toBeNull();
    expect(screen.queryByText('Sign in')).toBeNull();
  });

  it('an account without a name is named by its email, and shows its photo when it has one', () => {
    state.auth = { isAuthenticated: true, isLoading: false, user: { email: 'jane.doe@acme.io' }, avatarUrl: '/api/users/42/avatar' };
    renderIt();

    const avatar = screen.getByTestId('landing-account-avatar');
    expect(avatar.querySelector('img')?.getAttribute('src')).toBe('/api/users/42/avatar');
    expect(avatar.getAttribute('aria-label')).toBe('Your account (jane.doe@acme.io)');

    state.auth = { isAuthenticated: true, isLoading: false, user: { email: 'jane.doe@acme.io' }, avatarUrl: null };
    cleanup();
    renderIt();
    expect(screen.getByTestId('landing-account-avatar').textContent).toBe('JD');
  });

  it('regression: opening the app is a plain link, not a landing acquisition, and it works while the account is still read', () => {
    for (const auth of [
      { isAuthenticated: true, isLoading: false, user: { name: 'Lucas Martin' } },
      // Still reading the account, with a stored session: the link must not wait for it.
      { isAuthenticated: false, isLoading: true },
    ]) {
      state.auth = auth;
      hint(true);
      renderIt();

      const link = screen.getByTestId('landing-open-app');
      // fireEvent returns false when a handler called preventDefault, which SignInButton always
      // does (and then waits for the account). Environment note: with no router mounted here,
      // next/link leaves the click alone; under the app's router it would cancel it to navigate
      // in-page, so this assertion is only valid without a router, as in this file.
      expect(fireEvent.click(link), JSON.stringify(auth)).toBe(true);
      expect(link.getAttribute('href')).toBe('/app/chat');
      cleanup();
    }
    expect(analytics.setLandingIntent).not.toHaveBeenCalled();
    expect(analytics.track).not.toHaveBeenCalled();
  });

  it('regression: a browser holding a session hydrates the server\'s visitor end without a mismatch, then shows the account\'s place', async () => {
    state.auth = { isAuthenticated: false, isLoading: true };
    // The server knows nothing of the browser's storage: it sends the visitor end.
    const html = renderToString(<LandingAccount {...LABELS} />);
    hint(true);
    const container = document.createElement('div');
    container.innerHTML = html;
    document.body.appendChild(container);
    const recoverable = vi.fn();

    await act(async () => {
      hydrateRoot(container, <LandingAccount {...LABELS} />, { onRecoverableError: recoverable });
    });

    expect(recoverable).not.toHaveBeenCalled();
    expect(container.querySelector('[data-testid="landing-account"]')).not.toBeNull();
    expect(container.querySelector('[data-testid="landing-visitor-end"]')).toBeNull();
    container.remove();
  });

  it('while the account is read, a browser holding a session keeps the account\'s place; any other visitor sees the visitor end', () => {
    state.auth = { isAuthenticated: false, isLoading: true };
    hint(true);
    renderIt();
    expect(screen.getByTestId('landing-account').getAttribute('aria-busy')).toBe('true');
    // Not a guess at a name or a photo before the account is known.
    expect(screen.getByTestId('landing-account-avatar').textContent).toBe('');
    expect(screen.queryByText('Get started free')).toBeNull();
    cleanup();

    hint(false);
    renderIt();
    expect(screen.getByText('Get started free')).toBeTruthy();
    expect(screen.getByTestId('landing-visitor-end').dataset.state).toBe('pending');
  });

  it('a stored session that turns out to be over shows the visitor end once the account is read', () => {
    state.auth = { isAuthenticated: false, isLoading: true };
    hint(true);
    const { rerender } = renderIt();
    expect(screen.queryByText('Get started free')).toBeNull();

    state.auth = { isAuthenticated: false, isLoading: false };
    act(() => { rerender(<LandingAccount {...LABELS} />); });

    expect(screen.getByText('Get started free')).toBeTruthy();
    // Ready: no longer hidden by the document's session hint.
    expect(screen.getByTestId('landing-visitor-end').dataset.state).toBe('ready');
    expect(screen.getByTestId('landing-visitor-end').className).not.toContain('invisible');
  });

  it('off the main host (docs subdomain) without the app\'s cookie: the visitor end, ready, whatever this origin\'s own auth says', () => {
    state.auth = { isAuthenticated: false, isLoading: true };
    hint(true);
    renderIt('https://livecontext.ai');

    expect(screen.getByText('Get started free').getAttribute('href')).toBe('https://livecontext.ai/app/chat');
    expect(screen.queryByTestId('landing-account')).toBeNull();
    expect(screen.getByTestId('landing-visitor-end').dataset.state).toBe('ready');

    // The docs origin's own auth is not the app's session and decides nothing there.
    state.auth = { isAuthenticated: true, isLoading: false, user: { name: 'Lucas' } };
    cleanup();
    renderIt('https://livecontext.ai');
    expect(screen.queryByTestId('landing-account')).toBeNull();
  });

  it('regression: off the main host (docs subdomain) a reader signed in to the app sees their account, linked to the main site', () => {
    document.cookie = 'lc_account=LM; path=/';
    try {
      renderIt('https://livecontext.ai');

      expect(screen.getByTestId('landing-open-app').getAttribute('href')).toBe('https://livecontext.ai/app/chat');
      const avatar = screen.getByTestId('landing-account-avatar');
      expect(avatar.getAttribute('href')).toBe('https://livecontext.ai/app/settings');
      expect(avatar.textContent).toBe('LM');
      // The cookie names nobody: the label is the account's, without a name.
      expect(avatar.getAttribute('aria-label')).toBe('Your account');
      expect(screen.queryByText('Get started free')).toBeNull();
    } finally {
      document.cookie = 'lc_account=; path=/; max-age=0';
    }
  });

  it('off the main host, a hinted page hydrates the server\'s visitor end without a mismatch, then shows the account', async () => {
    const html = renderToString(<LandingAccount {...LABELS} baseUrl="https://livecontext.ai" />);
    // The server knows no cookie: pending, so the hint keeps it invisible before hydration.
    expect(html).toContain('data-state="pending"');
    document.cookie = 'lc_account=LM; path=/';
    const container = document.createElement('div');
    container.innerHTML = html;
    document.body.appendChild(container);
    const recoverable = vi.fn();
    try {
      await act(async () => {
        hydrateRoot(container, <LandingAccount {...LABELS} baseUrl="https://livecontext.ai" />, { onRecoverableError: recoverable });
      });

      expect(recoverable).not.toHaveBeenCalled();
      expect(container.querySelector('[data-testid="landing-account-avatar"]')?.textContent).toBe('LM');
    } finally {
      document.cookie = 'lc_account=; path=/; max-age=0';
      container.remove();
    }
  });

  it('regression: the server sends the visitor end hidden behind the session hint, so a signed-in browser never paints "Get started"', () => {
    state.auth = { isAuthenticated: false, isLoading: true };

    const html = renderToString(<LandingAccount {...LABELS} />);

    expect(html).toContain('data-state="pending"');
    // The class that hides it names the very attribute the inline script sets.
    expect(html).toContain(`[html[${SESSION_HINT_ATTR}]_&amp;]:invisible`);
    expect(html).not.toContain('landing-account');
  });

  it('initials: two letters for a full name, the start of a one-word name or an email, "?" without one', () => {
    expect(initials('Lucas Martin')).toBe('LM');
    expect(initials('lucas')).toBe('LU');
    expect(initials('jane.doe@acme.io')).toBe('JD');
    expect(initials(null)).toBe('?');
  });
});

describe('the session hint script', () => {
  it('marks the document when the browser holds a cloud session or a self-hosted one', () => {
    window.localStorage.setItem('oidc.user:https://auth.example/realms/x:web', '{"access_token":"t"}');
    runHintScript();
    expect(document.documentElement.hasAttribute(SESSION_HINT_ATTR)).toBe(true);

    hint(false);
    window.localStorage.clear();
    window.localStorage.setItem('ce_access_token', 'token');
    runHintScript();
    expect(document.documentElement.hasAttribute(SESSION_HINT_ATTR)).toBe(true);
  });

  it('marks it on the docs subdomain from the app\'s signed-in cookie, which that origin can read', () => {
    document.cookie = 'theme=dark; path=/';
    document.cookie = 'lc_account=LM; path=/';
    try {
      runHintScript();
      expect(document.documentElement.hasAttribute(SESSION_HINT_ATTR)).toBe(true);
    } finally {
      document.cookie = 'lc_account=; path=/; max-age=0';
      document.cookie = 'theme=; path=/; max-age=0';
    }
  });

  it('reads that cookie by its exact name: one that only ends the same marks nothing', () => {
    document.cookie = 'xlc_account=LM; path=/';
    try {
      runHintScript();
      expect(document.documentElement.hasAttribute(SESSION_HINT_ATTR)).toBe(false);
    } finally {
      document.cookie = 'xlc_account=; path=/; max-age=0';
    }
  });

  it('leaves it alone for a visitor: no session key, or an emptied one', () => {
    window.localStorage.setItem('theme', 'dark');
    window.localStorage.setItem('oidc.user:https://auth.example/realms/x:web', '');
    window.localStorage.setItem('ce_refresh_token', 'r');
    runHintScript();

    expect(document.documentElement.hasAttribute(SESSION_HINT_ATTR)).toBe(false);
  });

  it('never throws when storage is blocked', () => {
    const spy = vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => { throw new Error('blocked'); });
    try {
      expect(runHintScript).not.toThrow();
      expect(document.documentElement.hasAttribute(SESSION_HINT_ATTR)).toBe(false);
    } finally {
      spy.mockRestore();
    }
  });
});
