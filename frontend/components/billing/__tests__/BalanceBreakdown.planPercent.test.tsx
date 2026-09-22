/**
 * @vitest-environment jsdom
 *
 * The plan share on the wallet card: "98% of your plan remaining", and the gold
 * "+40% over your plan" above the grant.
 *
 * It used to live in the sidebar user menu, under a second bar that repeated
 * the ring drawn around the avatar three feet away. The menu is a place people
 * pass through; this card is the surface they open to READ their plan.
 *
 * What the move has to get right, and what each test pins:
 *
 *  - THE NUMERATOR. It hangs off the total, which is the whole wallet, and the
 *    whole wallet is what the sidebar ring measures. The first attempt attached
 *    it to the Subscription bar instead, whose numerator is that BUCKET: a
 *    wallet of 10,000 grant plus a 5,000 top-up then read "+50% over your plan"
 *    in gold in the sidebar and "100% of your plan remaining" here. There is a
 *    dedicated cross-surface test for exactly that wallet at the bottom.
 *  - DIRECTION. Remaining, not consumed: the figure it sits under is a
 *    remaining count, and "2% used" beside "9.8K available" reads as empty.
 *  - THE CAP. Bars saturate because they cannot draw past their end. A sentence
 *    can say 3,400%, and must, or a huge carry-over reads as an ordinary plan.
 *  - NO DENOMINATOR, NO CLAIM. Without an allowance there is nothing to state.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';
import deMessages from '../../../messages/de.json';

// The real fallback would mask a dropped locale: if it happened to return the
// locale the provider carries, omitting the argument would look correct.
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));

import { BalanceBreakdownCard } from '../BalanceBreakdown';

afterEach(cleanup);

function renderCard(
  props: { balance?: number; subBalance?: number; allowance?: number | null; paygBalance?: number } = {},
  locale: 'en' | 'de' = 'en',
) {
  const balance = props.balance ?? 9_779;
  const allowance = props.allowance === undefined ? 10_000 : props.allowance;
  return render(
    <NextIntlClientProvider
      locale={locale}
      messages={(locale === 'de' ? deMessages : messages) as Record<string, unknown>}
    >
      <BalanceBreakdownCard
        balance={balance}
        subBalance={props.subBalance ?? balance}
        paygBalance={props.paygBalance ?? 0}
        monthlyPlan={allowance === null ? undefined : { allowance }}
      />
    </NextIntlClientProvider>,
  );
}

const share = () => screen.queryByTestId('wallet-plan-share');

describe('the wallet card states how much of the plan is left', () => {
  it('spells the percentage in the REMAINING direction, under the figure it measures', () => {
    // 9,779 of a 10,000 grant is 98% left. "2% used" is the same fact and the
    // wrong sentence here: it sits under "9.8K / Total credits available".
    renderCard();
    expect(share()!.textContent).toBe('98% of your plan remaining');
  });

  it('tracks the WHOLE wallet, not the subscription bucket', () => {
    // The bucket split is what makes these two different numbers. 4,000 of grant
    // left plus a 6,000 top-up is a full plan's worth of credits; measuring the
    // bucket alone would announce 40%.
    renderCard({ balance: 10_000, subBalance: 4_000, paygBalance: 6_000 });
    expect(share()!.textContent).toBe('100% of your plan remaining');
  });

  it('says 0% on an exhausted wallet', () => {
    renderCard({ balance: 0, subBalance: 0 });
    expect(share()!.textContent).toBe('0% of your plan remaining');
  });

  it('floors a delinquent wallet at zero rather than printing a negative share', () => {
    // A debit can drive a wallet negative when the plan's monthly grant is
    // workflow-only. "-20% of your plan remaining" is not a quantity a reader
    // can hold.
    renderCard({ balance: -2_000, subBalance: -2_000 });
    expect(share()!.textContent).toBe('0% of your plan remaining');
  });

  it('states nothing at all when no allowance is knowable', () => {
    // CE, or a guest whose payer is someone else. A percentage needs a
    // denominator, and "0%" would be a claim about an account we could not read.
    renderCard({ allowance: null });
    expect(share()).toBeNull();
  });

  it('is prose the card can read, not a badge', () => {
    // text-sm is the house default for content; text-xs is for badges and
    // timestamps. This is a sentence a reader is meant to read. The colour is
    // set explicitly rather than inherited, which is how the bar it replaces
    // ended up drawing dark-on-dark in a container that set none.
    renderCard();
    expect(share()!.className).toContain('text-sm');
    expect(share()!.className).toContain('text-theme-');
  });
});

describe('the wallet card is where the surplus is spelt out', () => {
  // The ring turns gold and shows a bare "+". This card is the only place that
  // "+" is explained to a sighted reader, so these are not edge cases: they are
  // the whole reason the gold state is readable at all.

  it('states the surplus instead of a remaining share above the grant', () => {
    renderCard({ balance: 14_000, subBalance: 10_000, paygBalance: 4_000 });
    expect(share()!.textContent).toBe('+40% over your plan');
  });

  it('inks it gold, from the per-theme token', () => {
    // The bright metal that reads on the dark ground sits at 1.8:1 on white, so
    // a literal cannot serve both themes.
    renderCard({ balance: 14_000, subBalance: 10_000, paygBalance: 4_000 });
    expect(share()!.style.color).toContain('--credit-gold-ink');
  });

  it('leaves the sentence ordinary ink while inside the plan', () => {
    // Painting it gold under the grant would announce a surplus that is not there.
    renderCard();
    expect(share()!.style.color).toBe('');
  });

  it('says "just over" rather than "+0% over your plan"', () => {
    // 10,001 of a 10,000 grant is genuinely over, but the rounded figure is 0,
    // and "+0% over" contradicts the gold it is printed in.
    renderCard({ balance: 10_001, subBalance: 10_000, paygBalance: 1 });
    expect(share()!.textContent).toBe('Just over your plan');
  });

  it('states a huge surplus in full, where no bar could draw it', () => {
    renderCard({ balance: 35_000, subBalance: 1_000, paygBalance: 34_000, allowance: 1_000 });
    // Grouped for the app locale, like every other displayed figure.
    expect(share()!.textContent).toBe('+3,400% over your plan');
  });

  it('spells that figure for the APP locale, not the runner default', () => {
    // The surplus is the only branch whose figure can exceed three digits - a
    // remaining share is capped at 100 by definition - so it is the only place
    // grouping is observable at all. A bare ICU {percent} given a number is NOT
    // locale-formatted, which is how a four-figure percentage reached one
    // surface as "3400" while the surface beside it said "3.400" to the same
    // German reader.
    renderCard({ balance: 35_000, subBalance: 1_000, paygBalance: 34_000, allowance: 1_000 }, 'de');
    expect(share()!.textContent).toContain('3.400');
    expect(share()!.textContent).not.toContain('3,400');
  });
});
