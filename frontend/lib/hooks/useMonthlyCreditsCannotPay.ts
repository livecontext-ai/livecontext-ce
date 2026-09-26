'use client';

import { useCallback } from 'react';

import { useCreditBalance } from '@/lib/hooks/smart-hooks-complete';
import { IS_CE } from '@/lib/edition';

/**
 * Whether this account's credits can pay for what it is about to run.
 *
 * <p><b>The rule, once.</b> On the Free plan the monthly credits are ONE pool, but a
 * scoped one: the server funds workflow orchestration from it, and chat or agent turns
 * too, but ONLY on the models a cloud admin opened to the free tier. Everything else (a
 * turn on any other model, a generation on the platform key, a web search) must come out
 * of the separate pay-as-you-go bucket. So a Free account can hold a healthy balance and
 * still be refused the moment it picks a frontier model, which reads as a bug rather than
 * as a plan boundary. Every surface that offers such a choice asks this question, so it
 * is answered in one place.
 *
 * <p><b>Read from the server, never inferred.</b> `monthlyCreditsAreWorkflowOnly` is the
 * server's own answer, derived from the plan code, and it means "this plan's monthly
 * credits are scoped" (workflows plus free-tier models). Deriving it here from
 * `subBalance > 0 && paygBalance === 0` looks equivalent and is not: it is true of a
 * paying subscriber who simply has not topped up, and would put an upgrade badge in
 * front of someone who already pays.
 *
 * <p><b>Blocked means blocked now.</b> The scoping alone is not enough: a Free account
 * with pay-as-you-go credits in hand can pay. The badge appears only when the bucket
 * that must pay is empty.
 *
 * <p><b>"The bucket that must pay" depends on the model.</b> A free-tier model is paid
 * from the whole pool (monthly credits plus top-up), any other model from the top-up
 * alone. Surfaces that pick a model therefore ask {@code blockedForModel}; {@code blocked}
 * remains correct for the spends the monthly credits never touch.
 *
 * <p>Never true on CE, where credits are unlimited and the rule does not exist. The
 * field is absent there, which reads as `false`, but the edition is checked anyway so a
 * future wire change cannot switch a paywall on in a self-hosted install.
 */
export interface MonthlyCreditsVerdict {
  /**
   * True when a paid plan or a top-up is required before this can run.
   *
   * <p>Account-level: the right answer for the spends the monthly credits never cover
   * on the Free plan (a generation on the platform key, a web search, a turn on a model
   * outside the free tier). For anything that picks a MODEL, use {@link blockedForModel}.
   */
  blocked: boolean;
  /**
   * Same question, asked about one model.
   *
   * <p>A Free account with an empty pay-as-you-go bucket is blocked on most models and
   * not blocked at all on the free-tier ones while its monthly credits last, so marking
   * every row with an upgrade badge would tell it to pay for something it already has.
   *
   * <p>A model with no `freeTierEnabled` flag (an older catalogue payload, CE) reads as
   * not free-tier, so the verdict degrades to the account-level answer rather than
   * promising credits that may not apply.
   */
  blockedForModel: (model: { freeTierEnabled?: boolean } | null | undefined) => boolean;
  /**
   * The opposite question: is this model covered by this reader's Free credits RIGHT NOW?
   *
   * <p>True only when all three hold: the plan's monthly credits are scoped (the Free
   * plan), the MONTHLY credits still hold at least one credit (a top-up does not count:
   * a turn it pays for is not free), and the model is one a cloud admin opened to the
   * free tier.
   *
   * <p><b>Not the same as `prefersFreeTierModels && model.freeTierEnabled`</b>, which
   * omits the balance and would mark a covered model "free" for an account that has spent
   * its month, the state where {@link blockedForModel} is simultaneously true. This one
   * reads the monthly credits, which can only be smaller than the pool the other reads,
   * which makes them mutually exclusive by construction:
   * {@code freeTierForModel(m)} implies {@code !blockedForModel(m)}.
   *
   * <p>False while the balance is in flight: a promise of a free turn must not be made
   * before what pays for it is known.
   */
  freeTierForModel: (model: { freeTierEnabled?: boolean } | null | undefined) => boolean;
  /**
   * True when this account should be OFFERED the free-tier models first.
   *
   * <p>Deliberately not the same question as {@link blocked}: a Free account holding
   * pay-as-you-go credits is not blocked, and should still be shown the models its
   * monthly credits cover ahead of the ones that eat its top-up. So this tracks the
   * plan, not the balance.
   */
  prefersFreeTierModels: boolean;
  /**
   * False until the balance request has answered.
   *
   * <p>{@link prefersFreeTierModels} starts FALSE while that request is in flight, which
   * is indistinguishable from "this is a paid account". A caller that WRITES a default
   * model off this verdict must wait, or it persists the catalogue default for a free
   * account whose balance simply had not landed yet. Callers that only order or badge a
   * list can ignore this: they re-render when the answer arrives.
   */
  verdictReady: boolean;
}

