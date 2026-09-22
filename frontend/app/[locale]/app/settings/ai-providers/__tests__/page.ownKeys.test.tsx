// @vitest-environment jsdom
/**
 * Settings > AI providers on cloud: an admin gets the platform tabs plus "Your keys"; a
 * non-admin, who used to be turned away, gets the own-keys panel alone and never triggers
 * the admin-only status fetch.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { auth, credentialService } = vi.hoisted(() => ({
  auth: { roles: new Set<string>() },
  credentialService: {
    getLlmProviderStatus: vi.fn(),
  },
}));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false, isLoading: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn(), hasRole: (role: string) => auth.roles.has(role) }),
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true }));
vi.mock('@/lib/api/orchestrator/credential.service', () => ({ credentialService }));
vi.mock('@/lib/api/cloud-link.service', () => ({ cloudLinkService: { getStatus: vi.fn(), setLlmSource: vi.fn() } }));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('../components/UserKeysPanel', () => ({
  default: ({ definitions, pricingHref }: { definitions: Array<{ providerName: string }>; pricingHref: string }) => (
    <div data-testid="user-keys-panel" data-providers={definitions.map((d) => d.providerName).join(',')} data-pricing={pricingHref} />
  ),
}));
vi.mock('../components/ProviderCard', () => ({ default: () => <div data-testid="provider-card" /> }));
vi.mock('../components/BridgeSetupPanel', () => ({ default: () => null }));
vi.mock('../components/BridgeAccessPanel', () => ({ default: () => null }));
vi.mock('../components/ModelManagementPanel', () => ({ default: () => null }));
vi.mock('../components/ModelExecutionLinksPanel', () => ({ default: () => null }));
vi.mock('../components/ModelBundleSyncButton', () => ({ ModelBundleSyncButton: () => null }));

import AiProvidersPage from '../page';

beforeEach(() => {
  vi.clearAllMocks();
  auth.roles = new Set();
  credentialService.getLlmProviderStatus.mockResolvedValue([]);
});

afterEach(cleanup);

describe('Settings > AI providers, own keys on cloud', () => {
  it('states the offer once, not twice a few pixels apart', async () => {
    // The page header used to repeat `yourKeys.intro` above the panel, which opens with the same
    // sentence. Two paragraphs of one sentence, stacked. The panel is stubbed here, so anything
    // matching that key on screen can only be the header's copy - which must be gone.
    render(<AiProvidersPage />);
    await screen.findByTestId('user-keys-panel');

    expect(screen.queryByText('yourKeys.intro')).not.toBeInTheDocument();
    // The heading stays: something has to name the page.
    expect(screen.getByText('mode.yourKeys')).toBeInTheDocument();
  });

  it('a non-admin cloud user gets the own-keys panel alone, never "unauthorized", and no admin status fetch', async () => {
    render(<AiProvidersPage />);

    const panel = await screen.findByTestId('user-keys-panel');
    expect(screen.queryByText('unauthorized')).toBeNull();
    expect(screen.queryByText('errors.adminOnly')).toBeNull();
    expect(screen.queryByTestId('provider-card')).toBeNull();
    // The aggregators the platform prices on its own are not offered as own keys.
    expect(panel.getAttribute('data-providers')).not.toMatch(/openrouter|cohere/);
    expect(panel.getAttribute('data-providers')).toMatch(/anthropic/);
    expect(panel.getAttribute('data-pricing')).toBe('/en/app/settings/pricing');
    expect(credentialService.getLlmProviderStatus).not.toHaveBeenCalled();
  });

  it('an admin keeps the platform page, with a "Your keys" tab among the others, and the status fetch runs', async () => {
    auth.roles = new Set(['ADMIN']);

    render(<AiProvidersPage />);

    await waitFor(() => expect(credentialService.getLlmProviderStatus).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole('button', { name: /mode\.yourKeys/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /mode\.apiKey/ })).toBeInTheDocument();
    // The own-keys panel is a tab, not the whole page, for an admin.
    expect(screen.queryByTestId('user-keys-panel')).toBeNull();
  });
});
