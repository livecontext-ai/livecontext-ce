// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import { PartnerEarningsCalculator } from '../PartnerEarningsCalculator';
import type { PartnerTierTerm } from '@/lib/partners/tiers';

const TIERS: PartnerTierTerm[] = [
  { tier: 'silver', commission_percent: 30, threshold_minor: 0 },
  { tier: 'gold', commission_percent: 40, threshold_minor: 500_000 },
  { tier: 'platinum', commission_percent: 50, threshold_minor: 2_500_000 },
];

function renderCalculator({ founderOpen = true, locale = 'en' }: { founderOpen?: boolean; locale?: string } = {}) {
  return render(
    <NextIntlClientProvider locale={locale} messages={locale === 'fr' ? frMessages : enMessages}>
      <PartnerEarningsCalculator tiers={TIERS} currency="usd" settleDays={60} founderOpen={founderOpen} founderUntilLabel="Jan 01, 2027" />
    </NextIntlClientProvider>,
  );
}

const value = (key: string) => screen.getByTestId(`partner-calc-${key}`).textContent;
const credits = () => screen.getByLabelText('Credits per month') as HTMLInputElement;
const plan = (name: string) => screen.getByRole('button', { name });

describe('PartnerEarningsCalculator', () => {
  afterEach(cleanup);

  it('starts on 20 clients on Pro with 250K credits, billed at the real list price: $209 each, $2,090 a month by month 12', () => {
    renderCalculator();

    // Pro $24 + 250K credits $185 = $209. $4,180 a month, an invoice counting once 60 days old:
    // $8,360 settled by month 4 (Gold), $25,080 by month 8 (Platinum).
    expect(plan('Pro').getAttribute('aria-pressed')).toBe('true');
    expect(value('bill')).toBe('$209 a month per client');
    expect(value('firstMonth')).toBe('$1,254');
    expect(value('lastMonth')).toBe('$2,090');
    expect(value('yearOne')).toBe('$20,900');
    expect(screen.getByTestId('partner-calc-path').textContent).toBe('Gold from month 4. Platinum from month 8.');
  });

  it('draws the year on a money axis rounded up to a round figure, with the rate of each tier in the legend', () => {
    renderCalculator();

    // The best month is $2,090: the axis tops at $2,500, halfway at $1,250, from $0.
    expect(screen.getByText('$2,500')).toBeTruthy();
    expect(screen.getByText('$1,250')).toBeTruthy();
    expect(screen.getByText('$0')).toBeTruthy();
    expect(screen.getByText('Silver 30%')).toBeTruthy();
    expect(screen.getByText('Gold 40%')).toBeTruthy();
    expect(screen.getByText('Platinum 50%')).toBeTruthy();
  });

  it('a bigger plan and more credits raise the bill to what that client really pays', () => {
    renderCalculator();

    fireEvent.click(plan('Team'));
    fireEvent.change(credits(), { target: { value: '7' } });

    // Team $49 + 1M credits $825.
    expect(value('bill')).toBe('$874 a month per client');
    expect(value('firstMonth')).toBe('$5,244');
  });

  it('Starter stops at its credit cap: switching to it pulls a larger tier back to 100K', () => {
    renderCalculator();

    fireEvent.change(credits(), { target: { value: '9' } });
    fireEvent.click(plan('Starter'));

    expect(credits().getAttribute('max')).toBe('4');
    expect(credits().value).toBe('4');
    // Starter $10 + 100K credits $80.
    expect(value('bill')).toBe('$90 a month per client');
  });

  it('few small clients stay Silver all year', () => {
    renderCalculator();

    fireEvent.change(screen.getByLabelText('Paying clients you bring'), { target: { value: '2' } });
    fireEvent.click(plan('Starter'));
    fireEvent.change(credits(), { target: { value: '0' } });

    expect(value('bill')).toBe('$10 a month per client');
    expect(value('firstMonth')).toBe('$6');
    expect(value('yearOne')).toBe('$72');
    expect(screen.getByTestId('partner-calc-path').textContent).toBe('You stay Silver this year: bring more clients to move up.');
  });

  it('as a founding partner the whole year runs at the Platinum rate', () => {
    renderCalculator();

    fireEvent.click(screen.getByLabelText('If the team names me a founding partner (before Jan 01, 2027)'));

    expect(value('firstMonth')).toBe('$2,090');
    expect(value('lastMonth')).toBe('$2,090');
    expect(value('yearOne')).toBe('$25,080');
    expect(screen.getByTestId('partner-calc-path').textContent).toBe('Platinum from day one, for life.');
  });

  it('once the founder window has closed, the founder switch is not offered', () => {
    renderCalculator({ founderOpen: false });

    expect(screen.queryByLabelText(/founding partner/)).toBeNull();
  });

  it('speaks the page language, amounts, rates and credit counts included', () => {
    renderCalculator({ locale: 'fr' });

    expect(screen.getByText('Clients payants que vous amenez')).toBeTruthy();
    expect(value('yearOne')).toMatch(/20\s900\s\$/);
    expect(screen.getByText('Silver 30 %')).toBeTruthy();
    expect(screen.getByText(/^250\sk$/)).toBeTruthy();
  });

  it('regression: the money axis widens with its longest label, so "$100,000" never runs into the bars', () => {
    renderCalculator();
    const axisOf = () => screen.getByText('$0').closest('[aria-hidden]') as HTMLElement;
    const small = parseInt(axisOf().style.paddingLeft, 10);

    fireEvent.change(screen.getByLabelText('Paying clients you bring'), { target: { value: '200' } });
    fireEvent.click(plan('Team'));
    fireEvent.change(credits(), { target: { value: String(Number(credits().max)) } });

    const labels = Array.from(axisOf().querySelectorAll('span')).map((n) => n.textContent ?? '');
    const top = labels.sort((a, b) => b.length - a.length)[0];
    expect(top).toMatch(/^\$\d{1,3}(,\d{3})+$/);
    const wide = parseInt(axisOf().style.paddingLeft, 10);
    expect(wide).toBeGreaterThan(small);
    // About 7px a character at text-xs: the longest label fits in the padding.
    expect(wide).toBeGreaterThanOrEqual(top.length * 7);
  });
});
