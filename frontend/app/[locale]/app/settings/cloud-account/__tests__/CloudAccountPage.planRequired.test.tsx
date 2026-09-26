/**
 * @vitest-environment jsdom
 *
 * CE cloud-account page and the paid-plan rule for cloud links:
 *  - a link the cloud refuses for CLOUD_LINK_PLAN_REQUIRED (status.planRequired) shows the
 *    paid-plan banner with the CLOUD pricing page in a new tab, and the link stays shown as
 *    connected (it is kept, it comes back once the account pays);
 *  - `?cloud_link_error=expired` (the backend no longer knew the OAuth state) shows the
 *    "connect again" message and is removed from the address bar;
 *  - "Connect" navigates to the cloud onboarding startUrl when the backend offers it.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../../../../messages/en.json';

const h = vi.hoisted(() => ({
  searchParams: new URLSearchParams(),
  getStatus: vi.fn(),
  getAuthUrl: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useSearchParams: () => h.searchParams,
  usePathname: () => '/en/app/settings/cloud-account',
}));
vi.mock('@tanstack/react-query', () => {
  const client = { invalidateQueries: vi.fn() };
  return { useQueryClient: () => client };
});
vi.mock('@/lib/edition/edition', () => ({ IS_CE: true }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: (r: string) => r === 'ADMIN', isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/api/cloud-link.service', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api/cloud-link.service')>(
    '@/lib/api/cloud-link.service',
  );
  return {
    ...actual,
    cloudLinkService: {
      getStatus: (...a: unknown[]) => h.getStatus(...a),
      connect: vi.fn(),
      disconnect: vi.fn(),
      getConnectUrl: async (returnPath?: string) => actual.resolveConnectUrl(await h.getAuthUrl(returnPath)),
    },
  };
});
vi.mock('@/lib/api/ce-link.service', () => ({ ceLinkService: { mine: vi.fn() } }));
vi.mock('../components/BundlesSection', () => ({ default: () => <div data-testid="bundles" /> }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));

import CloudAccountPage from '../page';

const renderPage = () =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <CloudAccountPage />
    </NextIntlClientProvider>,
  );

describe('CloudAccountPage (CE) - paid-plan cloud link', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    h.searchParams = new URLSearchParams();
    window.history.replaceState({}, '', '/en/app/settings/cloud-account');
  });
  afterEach(cleanup);

  it('shows the paid-plan banner, linking to the cloud pricing page in a new tab, while keeping the link connected', async () => {
    h.getStatus.mockResolvedValue({
      linked: true,
      registered: true,
      cloudUsername: 'owner',
      planRequired: true,
      planRequiredPlanCode: 'FREE',
    });

    renderPage();

    const banner = await screen.findByTestId('cloud-link-plan-required-banner');
    expect(banner).toHaveTextContent('Your LiveContext Cloud link needs a paid plan');
    const link = screen.getByRole('link', { name: /View cloud plans/i });
    expect(link).toHaveAttribute('href', 'https://livecontext.ai/app/settings/pricing');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
    // Kept, not revoked: still reads as connected, with the disconnect action.
    expect(screen.getByRole('button', { name: /Disconnect/i })).toBeInTheDocument();
  });

  it('shows no paid-plan banner on a healthy link', async () => {
    h.getStatus.mockResolvedValue({ linked: true, registered: true, cloudUsername: 'owner', planRequired: false });

    renderPage();

    await screen.findByRole('button', { name: /Disconnect/i });
    expect(screen.queryByTestId('cloud-link-plan-required-banner')).toBeNull();
  });

  it('shows the expired message on ?cloud_link_error=expired and removes the parameter from the URL', async () => {
    h.searchParams = new URLSearchParams('cloud_link_error=expired');
    window.history.replaceState({}, '', '/en/app/settings/cloud-account?cloud_link_error=expired&tab=connection');
    h.getStatus.mockResolvedValue({ linked: false });

    renderPage();

    expect(await screen.findByTestId('cloud-link-expired-notice')).toHaveTextContent(
      'The connection expired, click Connect again.',
    );
    expect(window.location.search).toBe('?tab=connection');
  });

  it('a refresh after a completed link (install linked) cleans ?cloud_link_error=expired without showing the notice', async () => {
    h.searchParams = new URLSearchParams('cloud_link_error=expired');
    window.history.replaceState({}, '', '/en/app/settings/cloud-account?cloud_link_error=expired');
    h.getStatus.mockResolvedValue({ linked: true, registered: true, cloudUsername: 'owner', planRequired: false });

    renderPage();

    await screen.findByRole('button', { name: /Disconnect/i });
    expect(screen.queryByTestId('cloud-link-expired-notice')).toBeNull();
    expect(window.location.search).toBe('');
  });

  it('shows no expired message without the parameter', async () => {
    h.getStatus.mockResolvedValue({ linked: false });

    renderPage();

    await screen.findByRole('button', { name: 'Connect to Cloud' });
    expect(screen.queryByTestId('cloud-link-expired-notice')).toBeNull();
  });

  it('Connect navigates to the cloud onboarding startUrl', async () => {
    h.getStatus.mockResolvedValue({ linked: false });
    h.getAuthUrl.mockResolvedValue({
      authUrl: 'https://kc.example/auth',
      state: 's',
      startUrl: 'https://livecontext.ai/onboarding?ce_link=1&state=s',
    });
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      value: { ...originalLocation, href: 'http://ce.local/en/app/settings/cloud-account' },
      writable: true,
    });

    try {
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'Connect to Cloud' }));
      await waitFor(() =>
        expect(window.location.href).toBe('https://livecontext.ai/onboarding?ce_link=1&state=s'),
      );
    } finally {
      Object.defineProperty(window, 'location', { value: originalLocation, writable: true });
    }
  });
});
