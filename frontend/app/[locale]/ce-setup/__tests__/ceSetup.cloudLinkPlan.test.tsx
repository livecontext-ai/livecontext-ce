/**
 * @vitest-environment jsdom
 *
 * CE setup wizard, step 1 (cloud connection), and the paid-plan rule for cloud links:
 *  - once linked, a link the cloud refuses for CLOUD_LINK_PLAN_REQUIRED shows the paid-plan
 *    banner under the "connected" confirmation (the link is kept);
 *  - `?cloud_link_error=expired` shows the "connect again" message and is removed from the URL;
 *  - "Connect" goes to the cloud onboarding startUrl when the backend offers one.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../../messages/en.json';

const h = vi.hoisted(() => ({
  searchParams: new URLSearchParams(),
  getStatus: vi.fn(),
  getAuthUrl: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useSearchParams: () => h.searchParams,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/en/ce-setup',
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: (r: string) => r === 'ADMIN', isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/lib/api/orchestrator/credential.service', () => ({
  credentialService: {
    getLlmProviderStatus: vi.fn().mockResolvedValue([]),
    getPlatformCredentials: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('@/lib/api/orchestrator/bridge-access.service', () => ({ bridgeAccessService: {} }));
vi.mock('@/lib/api/services/catalog-visibility.service', () => ({
  catalogVisibilityService: { getIntegrations: vi.fn().mockResolvedValue([]) },
}));
vi.mock('@/lib/api', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/components/credentials/CredentialWizard', () => ({ CredentialWizard: () => null }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));
vi.mock('@/lib/api/cloud-link.service', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api/cloud-link.service')>(
    '@/lib/api/cloud-link.service',
  );
  return {
    ...actual,
    cloudLinkService: {
      getStatus: (...a: unknown[]) => h.getStatus(...a),
      connect: vi.fn(),
      probeTlsIntercept: vi.fn().mockResolvedValue({ intercepted: false }),
      getConnectUrl: async (returnPath?: string) => actual.resolveConnectUrl(await h.getAuthUrl(returnPath)),
    },
  };
});

import CeSetupPage from '../page';

const renderPage = () =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <CeSetupPage />
    </NextIntlClientProvider>,
  );

describe('CE setup step 1 - paid-plan cloud link', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    h.searchParams = new URLSearchParams();
    window.history.replaceState({}, '', '/en/ce-setup');
  });
  afterEach(cleanup);

  it('shows the paid-plan banner under the linked confirmation when the cloud requires a paid plan', async () => {
    h.getStatus.mockResolvedValue({ linked: true, registered: true, cloudUsername: 'owner', planRequired: true });

    renderPage();

    const banner = await screen.findByTestId('cloud-link-plan-required-banner');
    expect(banner).toHaveTextContent('Your LiveContext Cloud link needs a paid plan');
    expect(screen.getByRole('link', { name: /View cloud plans/i })).toHaveAttribute(
      'href',
      'https://livecontext.ai/app/settings/pricing',
    );
  });

  it('shows no banner on a healthy link', async () => {
    h.getStatus.mockResolvedValue({ linked: true, registered: true, cloudUsername: 'owner', planRequired: false });

    renderPage();

    await waitFor(() => expect(h.getStatus).toHaveBeenCalled());
    await screen.findByText(/owner/);
    expect(screen.queryByTestId('cloud-link-plan-required-banner')).toBeNull();
  });

  it('shows the expired message on ?cloud_link_error=expired and cleans the URL', async () => {
    h.searchParams = new URLSearchParams('cloud_link_error=expired');
    window.history.replaceState({}, '', '/en/ce-setup?cloud_link_error=expired');
    h.getStatus.mockResolvedValue({ linked: false });

    renderPage();

    expect(await screen.findByTestId('cloud-link-expired-notice')).toHaveTextContent(
      'The connection expired, click Connect again.',
    );
    expect(window.location.search).toBe('');
  });

  it('a refresh after a completed link (install linked) cleans ?cloud_link_error=expired without showing the notice', async () => {
    h.searchParams = new URLSearchParams('cloud_link_error=expired');
    window.history.replaceState({}, '', '/en/ce-setup?cloud_link_error=expired');
    h.getStatus.mockResolvedValue({ linked: true, registered: true, cloudUsername: 'owner', planRequired: false });

    renderPage();

    await screen.findByText(/owner/);
    expect(screen.queryByTestId('cloud-link-expired-notice')).toBeNull();
    expect(window.location.search).toBe('');
  });

  it('Connect goes to the cloud onboarding startUrl, with the wizard as returnPath', async () => {
    h.getStatus.mockResolvedValue({ linked: false });
    h.getAuthUrl.mockResolvedValue({
      authUrl: 'https://kc.example/auth',
      state: 's',
      startUrl: 'https://livecontext.ai/onboarding?ce_link=1&state=s',
    });
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      value: { ...originalLocation, href: 'http://ce.local/en/ce-setup' },
      writable: true,
    });
    try {
      renderPage();
      const connect = await screen.findByRole('button', { name: messages.ceSetup.cloudLinkConnect });
      fireEvent.click(connect);
      await waitFor(() =>
        expect(window.location.href).toBe('https://livecontext.ai/onboarding?ce_link=1&state=s'),
      );
      expect(h.getAuthUrl).toHaveBeenCalledWith('/en/ce-setup');
    } finally {
      Object.defineProperty(window, 'location', { value: originalLocation, writable: true });
    }
  });
});
