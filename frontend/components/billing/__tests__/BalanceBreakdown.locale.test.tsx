/**
 * @vitest-environment jsdom
 *
 * The wallet card had NO test of any kind, which is how the app locale could be
 * dropped from any of `BalanceBreakdownCard`'s four `formatCreditsCompact`
 * calls with the whole suite green. That is not cosmetic: without an explicit locale the formatter
 * falls back to `getClientLocale()`, which returns a hardcoded 'en' when there
 * is no window - so a server render spells the number in English and hydration
 * re-spells it, for all five non-English locales.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';
import deMessages from '../../../messages/de.json';

// The real fallback would mask the defect: if it happened to return the same
// locale the provider carries, dropping the argument would look correct.
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));

import { BalanceBreakdownCard } from '../BalanceBreakdown';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderCard(locale: 'en' | 'de', props: Record<string, unknown> = {}) {
  return render(
    <NextIntlClientProvider locale={locale} messages={(locale === 'de' ? deMessages : messages) as Record<string, unknown>}>
      <BalanceBreakdownCard balance={9_779} subBalance={9_779} paygBalance={0} {...props} />
    </NextIntlClientProvider>,
  );
}

describe('BalanceBreakdownCard - spells its numbers for the APP locale', () => {
  it('uses the English decimal point under an en provider', () => {
    renderCard('en');
    expect(screen.getAllByText('9.8K').length).toBeGreaterThan(0);
  });

  it('uses the German decimal comma under a de provider', () => {
    // The fallback is pinned to 'en' above, so a comma can only come from the
    // locale this component passes in explicitly.
    renderCard('de');
    expect(screen.getAllByText('9,8K').length).toBeGreaterThan(0);
    expect(screen.queryByText('9.8K')).toBeNull();
  });

  it('spells the allowance in the plan counter too', () => {
    renderCard('de', { monthlyPlan: { allowance: 10_000 } });
    // Rendered as "balance / allowance"; both halves go through the formatter.
    expect(screen.getByText(/10,0K/)).toBeTruthy();
  });

  it('leaves NO English-spelled number anywhere on a German card', () => {
    // Scoped to the whole rendered card rather than one row: BalanceBreakdownCard
    // calls the formatter in FOUR places, and asserting on a single one lets the
    // other three silently lose their locale - which is exactly what happened,
    // because a sibling row rendered the same string and kept the assertion green.
    //
    // The file's other two call sites live in BalanceBreakdownTooltip, which this
    // suite never mounts and which no longer has a live caller in either edition
    // (AppSidebar's cloud arm takes SidebarCreditBalance, and its CE arm is fed a
    // hard-coded null). They are deliberately NOT covered here: writing a test
    // for a component nothing renders would assert a fiction, which is the very
    // thing this file exists to avoid.
    const { container } = renderCard('de', { monthlyPlan: { allowance: 10_000 }, paygBalance: 1_400 });

    expect(container.textContent).not.toMatch(/\d+\.\d[KM]/);
    expect(container.textContent).toMatch(/\d+,\d[KM]/);
  });

  it('would flag an English card as English, so the check above is not vacuous', () => {
    const { container } = renderCard('en', {
      monthlyPlan: { allowance: 10_000 },
      paygBalance: 1_400,
      aiBalance: 1_200,
    });

    expect(container.textContent).toMatch(/\d+\.\d[KM]/);
  });
});

describe('BalanceBreakdownCard - the AI allowance row (V494)', () => {
  it('draws the allowance as its own row, outside the wallet figure', () => {
    // The pot cannot pay for anything but agent and chat turns on the free-tier
    // models, so it is shown beside the wallet and never added into it. A reader
    // who saw it summed would plan spending they cannot do.
    renderCard('en', { balance: 9_779, subBalance: 9_779, paygBalance: 0, aiBalance: 100 });

    expect(screen.getByText(messages.billing.payg.breakdown.ai)).toBeTruthy();
    // One decimal, like every other figure on this card (formatCreditsCompact).
    expect(screen.getByText('100.0')).toBeTruthy();
    // The headline balance is untouched by the pot.
    expect(screen.getAllByText('9.8K').length).toBeGreaterThan(0);
  });

  it('omits the row on a plan that has no allowance at all', () => {
    // Every paid plan: an empty row would advertise a bucket the account does not
    // have. Zero alone does not decide it, which is what the next case is about.
    renderCard('en', { aiBalance: 0, hasAiAllowance: false });

    expect(screen.queryByText(messages.billing.payg.breakdown.ai)).toBeNull();
  });

  it('SHOWS the row at zero when the plan does grant an allowance', () => {
    // The reader whose chat just stopped working. Hiding a spent pot makes it
    // indistinguishable from a plan that never had one, and leaves the one person
    // who needs the explanation with a card that says nothing at all.
    renderCard('en', { aiBalance: 0, hasAiAllowance: true });

    const label = screen.getByText(messages.billing.payg.breakdown.ai);
    // Scoped to the allowance row: the PAYG row also reads 0.0 in this fixture.
    expect(label.parentElement?.textContent).toContain('0.0');
  });

  it('omits the row when the balance endpoint did not answer', () => {
    renderCard('en');

    expect(screen.queryByText(messages.billing.payg.breakdown.ai)).toBeNull();
  });
});

describe('BalanceBreakdownCard - the subscription bar cannot render a negative width', () => {
  it('draws an empty bar for a negative bucket rather than collapsing', () => {
    // A debit takes the whole cost from PAYG when the plan's monthly grant is
    // workflow-only (FREE), so a bucket can go negative. `width: "-20%"` is an
    // invalid declaration: the bar vanishes instead of reading empty, and this
    // change widened the exposure by giving FREE accounts a denominator here.
    renderCard('en', { balance: -200, subBalance: -200, paygBalance: 0, monthlyPlan: { allowance: 1_000 } });

    // Asserted as "the width is 0%", NOT as "no width starts with a minus": an
    // invalid declaration is DROPPED by the DOM, so `style.width` comes back as
    // the empty string and a minus-scanning assertion passes on the very bug it
    // is meant to catch. Only naming the expected value discriminates.
    expect(screen.getByTestId('subscription-gauge-fill').style.width).toBe('0%');
  });

  it('still fills proportionally for an ordinary bucket, so the clamp is not a floor at zero', () => {
    renderCard('en', { balance: 600, subBalance: 600, paygBalance: 0, monthlyPlan: { allowance: 1_000 } });
    expect(screen.getByTestId('subscription-gauge-fill').style.width).toBe('60%');
  });
});
