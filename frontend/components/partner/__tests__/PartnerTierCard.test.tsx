// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import { PartnerDashboard } from '../PartnerDashboard';
import type { PartnerAccount, PartnerStanding } from '@/lib/api/services/partner-program-api.service';

function account(standing: PartnerStanding | null): PartnerAccount {
  return {
    code: 'ACME', commission_percent: 40, commission_months: 12, hold_days: 14, audience_credits: 10000,
    valid_until: null, redemptions: 5, paying_customers: 2,
    commissions: { on_hold: {}, payable: {}, paid: {}, voided: {} }, lines: [], standing,
  };
}

function renderDashboard(standing: PartnerStanding | null, { effective = 40, settleDays = 60 as number | null } = {}) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <PartnerDashboard partner={{ ...account(standing), commission_percent: effective }} active onCopy={() => {}} settleDays={settleDays} />
    </NextIntlClientProvider>,
  );
}

describe('partner dashboard: the tier card', () => {
  afterEach(cleanup);

  it('shows the tier, its rate and how far the next tier is, on settled revenue', () => {
    renderDashboard({
      tier: 'gold', founder: false, revenue_minor: 600_000, currency: 'usd',
      next_tier: 'platinum', next_threshold_minor: 2_500_000, commission_percent: 40,
    });

    const card = screen.getByTestId('partner-tier');
    expect(within(card).getByText('Gold')).toBeTruthy();
    expect(within(card).getByText('40% on every paid invoice')).toBeTruthy();
    expect(within(card).getByText('$6,000.00 of $25,000.00 to reach Platinum')).toBeTruthy();
    expect(within(card).getByRole('progressbar').getAttribute('aria-valuenow')).toBe('24');
    expect(within(card).getByText('An invoice counts toward your tier once it is 60 days old and was not refunded. Your volume never lowers your tier.')).toBeTruthy();
  });

  it('regression: a partner whose code pays more than their tier sees the rate they actually earn, once', () => {
    // An early partner code created at 50%, the partner still Silver (30%).
    renderDashboard({
      tier: 'silver', founder: false, revenue_minor: 0, currency: 'usd',
      next_tier: 'gold', next_threshold_minor: 500_000, commission_percent: 30,
    }, { effective: 50 });

    const card = screen.getByTestId('partner-tier');
    expect(within(card).getByText('50% on every paid invoice')).toBeTruthy();
    expect(within(card).queryByText('30% on every paid invoice')).toBeNull();
  });

  it('without the settle window (older terms) the rule is stated without a figure', () => {
    renderDashboard({
      tier: 'silver', founder: false, revenue_minor: 0, currency: 'usd',
      next_tier: 'gold', next_threshold_minor: 500_000, commission_percent: 30,
    }, { settleDays: null });

    expect(screen.getByText('An invoice counts toward your tier once it can no longer be refunded. Your volume never lowers your tier.')).toBeTruthy();
  });

  it('at the top there is nothing left to reach, and a founder is told it is for life', () => {
    renderDashboard({
      tier: 'platinum', founder: true, revenue_minor: 0, currency: 'usd',
      next_tier: null, next_threshold_minor: null, commission_percent: 50,
    });

    const card = screen.getByTestId('partner-tier');
    expect(within(card).queryByRole('progressbar')).toBeNull();
    expect(within(card).getByText('Founding partner')).toBeTruthy();
    expect(within(card).getByText('Founding partner: Platinum for the whole of your participation, under the Partner Program Terms (clause 7.5).')).toBeTruthy();
  });

  it('a progress bar never overflows once the threshold is passed', () => {
    renderDashboard({
      tier: 'silver', founder: false, revenue_minor: 900_000, currency: 'usd',
      next_tier: 'gold', next_threshold_minor: 500_000, commission_percent: 30,
    });

    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('100');
  });

  it('an older payload without a standing simply has no tier card', () => {
    renderDashboard(null);

    expect(screen.queryByTestId('partner-tier')).toBeNull();
  });
});
