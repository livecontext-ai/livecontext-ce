// @vitest-environment jsdom
/**
 * Regression (CASA round 2, NIT 1): on the cloud, the login and register pages only hand over to
 * Keycloak, and showed their error only when that hand-over REJECTED. react-oidc-context never
 * rejects: a failed redirect (offline, discovery unreachable) resolves null. So an offline page
 * load sat behind an endless spinner. A redirect that leaves the page never settles, so one that
 * settles at all did not leave: both pages now show their error and a retry, and the retry is an
 * explicit action (a clean loop budget). The full provider stack is exercised in
 * lib/providers/__tests__/smart-providers.storedSessionRecovery.test.tsx; this pins both pages.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

vi.mock('@/lib/edition', () => ({ IS_CLOUD: true, IS_CE: false }));
vi.mock('next-intl', () => ({
  useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
  useLocale: () => 'en',
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams('returnTo=%2Fen%2Fapp%2Fworkflows%2F7'),
}));
vi.mock('next/link', () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>,
}));
vi.mock('@/components/auth/AuthLayout', () => ({
  AuthLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div>spinner</div> }));
vi.mock('@/lib/providers/embedded-auth-provider', () => ({ embeddedLogin: vi.fn(), embeddedRegister: vi.fn() }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/lib/api', () => ({ apiClient: { get: vi.fn().mockResolvedValue(null) } }));

const loginWithRedirect = vi.fn();
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isAuthenticated: false, isLoading: false, loginWithRedirect }),
}));

import LoginPage from '../page';
import RegisterPage from '../../register/page';

beforeEach(() => {
  loginWithRedirect.mockReset();
});
afterEach(() => cleanup());

describe.each([
  ['login', LoginPage, 'errors.signInUnreachable'],
  ['register', RegisterPage, 'errors.signInUnreachable'],
])('cloud %s page: a Keycloak redirect that did not leave the page', (_name, Page, errorText) => {
  it('resolved (react-oidc-context swallowed the failure): error and retry, not an endless spinner', async () => {
    loginWithRedirect.mockResolvedValueOnce(undefined);

    render(<Page />);

    expect(await screen.findByText(errorText)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'errors.retry' })).toBeInTheDocument();
    expect(screen.queryByText('spinner')).not.toBeInTheDocument();
    expect(loginWithRedirect).toHaveBeenCalledWith({
      appState: { returnTo: '/en/app/workflows/7' },
      resetLoopGuards: false,
    });
  });

  it('rejected: the same error and retry', async () => {
    loginWithRedirect.mockRejectedValueOnce(new Error('boom'));

    render(<Page />);

    expect(await screen.findByText(errorText)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'errors.retry' })).toBeInTheDocument();
  });

  it('a redirect still leaving the page keeps the spinner', async () => {
    loginWithRedirect.mockImplementation(() => new Promise<void>(() => {}));

    render(<Page />);
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByText('spinner')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'errors.retry' })).not.toBeInTheDocument();
  });

  it('the retry is an explicit sign-in (clean loop budget) and hides the error while it leaves', async () => {
    loginWithRedirect.mockResolvedValueOnce(undefined);
    render(<Page />);
    const retry = await screen.findByRole('button', { name: 'errors.retry' });

    loginWithRedirect.mockImplementation(() => new Promise<void>(() => {}));
    await act(async () => { retry.click(); });

    expect(loginWithRedirect).toHaveBeenLastCalledWith({
      appState: { returnTo: '/en/app/workflows/7' },
      resetLoopGuards: true,
    });
    expect(screen.queryByText(errorText)).not.toBeInTheDocument();
    expect(screen.getByText('spinner')).toBeInTheDocument();
  });
});
