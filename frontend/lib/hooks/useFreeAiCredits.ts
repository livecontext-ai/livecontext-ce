'use client';

import { useMemo } from 'react';
import { usePlans } from '@/lib/hooks/smart-hooks-complete';
import type { BillingPlan } from '@/lib/api/services/billing-api.service';
import { FREE_AI_CREDITS } from '@/lib/billing/pricing-constants';

/**
 * The Free plan's monthly AI allowance, as the reader should see it quoted (V494).
 *
 * <p>The allowance is admin-configurable (`auth.plan.included_ai_credits`), so the
 * only honest source is the live plan row. {@link FREE_AI_CREDITS} is the seeded
 * value and stands in for it in the two cases where no row is available: the public
 * landing, where `usePlans` is gated on authentication and fetches nothing, and the
 * moment before the request settles.
 *
 * <p>It lives in one place because two surfaces quote it side by side: the plan card
 * on the pricing page and the comparison table opened from that same page. Resolving
 * it twice is how the card ends up saying 250 while the table still says 100.
 */
export interface FreeAiCreditsAnswer {
  /** The figure to quote. */
  credits: number;
  /**
   * True when {@link credits} came from the live FREE plan row, false while it is the
   * seeded stand-in.
   *
   * <p>Most surfaces can ignore this: quoting the seeded figure for a moment on a page
   * that is mostly about something else is a fair trade for rendering at once. A surface
   * whose WHOLE JOB is to state this number cannot, because `plans` reads `[]` both in
   * flight and after the request has exhausted its retries, so the stand-in is
   * indistinguishable from an answer and would simply stay on screen. Such a surface
   * waits on this instead - under a bound of its own, since it may never turn true.
   */
  resolved: boolean;
}

export function resolveFreeAiCredits(plans: BillingPlan[] | undefined): number {
  return resolveFreeAiCreditsAnswer(plans).credits;
}

export function resolveFreeAiCreditsAnswer(plans: BillingPlan[] | undefined): FreeAiCreditsAnswer {
  const free = plans?.find((plan) => plan?.code === 'FREE');
  if (!free) {
    // No row to read: the public landing (where the plans query is gated on
    // authentication) or a request still in flight. The seeded figure is the honest
    // stand-in for both.
    return { credits: FREE_AI_CREDITS, resolved: false };
  }
  // The row IS here and says nothing: the plan grants no allowance. Falling back to the
  // seeded 100 there would advertise credits the renewal will never hand out - NULL and
  // 0 are the two ways an admin closes the free tier, and they must read the same.
  return {
    credits: typeof free.includedAiCredits === 'number' ? free.includedAiCredits : 0,
    resolved: true,
  };
}

export function useFreeAiCreditsAnswer(): FreeAiCreditsAnswer {
  const { plans } = usePlans();
  return useMemo(() => resolveFreeAiCreditsAnswer(plans as BillingPlan[] | undefined), [plans]);
}

export function useFreeAiCredits(): number {
  return useFreeAiCreditsAnswer().credits;
}

export default useFreeAiCredits;
