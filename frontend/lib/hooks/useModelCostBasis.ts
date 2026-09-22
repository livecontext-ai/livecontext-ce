'use client';

import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/api/query-keys';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { IS_CE } from '@/lib/edition';
import type { ModelCostBasis } from '@/lib/billing/model-cost-estimate';

/**
 * What a model will cost before it is picked: the effective billing multiplier
 * and the token workload of each cost profile, both served by auth-service.
 *
 * <p><b>Why the server owns the numbers.</b> The margin lever has exactly one
 * home (`billing.llm.cloud-multiplier`, pinned per environment and guarded by
 * `EconomicsConfigPinTest`). A picker holding its own copy would quote a price
 * the ledger does not charge the day the lever moves, and nothing would fail.
 *
 * <p><b>Why it is a hook of its own</b>, next to
 * {@link useMonthlyCreditsCannotPay} rather than folded into the pickers: the
 * answer is about the install, not about any one model, so it is asked ONCE per
 * picker and handed down to the presentational rows. A query behind every
 * option would re-observe the same cached answer once per catalogue entry, and
 * would make the row unrenderable without a query client.
 *
 * <p>Never fired on CE: credits are not metered there, so there is no margin and
 * nothing to estimate. The endpoint answers `enabled: false` for the same
 * reason; the edition is checked here too so a self-hosted install makes no
 * request at all.
 *
 * <p><b>The own-key block is not filtered here.</b> Whether the caller may run on
 * their own key is a PLAN answer, and this hook held a client-side copy of it
 * until 2026-09-18. That copy reported UNLOCKED while the plan map was loading and
 * again when the request for it failed, which is the right default for drawing a
 * padlock and the wrong one for quoting a price: a FREE account would have been
 * shown the flat fee for a route the run pins to the platform key. The server now
 * applies the gate against the same table the run reads, so the block arriving IS
 * the answer, and there is no second opinion to diverge from it.
 */
export function useModelCostBasis(options: { enabled?: boolean } = {}): {
  basis: ModelCostBasis | null;
  isLoading: boolean;
} {
  const { isAuthenticated, isReady } = useAuthGuard();
  const enabled = options.enabled ?? true;

  const { data, isLoading } = useQuery({
    queryKey: queryKeys.billing.estimateBasis(),
    queryFn: () => unifiedApiService.getModelCostBasis(),
    enabled: !!(enabled && isAuthenticated && isReady && !IS_CE),
    // Multiplier and profiles change on a deploy, never within a session.
    staleTime: 30 * 60 * 1000,
    refetchOnWindowFocus: false,
    retry: 1,
  });

  return { basis: data ?? null, isLoading };
}