/** The server refuses an LLM turn below one credit (CreditService.hasSufficientCredits). */
const MIN_TURN_CREDITS = 1;

export function useMonthlyCreditsCannotPay(): MonthlyCreditsVerdict {
  const { subBalance, paygBalance, monthlyCreditsAreWorkflowOnly, hasAnswered, isLoading } =
    useCreditBalance();

  const blocked = !IS_CE
    && monthlyCreditsAreWorkflowOnly
    // `null` is "not answered yet", which is not "empty": treating it as empty
    // would flash a paywall on every page load before the balance lands.
    && paygBalance != null
    && paygBalance <= 0;

  // What a free-tier turn may draw: the whole pool, monthly credits plus top-up. Same
  // "null is not empty" rule as above: an unanswered balance must not lift the badge
  // off a row the account genuinely cannot pay for, nor promise a free turn early.
  // The threshold is the server's own: a turn needs at least one credit
  // (hasSufficientCredits), so 0.4 left is "cannot pay", not "free".
  const poolCanPay = subBalance != null && subBalance + (paygBalance ?? 0) >= MIN_TURN_CREDITS;
  // What the "Free" promise is about: the MONTHLY credits alone. Once the month is spent
  // a free-tier turn is still payable from a top-up, but it is then paid money, and a
  // chip saying "your monthly credits pay for this" would be false. A negative top-up
  // (debt) is netted in, since the server nets it against the same total.
  const monthlyCanPay = subBalance != null
    && subBalance + Math.min(paygBalance ?? 0, 0) >= MIN_TURN_CREDITS;

  const prefersFreeTierModels = !IS_CE && monthlyCreditsAreWorkflowOnly;

  // Stable across renders while the two inputs hold: ModelSelectorDropdown memoises
  // its notice verdict on this reference.
  const blockedForModel = useCallback(
    (model: { freeTierEnabled?: boolean } | null | undefined) =>
      blocked && !(poolCanPay && model?.freeTierEnabled === true),
    [blocked, poolCanPay],
  );

  // Lives HERE rather than in the surfaces so it cannot drift from `blockedForModel`:
  // `monthlyCanPay` implies `poolCanPay` (the top-up is only ever added when positive),
  // so a model marked free is never also marked blocked.
  const freeTierForModel = useCallback(
    (model: { freeTierEnabled?: boolean } | null | undefined) =>
      prefersFreeTierModels && monthlyCanPay && model?.freeTierEnabled === true,
    [prefersFreeTierModels, monthlyCanPay],
  );

  return {
    blocked,
    blockedForModel,
    freeTierForModel,
    prefersFreeTierModels,
    // CE never asks, so it is answered from the start. On cloud it takes the PAYLOAD,
    // not merely "not loading": react-query reports isLoading=false for a disabled query
    // (no session yet), and the model catalogue fetches on mount against a public
    // endpoint, so a panel can have models and no verdict at the same moment.
    verdictReady: IS_CE || (!isLoading && hasAnswered === true),
  };
}
