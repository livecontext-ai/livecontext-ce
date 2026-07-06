// @vitest-environment jsdom
/**
 * Regression guard: usePaygTiers must NEVER fire its query in CE.
 *
 * The CE monolith has no billing service (ce-contracts pins
 * /api/billing/payg-tiers to 404), yet InsufficientCreditsModal mounts this
 * hook app-wide, so an ungated query 404s + console-errors on EVERY page
 * navigation of a fresh CE install (the "first login, change page, error"
 * report). The hook gates on IS_CE; cloud behaviour is unchanged.
 *
 * IS_CE is a module-level const frozen at import, so each case re-mocks
 * '@/lib/edition' and re-imports the hook module (same pattern as
 * format-cost.test.ts).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, cleanup, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';

const getPaygTiers = vi.fn().mockResolvedValue({ configured: true, tiers: [{ tier: 'small' }] });

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isReady: true, user: { sub: 'u-1' } }),
}));
vi.mock('../../api/unified-api-service', () => ({
  unifiedApiService: { getPaygTiers: (...args: unknown[]) => getPaygTiers(...args) },
}));

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

async function importHook(isCe: boolean) {
  vi.doMock('@/lib/edition', () => ({ IS_CE: isCe, IS_CLOUD: !isCe, EDITION: isCe ? 'ce' : 'cloud' }));
  const mod = await import('../smart-hooks-complete');
  return mod.usePaygTiers;
}

beforeEach(() => {
  vi.resetModules();
  getPaygTiers.mockClear();
});

afterEach(() => {
  cleanup();
  vi.doUnmock('@/lib/edition');
});

describe('usePaygTiers CE gate', () => {
  it('CE: never fetches the PAYG tiers (no 404 spam on page navigation)', async () => {
    const usePaygTiers = await importHook(true);

    const { result } = renderHook(() => usePaygTiers(), { wrapper });
    // Deterministic idle check: a disabled query settles to isLoading=false
    // without ever invoking the fetcher (vs. "slow fetch" which would keep
    // isLoading=true until resolution).
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(getPaygTiers).not.toHaveBeenCalled();
    expect(result.current.tiers).toEqual([]);
    expect(result.current.configured).toBe(false);
  });

  it('cloud: fetches the PAYG tiers as before', async () => {
    const usePaygTiers = await importHook(false);

    const { result } = renderHook(() => usePaygTiers(), { wrapper });
    await waitFor(() => expect(getPaygTiers).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(result.current.configured).toBe(true));
  });
});
