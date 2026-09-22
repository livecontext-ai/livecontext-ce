'use client';

import { useCallback } from 'react';

import { useCreditBalance } from '@/lib/hooks/smart-hooks-complete';
import { IS_CE } from '@/lib/edition';

/**
 * Whether this account's credits cannot pay for anything but a workflow node.
 *
 * <p><b>The rule, once.</b> On the Free plan the monthly grant is scoped to
 * workflow orchestration: the server funds only the `WORKFLOW_NODE` source
 * types from it, and everything else (a chat turn, an agent's tokens, a
 * generation on the platform key, a web search) must come out of the separate
 * pay-as-you-go bucket. So a Free account can hold a healthy balance and still
 * be refused the moment it picks a model, which reads as a bug rather than as a
 * plan boundary. Every surface that offers such a choice asks this question, so
 * it is answered in one place.
 *
 * <p><b>Read from the server, never inferred.</b> `monthlyCreditsAreWorkflowOnly`
 * is the server's own answer, derived from the plan code. Deriving it here from
 * `subBalance > 0 && paygBalance === 0` looks equivalent and is not: it is true
 * of a paying subscriber who simply has not topped up, and would put an upgrade
 * badge in front of someone who already pays.
 *
 * <p><b>Blocked means blocked now.</b> The scoping alone is not enough: a Free
 * account with pay-as-you-go credits in hand can pay. The badge appears only
 * when the bucket that must pay is empty.
 *
 * <p><b>And since V494, "the bucket that must pay" depends on the model.</b> The
 * Free plan carries a separate monthly AI allowance that funds chat and agent
 * turns on the models opened to the free tier. So the account-level answer is no
 * longer the whole truth for any surface that picks a model: those surfaces ask
 * {@code blockedForModel}, which lets the allowance answer for the models it
 * actually covers. {@code blocked} remains correct for the spends the allowance
 * never touches.
 *
 * <p>Never true on CE, where credits are unlimited and the rule does not exist.
 * The field is absent there, which reads as `false`, but the edition is checked
 * anyway so a future wire change cannot switch a paywall on in a self-hosted
 * install.
 */
export interface MonthlyCreditsVerdict {
  /**
   * True when a paid plan or a top-up is required before this can run.
   *
   * <p>Account-level, and it stays that way: it is the right answer for the
   * spends the AI allowance never covers (a generation on the platform key, a
   * web search, a workflow node). For anything that picks a MODEL, use
   * {@link blockedForModel} instead - see below.
   */
  blocked: boolean;
  /**
   * Same question, asked about one model (V494).
   *
   * <p>The account-level answer stopped being sufficient the day the Free plan
   * gained a separate AI allowance: that pot pays for chat and agent turns, but
   * ONLY on the models a cloud admin opened to the free tier. So a Free account
   * with an empty pay-as-you-go bucket is blocked on most models and not blocked
   * at all on those - and marking every row with an upgrade badge would tell it
   * to pay for something it already has.
   *
   * <p>A model with no `freeTierEnabled` flag (an older catalogue payload, CE)
   * reads as not free-tier, so the verdict degrades to the account-level answer
   * rather than promising an allowance that may not apply.
   */
  blockedForModel: (model: { freeTierEnabled?: boolean } | null | undefined) => boolean;
  /**
   * The opposite question, and the reason it is asked here: is this model free
   * for this reader RIGHT NOW?
   *
   * <p>True only when all three hold: the plan's monthly credits are
   * workflow-scoped (so there is an AI allowance at all), the allowance still has
   * something in it, and the model is one a cloud admin opened to the free tier.
   *
   * <p><b>Not the same as `prefersFreeTierModels && model.freeTierEnabled`</b>,
   * which is what a surface would reach for. That omits the balance, so it marks a
   * covered model "free" for an account that has spent its monthly pot - the state
   * every active free account reaches each month, and the one where
   * {@link blockedForModel} is simultaneously true. The two would then contradict
   * each other on the same row. Both read one `allowanceCanPay`, which is what
   * makes them mutually exclusive by construction:
   * {@code freeTierForModel(m)} implies {@code !blockedForModel(m)}.
   *
   * <p>False while the balance is in flight, for the same reason the lock is: a
   * promise of a free turn must not be made before what pays for it is known.
   *
   * <p><b>Note what this means for the surfaces that GREY the other models.</b>
   * They grey on {@link blockedForModel}, so a Free account that has topped up
   * sees no greying at all: with pay-as-you-go credits in hand it can run every
   * model, and dimming them would be telling it otherwise. The chip and the
   * greying therefore answer two different questions - "this one is free" and
   * "this one you cannot pay for right now" - and only the first tracks the free
   * tier itself.
   */
  freeTierForModel: (model: { freeTierEnabled?: boolean } | null | undefined) => boolean;
  /**
   * True when this account should be OFFERED the free-tier models first (V494).
   *
   * <p>Deliberately not the same question as {@link blocked}: a Free account
   * holding pay-as-you-go credits is not blocked, and should still be shown the
   * models its monthly allowance covers ahead of the ones that eat its top-up.
   * So this tracks the plan, not the balance.
   */
  prefersFreeTierModels: boolean;
  /**
   * False until the balance request has answered (V494).
   *
   * <p>{@link prefersFreeTierModels} starts FALSE while that request is in flight,
   * which is indistinguishable from "this is a paid account". A caller that WRITES a
   * default model off this verdict must wait, or it persists the catalogue default for
   * a free account whose balance simply had not landed yet - and a persisted choice is
   * never revisited. Callers that only order or badge a list can ignore this: they
   * re-render for free when the answer arrives.
   */
  verdictReady: boolean;
}

