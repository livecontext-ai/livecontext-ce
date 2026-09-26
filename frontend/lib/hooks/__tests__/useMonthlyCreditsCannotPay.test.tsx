// @vitest-environment jsdom
/**
 * The rule behind every upgrade badge in the app, tested where it is stated.
 *
 * <p>It decides whether a Free account's credits can pay for what a model does.
 * Getting it wrong in either direction is expensive: too eager and it puts a
 * paywall in front of a paying customer, too shy and the reader picks a model
 * that is about to be refused.
 */
import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const balance = vi.hoisted(() => ({
  paygBalance: null as number | null,
  subBalance: null as number | null,
  monthlyCreditsAreWorkflowOnly: false,
  hasAnswered: true,
  isLoading: false,
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useCreditBalance: () => balance,
}));

const edition = vi.hoisted(() => ({ isCe: false }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return edition.isCe; },
}));

import { useMonthlyCreditsCannotPay } from '../useMonthlyCreditsCannotPay';

function verdict() {
  return renderHook(() => useMonthlyCreditsCannotPay()).result.current.blocked;
}

beforeEach(() => {
  balance.paygBalance = null;
  balance.subBalance = null;
  balance.monthlyCreditsAreWorkflowOnly = false;
  balance.hasAnswered = true;
  balance.isLoading = false;
  edition.isCe = false;
});
afterEach(() => vi.clearAllMocks());

describe('useMonthlyCreditsCannotPay', () => {
  it('blocks a Free account whose top-up bucket is empty', () => {
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;

    expect(verdict()).toBe(true);
  });

  it('blocks one whose top-up bucket has gone negative', () => {
    // Post-flight LLM debits are allowed to overdraw, so a Free account can
    // land below zero and stay there. Below zero is still "cannot pay".
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = -12;

    expect(verdict()).toBe(true);
  });

  it('clears a Free account that has topped up', () => {
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 250;

    expect(verdict()).toBe(false);
  });

  it('regression: clears a PAYING account with no top-up, which is the ordinary case', () => {
    // A monthly balance and an empty top-up bucket is exactly what a PRO or
    // TEAM subscriber looks like. Inferring the rule from the two numbers
    // instead of reading the server's flag badged every one of them.
    balance.monthlyCreditsAreWorkflowOnly = false;
    balance.paygBalance = 0;

    expect(verdict()).toBe(false);
  });

  it('regression: says nothing while the balance is still on its way', () => {
    // `null` is "not answered yet", not "empty". Read as empty, it flashed a
    // paywall on every page load before the balance landed.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = null;

    expect(verdict()).toBe(false);
  });

  it('regression: never blocks on a self-hosted install', () => {
    // CE has unlimited credits and no plans. Guarded on the edition as well as
    // the wire field, so a future change to the balance payload cannot switch a
    // paywall on in someone else's install.
    edition.isCe = true;
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;

    expect(verdict()).toBe(false);
  });
});

/**
 * The verdict is not one answer per account.
 *
 * <p>The Free plan monthly credits are one pool that pays for workflows AND for
 * chat and agent turns, but only on the models a cloud admin opened to the free
 * tier. Left account-level, the rule would have put an upgrade badge on EVERY
 * model of an account that can already pay for some of them, which is the "too
 * eager" failure the file header warns about.
 */
