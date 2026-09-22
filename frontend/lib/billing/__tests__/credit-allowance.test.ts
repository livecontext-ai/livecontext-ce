import { describe, it, expect } from 'vitest';
import {
  FREE_MONTHLY_CREDITS,
  resolveMonthlyAllowance,
  computeCreditGauge,
} from '../credit-allowance';
import { CREDIT_TIERS } from '../pricing-constants';

describe('resolveMonthlyAllowance', () => {
  it('gives a FREE account the 1,000-credit monthly reset rather than no allowance', () => {
    // The backend grants FREE plan.included_llm_tokens = 1000 every cycle
    // (V285), so a FREE wallet HAS a denominator and its ring shows a real
    // percentage instead of an empty dial.
    expect(resolveMonthlyAllowance('FREE', 0)).toBe(1_000);
    expect(FREE_MONTHLY_CREDITS).toBe(1_000);
  });

  it('gives a FREE row that holds a real credit pack the PACK, not the 1,000 reset', () => {
    // grantsBasePack is true for any row with creditQuantity > 0, whatever the plan code, and
    // a tier index above 0 can only come from a quantity that matched a tier cost exactly. So
    // the backend grants this row 10,000 and the gauge used to measure it against 1,000 -
    // which, once the wallet card started naming a date, became a dated promise of the wrong
    // amount.
    expect(resolveMonthlyAllowance('FREE', 1)).toBe(10_000);
    expect(resolveMonthlyAllowance('FREE', 4)).toBe(100_000);
    // Tier 0 is the no-pack case and still reads the plan: FREE keeps its monthly reset.
    expect(resolveMonthlyAllowance('FREE', 0)).toBe(FREE_MONTHLY_CREDITS);
  });

  it('treats a missing plan code as FREE, not as "unknown"', () => {
    // The billing payload omits the subscription row entirely for an account
    // that has never subscribed; that account still gets the monthly reset.
    expect(resolveMonthlyAllowance(null, null)).toBe(1_000);
    expect(resolveMonthlyAllowance(undefined, undefined)).toBe(1_000);
  });

  it('gives a paid plan its purchased credit tier', () => {
    expect(resolveMonthlyAllowance('PRO', 1)).toBe(10_000);
    expect(resolveMonthlyAllowance('TEAM', 4)).toBe(CREDIT_TIERS[4]);
  });

  it('falls back to tier 0 for a paid plan carrying no credit pack', () => {
    // Mirrors the backend: a paid plan with creditQuantity 0 resolves to tier 0
    // (5,000), never to the FREE plan's included_llm_tokens.
    expect(resolveMonthlyAllowance('STARTER', 0)).toBe(5_000);
    expect(resolveMonthlyAllowance('STARTER', null)).toBe(5_000);
  });

  it('returns null for a paid plan whose tier index is off the scale', () => {
    // No denominator is better than a fabricated one: the surfaces hide the
    // Total row, the gauge AND the ring entirely when this is null.
    expect(resolveMonthlyAllowance('PRO', 99)).toBeNull();
    expect(resolveMonthlyAllowance('PRO', -1)).toBeNull();
  });
});

describe('computeCreditGauge - under allowance', () => {
  it('fills by the CONSUMED share, so a barely-used plan reads low', () => {
    // The ElevenLabs-style dial: 10,000 granted, 9,779 left => 2% used. Filling
    // by what REMAINS would draw the opposite bar.
    const g = computeCreditGauge(9_779, 10_000);
    expect(g.fillPct).toBe(2);
    expect(g.isOver).toBe(false);
    expect(g.overPct).toBe(0);
  });

  it('reads a full untouched grant as 0% consumed', () => {
    const g = computeCreditGauge(1_000, 1_000);
    expect(g.fillPct).toBe(0);
    expect(g.isOver).toBe(false);
  });

  it('reads an exhausted wallet as 100% consumed', () => {
    const g = computeCreditGauge(0, 1_000);
    expect(g.fillPct).toBe(100);
    expect(g.isOver).toBe(false);
  });

  it('clamps a delinquent negative balance to fully consumed, not a negative fill', () => {
    // A bar cannot draw "less than empty"; a negative width would silently
    // render as no bar at all, which reads as a healthy account.
    const g = computeCreditGauge(-450, 1_000);
    expect(g.fillPct).toBe(100);
    expect(g.isOver).toBe(false);
  });
});

describe('computeCreditGauge - over allowance (gold state)', () => {
  it('flags a wallet above its grant and measures the SURPLUS, not the balance', () => {
    // 1,000 granted with 1,400 held: a 400-credit surplus = +40%.
    const g = computeCreditGauge(1_400, 1_000);
    expect(g.isOver).toBe(true);
    expect(g.overPct).toBe(40);
    expect(g.fillPct).toBe(40);
  });

  it('saturates the FILL at 100% while still reporting the true surplus', () => {
    // 1,000 granted with 35,000 held: +3,400%. The bar pins at full; only the
    // label is allowed to state the real figure.
    const g = computeCreditGauge(35_000, 1_000);
    expect(g.isOver).toBe(true);
    expect(g.overPct).toBe(3_400);
    expect(g.fillPct).toBe(100);
  });

  it('fills exactly 100% at double the allowance, the boundary of the cap', () => {
    const g = computeCreditGauge(2_000, 1_000);
    expect(g.overPct).toBe(100);
    expect(g.fillPct).toBe(100);
  });

  it('does not flag a balance exactly AT the allowance', () => {
    // Equality is the normal full-grant state, not a surplus - it must stay ink,
    // not gold, or every freshly renewed account would look exceptional.
    expect(computeCreditGauge(1_000, 1_000).isOver).toBe(false);
  });

  it('flags the smallest possible surplus rather than rounding it away', () => {
    // overPct rounds to 0 here, but isOver stays true so the state is still
    // gold: the wallet genuinely holds more than the grant.
    const g = computeCreditGauge(1_001, 1_000);
    expect(g.isOver).toBe(true);
    expect(g.overPct).toBe(0);
    expect(g.fillPct).toBe(0);
  });
});

