// @vitest-environment jsdom
/**
 * Settings > AI providers keeps the open tab in the address (`?tab=`): a reload reopens it,
 * and leaving the Models tab drops the filters that tab kept there.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const { credentialService } = vi.hoisted(() => ({
  credentialService: { getLlmProviderStatus: vi.fn() },
}));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false, isLoading: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn(), hasRole: (role: string) => role === 'ADMIN' }),
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true }));
vi.mock('@/lib/api/orchestrator/credential.service', () => ({ credentialService }));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: { getStatus: vi.fn(), setLlmSource: vi.fn() },
  cloudSourceErrorKey: vi.fn(() => null),
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('../components/UserKeysPanel', () => ({ default: () => <div data-testid="user-keys-panel" /> }));
vi.mock('../components/ProviderCard', () => ({ default: () => <div data-testid="provider-card" /> }));
vi.mock('../components/BridgeSetupPanel', () => ({ default: () => null }));
vi.mock('../components/BridgeAccessPanel', () => ({ default: () => null }));
vi.mock('../components/ModelManagementPanel', () => ({ default: () => <div data-testid="models-panel" /> }));
vi.mock('../components/ModelExecutionLinksPanel', () => ({ default: () => <div data-testid="links-panel" /> }));
vi.mock('../components/ModelBundleSyncButton', () => ({ ModelBundleSyncButton: () => null }));

import AiProvidersPage from '../page';

const PAGE = '/en/app/settings/ai-providers';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

beforeEach(() => {
  vi.clearAllMocks();
  credentialService.getLlmProviderStatus.mockResolvedValue([]);
});
afterEach(cleanup);

describe('Settings > AI providers, the tab in the address', () => {
  it('opens on the tab the address names', async () => {
    openAt('tab=models');
    render(<AiProvidersPage />);

    expect(await screen.findByTestId('models-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('links-panel')).toBeNull();
  });

  it('falls back to the first tab on a name it does not have', async () => {
    openAt('tab=nope');
    render(<AiProvidersPage />);

    await waitFor(() => expect(credentialService.getLlmProviderStatus).toHaveBeenCalled());
    expect(screen.queryByTestId('models-panel')).toBeNull();
    expect(screen.queryByTestId('user-keys-panel')).toBeNull();
  });

  it('picking a tab pushes it, and leaving Models drops the filters that tab owned', async () => {
    openAt('tab=models&status=off&q=gpt&category=browser_agent');
    render(<AiProvidersPage />);
    await screen.findByTestId('models-panel');

    fireEvent.click(screen.getByRole('button', { name: /mode\.executionLinks/ }));

    expect(await screen.findByTestId('links-panel')).toBeInTheDocument();
    expect(fakeFolderRouter.search()).toBe('tab=execution_links');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('push');

    // Back to the default tab: spelled by absence.
    fireEvent.click(screen.getByRole('button', { name: /mode\.apiKey/ }));
    expect(fakeFolderRouter.search()).toBe('');
  });
});
