'use client';

/**
 * One read for every "credits" surface: the wallet balance, the plan's monthly
 * grant, and the gauge numbers derived from the two.
 *
 * Both halves already existed but were combined ad hoc per surface (the quota
 * page derived the allowance inline, the sidebar showed a bare number with no
 * allowance at all). Centralising it means the header ring, the sidebar block
 * and the wallet card cannot disagree about what "Total" is - all three read
 * this hook, so a FREE account cannot be told "Total 1,000" by the dial and
 * "no monthly allowance" by the page the dial links to.
 *
 * The hard part is not the arithmetic, it is knowing when there IS no
 * denominator. `allowance: null` is a first-class answer here, and callers must
 * render it as "no gauge", never as a 0% dial.
 */

import { useMemo } from 'react';
import { useSubscription, useCreditBalance } from '@/lib/hooks/smart-hooks-complete';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { IS_CE } from '@/lib/edition';
import { resolveMonthlyAllowance, computeCreditGauge, type CreditGauge } from '@/lib/billing/credit-allowance';

export interface CreditWallet {
  /** Credits left to spend (sub + PAYG). null while loading. */
  balance: number | null;
  /** Renewal-grant bucket. null before the V250 payload lands. */
  subBalance: number | null;
  /** Top-up bucket. null before the V250 payload lands. */
  paygBalance: number | null;
  /** V494 - the monthly AI allowance, a third bucket deliberately outside `balance`. */
  aiBalance: number | null;
  /**
   * V494 - whether this account is on a plan whose monthly credits cannot fund a turn,
   * i.e. the shape of account the AI allowance exists for.
   *
   * <p>Distinct from `aiBalance > 0`, and the distinction is the whole point: a Free
   * account that has spent its pot reads 0, exactly like a paid account that never had
   * one. Hiding both looks identical on screen and means the reader whose chat just
   * stopped working is shown nothing at all about why.
   *
   * <p>It does NOT check that the plan still configures a non-zero allowance: that
   * lives on the plan row, and this hook is mocked by half the app's suites, so
   * reaching for another query here would make every one of them fetch more. The quota
   * page combines the two - see its `hasAiAllowance` prop.
   */
  hasAiAllowance: boolean;
  /**
   * Credits granted per billing cycle, incl. the FREE monthly reset.
   * null means "no denominator is knowable" - the billing payload is missing or
   * errored, or the wallet on screen belongs to another account (see below).
   */
  allowance: number | null;
  /**
   * When the subscription bucket is next zeroed and re-granted, as the ISO string the
   * billing payload carries, or null when no date can be named.
   *
   * <p>Computed server-side (`CreditAttributionService.nextCreditGrantAt`) and NOT derived
   * here from `currentPeriodEnd`, because on a YEARLY subscription those are different
   * dates: the invoice is annual and the credit pack is monthly. Deriving it in the client
   * would be a second opinion about a schedule the backend owns, and it would be wrong for
   * eleven months of every year.
   *
   * <p>null is a real answer - a cancelled or past-due subscription is owed no further
   * grant - and callers must render it as no sentence at all, never as a fallback date.
   * It is also null whenever {@link allowance} is, by construction: see the memo below.
   */
  renewsAt: string | null;
  /**
   * When the current billing period ends, i.e. when the card is next charged.
   *
   * <p>Carried for ONE purpose: a surface that states {@link renewsAt} has to be able to
   * explain why it is not this date. Comparing the two is deliberately how that is decided,
   * rather than reading the subscription's `cadence`: both values come from the SAME row that
   * the backend computed `renewsAt` from, so they cannot disagree, whereas the served
   * `cadence` prefers the attached PRICE row's value and `StripeBillingService.swapPlan`
   * moves a subscription's cadence without moving its price. A billing-cycle switcher would
   * then get the right date and no explanation for it.
   *
   * <p>It is also the more precise test: it asks whether the two dates on screen actually
   * differ, which is the thing being explained, instead of asking a question whose answer
   * only usually implies it.
   */
  periodEndsAt: string | null;
  gauge: CreditGauge;
  /** True while either query is still in flight. */
  isLoading: boolean;
}

