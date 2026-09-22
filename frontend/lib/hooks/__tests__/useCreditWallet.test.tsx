// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook } from '@testing-library/react';

/**
 * The wallet hook, rendered for real. What it has to get right is the
 * DENOMINATOR: the balance it reads is the payer's, and under owner-pays
 * (ADR-009) the payer is not always the person looking at the screen.
 *
 * The two source hooks are stubbed at their own boundary (they are covered by
 * their own tests); everything from those two payloads to the returned
 * allowance and gauge is the thing under test.
 */

const mocks = vi.hoisted(() => ({
  useSubscription: vi.fn(),
  useCreditBalance: vi.fn(),
  org: { currentOrgId: null as string | null, currentOrgRole: null as string | null },
  isCe: { value: false },
}));

vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return mocks.isCe.value;
  },
}));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: mocks.useSubscription,
  useCreditBalance: mocks.useCreditBalance,
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (s: typeof mocks.org) => unknown) => selector(mocks.org),
}));

import { useCreditWallet } from '../useCreditWallet';

/** A resolved balance payload. `balance` is what is LEFT to spend. */
function givenBalance(balance: number | null, extra: Record<string, unknown> = {}) {
  mocks.useCreditBalance.mockReturnValue({
    balance,
    subBalance: null,
    paygBalance: null,
    isLoading: false,
    ...extra,
  });
}

/**
 * A billing payload. `activeOrgPlanCode` is the WORKSPACE's plan.
 *
 * The real `useSubscription` returns `subscription: data || null` - it can never
 * hand back `undefined` - and `error` as a string. Mirroring that exactly matters:
 * a helper that yields `undefined` would let a guard on `=== undefined` look
 * tested while being dead against the real hook.
 */
function givenSubscription(
  payload: Record<string, unknown> | null,
  { isLoading = false, error = null as string | null } = {},
) {
  mocks.useSubscription.mockReturnValue({ subscription: payload, isLoading, error });
}

