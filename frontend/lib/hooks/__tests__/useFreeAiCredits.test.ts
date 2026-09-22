/**
 * The number the Free plan's AI allowance is quoted with (V494).
 *
 * <p>Two surfaces show it side by side: the plan card on the pricing page and the
 * comparison table that page opens. They resolved it independently until this hook
 * existed, which meant raising the allowance to 250 made the card say 250 and the
 * table still say 100, on the same screen, with nothing failing anywhere.
 *
 * <p>The resolution is tested as a pure function: the hook itself is `usePlans` plus
 * this, and `usePlans` is a react-query hook whose gating (authenticated only) is the
 * very thing that makes the fallback the landing's normal case rather than an error.
 */
import { describe, expect, it } from 'vitest';
import { resolveFreeAiCredits, resolveFreeAiCreditsAnswer } from '@/lib/hooks/useFreeAiCredits';
import { FREE_AI_CREDITS } from '@/lib/billing/pricing-constants';
import type { BillingPlan } from '@/lib/api/services/billing-api.service';

const plan = (code: string, includedAiCredits?: number | null): BillingPlan =>
  ({ code, includedAiCredits }) as BillingPlan;

describe('resolveFreeAiCredits', () => {
  it('quotes what the admin configured on the Free plan row', () => {
    expect(resolveFreeAiCredits([plan('STARTER', null), plan('FREE', 250)])).toBe(250);
  });

  it('falls back to the seeded figure on the public landing, where no plans are fetched', () => {
    // `usePlans` is gated on authentication, so a visitor legitimately has none.
    expect(resolveFreeAiCredits(undefined)).toBe(FREE_AI_CREDITS);
    expect(resolveFreeAiCredits([])).toBe(FREE_AI_CREDITS);
  });

  it('reads a Free row that grants nothing as ZERO, not as the seeded figure', () => {
    // The row IS here and says nothing, which is one of the two ways an admin closes
    // the free tier (the other is 0). Falling back to the seeded 100 here would keep
    // the pricing page advertising credits the renewal will never hand out, and would
    // draw the allowance row on a wallet that has no pot - the fallback belongs to
    // "no row to read", not to "the row says no".
    expect(resolveFreeAiCredits([plan('FREE', null)])).toBe(0);
    expect(resolveFreeAiCredits([plan('FREE')])).toBe(0);
  });

  it('reads zero as zero, not as missing', () => {
    // An admin who closes the free tier sets it to 0, and the surfaces must say
    // so instead of falling back to the seeded 100 they were opened with.
    expect(resolveFreeAiCredits([plan('FREE', 0)])).toBe(0);
  });

  it('ignores the allowance of any other plan', () => {
    expect(resolveFreeAiCredits([plan('PRO', 900), plan('FREE', 100)])).toBe(100);
    expect(resolveFreeAiCredits([plan('PRO', 900)])).toBe(FREE_AI_CREDITS);
  });
});

describe('resolveFreeAiCreditsAnswer', () => {
  // The seeded figure and a configured 100 are the SAME number, so a surface
  // whose whole job is to state this allowance cannot tell "not answered yet"
  // from "answered, and it is 100" by the figure alone. That surface (the
  // post-onboarding welcome gift) waits on this flag before it quotes anything.
  it('says the figure came from the live Free plan row', () => {
    expect(resolveFreeAiCreditsAnswer([plan('FREE', 250)])).toEqual({ credits: 250, resolved: true });
  });

  it('marks a row that grants nothing as answered, because it IS the answer', () => {
    // NULL and 0 are the two ways an admin closes the free tier. Reporting
    // either as unresolved would make a deliberate decision look like a
    // request still in flight, and hold a screen open waiting for it.
    expect(resolveFreeAiCreditsAnswer([plan('FREE', 0)])).toEqual({ credits: 0, resolved: true });
    expect(resolveFreeAiCreditsAnswer([plan('FREE', null)])).toEqual({ credits: 0, resolved: true });
  });

  it('marks the seeded stand-in as NOT answered, whatever produced it', () => {
    // In flight, failed after its retries, and the signed-out landing all reach
    // here as an empty or absent list. They are the same shape on purpose;
    // what matters is that none of them is an answer.
    expect(resolveFreeAiCreditsAnswer(undefined)).toEqual({ credits: FREE_AI_CREDITS, resolved: false });
    expect(resolveFreeAiCreditsAnswer([])).toEqual({ credits: FREE_AI_CREDITS, resolved: false });
    expect(resolveFreeAiCreditsAnswer([plan('PRO', 900)])).toEqual({ credits: FREE_AI_CREDITS, resolved: false });
  });

  it('is the one resolution, so the legacy helper cannot drift from it', () => {
    const cases = [undefined, [], [plan('FREE', 250)], [plan('FREE', 0)], [plan('PRO', 900)]];
    for (const plans of cases) {
      expect(resolveFreeAiCredits(plans)).toBe(resolveFreeAiCreditsAnswer(plans).credits);
    }
  });
});