describe('useMonthlyCreditsCannotPay - per model', () => {
  function verdictFor(model: { freeTierEnabled?: boolean } | null) {
    return renderHook(() => useMonthlyCreditsCannotPay()).result.current.blockedForModel(model);
  }

  /** The ordinary Free account: monthly credits, no top-up. */
  function freeAccountWithMonthly(monthly: number | null) {
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = monthly;
  }

  it('does NOT block a free-tier model while the monthly credits last', () => {
    freeAccountWithMonthly(100);

    expect(verdictFor({ freeTierEnabled: true })).toBe(false);
  });

  it('still blocks a model outside the free tier, on the same account', () => {
    freeAccountWithMonthly(100);

    expect(verdictFor({ freeTierEnabled: false })).toBe(true);
  });

  it('blocks every model once the monthly credits are spent', () => {
    freeAccountWithMonthly(0);

    expect(verdictFor({ freeTierEnabled: true })).toBe(true);
  });

  it('treats an unanswered monthly balance as empty, so no badge is lifted on a guess', () => {
    // Mirrors the existing "null is not empty" rule for paygBalance, in the
    // opposite direction: there the unknown must not ADD a paywall, here it must
    // not REMOVE one.
    freeAccountWithMonthly(null);

    expect(verdictFor({ freeTierEnabled: true })).toBe(true);
  });

  it('treats a model with no flag as outside the free tier', () => {
    // An older catalogue payload, or CE. Degrading to the account-level answer
    // withholds a benefit rather than promising one that may not apply.
    freeAccountWithMonthly(100);

    expect(verdictFor({})).toBe(true);
    expect(verdictFor(null)).toBe(true);
  });

  it('never blocks a paying account, whatever its balances', () => {
    balance.monthlyCreditsAreWorkflowOnly = false;
    balance.paygBalance = 0;
    balance.subBalance = 0;

    expect(verdictFor({ freeTierEnabled: false })).toBe(false);
  });

  it('offers the free-tier models first on a Free plan, even when it CAN pay', () => {
    // Ordering tracks the plan, not the balance: a Free account holding a top-up
    // is not blocked, and should still meet the models its monthly credits cover
    // before the ones that eat that top-up.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 500;

    const { result } = renderHook(() => useMonthlyCreditsCannotPay());
    expect(result.current.blocked).toBe(false);
    expect(result.current.prefersFreeTierModels).toBe(true);
  });

  it('does not reorder anything for a paid account, or on CE', () => {
    balance.monthlyCreditsAreWorkflowOnly = false;
    expect(renderHook(() => useMonthlyCreditsCannotPay()).result.current.prefersFreeTierModels).toBe(false);

    edition.isCe = true;
    balance.monthlyCreditsAreWorkflowOnly = true;
    expect(renderHook(() => useMonthlyCreditsCannotPay()).result.current.prefersFreeTierModels).toBe(false);
  });
});

describe('one Free pool - monthly credits pay for free-tier chat', () => {
  it('regression: a Free account with monthly credits and NO separate AI pot can chat on a free-tier model', () => {
    // The retired AI pot is gone (aiBalance is always 0). Reading it here made every
    // Free account look unable to chat although its monthly credits now pay for it.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = 1000;
    (balance as Record<string, unknown>).aiBalance = 0;

    const { result } = renderHook(() => useMonthlyCreditsCannotPay());
    expect(result.current.blockedForModel({ freeTierEnabled: true })).toBe(false);
    expect(result.current.freeTierForModel({ freeTierEnabled: true })).toBe(true);
    expect(result.current.blockedForModel({ freeTierEnabled: false })).toBe(true);
  });

  it('regression: once the month is spent a top-up still pays, but the model is no longer marked free', () => {
    // The chip's tooltip says the MONTHLY credits pay for this model. Once they are
    // spent a free-tier turn is paid from the top-up, which is real money: marking it
    // "free" then was a false promise. It must stay usable (not blocked), just unmarked.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 40;
    balance.subBalance = 0;

    const { result } = renderHook(() => useMonthlyCreditsCannotPay());
    expect(result.current.freeTierForModel({ freeTierEnabled: true })).toBe(false);
    expect(result.current.blockedForModel({ freeTierEnabled: true })).toBe(false);
  });

  it('a top-up does not make a monthly debt look free', () => {
    // A negative top-up (post-flight debt) is netted against the monthly credits by the
    // server, so 0.5 monthly with -10 top-up covers nothing.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = -10;
    balance.subBalance = 5;

    const { result } = renderHook(() => useMonthlyCreditsCannotPay());
    expect(result.current.freeTierForModel({ freeTierEnabled: true })).toBe(false);
    expect(result.current.blockedForModel({ freeTierEnabled: true })).toBe(true);
  });

  it('regression: less than one credit left is "cannot pay", matching the server threshold', () => {
    // The server refuses a turn below one credit. Showing the model as free with 0.4
    // left promised a turn the very next request refused.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = 0.4;

    const { result } = renderHook(() => useMonthlyCreditsCannotPay());
    expect(result.current.freeTierForModel({ freeTierEnabled: true })).toBe(false);
    expect(result.current.blockedForModel({ freeTierEnabled: true })).toBe(true);
  });

  it('exactly one credit left still pays', () => {
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = 1;

    const { result } = renderHook(() => useMonthlyCreditsCannotPay());
    expect(result.current.freeTierForModel({ freeTierEnabled: true })).toBe(true);
    expect(result.current.blockedForModel({ freeTierEnabled: true })).toBe(false);
  });
});

