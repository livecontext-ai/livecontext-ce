// @vitest-environment jsdom
/**
 * The one in-product entry point to password reset.
 *
 * Everything else in the reset flow is reachable only from this link or from the
 * URL inside the e-mail it eventually produces. Measured: deleting the link
 * block from the login page left every frontend test green and `tsc --noEmit`
 * clean, and `auth.login.forgotPassword` would have become an unused key in six
 * locales without the parity test noticing, because that test compares key SETS
 * and not usage. So the feature could have shipped complete, tested, and
 * unreachable by anyone who did not already have a reset e-mail.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

const editionMock = vi.hoisted(() => ({ IS_CLOUD: false, IS_CE: true }));
vi.mock('@/lib/edition', () => editionMock);

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'fr',
}));

const replaceSpy = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: replaceSpy }),
  useSearchParams: () => ({ get: () => null }),
}));

vi.mock('next/link', () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => (
    <a href={href}>{children}</a>
  ),
}));

vi.mock('@/components/auth/AuthLayout', () => ({
  AuthLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div>spinner</div> }));

vi.mock('@/lib/providers/embedded-auth-provider', () => ({ embeddedLogin: vi.fn() }));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({
    isAuthenticated: false,
    isLoading: false,
    // The cloud branch chains .catch() on this, so it has to be a promise.
    loginWithRedirect: vi.fn().mockResolvedValue(undefined),
  }),
}));

vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

// A virgin CE install would redirect to registration; this one has an account.
vi.mock('@/lib/api', () => ({
  apiClient: { get: vi.fn().mockResolvedValue({ hasUsers: true, firstRun: false }) },
}));
vi.mock('@/components/security/onboardingStatus', () => ({ CE_STATUS_API_PATH: '/ce/status' }));
vi.mock('@/lib/auth/ceFirstRun', () => ({ isCeFirstRun: () => false }));

import LoginPage from '../page';

beforeEach(() => {
  editionMock.IS_CLOUD = false;
  replaceSpy.mockReset();
});

afterEach(() => cleanup());

describe('the login page offers the way back in', () => {
  it('renders a "forgot password" link pointing at the CURRENT locale', async () => {
    render(<LoginPage />);

    const link = await screen.findByRole('link', { name: 'forgotPassword' });
    // The locale must be carried: the proxy needs it on this path, and a
    // hardcoded /en would drop a French user into English.
    expect(link).toHaveAttribute('href', '/fr/forgot-password');
  });

  it('places it with the password field, not buried in the footer', async () => {
    render(<LoginPage />);

    const link = await screen.findByRole('link', { name: 'forgotPassword' });
    const label = screen.getByText('password');
    // Someone who cannot get in is looking at the input that just failed. This
    // asserts the two are siblings rather than merely both present.
    expect(label.parentElement).toBe(link.parentElement);
  });

  it('is NOT rendered on cloud, where Keycloak owns the flow', async () => {
    editionMock.IS_CLOUD = true;
    render(<LoginPage />);

    await waitFor(() => expect(screen.queryByRole('link', { name: 'forgotPassword' })).toBeNull());
  });
});