beforeEach(() => {
  // Personal context by default (no active workspace), which is the solo user.
  mocks.org = { currentOrgId: null, currentOrgRole: null };
  mocks.isCe.value = false;
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('useCreditWallet - allowance', () => {
  it('gauges a FREE account against its 1,000-credit monthly reset', () => {
    givenBalance(980);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBe(1_000);
    expect(result.current.gauge.fillPct).toBe(2);
  });

  it('gauges a paid account against its purchased credit tier', () => {
    givenBalance(9_779);
    givenSubscription({ subscription: { planCode: 'PRO', creditTierIndex: 1 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBe(10_000);
    expect(result.current.gauge.fillPct).toBe(2);
  });

  it('goes gold when a top-up pushes the wallet above the cycle grant', () => {
    givenBalance(1_400);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.gauge.isOver).toBe(true);
    expect(result.current.gauge.overPct).toBe(40);
  });
});

describe('useCreditWallet - owner-pays guest (ADR-009)', () => {
  it("reports NO allowance when the active workspace runs on another account's plan", () => {
    // The guest is FREE; the workspace is TEAM, so /credits/balance returned the
    // OWNER's wallet. Gauging 250,000 owner credits against the guest's own
    // 1,000-credit grant would paint a gold "+24,900% over your plan" - a claim
    // about a plan that is not paying for anything here.
    givenBalance(250_000);
    givenSubscription({
      activeOrgPlanCode: 'TEAM',
      subscription: { planCode: 'FREE', creditTierIndex: 0 },
    });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
    expect(result.current.gauge.isOver).toBe(false);
    expect(result.current.gauge.fillPct).toBe(0);
    // The balance itself is still true and still shown.
    expect(result.current.balance).toBe(250_000);
  });

  it('still gauges when the workspace runs on the SAME plan the tier came from', () => {
    givenBalance(5_000);
    givenSubscription({
      activeOrgPlanCode: 'PRO',
      subscription: { planCode: 'PRO', creditTierIndex: 1 },
    });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBe(10_000);
  });

  it('treats a FREE workspace and an unnamed personal plan as the same plan', () => {
    // A row that carries no planCode reads as FREE, so a FREE workspace is not
    // a foreign one and the gauge still runs.
    //
    // This used to be written with NO subscription row at all, on the premise
    // that "the payload omits the row for an account that never subscribed".
    // That premise is false: FreeSubscriptionProvisioner gives every user a FREE
    // row, so the row is always there - `subscription: null` means the read
    // FAILED (status "error", still HTTP 200) or there is genuinely no
    // subscription, and inventing a 1,000 grant for either is the bug the
    // failed-read guard now prevents.
    givenBalance(400);
    givenSubscription({ subscription: { creditTierIndex: 0 }, activeOrgPlanCode: 'FREE' });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBe(1_000);
    expect(result.current.gauge.fillPct).toBe(60);
  });

  it('gauges normally when the payload carries no workspace plan at all', () => {
    givenBalance(400);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBe(1_000);
  });
});

describe('useCreditWallet - no denominator is knowable', () => {
  it('reports NO allowance when the billing request failed', () => {
    // Falling through would resolve a null plan code to the FREE grant and gauge
    // a PRO wallet against 1,000 credits - a confident claim about an account we
    // could not read. The balance itself came from a different query and stands.
    givenBalance(42_000);
    givenSubscription(null, { error: 'Network error' });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
    expect(result.current.gauge.isOver).toBe(false);
    expect(result.current.balance).toBe(42_000);
    // Crucially NOT stuck loading: the surfaces must still show the number.
    expect(result.current.isLoading).toBe(false);
  });

  it('reports NO allowance while the billing payload is still null', () => {
    givenBalance(500);
    givenSubscription(null, { isLoading: true });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
  });

  it('reports NO allowance for a paid plan whose tier index is off the scale', () => {
    givenBalance(500);
    givenSubscription({ subscription: { planCode: 'PRO', creditTierIndex: 99 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
    expect(result.current.gauge.fillPct).toBe(0);
  });
});

describe('useCreditWallet - loading', () => {
  it('is loading while the balance query is in flight', () => {
    givenBalance(null, { isLoading: true });
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.isLoading).toBe(true);
  });

  it('is loading while the billing query is in flight', () => {
    givenBalance(500);
    givenSubscription(null, { isLoading: true });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.isLoading).toBe(true);
  });

  it('is resolved once both payloads have landed', () => {
    givenBalance(500);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.isLoading).toBe(false);
  });
});

describe('useCreditWallet - the payer test is the ROLE, not the plan name', () => {
  it('reports NO allowance for a member of a workspace on the SAME plan code', () => {
    // The hole the plan-code comparison alone could not see: two colleagues both
    // on PRO with different credit packs (tier 0 = 5,000 vs tier 4 = 100,000)
    // match on code while their grants differ 20x. Gauging the owner's 100,000
    // against our own 5,000 prints a gold "+1,900% over your plan" on a
    // completely ordinary team.
    givenBalance(100_000);
    givenSubscription({
      activeOrgPlanCode: 'PRO',
      subscription: { planCode: 'PRO', creditTierIndex: 0 },
    });
    mocks.org = { currentOrgId: 'org-1', currentOrgRole: 'MEMBER' };

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
    expect(result.current.gauge.isOver).toBe(false);
  });

  it('reports NO allowance for an ADMIN either - the OWNER is the payer', () => {
    givenBalance(100_000);
    givenSubscription({ activeOrgPlanCode: 'PRO', subscription: { planCode: 'PRO', creditTierIndex: 0 } });
    mocks.org = { currentOrgId: 'org-1', currentOrgRole: 'ADMIN' };

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
  });

  it('gauges for the OWNER of the active workspace, whose tier we do hold', () => {
    givenBalance(9_779);
    givenSubscription({ activeOrgPlanCode: 'PRO', subscription: { planCode: 'PRO', creditTierIndex: 1 } });
    mocks.org = { currentOrgId: 'org-1', currentOrgRole: 'OWNER' };

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBe(10_000);
    expect(result.current.gauge.fillPct).toBe(2);
  });

  it('gauges in personal context, where there is no workspace to belong to', () => {
    givenBalance(500);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });
    mocks.org = { currentOrgId: null, currentOrgRole: null };

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBe(1_000);
  });

  it('withholds the gauge while the role has not hydrated, rather than guessing', () => {
    // A late/failed role read must not be allowed to assert a denominator.
    givenBalance(500);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });
    mocks.org = { currentOrgId: 'org-1', currentOrgRole: null };

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
  });
});