export function useCreditWallet(): CreditWallet {
  const {
    subscription,
    isLoading: isSubscriptionLoading,
    error: subscriptionError,
  } = useSubscription();
  const {
    balance,
    subBalance,
    paygBalance,
    aiBalance,
    // The same server-side answer the modals use: true exactly for the accounts whose
    // monthly credits cannot fund a turn, which are the accounts the pot exists for.
    monthlyCreditsAreWorkflowOnly,
    isLoading: isBalanceLoading,
  } = useCreditBalance();

  const planCode = (subscription as any)?.subscription?.planCode ?? null;
  const creditTierIndex = (subscription as any)?.subscription?.creditTierIndex ?? 0;
  const activeOrgPlanCode = (subscription as any)?.activeOrgPlanCode ?? null;
  const nextCreditGrantAt = (subscription as any)?.subscription?.nextCreditGrantAt ?? null;
  const currentPeriodEnd = (subscription as any)?.subscription?.currentPeriodEnd ?? null;
  /**
   * Did we actually READ this account's plan?
   *
   * A transport error is not the only way to fail. `GET /api/billing/me`
   * catches its own lookup failure and answers **HTTP 200** with
   * `{subscription: null, status: "error"}`, so react-query reports no error at
   * all and the envelope is truthy. Testing only those two things let a failed
   * read fall through to `resolveMonthlyAllowance(null, 0)` = the FREE grant,
   * and a PRO account holding 40,000 credits was then gauged against 1,000 and
   * told, in gold, "+3,900% over your plan" - precisely the false claim the
   * guard below exists to prevent.
   *
   * So the inner subscription object has to be PRESENT. That also covers
   * `status: "no_subscription"`: an account with no row has no grant to gauge,
   * and no ring is the honest rendering of that.
   */
  const billingPayload = subscription as { status?: string; subscription?: unknown } | null;
  const hasBillingPayload =
    !!billingPayload &&
    !subscriptionError &&
    billingPayload.status !== 'error' &&
    !!billingPayload.subscription;
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  // Both halves come from ONE selector on purpose. `useIsCurrentOrgOwner()`
  // exists and says `currentOrgRole === 'OWNER'`, but this hook needs
  // `currentOrgId` from the same store anyway (personal context has no
  // workspace and the viewer is then necessarily the payer), so reading one
  // field through a named hook and the other through the store would be two
  // entry points to the same state for one trivial comparison.
  const currentOrgRole = useCurrentOrgStore((s) => s.currentOrgRole);

  /**
   * The grant and its date, resolved together.
   *
   * <p>They are ONE memo, not two, because the card states them in one sentence
   * ("+10,000 credits on 14 Oct") and every reason to withhold one is a reason to
   * withhold the other: an unreadable plan, CE, or a wallet that belongs to somebody
   * else. Two independent guards would eventually diverge and put a date we are sure of
   * next to an amount we are not, which is the shape of claim this hook exists to refuse.
   */
  const { allowance, renewsAt, periodEndsAt } = useMemo<{
    allowance: number | null;
    renewsAt: string | null;
    periodEndsAt: string | null;
  }>(() => {
    const NOTHING_KNOWN = { allowance: null, renewsAt: null, periodEndsAt: null };

    // No billing payload (still loading, disabled, or the request failed) means
    // no plan is known. Falling through would resolve a null plan code to the
    // FREE grant and gauge a PRO wallet against 1,000 credits, which is a claim
    // about an account we could not read.
    if (!hasBillingPayload) return NOTHING_KNOWN;

    // CE bills in dollars against no monthly grant, so there is no cycle
    // allowance to gauge against - whatever plan code the CE billing stub
    // reports (it answers planCode "FREE", which would otherwise resolve to a
    // fabricated 1,000-credit grant).
    //
    // DEFENCE IN DEPTH, not load-bearing: all three consumers are already
    // unreachable in CE - the header dial and the sidebar block self-gate on
    // IS_CE, and the quota page branches to CeQuotaPage before QuotaPageInner
    // (which is what calls this hook) ever mounts. An earlier version of this
    // comment claimed the quota page rendered this hook in both editions; that
    // was simply false, and it was the stated reason for the gate. It stays
    // because a fourth consumer must not have to rediscover the rule, but no
    // user-visible behaviour depends on it today.
    // IS_CE, deliberately, and NOT IS_MANAGED_CLOUD - which edition.ts says to
    // prefer for anything mirroring the backend's isManagedCloud(). The whole
    // billing DISPLAY layer is keyed on IS_CE (formatCostCompact switches
    // credits/dollars on it, BalanceBreakdown reads it), and those components
    // render on the same screens as these. Splitting one surface onto the other
    // constant would make a self-hosted-enterprise install show a credit ring
    // beside dollar amounts. If IS_CE is the wrong axis for billing display, it
    // is wrong uniformly and belongs fixed as a class, not one gate at a time.
    if (IS_CE) return NOTHING_KNOWN;

    // Owner-pays (ADR-009): /credits/balance returns the PAYER's wallet, which
    // inside someone else's workspace is the OWNER's, not ours. Our own tier is
    // then the wrong denominator and the payload carries no owner tier, so a
    // FREE guest in a TEAM workspace would be gauged 250,000 credits against a
    // 1,000 grant and told, in gold, "+24,900% over your plan".
    //
    // ROLE is the precise test, not the plan code. Comparing `activeOrgPlanCode`
    // to our own only proves the two plans share a NAME: two colleagues both on
    // PRO with different credit packs (tier 0 = 5,000 vs tier 4 = 100,000) match
    // on code while their grants differ 20x, which reproduces the exact bug on a
    // completely ordinary team. Only the OWNER of the active workspace is
    // guaranteed to be the payer whose tier we hold.
    //
    // Both conditions, deliberately: the role can hydrate late or wrong, and the
    // whole design here prefers showing no gauge to showing a false one.
    const weArePayer = !currentOrgId || currentOrgRole === 'OWNER';
    const workspaceIsOnOurPlan =
      !activeOrgPlanCode || activeOrgPlanCode === (planCode ?? 'FREE');
    if (!weArePayer || !workspaceIsOnOurPlan) return NOTHING_KNOWN;

    return {
      allowance: resolveMonthlyAllowance(planCode, creditTierIndex),
      // Whatever the backend answered, including null for a cancelled or past-due row.
      // Anything else here would be this client inventing a renewal date.
      renewsAt: typeof nextCreditGrantAt === 'string' ? nextCreditGrantAt : null,
      periodEndsAt: typeof currentPeriodEnd === 'string' ? currentPeriodEnd : null,
    };
  }, [
    hasBillingPayload,
    planCode,
    creditTierIndex,
    activeOrgPlanCode,
    currentOrgId,
    currentOrgRole,
    nextCreditGrantAt,
    currentPeriodEnd,
  ]);

  const gauge = useMemo(() => computeCreditGauge(balance, allowance), [balance, allowance]);

  return {
    balance,
    subBalance,
    paygBalance,
    aiBalance,
    hasAiAllowance: monthlyCreditsAreWorkflowOnly === true,
    allowance,
    renewsAt,
    periodEndsAt,
    gauge,
    isLoading: isBalanceLoading || isSubscriptionLoading,
  };
}
