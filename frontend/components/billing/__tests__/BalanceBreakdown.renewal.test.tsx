/**
 * @vitest-environment jsdom
 *
 * "When do my credits come back, and how many" - the one question a wallet cannot answer
 * from its own balance, and the one this card had no answer for.
 *
 * What each test pins:
 *
 *  - THE TWO HALVES TRAVEL TOGETHER. A date with no amount answers half the question and an
 *    amount with no date is where the card already was, so the line renders only when both
 *    are known.
 *  - THE YEARLY CASE, which is the whole reason the date is served rather than derived: the
 *    invoice is annual and the credit pack is monthly, so the date on the Billing page is
 *    the wrong one for eleven months of every year. The card must show what the backend
 *    named, and must be able to explain why the two differ.
 *  - NO DATE, NO SENTENCE. The backend answers null for a subscription that is cancelled or
 *    out of good standing. Substituting a period end there would promise a renewal to
 *    somebody who has cancelled.
 *  - THE RULE IS BEHIND THE "i", NOT IN THE LINE. The line has to be readable at a glance;
 *    "unused credits are not carried over" is read once and remembered. It is present and
 *    not omitted, because a reader who believes the balance accumulates is budgeting against
 *    a number that will not exist next month.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import React from 'react';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';
import frMessages from '../../../messages/fr.json';

// The real fallback would mask a dropped locale argument: if it happened to return the
// locale the provider carries, omitting it downstream would still look correct.
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));

import { BalanceBreakdownCard } from '../BalanceBreakdown';

afterEach(cleanup);

/** 2026-10-14, the shape `/billing/me` serves: a LocalDateTime with no zone designator. */
const GRANT_DATE = '2026-10-14T23:53:09';
/** The next invoice of a YEARLY subscription: eleven months past the next credit grant. */
const YEARLY_INVOICE = '2027-09-14T23:53:09';

function renderCard(
  monthlyPlan?: {
    allowance: number;
    renewsAt?: string | null;
    periodEndsAt?: string | null;
    hasScheduledChange?: boolean;
  },
  locale: 'en' | 'fr' = 'en',
) {
  return render(
    <NextIntlClientProvider
      locale={locale}
      messages={(locale === 'fr' ? frMessages : messages) as Record<string, unknown>}
    >
      <BalanceBreakdownCard
        balance={9_779}
        subBalance={9_779}
        paygBalance={0}
        monthlyPlan={monthlyPlan}
      />
    </NextIntlClientProvider>,
  );
}

const line = () => screen.queryByTestId('subscription-renewal-line');
const openExplainer = () => fireEvent.click(screen.getByTestId('subscription-renewal-info'));

describe('the wallet card says when the credits come back, and how many', () => {
  it('states the amount and the date in one sentence', () => {
    renderCard({ allowance: 10_000, renewsAt: GRANT_DATE, periodEndsAt: GRANT_DATE });

    // The amount is the grant, exact rather than compact: it is the answer to "how many",
    // and "10.0K" is a rounding of an answer people plan against.
    //
    // "Back to", never "+". The bucket is ZEROED by resetBalance before the re-grant, so a
    // subscriber holding 8,000 of a 10,000 grant ends the day with 10,000, not 18,000. The
    // plus sign asserted the arithmetic the mechanism does not perform.
    expect(line()!.textContent).toBe('Back to 10,000 credits on Oct 14, 2026 UTC');
    expect(line()!.textContent).not.toContain('+');
  });

  it('groups the amount in the app locale, not the browser one', () => {
    renderCard({ allowance: 100_000, renewsAt: GRANT_DATE, periodEndsAt: YEARLY_INVOICE }, 'fr');

    // French groups with a narrow no-break space and names the month in French. Asserting
    // the digits and the month separately keeps the test on the LOCALE and off the exact
    // separator codepoint, which is an Intl implementation detail.
    expect(line()!.textContent).toContain('100');
    expect(line()!.textContent).toContain('oct.');
    expect(line()!.textContent).not.toContain('100,000');
  });

  it('says nothing at all when the backend named no date', () => {
    // Cancelled, past due, or incomplete: no further grant is owed. The cancel banner on
    // the Billing page already says when access ends, and a renewal promised here would
    // contradict it.
    renderCard({ allowance: 10_000, renewsAt: null, periodEndsAt: GRANT_DATE });

    expect(line()).toBeNull();
    expect(screen.queryByTestId('subscription-renewal-info')).toBeNull();
  });

  it('says nothing when there is no allowance to name, even with a date', () => {
    // CE, or a guest whose wallet belongs to the workspace owner. Half a sentence about an
    // account we could not read is worse than silence.
    renderCard({ allowance: 0, renewsAt: GRANT_DATE, periodEndsAt: GRANT_DATE });

    expect(line()).toBeNull();
  });

  it('says nothing while a plan or tier change is scheduled, whose amount it cannot price', () => {
    // `allowance` is the tier in force TODAY. With a downgrade to tier 1 already scheduled,
    // this card would name the right date beside the wrong number: "+100,000 credits on 14
    // Oct" to somebody who receives 10,000 that day. The Billing page stands down under the
    // same condition, and nothing on either surface can price the target tier.
    renderCard({
      allowance: 100_000,
      renewsAt: GRANT_DATE,
      periodEndsAt: YEARLY_INVOICE,
      hasScheduledChange: true,
    });

    expect(line()).toBeNull();
    expect(screen.queryByTestId('subscription-renewal-info')).toBeNull();
  });

  it('still draws the line when no change is scheduled, so the guard is not always-on', () => {
    renderCard({
      allowance: 100_000,
      renewsAt: GRANT_DATE,
      periodEndsAt: YEARLY_INVOICE,
      hasScheduledChange: false,
    });

    expect(line()).not.toBeNull();
  });

  it('says nothing when the card is given no plan at all', () => {
    renderCard(undefined);

    expect(line()).toBeNull();
  });

  it('ignores a date it cannot parse instead of printing a placeholder', () => {
    renderCard({ allowance: 10_000, renewsAt: 'not-a-date', periodEndsAt: GRANT_DATE });

    expect(line()).toBeNull();
  });
});