describe('useCreditWallet - CE has no monthly grant to gauge against', () => {
  it('returns no allowance in CE even though the billing stub reports FREE', () => {
    // CE bills in dollars and never grants credits on a cycle. Its billing stub
    // still answers planCode "FREE", so without the gate the shared resolver
    // would hand every self-hosted install a fabricated 1,000-credit allowance.
    //
    // This pins DEFENCE IN DEPTH, not a user-visible behaviour: all three
    // consumers already self-gate or branch away in CE (the quota page renders
    // CeQuotaPage instead of the component that calls this hook). The comment
    // here used to claim the quota page reached this hook in CE, which was
    // false - the test passed either way, so it certified the claim rather than
    // checking it.
    mocks.isCe.value = true;
    givenBalance(980);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
    expect(result.current.gauge.fillPct).toBe(0);
    expect(result.current.gauge.isOver).toBe(false);
  });

  it('still reports the balance in CE - only the denominator is unknown', () => {
    mocks.isCe.value = true;
    givenBalance(980);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    expect(renderHook(() => useCreditWallet()).result.current.balance).toBe(980);
  });

  it('resolves the same account normally in cloud, so the gate is edition-specific', () => {
    // The control: without it, a hook that returned null unconditionally would
    // pass both assertions above.
    mocks.isCe.value = false;
    givenBalance(980);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    expect(renderHook(() => useCreditWallet()).result.current.allowance).toBe(1_000);
  });
});

describe('useCreditWallet - the bucket split it passes through', () => {
  it('forwards subBalance and paygBalance unswapped', () => {
    // The panel labels these "Subscription" and "PAYG top-up" and they are not
    // interchangeable: one resets each cycle, the other persists. Swapping the
    // two fields here was invisible to every other test in the suite, because
    // the component tests inject them as props and never reach this hook.
    givenBalance(1_400, { subBalance: 1_000, paygBalance: 400 });
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.subBalance).toBe(1_000);
    expect(result.current.paygBalance).toBe(400);
    expect(result.current.balance).toBe(1_400);
  });

  it('keeps them null before the bucketed payload lands', () => {
    givenBalance(1_400);
    givenSubscription({ subscription: { planCode: 'FREE', creditTierIndex: 0 } });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.subBalance).toBeNull();
    expect(result.current.paygBalance).toBeNull();
  });
});

describe('useCreditWallet - a failed read that answers HTTP 200', () => {
  it('shows NO allowance when the payload says status:"error"', () => {
    // GET /api/billing/me catches its own lookup failure and returns 200 with
    // {subscription: null, status: "error"}. React Query reports no error and
    // the envelope is truthy, so guarding on those two alone fell through to
    // the FREE grant: a PRO wallet of 40,000 credits was gauged against 1,000
    // and painted gold at "+3,900% over your plan".
    givenBalance(40_000);
    givenSubscription({ subscription: null, status: 'error', error: 'Impossible de recuperer le statut' });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
    expect(result.current.gauge.isOver).toBe(false);
    expect(result.current.balance).toBe(40_000);
  });

  it('shows NO allowance when the account has no subscription row', () => {
    // status:"no_subscription" - no row means no grant, and no ring is the
    // honest rendering of that rather than an invented 1,000.
    givenBalance(40_000);
    givenSubscription({ subscription: null, status: 'no_subscription', hasActiveSubscription: false });

    expect(renderHook(() => useCreditWallet()).result.current.allowance).toBeNull();
  });

  it('still resolves a healthy payload, so the guard is not simply always false', () => {
    givenBalance(9_779);
    givenSubscription({ subscription: { planCode: 'PRO', creditTierIndex: 1 }, status: 'active' });

    expect(renderHook(() => useCreditWallet()).result.current.allowance).toBe(10_000);
  });
});

describe('useCreditWallet - a stale payload alongside a fresh error', () => {
  it('refuses to gauge when the last refetch failed over cached data', () => {
    // react-query keeps the previous data and sets `error` when a refetch
    // fails, so the payload is PRESENT and untrustworthy at the same time -
    // the one shape `!!billingPayload.subscription` cannot catch on its own.
    givenBalance(40_000);
    givenSubscription({ subscription: { planCode: 'PRO', creditTierIndex: 1 } }, { error: 'Network error' });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
    expect(result.current.gauge.isOver).toBe(false);
  });

  it('gauges the same payload normally once the error clears', () => {
    givenBalance(40_000);
    givenSubscription({ subscription: { planCode: 'PRO', creditTierIndex: 1 } });

    expect(renderHook(() => useCreditWallet()).result.current.allowance).toBe(10_000);
  });
});

describe('useCreditWallet - each clause of the read guard, separately', () => {
  // The three clauses are separately reachable and were not separately tested:
  // the fixture for the "HTTP 200 with status:error" case also nulled the
  // subscription, so the sibling clause satisfied it and `status !== 'error'`
  // could be deleted with the whole file green.

  it('refuses a payload that is PRESENT but flagged as an error', () => {
    // The shape only the status clause catches. Not hypothetical: the endpoint
    // builds its response incrementally, so a partially-populated body with an
    // error status is one refactor away.
    givenBalance(40_000);
    givenSubscription({ subscription: { planCode: 'PRO', creditTierIndex: 1 }, status: 'error' });

    expect(renderHook(() => useCreditWallet()).result.current.allowance).toBeNull();
  });

  it('refuses a payload whose subscription object is missing', () => {
    givenBalance(40_000);
    givenSubscription({ status: 'ok' });

    expect(renderHook(() => useCreditWallet()).result.current.allowance).toBeNull();
  });

  it('accepts a payload that passes all three, so none of them is always-false', () => {
    givenBalance(9_779);
    givenSubscription({ subscription: { planCode: 'PRO', creditTierIndex: 1 }, status: 'ok' });

    expect(renderHook(() => useCreditWallet()).result.current.allowance).toBe(10_000);
  });
});

