/**
 * Monthly credit allowance + gauge maths, shared by every surface that shows a
 * "how much of my plan is left" indicator (the top-header balance ring, the
 * sidebar user block, the wallet card on /app/settings/quota).
 *
 * Two things live here because they were previously re-derived per surface and
 * drifted:
 *
 *  1. {@link resolveMonthlyAllowance} - the credits granted at the start of each
 *     billing cycle. A PAID plan's grant is its purchased credit tier; a FREE
 *     account still has one ({@link FREE_MONTHLY_CREDITS}), which is why the
 *     ring can show a real percentage for everybody instead of an empty dial.
 *     This module is edition-agnostic on purpose: CE has no monthly grant at
 *     all, and that gate lives in {@link useCreditWallet} so the maths here stay
 *     pure and directly testable.
 *
 *  2. {@link computeCreditGauge} - turns (balance, allowance) into the numbers a
 *     bar/ring renders. `balance` is what is LEFT, so the consumed share is
 *     `allowance - balance`; a balance ABOVE the allowance is not an error, it
 *     is carry-over or a PAYG top-up sitting on top of the cycle grant, and it
 *     switches the gauge into its gold "over allowance" state.
 */

import { CREDIT_TIERS } from './pricing-constants';

/**
 * Credits granted to a FREE account at the start of every month.
 *
 * Mirrors the backend grant, which is a DB column, not a Java constant:
 * `auth.plan.included_llm_tokens` for code 'FREE', seeded 1000 by migration V4,
 * zeroed by V56 and restored by V285. The public pricing copy states the same
 * figure ("1,000 credits per month", `billing` block of every locale file).
 * Change one of the three and change all three.
 */
export const FREE_MONTHLY_CREDITS = 1_000;

/**
 * Credits granted per cycle for the given plan, or null when the plan carries no
 * cycle grant at all (unknown/again-loading plan code).
 *
 * FREE (and a null plan code, which the billing payload uses for "no
 * subscription row yet") resolves to {@link FREE_MONTHLY_CREDITS} rather than
 * null: those accounts DO get a monthly reset, so gauging them against it is
 * accurate, not a guess.
 *
 * A paid plan carrying no credit pack resolves to tier 0 (5,000), never to the
 * FREE grant - matching the backend, where `creditQuantity == 0` on a paid plan
 * reads `CREDIT_TIERS[0]` and never `included_llm_tokens`.
 */
export function resolveMonthlyAllowance(
  planCode: string | null | undefined,
  creditTierIndex: number | null | undefined,
): number | null {
  // A PURCHASED PACK WINS OVER THE PLAN, on every plan code including FREE. The backend's
  // `grantsBasePack` is true for any row with `creditQuantity > 0`, and a tier index above 0
  // can only come from a quantity that matched a tier's cost exactly (`resolveTierIndex` falls
  // back to 0 otherwise), so such a row is granted CREDIT_TIERS[index] and never the plan's
  // included_llm_tokens. Keying on the plan code first said 1,000 for a FREE row holding a
  // real pack: the wrong denominator on the gauge, and once the card began naming a date, a
  // dated promise of an amount that is not the one that lands.
  const purchased = CREDIT_TIERS[creditTierIndex ?? 0];
  if ((creditTierIndex ?? 0) > 0) {
    return typeof purchased === 'number' ? purchased : null;
  }
  if (!planCode || planCode === 'FREE') return FREE_MONTHLY_CREDITS;
  return typeof purchased === 'number' ? purchased : null;
}

export interface CreditGauge {
  /** True when the balance sits ABOVE the cycle grant (carry-over / PAYG top-up). */
  isOver: boolean;
  /**
   * How much the balance exceeds the grant, as a percentage of the grant.
   * NOT capped - a 1,000-credit plan holding 35,000 credits reports 3,400 so the
   * label can state the real figure. Only {@link fillPct} saturates.
   */
  overPct: number;
  /**
   * What the bar/ring actually fills, 0-100.
   * Normal state: the consumed share. Over-allowance state: the excess share,
   * capped at 100 - a bar cannot draw past full, so a very large surplus keeps
   * it pinned at 100 rather than silently wrapping around.
   */
  fillPct: number;
}

/**
 * Frozen: it is handed out by reference to every caller that has no denominator,
 * so a mutation anywhere would corrupt the "no gauge" state everywhere.
 */
const EMPTY_GAUGE: CreditGauge = Object.freeze({ isOver: false, overPct: 0, fillPct: 0 });

/**
 * Gauge numbers for a wallet balance against its cycle grant.
 *
 * Returns the all-zero gauge when either input is missing or the allowance is
 * non-positive: no allowance means no denominator, and a fabricated percentage
 * is worse than no dial at all. Callers must therefore ALSO hide the dial when
 * the allowance is null - a zero gauge renders as "0% used", which is a claim.
 */
export function computeCreditGauge(
  balance: number | null | undefined,
  allowance: number | null | undefined,
): CreditGauge {
  // Non-finite guards, not just null ones: NaN slips past every comparison below
  // and would reach the bar as `width: "NaN%"`, which paints an EMPTY bar - i.e.
  // a corrupt balance would render as a healthy, untouched plan.
  if (typeof balance !== 'number' || !Number.isFinite(balance)) return EMPTY_GAUGE;
  if (typeof allowance !== 'number' || !Number.isFinite(allowance) || allowance <= 0) {
    return EMPTY_GAUGE;
  }

  if (balance > allowance) {
    const overPct = ((balance - allowance) / allowance) * 100;
    return {
      isOver: true,
      overPct: Math.round(overPct),
      fillPct: Math.min(100, Math.round(overPct)),
    };
  }

  // BOTH branches measure the WHOLE wallet against the grant, and they have to,
  // because that is what keeps the scale continuous where they meet: at exactly
  // one grant the consumed share is 0 and the surplus is 0, so the dial passes
  // through the boundary without moving.
  //
  // A previous version measured the SUBSCRIPTION bucket under the grant, to fix
  // a real imprecision: with the grant spent and a 400-credit top-up left, this
  // says "60% of your plan used" while the rows below it read "Subscription 0 /
  // PAYG top-up 400". Measuring the bucket made that sentence true - and broke
  // the boundary. With a spent grant, 1,000 of top-up read "100% used" on a FULL
  // dial and 1,001 read "Just over your plan" on an EMPTY gold one: a 100-to-0
  // jump on one credit, and two adjacent states asserting the opposite of each
  // other about the same wallet. That is worse than the imprecision it fixed.
  //
  // So the imprecision stays, bounded: it only arises when a top-up exists, and
  // that is exactly when the panel renders the two bucket rows underneath, which
  // say plainly where the credits are. Making the label bucket-accurate without
  // reintroducing the discontinuity means changing what the gold state measures,
  // and the gold state's meaning ("how much MORE than my plan do I hold") is a
  // product decision, not a maths one.
  //
  // A negative balance (delinquent account) reads as fully consumed, never as a
  // negative fill - the bar has no way to draw "less than empty".
  const remaining = Math.max(0, balance);
  const consumedPct = 100 - Math.round((remaining / allowance) * 100);

  // No low clamp, and it would be dead code: this branch runs only when
  // `balance <= allowance`, so `remaining` is at most the allowance and
  // `consumedPct` cannot go below 0. (It COULD when an earlier version passed a
  // separate bucket in as the numerator - that version is gone, and the clamp
  // went with it rather than sitting here implying a state that cannot occur.)
  const fillPct = remaining > 0 ? Math.min(99, consumedPct) : consumedPct;

  return { isOver: false, overPct: 0, fillPct };
}
