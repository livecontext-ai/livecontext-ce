// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import type { ModelCostBasis } from '@/lib/billing/model-cost-estimate';

/**
 * This hook decides what every model picker knows about price. It held a client-side copy of
 * the own-key PLAN lock until 2026-09-18, and that copy was wrong in the expensive direction:
 * `usePlanFeatureGate` answers UNLOCKED while the plan map is loading AND when the request for
 * it fails, so a FREE account whose plan call failed was quoted the flat own-key fee for a turn
 * the run bills at the platform rate. The gate now lives in auth-service, against the same table
 * the run reads, and the block arriving IS the answer.
 *
 * What these tests pin is therefore an ABSENCE: this hook must hand the server's answer on
 * unchanged. Re-introducing a filter here would pass the picker tests, because they are handed
 * a basis directly.
 */

const h = vi.hoisted(() => ({
  getModelCostBasis: vi.fn(),
  planFeatureGate: vi.fn(),
}));

vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getModelCostBasis: h.getModelCostBasis },
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isReady: true }),
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
// Wired so the assertion below is about this hook, not about the mock being absent: if the
// hook ever asks the plan gate again, this records it.
vi.mock('@/hooks/usePlanFeatureGate', () => ({
  usePlanFeatureGate: (...args: unknown[]) => {
    h.planFeatureGate(...args);
    return { planCode: 'FREE', isLoading: false, requirements: undefined, lockFor: () => ({ locked: true, requiredPlan: 'PRO' }) };
  },
}));

import { useModelCostBasis } from '../useModelCostBasis';

const BASIS: ModelCostBasis = {
  enabled: true,
  profiles: { chatConversation: { inputCoefficient: 1, outputCoefficient: 1 } },
  ownKey: { providers: ['anthropic'], feeByTier: { budget: 1, mid: 2, high: 5, top: 10, unknown: 2 } },
};

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  h.getModelCostBasis.mockReset();
  h.planFeatureGate.mockReset();
});

describe('useModelCostBasis', () => {
  it('hands the own-key block on untouched, whatever the client thinks of the plan', async () => {
    h.getModelCostBasis.mockResolvedValue(BASIS);

    const { result } = renderHook(() => useModelCostBasis(), { wrapper });

    await waitFor(() => expect(result.current.basis).not.toBeNull());
    expect(result.current.basis?.ownKey?.providers).toEqual(['anthropic']);
    // The mocked gate above reports LOCKED. A hook that re-read it would have stripped the
    // block, which is exactly the defect this replaced.
    expect(h.planFeatureGate).not.toHaveBeenCalled();
  });

  it('answers null until the server has answered, so a picker shows no price rather than a wrong one', async () => {
    let resolve: (value: ModelCostBasis) => void = () => {};
    h.getModelCostBasis.mockReturnValue(new Promise<ModelCostBasis>(r => { resolve = r; }));

    const { result } = renderHook(() => useModelCostBasis(), { wrapper });

    expect(result.current.basis).toBeNull();
    expect(result.current.isLoading).toBe(true);

    resolve(BASIS);
    await waitFor(() => expect(result.current.basis).not.toBeNull());
  });

  it('asks nothing at all when the caller disables it', () => {
    renderHook(() => useModelCostBasis({ enabled: false }), { wrapper });

    expect(h.getModelCostBasis).not.toHaveBeenCalled();
  });
});