describe('useCreditWallet - when the credits come back', () => {
  const GRANT_DATE = '2026-10-14T23:53:09';

  const INVOICE_DATE = '2027-09-14T23:53:09';

  it('carries the credit date and the invoice date the billing payload named', () => {
    givenBalance(9_779);
    givenSubscription({
      subscription: {
        planCode: 'TEAM',
        creditTierIndex: 4,
        cadence: 'yearly',
        currentPeriodEnd: INVOICE_DATE,
        nextCreditGrantAt: GRANT_DATE,
      },
    });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.renewsAt).toBe(GRANT_DATE);
    // The invoice date is carried so a surface can explain why it is not the credit date.
    // Read off the SAME row the backend computed the credit date from, which is why it is
    // currentPeriodEnd and not the served `cadence`: swapPlan moves a subscription's cadence
    // without moving the price row the payload prefers when serving it.
    expect(result.current.periodEndsAt).toBe(INVOICE_DATE);
    expect(result.current.allowance).toBe(100_000);
  });

  it('ignores a non-string invoice date, exactly as it does the credit date', () => {
    givenBalance(9_779);
    givenSubscription({
      subscription: {
        planCode: 'PRO',
        creditTierIndex: 1,
        currentPeriodEnd: [2027, 9, 14],
        nextCreditGrantAt: GRANT_DATE,
      },
    });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.renewsAt).toBe(GRANT_DATE);
    expect(result.current.periodEndsAt).toBeNull();
  });

  it('reports no date when the backend named none - a cancelled row is owed no grant', () => {
    // The backend answers null for a subscription set to cancel or out of good standing.
    // Nothing here may substitute a period end for it: that date is when access STOPS.
    givenBalance(9_779);
    givenSubscription({
      subscription: { planCode: 'PRO', creditTierIndex: 1, nextCreditGrantAt: null },
    });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.renewsAt).toBeNull();
    // The grant itself is still knowable, and the gauge still needs it.
    expect(result.current.allowance).toBe(10_000);
  });

  it('withholds the date for the same reason it withholds the allowance: the wallet is not ours', () => {
    // Owner-pays: the balance on screen belongs to the workspace owner, so OUR renewal
    // date beside THEIR balance would be two accounts stated as one.
    givenBalance(250_000);
    givenSubscription({
      activeOrgPlanCode: 'TEAM',
      subscription: {
        planCode: 'FREE',
        creditTierIndex: 0,
        currentPeriodEnd: INVOICE_DATE,
        nextCreditGrantAt: GRANT_DATE,
      },
    });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.allowance).toBeNull();
    expect(result.current.renewsAt).toBeNull();
    expect(result.current.periodEndsAt).toBeNull();
  });

  it('withholds the date on a failed read, which answers HTTP 200 with status error', () => {
    givenBalance(40_000);
    givenSubscription({
      status: 'error',
      subscription: { planCode: 'PRO', creditTierIndex: 1, nextCreditGrantAt: GRANT_DATE },
    });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.renewsAt).toBeNull();
  });

  it('withholds the date in CE, which has no cycle grant to renew', () => {
    mocks.isCe.value = true;
    givenBalance(9_779);
    givenSubscription({
      subscription: { planCode: 'FREE', creditTierIndex: 0, nextCreditGrantAt: GRANT_DATE },
    });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.renewsAt).toBeNull();
    expect(result.current.allowance).toBeNull();
  });

  it('ignores a non-string date instead of passing it on to a formatter', () => {
    // A LocalDateTime rendered as a [y,M,d,...] array is what a mis-configured mapper
    // produces; formatting it downstream yields "Invalid Date", not an error anyone sees.
    givenBalance(9_779);
    givenSubscription({
      subscription: {
        planCode: 'PRO',
        creditTierIndex: 1,
        nextCreditGrantAt: [2026, 10, 14, 23, 53, 9],
      },
    });

    const { result } = renderHook(() => useCreditWallet());

    expect(result.current.renewsAt).toBeNull();
  });
});
