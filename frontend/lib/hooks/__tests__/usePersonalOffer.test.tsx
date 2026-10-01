// @vitest-environment jsdom
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PENDING_PERSONAL_OFFER_KEY } from '@/lib/lifecycle/pendingPersonalOffer';
import { usePersonalOffer } from '../usePersonalOffer';

const mocks = vi.hoisted(() => ({
  userId: 7 as number | null,
  current: vi.fn(),
  preview: vi.fn(),
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useOptionalAuth: () => ({
    isAuthenticated: mocks.userId != null,
    isReady: true,
    isLoading: false,
    numericUserId: mocks.userId,
  }),
}));
vi.mock('@/lib/api/services/reward-api.service', () => ({
  rewardApi: {
    getCurrentPersonalOffer: mocks.current,
    previewPersonalOffer: mocks.preview,
  },
}));

let queryClient: QueryClient;
function wrapper({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  sessionStorage.clear();
  history.replaceState(null, '', '/en/app/settings/pricing');
  mocks.userId = 7;
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  mocks.current.mockReset().mockResolvedValue({ status: 'AVAILABLE', offerId: 12, offerVersion: 3 });
  mocks.preview.mockReset().mockImplementation(async (input) => ({
    status: 'AVAILABLE', offerId: 12, offerVersion: 3,
    monthlyCredits: input.creditTierIndex === 2 ? 50000 : 5000,
    billingCycle: input.billingCycle, expiresAt: '2026-10-01T00:00:00Z',
    plans: [{ planCode: 'PRO', bonusCredits: input.creditTierIndex === 2 ? 10000 : 0,
      paygFaceValueUsd: 10, status: input.creditTierIndex === 2 ? 'ELIGIBLE' : 'NO_BONUS' }],
  }));
});

describe('usePersonalOffer', () => {
  it('verifies an issued campaign link on the authenticated account and then clears its browser candidate', async () => {
    history.replaceState(null, '', '/en/app/settings/pricing?lc_offer=OFFER123&lc_ref=PARTNER123');
    const { result } = renderHook(() => usePersonalOffer(2, 'monthly'), { wrapper });

    await waitFor(() => expect(result.current.preview?.offerId).toBe(12));
    expect(mocks.preview).toHaveBeenCalledWith({ code: 'OFFER123', creditTierIndex: 2, billingCycle: 'monthly' });
    await waitFor(() => expect(result.current.candidateCode).toBeNull());
    expect(sessionStorage.getItem(PENDING_PERSONAL_OFFER_KEY)).toBeNull();
    expect(new URL(location.href).searchParams.get('lc_ref')).toBe('PARTNER123');
  });

  it('shows only the preview for the latest pack and cadence when responses arrive out of order', async () => {
    let resolveOld: (value: unknown) => void = () => {};
    mocks.preview.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockResolvedValue({ status: 'AVAILABLE', offerId: 12, offerVersion: 3, monthlyCredits: 50000,
        billingCycle: 'yearly', expiresAt: '2026-10-01T00:00:00Z',
        plans: [{ planCode: 'PRO', bonusCredits: 10000, paygFaceValueUsd: 10, status: 'ELIGIBLE' }] });
    const { result, rerender } = renderHook(({ tier, cycle }) => usePersonalOffer(tier, cycle), {
      initialProps: { tier: 0, cycle: 'monthly' as 'monthly' | 'yearly' }, wrapper,
    });
    await waitFor(() => expect(mocks.preview).toHaveBeenCalledTimes(1));
    rerender({ tier: 2, cycle: 'yearly' });
    await waitFor(() => expect(result.current.preview?.billingCycle).toBe('yearly'));
    resolveOld({ status: 'AVAILABLE', offerId: 12, offerVersion: 3, monthlyCredits: 5000,
      billingCycle: 'monthly', expiresAt: '2026-10-01T00:00:00Z', plans: [] });
    expect(result.current.preview?.monthlyCredits).toBe(50000);
  });

  it('drops a pending code when the authenticated account changes', async () => {
    history.replaceState(null, '', '/en/app/settings/pricing?lc_offer=OFFER123');
    mocks.preview.mockImplementation(() => new Promise(() => {}));
    const { result, rerender } = renderHook(() => usePersonalOffer(0, 'monthly'), { wrapper });
    await waitFor(() => expect(result.current.candidateCode).toBe('OFFER123'));
    mocks.userId = 8;
    rerender();
    await waitFor(() => expect(result.current.candidateCode).toBeNull());
    expect(sessionStorage.getItem(PENDING_PERSONAL_OFFER_KEY)).toBeNull();
  });

  it('surfaces an unavailable current offer lookup instead of silently allowing a full-price checkout', async () => {
    mocks.current.mockRejectedValue(new Error('network'));
    const { result } = renderHook(() => usePersonalOffer(0, 'monthly'), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});
