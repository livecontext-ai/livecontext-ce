'use client';

/**
 * Is a plan or credit-tier change already scheduled for the end of this billing period?
 *
 * <p>One read for every surface that must not describe the CURRENT subscription as though it
 * were the one that will be in force on a future date. There are two such surfaces and they
 * had drifted apart the moment the second one appeared: the Billing page suppressed its "next
 * billing" line under a scheduled change, and the wallet card on Quota & Usage went on
 * promising "+100,000 credits on 14 Oct" to somebody whose scheduled downgrade means 10,000
 * will land that day. The amount is wrong, it is about money, and nothing on screen said so.
 *
 * <p><b>Why it is a client read at all.</b> A scheduled change has no local row: it lives in
 * Stripe and `StripeScheduleService.getScheduledChange` resolves it with a live API call. It
 * therefore cannot be folded into `/api/billing/me`, which every page load hits with
 * `no-store`, without putting a Stripe round-trip on the app's hot path. Keeping it a separate,
 * cached query is the reason the two settings pages can ask and the header dial never does.
 *
 * <p><b>Fails OPEN</b>, matching what the Billing page already did with this same query: a read
 * that errors or is still in flight reports no scheduled change, so a correct line stays on
 * screen for everyone rather than being blanked by an unrelated outage. The failure this
 * protects against is rare and self-correcting (the date arrives and the real amount lands);
 * hiding the answer for every user whenever Stripe is slow is neither.
 *
 * <p>The query key is shared with the Billing page's own use, so react-query serves both from
 * one cache entry and two open settings tabs cost one request.
 */

import { useQuery } from '@tanstack/react-query';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import type { ScheduledChangeResponse } from '@/lib/api/services/billing-api.service';

export interface ScheduledPlanChange {
  /**
   * True only when the read SUCCEEDED and reported a pending change. Loading and errors are
   * false, deliberately: see the fail-open note above.
   */
  hasScheduledChange: boolean;
  /** The change itself (effective date, target plan/tier), when there is one. */
  scheduledChange: ScheduledChangeResponse['scheduledChange'];
  isLoading: boolean;
}

export function useScheduledPlanChange(enabled = true): ScheduledPlanChange {
  const query = useQuery<ScheduledChangeResponse>({
    queryKey: ['billing', 'scheduledChange'],
    queryFn: () => unifiedApiService.getScheduledChange(),
    enabled,
    staleTime: 60_000,
  });

  return {
    hasScheduledChange: query.data?.hasScheduledChange === true,
    scheduledChange: query.data?.scheduledChange,
    isLoading: query.isLoading,
  };
}
