/**
 * @vitest-environment jsdom
 *
 * Cloud-account page - model-cache invalidation on link changes. Linking or
 * unlinking LiveContext Cloud changes which model catalog /v3/chat/models
 * returns (BYOK vs cloud), and useModels holds a module-level cache with a
 * 5-minute TTL: without an explicit clearModelsCache() the pickers keep
 * showing the stale (possibly empty) catalog after the OAuth callback - the
 * exact dead end the NoProviderCta feature exists to fix. Pins that BOTH the
 * connect callback and the disconnect clear the models cache AND refresh the
 * shared ['cloud-link','status'] react-query entry (sidebar, marketplace
 * gate, CTA surfaces).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const h = vi.hoisted(() => ({
  searchParams: new URLSearchParams(),
  invalidateQueries: vi.fn(),
  clearModelsCache: vi.fn(),
  connect: vi.fn(),
  disconnect: vi.fn(),
  getStatus: vi.fn(),
}));

// The t function must be REFERENTIALLY STABLE across renders (like the real
// next-intl): handleCallback/loadStatus depend on it via useCallback, so a
// fresh function per render would re-fire the callback effect in a loop.
vi.mock('next-intl', () => {
  const cache = new Map<string, (k: string) => string>();
  return {
    useTranslations: (ns?: string) => {
      const key = ns ?? '';
      if (!cache.has(key)) cache.set(key, (k: string) => `${key}.${k}`);
      return cache.get(key)!;
    },
  };
});
vi.mock('next/navigation', () => ({
  useSearchParams: () => h.searchParams,
  usePathname: () => '/en/app/settings/cloud-account',
}));
// Same stability requirement as the t mock: the real useQueryClient returns
// the SAME client instance every render (handleCallback depends on it).
vi.mock('@tanstack/react-query', () => {
  const client = { invalidateQueries: (...a: unknown[]) => h.invalidateQueries(...a) };
  return { useQueryClient: () => client };
});
vi.mock('@/lib/edition/edition', () => ({ IS_CE: true }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: (r: string) => r === 'ADMIN', isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: h.clearModelsCache }));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: {
    getStatus: (...a: unknown[]) => h.getStatus(...a),
    connect: (...a: unknown[]) => h.connect(...a),
    disconnect: (...a: unknown[]) => h.disconnect(...a),
  },
}));
vi.mock('@/lib/api/ce-link.service', () => ({ ceLinkService: { listInstalls: vi.fn().mockResolvedValue([]) } }));
vi.mock('../components/BundlesSection', () => ({ default: () => <div data-testid="bundles" /> }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));

import CloudAccountPage from '../page';

describe('CloudAccountPage - models cache invalidation on link changes', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    h.searchParams = new URLSearchParams();
    h.getStatus.mockResolvedValue({ linked: false });
    h.connect.mockResolvedValue({ linked: true, registered: true });
    h.disconnect.mockResolvedValue(undefined);
    window.history.replaceState({}, '', '/en/app/settings/cloud-account');
  });
  afterEach(cleanup);

  it('clears the models cache and refreshes the cloud-link query when the OAuth callback completes', async () => {
    h.searchParams = new URLSearchParams('cloud_link_callback=1&state=oauth-state-1');

    render(<CloudAccountPage />);

    await waitFor(() => expect(h.connect).toHaveBeenCalledWith('oauth-state-1'));
    await waitFor(() => expect(h.clearModelsCache).toHaveBeenCalledTimes(1));
    expect(h.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['cloud-link', 'status'] });
  });

  it('does NOT clear the models cache when the callback fails (catalog unchanged)', async () => {
    h.searchParams = new URLSearchParams('cloud_link_callback=1&state=bad-state');
    h.connect.mockRejectedValue(new Error('invalid state'));

    render(<CloudAccountPage />);

    await waitFor(() => expect(h.connect).toHaveBeenCalled());
    expect(h.clearModelsCache).not.toHaveBeenCalled();
    expect(h.invalidateQueries).not.toHaveBeenCalled();
  });

  it('clears the models cache and refreshes the cloud-link query on disconnect (symmetric)', async () => {
    h.getStatus.mockResolvedValue({ linked: true, registered: true, cloudUsername: 'admin' });

    render(<CloudAccountPage />);
    // Open the confirm dialog then confirm the disconnect.
    const disconnectButton = await screen.findByRole('button', { name: /disconnect/i });
    fireEvent.click(disconnectButton);
    const confirmButton = await screen.findByRole('button', { name: /confirmDisconnect\.button/i });
    fireEvent.click(confirmButton);

    await waitFor(() => expect(h.disconnect).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(h.clearModelsCache).toHaveBeenCalledTimes(1));
    expect(h.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['cloud-link', 'status'] });
  });
});
