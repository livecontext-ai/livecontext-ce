// @vitest-environment jsdom
/**
 * The shared "is a plan change already scheduled?" read.
 *
 * <p>Two settings surfaces must stand down under this condition, and they must agree: the
 * Billing page suppresses its next-billing and credit-refresh rows, and the wallet card on
 * Quota & Usage suppresses a renewal amount it can only state at the CURRENT tier. They had
 * one implementation each before this hook, which is how the second surface shipped promising
 * "+100,000 credits" to a wallet scheduled to drop to 10,000.
 *
 * <p>What is pinned here is the part a rendering test cannot see: the query key, which is what
 * makes two open tabs cost one Stripe round-trip, and the fail-open posture.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { cleanup, renderHook } from '@testing-library/react';

const mocks = vi.hoisted(() => ({
  useQuery: vi.fn(),
  getScheduledChange: vi.fn(),
}));

vi.mock('@tanstack/react-query', () => ({ useQuery: mocks.useQuery }));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getScheduledChange: mocks.getScheduledChange },
}));

import { useScheduledPlanChange } from '../useScheduledPlanChange';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

/** The options the hook handed react-query on the last render. */
function lastQueryOptions() {
  return mocks.useQuery.mock.calls[mocks.useQuery.mock.calls.length - 1][0];
}

describe('useScheduledPlanChange', () => {
  it('reports a pending change, with the change itself', () => {
    const change = { effectiveDate: '2026-10-14T23:53:09', changeType: 'credit_tier_change' };
    mocks.useQuery.mockReturnValue({
      data: { hasScheduledChange: true, scheduledChange: change },
      isLoading: false,
    });

    const { result } = renderHook(() => useScheduledPlanChange());

    expect(result.current.hasScheduledChange).toBe(true);
    expect(result.current.scheduledChange).toEqual(change);
  });

  it('reports none while the read is still in flight, so a correct line is not blanked', () => {
    mocks.useQuery.mockReturnValue({ data: undefined, isLoading: true });

    const { result } = renderHook(() => useScheduledPlanChange());

    expect(result.current.hasScheduledChange).toBe(false);
    expect(result.current.isLoading).toBe(true);
  });

  it('reports none on an empty payload, however it arose - fails OPEN', () => {
    // A Stripe outage must not hide every subscriber's renewal date. Note WHERE the swallow
    // happens: `billingService.getScheduledChange` catches its own error and resolves
    // `{hasScheduledChange:false}`, so react-query rarely sees a rejection at all and this
    // hook's job is only to not invent a change from an absent payload. Asserting an
    // `isError` state would have pinned a path production does not produce.
    mocks.useQuery.mockReturnValue({ data: undefined, isLoading: false });

    const { result } = renderHook(() => useScheduledPlanChange());

    expect(result.current.hasScheduledChange).toBe(false);
    expect(result.current.scheduledChange).toBeUndefined();
  });

  it('treats a truthy-but-not-true flag as no change, rather than coercing it', () => {
    mocks.useQuery.mockReturnValue({
      data: { hasScheduledChange: 'yes' as unknown as boolean },
      isLoading: false,
    });

    expect(renderHook(() => useScheduledPlanChange()).result.current.hasScheduledChange).toBe(false);
  });

  it('uses the key the Billing page already used, so both surfaces share one cached read', () => {
    // The whole point of a shared hook: this is a live Stripe call, and a second key would
    // double it for anyone with both settings pages open.
    mocks.useQuery.mockReturnValue({ data: undefined, isLoading: false });

    renderHook(() => useScheduledPlanChange());

    expect(lastQueryOptions().queryKey).toEqual(['billing', 'scheduledChange']);
    expect(lastQueryOptions().staleTime).toBe(60_000);
  });

  it('does not fetch at all when disabled, which is how a signed-out page stays quiet', () => {
    mocks.useQuery.mockReturnValue({ data: undefined, isLoading: false });

    renderHook(() => useScheduledPlanChange(false));

    expect(lastQueryOptions().enabled).toBe(false);
  });

  it('fetches through the shared API service, not a hand-rolled request', () => {
    mocks.useQuery.mockReturnValue({ data: undefined, isLoading: false });
    renderHook(() => useScheduledPlanChange());

    lastQueryOptions().queryFn();

    expect(mocks.getScheduledChange).toHaveBeenCalledTimes(1);
  });
});