describe('computeCreditGauge - no denominator', () => {
  it('returns an empty gauge when the balance has not loaded', () => {
    expect(computeCreditGauge(null, 1_000).fillPct).toBe(0);
    expect(computeCreditGauge(undefined, 1_000).isOver).toBe(false);
  });

  it('returns an empty gauge when there is no allowance to gauge against', () => {
    // Dividing by a null/zero allowance would yield Infinity or NaN and paint a
    // full or blank bar; both would be a claim about the account.
    expect(computeCreditGauge(500, null)).toEqual({ isOver: false, overPct: 0, fillPct: 0 });
    expect(computeCreditGauge(500, 0).fillPct).toBe(0);
    expect(computeCreditGauge(500, -10).fillPct).toBe(0);
  });

  it('rejects a NaN balance instead of painting it as an untouched plan', () => {
    // NaN slips past every comparison and reaches the bar as width "NaN%", which
    // renders EMPTY - i.e. a corrupt balance would look like a healthy one.
    const g = computeCreditGauge(Number.NaN, 1_000);
    expect(g.fillPct).toBe(0);
    expect(g.isOver).toBe(false);
  });

  it('rejects an infinite balance or allowance', () => {
    expect(computeCreditGauge(Number.POSITIVE_INFINITY, 1_000).isOver).toBe(false);
    expect(computeCreditGauge(500, Number.POSITIVE_INFINITY).fillPct).toBe(0);
    expect(computeCreditGauge(500, Number.NaN).fillPct).toBe(0);
  });

  it('hands out an immutable empty gauge, since every no-denominator caller shares it', () => {
    // Asserted as a property of the object, not via a thrown error: a bare
    // .toThrow() would also pass on an unrelated throw, and outside ESM strict
    // mode the assignment fails silently rather than throwing at all.
    const g = computeCreditGauge(null, null);
    expect(Object.isFrozen(g)).toBe(true);

    try {
      (g as { fillPct: number }).fillPct = 99;
    } catch {
      /* strict mode throws; sloppy mode ignores. Either way the value must hold. */
    }
    expect(g.fillPct).toBe(0);
    expect(computeCreditGauge(null, null).fillPct).toBe(0);
  });
});

describe('computeCreditGauge - the 100% endpoint is a claim, not a rounding', () => {
  it('stops at 99 while credits are still spendable', () => {
    // 4 credits out of 1,000 is 0.4% left, which rounds the consumed share to a
    // flat 100 - and the surfaces render that number as the sentence "100% of
    // your plan used". That sentence is false while the wallet can still pay for
    // something, and it is the one value on the scale that reads as absolute
    // rather than approximate.
    expect(computeCreditGauge(4, 1_000).fillPct).toBe(99);
    expect(computeCreditGauge(0.5, 1_000).fillPct).toBe(99);
  });

  it('reaches 100 only on an actually empty wallet', () => {
    expect(computeCreditGauge(0, 1_000).fillPct).toBe(100);
  });

  it('still reaches 100 on a negative balance, which is emptier than empty', () => {
    // A delinquent account must not be shown as 99% consumed with something left.
    expect(computeCreditGauge(-250, 1_000).fillPct).toBe(100);
  });

  it('leaves every ordinary value rounding freely', () => {
    // The clamp is one endpoint, not a general ceiling: it must not shave a
    // percent off the middle of the scale.
    expect(computeCreditGauge(9_779, 10_000).fillPct).toBe(2);
    expect(computeCreditGauge(500, 1_000).fillPct).toBe(50);
    expect(computeCreditGauge(10, 1_000).fillPct).toBe(99);
  });
});

describe('computeCreditGauge - the scale is continuous across the boundary', () => {
  // An earlier attempt measured the SUBSCRIPTION bucket under the grant, so the
  // sentence "X% of your plan used" would be true when a top-up existed. It made
  // the dial jump: with the grant spent, 1,000 of top-up read 100% on a FULL
  // dial and 1,001 read "Just over your plan" on an EMPTY gold one. Both branches
  // now measure the whole wallet, which is what keeps the two ends meeting at
  // zero, and these cases exist so nobody re-derives that fix without seeing the
  // cost.

  it('leaves the fill at rest on either side of exactly one grant', () => {
    const at = computeCreditGauge(1_000, 1_000);
    const justOver = computeCreditGauge(1_001, 1_000);

    expect(at.isOver).toBe(false);
    expect(at.fillPct).toBe(0);
    expect(justOver.isOver).toBe(true);
    expect(justOver.fillPct).toBe(0);
  });

  it('moves by one step for one step of balance, on both sides', () => {
    // No jump anywhere along the crossing: a credit either way is a credit.
    expect(computeCreditGauge(999, 1_000).fillPct).toBe(0);
    expect(computeCreditGauge(1_100, 1_000).fillPct).toBe(10);
    expect(computeCreditGauge(900, 1_000).fillPct).toBe(10);
  });

  it('never reports a negative fill, whatever the buckets did', () => {
    // A debit can drive the PAYG bucket negative while the grant bucket is
    // still funded. `width: "-20%"` is an invalid declaration, so the bar
    // silently collapsed and the label read "-20% of your plan used".
    expect(computeCreditGauge(1_000, 1_000).fillPct).toBeGreaterThanOrEqual(0);
    expect(computeCreditGauge(-50, 1_000).fillPct).toBe(100);
  });
});