describe('the "i" carries the rules, the line carries the facts', () => {
  it('states that unused subscription credits are replaced, not accumulated', () => {
    renderCard({ allowance: 10_000, renewsAt: GRANT_DATE, periodEndsAt: GRANT_DATE });
    openExplainer();

    const popover = screen.getByTestId('subscription-renewal-popover');
    expect(popover.textContent).toContain('not carried over');
    expect(popover.textContent).toContain('replaced, not increased');
  });

  it('states that top-up credits survive the renewal, so the two buckets are not confused', () => {
    renderCard({ allowance: 10_000, renewsAt: GRANT_DATE, periodEndsAt: GRANT_DATE });
    openExplainer();

    expect(screen.getByTestId('subscription-renewal-popover').textContent)
      .toContain('Top-up credits are not affected');
  });

  it('repeats the amount and the date inside, so the popover stands on its own', () => {
    renderCard({ allowance: 10_000, renewsAt: GRANT_DATE, periodEndsAt: GRANT_DATE });
    openExplainer();

    expect(screen.getByTestId('subscription-renewal-popover').textContent)
      .toContain('subscription balance returns to 10,000 credits on Oct 14, 2026 UTC');
  });

  it('explains the annual invoice / monthly grant split, naming the invoice date', () => {
    // The fact a yearly subscriber could not get anywhere in the product: the Billing page
    // showed one date, eleven months from the one that matters for credits. The sentence
    // names BOTH, so the reader can see which is which rather than being told a rule.
    renderCard({ allowance: 100_000, renewsAt: GRANT_DATE, periodEndsAt: YEARLY_INVOICE });
    openExplainer();

    expect(screen.getByTestId('subscription-renewal-popover').textContent)
      .toContain('You are billed on Sep 14, 2027 UTC, but your credits are granted every month');
  });

  it('does NOT explain a split that does not exist when the two dates are one event', () => {
    // On a monthly plan the invoice and the grant are the same instant. Stating the
    // distinction there would invent a difference the reader does not have.
    renderCard({ allowance: 10_000, renewsAt: GRANT_DATE, periodEndsAt: GRANT_DATE });
    openExplainer();

    expect(screen.getByTestId('subscription-renewal-popover').textContent)
      .not.toContain('You are billed on');
  });

  it('says nothing about billing when no invoice date is known, rather than guessing', () => {
    renderCard({ allowance: 10_000, renewsAt: GRANT_DATE, periodEndsAt: null });
    openExplainer();

    expect(screen.getByTestId('subscription-renewal-popover').textContent)
      .not.toContain('You are billed on');
  });

  it('says nothing about billing when the invoice date is unparseable', () => {
    // The credit line still renders: one bad value must not take the answer with it.
    renderCard({ allowance: 10_000, renewsAt: GRANT_DATE, periodEndsAt: 'not-a-date' });
    expect(line()).not.toBeNull();
    openExplainer();

    expect(screen.getByTestId('subscription-renewal-popover').textContent)
      .not.toContain('You are billed on');
  });

  it('keeps the rules out of the line itself - the glance stays short', () => {
    renderCard({ allowance: 10_000, renewsAt: GRANT_DATE, periodEndsAt: YEARLY_INVOICE });

    // Before the click, nothing but the one sentence is on screen.
    expect(screen.queryByTestId('subscription-renewal-popover')).toBeNull();
    expect(line()!.textContent).not.toContain('not carried over');
  });
});
