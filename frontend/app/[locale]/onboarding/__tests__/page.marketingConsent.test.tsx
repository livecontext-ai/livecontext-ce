// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * Marketing e-mail consent on the first onboarding screen (cloud): unchecked by default,
 * never sent while untouched, and sent once when the person leaves step 1 with it checked
 * (advance or skip), not on every click.
 */
const mocks = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  setMarketingConsent: vi.fn(),
}));

vi.mock('@/lib/navigation/leaveForChat', () => ({ leaveForChat: vi.fn() }));

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
    logout: vi.fn(),
  }),
}));

vi.mock('@/lib/api', () => ({
  apiClient: { get: mocks.apiGet, post: mocks.apiPost },
}));

vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { setMarketingConsent: mocks.setMarketingConsent },
}));

vi.mock('next/navigation', () => ({
  usePathname: () => window.location.pathname,
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="spinner" />,
}));

import OnboardingPage from '../page';

function mockStatusOnStep1() {
  mocks.apiGet.mockImplementation(async (path: string) => {
    if (path === '/auth/email/status') return { verified: true };
    if (path === '/auth-service/api/onboarding/status') {
      return {
        needsOnboarding: true,
        completed: false,
        skipped: false,
        currentStep: 1,
        displayName: 'Jane',
        profession: 'sales',
        companySize: 'solo',
      };
    }
    if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
      return { available: true, message: '' };
    }
    throw new Error(`unexpected GET ${path}`);
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OnboardingPage />
    </QueryClientProvider>,
  );
}

async function nextEnabled() {
  const next = screen.getByRole('button', { name: /^next$/ });
  await waitFor(() => expect(next).toBeEnabled());
  return next;
}

describe('Onboarding marketing consent', () => {
  beforeEach(() => {
    sessionStorage.clear();
    mocks.apiGet.mockReset();
    mocks.apiPost.mockReset();
    mocks.setMarketingConsent.mockReset();
    mocks.apiPost.mockResolvedValue({});
    mocks.setMarketingConsent.mockResolvedValue(undefined);
    mockStatusOnStep1();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it('is unchecked by default and sends nothing when left untouched', async () => {
    renderPage();

    const box = await screen.findByRole('checkbox', { name: 'marketingConsent' });
    expect(box).not.toBeChecked();

    fireEvent.click(await nextEnabled());
    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    expect(mocks.setMarketingConsent).not.toHaveBeenCalled();
  });

  it('sends consent once when the person advances from step 1 with the box checked', async () => {
    renderPage();

    const box = await screen.findByRole('checkbox', { name: 'marketingConsent' });
    fireEvent.click(box);
    fireEvent.click(box);
    fireEvent.click(box);
    expect(box).toBeChecked();
    // Clicking alone persists nothing.
    expect(mocks.setMarketingConsent).not.toHaveBeenCalled();

    fireEvent.click(await nextEnabled());

    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    expect(mocks.setMarketingConsent).toHaveBeenCalledTimes(1);
    expect(mocks.setMarketingConsent).toHaveBeenCalledWith(true);
  });

  it('does not send consent when the onboarding save is refused (the step does not advance)', async () => {
    mocks.apiPost.mockRejectedValue(new Error('400'));
    renderPage();

    fireEvent.click(await screen.findByRole('checkbox', { name: 'marketingConsent' }));
    fireEvent.click(await nextEnabled());

    expect(await screen.findByText('saveError')).toBeInTheDocument();
    expect(mocks.setMarketingConsent).not.toHaveBeenCalled();
  });

  it('honours a checked box on skip too', async () => {
    renderPage();

    fireEvent.click(await screen.findByRole('checkbox', { name: 'marketingConsent' }));
    await nextEnabled();
    fireEvent.click(screen.getByRole('button', { name: /skipForNow/ }));

    await waitFor(() => expect(mocks.setMarketingConsent).toHaveBeenCalledWith(true));
  });
});