export function useMonthlyCreditsCannotPay(): MonthlyCreditsVerdict {
  const { paygBalance, aiBalance, monthlyCreditsAreWorkflowOnly, hasAnswered, isLoading } =
    useCreditBalance();

  const blocked = !IS_CE
    && monthlyCreditsAreWorkflowOnly
    // `null` is "not answered yet", which is not "empty": treating it as empty
    // would flash a paywall on every page load before the balance lands.
    && paygBalance != null
    && paygBalance <= 0;

  // Same "null is not empty" rule as above: an unanswered allowance must not
  // lift the badge off a row the account genuinely cannot pay for, nor promise a
  // free turn before the balance that would pay for it has landed.
  const allowanceCanPay = aiBalance != null && aiBalance > 0;

  const prefersFreeTierModels = !IS_CE && monthlyCreditsAreWorkflowOnly;

  // Stable across renders while the two inputs hold: ModelSelectorDropdown memoises
  // its notice verdict on this reference, and a fresh closure each render made that
  // memo decoration rather than memoisation.
  const blockedForModel = useCallback(
    (model: { freeTierEnabled?: boolean } | null | undefined) =>
      blocked && !(allowanceCanPay && model?.freeTierEnabled === true),
    [blocked, allowanceCanPay],
  );

  // The positive answer, and it lives HERE rather than in the surfaces so it cannot
  // drift from `blockedForModel` above: both read the same `allowanceCanPay`, which is
  // what makes them mutually exclusive rather than merely usually different. A surface
  // resolving this as "on the free plan AND the model is open to it" would mark a
  // covered model free while the pot for it is empty, which is the state every active
  // free account reaches every month, and it would say so directly beside the lock.
  const freeTierForModel = useCallback(
    (model: { freeTierEnabled?: boolean } | null | undefined) =>
      prefersFreeTierModels && allowanceCanPay && model?.freeTierEnabled === true,
    [prefersFreeTierModels, allowanceCanPay],
  );

  return {
    blocked,
    blockedForModel,
    freeTierForModel,
    prefersFreeTierModels,
    // CE never asks, so it is answered from the start. On cloud it takes the PAYLOAD,
    // not merely "not loading": react-query reports isLoading=false for a disabled query
    // (no session yet), and the model catalogue does NOT wait for the same session - it
    // fetches on mount against a public endpoint - so a panel can have models and no
    // verdict at the same moment. That is precisely the window in which a wrong default
    // gets persisted for the fresh signup this feature exists to serve.
    verdictReady: IS_CE || (!isLoading && hasAnswered === true),
  };
}
