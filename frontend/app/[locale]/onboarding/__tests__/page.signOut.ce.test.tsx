// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * The onboarding escape hatches under the CE edition.
 *
 * Its sibling `page.emailStepSignOut.test.tsx` mocks `IS_CE: false` at file
 * scope, which pins the whole of it to cloud. That is not a detail here: CE is
 * the edition where `emailCodeFlowEnabled` is false, so the email step never
 * renders and the error card is the ONLY hatch a self-hosted user can reach.
 * Nothing was covering it.
 *
 * The two cases below are the ones the edition actually changes: the hatch is
 * present on the error card, and the email-step hatch is unreachable because
 * the step itself is (CE accounts are verified at registration, so sending them
 * a code would be a step that can never be passed).
 */
const mocks = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  logout: vi.fn(),
  track: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({
    user: { sub: 'u1', name: 'Jane', email: 'jane@example.com' },
    isLoading: false,
    isAuthenticated: true,
    loginWithRedirect: vi.fn(),
    logout: mocks.logout,
  }),
}));

vi.mock('@/lib/api', () => ({
  apiClient: { get: mocks.apiGet, post: mocks.apiPost },
}));

vi.mock('@/lib/edition', () => ({ IS_CE: true }));

vi.mock('@/lib/analytics/analytics', () => ({ track: mocks.track }));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="spinner" />,
}));

import OnboardingPage from '../page';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OnboardingPage />
    </QueryClientProvider>,
  );
}

describe('Onboarding sign-out under CE', () => {
  beforeEach(() => {
    sessionStorage.clear();
    mocks.apiGet.mockReset();
    mocks.apiPost.mockReset();
    mocks.logout.mockReset();
    mocks.track.mockReset();
    mocks.apiPost.mockResolvedValue({});
    mocks.logout.mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
  });

  it('the error card is a way out on a self-hosted install too', async () => {
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth-service/api/onboarding/status') throw new Error('status unavailable');
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        return { available: true, message: '' };
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('errorTitle')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'signOut' }));

    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.track).toHaveBeenCalledWith('onboarding_signed_out', { signed_out_from: 'error' });
  });

  it('never asks a self-hosted account for an email code, so that hatch is not needed', async () => {
    // `emailCodeFlowEnabled = !IS_CE`, and a CE account is verified at
    // registration. The email status endpoint is not even called: asserting
    // that is what proves the step is unreachable rather than merely unrendered.
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        return { available: true, message: '' };
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('ce.step1.title')).toBeInTheDocument();
    expect(mocks.apiGet).not.toHaveBeenCalledWith('/auth/email/status');
    expect(screen.queryByRole('button', { name: 'emailVerification.wrongEmail' }))
      .not.toBeInTheDocument();
    await waitFor(() => expect(mocks.apiPost).not.toHaveBeenCalled());
  });
});