describe('verdictReady - when a caller may WRITE a default off this verdict', () => {
  function ready() {
    return renderHook(() => useMonthlyCreditsCannotPay()).result.current.verdictReady;
  }

  it('is false while the balance request is in flight', () => {
    balance.isLoading = true;
    balance.hasAnswered = false;

    expect(ready()).toBe(false);
  });

  it('is false when the query is DISABLED, which reports not-loading with no data', () => {
    // The case that makes "not loading" the wrong signal: before a session exists
    // react-query reports isLoading=false for a disabled query, every field reads as
    // its own default, and that is indistinguishable from a real answer. The model
    // catalogue does not wait for the same session - it fetches on mount against a
    // public endpoint - so a panel really can have models and no verdict at once, and
    // would persist a default the later verdict can never correct.
    balance.isLoading = false;
    balance.hasAnswered = false;

    expect(ready()).toBe(false);
  });

  it('is true once the payload has arrived', () => {
    balance.isLoading = false;
    balance.hasAnswered = true;

    expect(ready()).toBe(true);
  });

  it('is true from the start on CE, which never asks', () => {
    edition.isCe = true;
    balance.isLoading = true;
    balance.hasAnswered = false;

    expect(ready()).toBe(true);
  });
});

describe('freeTierForModel - which model is free RIGHT NOW', () => {
  const covered = { freeTierEnabled: true };
  const uncovered = { freeTierEnabled: false };

  function verdictFor(model: { freeTierEnabled?: boolean } | null | undefined) {
    return renderHook(() => useMonthlyCreditsCannotPay()).result.current.freeTierForModel(model);
  }

  it('marks a covered model while the monthly credits still have something in them', () => {
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = 80;

    expect(verdictFor(covered)).toBe(true);
  });

  it('regression: stops marking it the moment the monthly credits are spent', () => {
    // The state every active free account reaches every month, and the reason this
    // question cannot be answered from the plan and the model's flag alone. Answered
    // that way, the row said "free" beside the lock that said the opposite, with a
    // tooltip promising credits that had nothing left in them.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = 0;

    expect(verdictFor(covered)).toBe(false);
  });

  it('regression: the two markers can never appear on the same row', () => {
    // The property, not an instance of it: whatever the balances, a model the chip
    // marks is a model the lock does not, because the monthly credits the chip reads
    // can never exceed the pool the lock reads.
    balance.monthlyCreditsAreWorkflowOnly = true;
    for (const payg of [null, -5, 0, 0.5, 500]) {
      for (const monthly of [null, 0, 0.5, 1, 80]) {
        balance.paygBalance = payg;
        balance.subBalance = monthly;
        const { result } = renderHook(() => useMonthlyCreditsCannotPay());
        for (const model of [covered, uncovered]) {
          const free = result.current.freeTierForModel(model);
          const blocked = result.current.blockedForModel(model);
          expect(free && blocked, `payg=${payg} monthly=${monthly} free=${free} blocked=${blocked}`).toBe(false);
        }
      }
    }
  });

  it('never marks a model the free tier was not opened to', () => {
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = 80;

    expect(verdictFor(uncovered)).toBe(false);
  });

  it('treats an older payload with no flag as not covered', () => {
    // `freeTierEnabled` is absent from a catalogue row that predates the column, and
    // from CE. Reading absence as covered would promise a free turn on every model.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = 80;

    expect(verdictFor({})).toBe(false);
    expect(verdictFor(undefined)).toBe(false);
    expect(verdictFor(null)).toBe(false);
  });

  it('marks nothing while the monthly balance is still in flight', () => {
    // Same "null is not empty" rule the lock follows, pointing the other way: a
    // promise of a free turn must not be made before what pays for it is known.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = null;

    expect(verdictFor(covered)).toBe(false);
  });

  it('marks nothing for a paid account, or on CE', () => {
    // A subscriber's own credits pay for the model; telling them it is free is false,
    // and CE has no plans at all.
    balance.monthlyCreditsAreWorkflowOnly = false;
    balance.subBalance = 80;
    expect(verdictFor(covered)).toBe(false);

    edition.isCe = true;
    balance.monthlyCreditsAreWorkflowOnly = true;
    expect(verdictFor(covered)).toBe(false);
  });

  it('keeps one stable reference while the balances hold, because callers memoise on it', () => {
    // The surfaces pass this straight into a useMemo dependency list, exactly as they
    // do with blockedForModel; a fresh closure per render would make those memos
    // decoration.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;
    balance.subBalance = 80;

    const { result, rerender } = renderHook(() => useMonthlyCreditsCannotPay());
    const first = result.current.freeTierForModel;
    rerender();

    // Asserted to BE a function first: comparing two absent fields would pass on a
    // hook that does not answer this question at all.
    expect(typeof first).toBe('function');
    expect(result.current.freeTierForModel).toBe(first);
  });
});
