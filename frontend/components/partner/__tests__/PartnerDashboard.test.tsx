// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import type { PartnerAccount, PartnerMonth, PartnerStanding } from '@/lib/api/services/partner-program-api.service';
import type { PartnerTierTerm } from '@/lib/partners/tiers';

vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));
// The link builder lists the partner's offers: none here.
vi.mock('@/lib/api/services/partner-program-api.service', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  partnerProgramApi: { offers: async () => ({ offers: [] }), createOffer: vi.fn(), deactivateOffer: vi.fn() },
}));

import { SITE_URL } from '@/lib/seo/siteUrl';
import { earningsSeries, monthLabel, PartnerDashboard } from '../PartnerDashboard';

const TIERS: PartnerTierTerm[] = [
  { tier: 'silver', commission_percent: 30, threshold_minor: 0 },
  { tier: 'gold', commission_percent: 40, threshold_minor: 500_000 },
  { tier: 'platinum', commission_percent: 50, threshold_minor: 2_500_000 },
];

const months = (values: Record<string, number>[]): PartnerMonth[] =>
  values.map((commissions, i) => ({ month: `2026-${String(i + 1).padStart(2, '0')}`, commissions }));

function account(overrides: Partial<PartnerAccount> = {}): PartnerAccount {
  return {
    code: 'ACME', commission_percent: 40, commission_months: 12, hold_days: 14, audience_credits: 10000,
    valid_until: null, redemptions: 5, paying_customers: 2,
    commissions: { on_hold: {}, payable: {}, paid: {}, voided: {} }, lines: [], standing: null,
    ...overrides,
  };
}

function renderDashboard(partner: PartnerAccount, { tiers = [] as PartnerTierTerm[], active = true, locale = 'en' } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale={locale} messages={locale === 'fr' ? frMessages : enMessages}>
        <PartnerDashboard partner={partner} active={active} onCopy={() => {}} settleDays={60} tiers={tiers} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('earningsSeries: what the chart draws', () => {
  it('draws the currency the partner earned most in, in minor units, oldest month first', () => {
    const series = earningsSeries(months([{ usd: 100 }, { eur: 900, usd: 200 }, { eur: 50 }]), 'usd');

    expect(series).toEqual({
      currency: 'eur', months: ['2026-01', '2026-02', '2026-03'], minor: [0, 900, 50],
      // The printed figures keep every currency.
      thisMonth: { eur: 50 }, totals: { usd: 300, eur: 950 },
    });
  });

  it('an empty year keeps the standing currency, every month at zero', () => {
    expect(earningsSeries(months([{}, {}]), 'EUR')).toEqual({
      currency: 'eur', months: ['2026-01', '2026-02'], minor: [0, 0], thisMonth: {}, totals: {},
    });
  });

  it('an older backend (no months) or a single month draws nothing', () => {
    expect(earningsSeries(undefined, 'usd')).toBeNull();
    expect(earningsSeries(months([{ usd: 5 }]), 'usd')).toBeNull();
  });
});

describe('monthLabel', () => {
  it('reads the month in the app locale, never the browser one', () => {
    expect(monthLabel('2026-11', 'en')).toBe('Nov 26');
    expect(monthLabel('2026-11', 'fr')).toBe('nov. 26');
  });

  it('a malformed month is printed as it came, not as "Invalid Date"', () => {
    expect(monthLabel('soon', 'en')).toBe('soon');
  });
});

describe('the partner dashboard hero', () => {
  afterEach(cleanup);

  it('shows the commission of the current (last) month and the year total on the chart card', () => {
    renderDashboard(account({ months: months([{ usd: 1000 }, { usd: 0 }, { usd: 4550 }]) }));

    expect(screen.getByTestId('partner-month-earned').textContent).toBe('$45.50');
    expect(screen.getByTestId('partner-earnings-total').textContent).toBe('$55.50 over 12 months');
    expect(screen.getByRole('img', { name: 'Your commission each month, from Jan 26 to Mar 26' })).toBeTruthy();
  });

  it('regression: a partner paid in two currencies reads this month and the year in both, never "0" for the other one', () => {
    // Mostly euros over the year (the chart's currency), but this month paid in dollars only.
    renderDashboard(account({ months: months([{ eur: 90000 }, { eur: 10000 }, { usd: 500 }]) }));

    expect(screen.getByTestId('partner-month-earned').textContent).toBe('$5.00');
    expect(screen.getByTestId('partner-earnings-total').textContent).toBe('€1,000.00, $5.00 over 12 months');
  });

  it('a partner without a paying client yet sees an empty, dimmed chart that says where the curve starts', () => {
    renderDashboard(account({ months: months([{}, {}, {}]) }));

    expect(screen.getByTestId('partner-earnings-empty').textContent).toBe('Your curve starts with your first paying client.');
    expect(screen.queryByTestId('partner-earnings-total')).toBeNull();
    expect(screen.getByTestId('partner-month-earned').textContent).toBe('$0.00');
  });

  it('an older backend without months shows no chart and no month figure, the rest of the hero stays', () => {
    renderDashboard(account());

    expect(screen.queryByTestId('partner-earnings')).toBeNull();
    expect(screen.queryByTestId('partner-month-earned')).toBeNull();
    // On the public site, not on the address the page is open on (jsdom: localhost).
    expect(screen.getByTestId('partner-link').textContent).toBe(`${SITE_URL}/?lc_ref=ACME`);
    expect(screen.getByTestId('partner-link').textContent).not.toContain(window.location.host);
  });

  it('nothing payable: no payable line in the hero, and the payable tile reads "-"', () => {
    renderDashboard(account({ months: months([{}, {}]) }));

    expect(screen.queryByTestId('partner-hero-payable')).toBeNull();
    expect(screen.getByTestId('partner-stat-payable').textContent).toContain('-');
  });

  it('an active partner can build a link for a client, with their code and its offer', () => {
    renderDashboard(account());

    const builder = screen.getByTestId('partner-link-builder');
    expect(within(builder).getByTestId('builder-offer').textContent).toBe('Plus 10,000 free credits with your code ACME');
  });

  it('a used-up code (every allowed sign-up taken) builds no link: it would promise credits it no longer gives', () => {
    renderDashboard(account({ max_uses: 5, redemptions: 5 }));

    expect(screen.queryByTestId('partner-link-builder')).toBeNull();
  });

  it('a code with sign-ups left still builds links', () => {
    renderDashboard(account({ max_uses: 5, redemptions: 4 }));

    expect(screen.getByTestId('partner-link-builder')).toBeTruthy();
  });

  it('when the code states no rate, the builder estimates the commission on the tier rate', () => {
    renderDashboard(account({
      commission_percent: null,
      standing: { tier: 'gold', founder: false, revenue_minor: 600_000, currency: 'usd', next_tier: 'platinum', next_threshold_minor: 2_500_000, commission_percent: 40 },
    }));

    expect(within(screen.getByTestId('partner-link-builder')).getByTestId('builder-commission').textContent)
      .toContain('About $84 a month in commission on this client');
  });

  it('an inactive code builds no link: it would bring sign-ups to nobody', () => {
    renderDashboard(account(), { active: false });

    expect(screen.queryByTestId('partner-link-builder')).toBeNull();
  });

  it('the conditions list the duration, the referral credits and the refund window', () => {
    renderDashboard(account());

    const conditions = screen.getByTestId('partner-conditions');
    expect(within(conditions).getByText('For 12 months per customer')).toBeTruthy();
    expect(within(conditions).getByText('10,000 free credits for each client you bring')).toBeTruthy();
    expect(within(conditions).getByText('Payable after a 14-day refund window')).toBeTruthy();
    expect(screen.queryByTestId('partner-code-valid-until')).toBeNull();
    expect(screen.queryByTestId('partner-code-max-uses')).toBeNull();
  });

  it('French: amounts and months in the French format', () => {
    renderDashboard(account({ months: months([{ eur: 0 }, { eur: 123456 }]), standing: null }), { locale: 'fr' });

    expect(screen.getByTestId('partner-month-earned').textContent).toMatch(/^1\s234,56\s€$/);
    expect(screen.getByRole('img', { name: 'Votre commission chaque mois, de janv. 26 à févr. 26' })).toBeTruthy();
  });
});

describe('the tier ladder', () => {
  afterEach(cleanup);

  const standing = (overrides: Partial<PartnerStanding>): PartnerStanding => ({
    tier: 'gold', founder: false, revenue_minor: 600_000, currency: 'usd',
    next_tier: 'platinum', next_threshold_minor: 2_500_000, commission_percent: 40, ...overrides,
  });
  const reached = () => [...document.querySelectorAll('[data-tier-node]')].map((n) => n.getAttribute('data-reached'));

  it('Gold on the way to Platinum: Silver and Gold reached, the Gold to Platinum segment is the progress bar', () => {
    renderDashboard(account({ standing: standing({}) }), { tiers: TIERS });

    const ladder = screen.getByTestId('partner-tier-ladder');
    expect(reached()).toEqual(['true', 'true', 'false']);
    const bar = within(ladder).getByRole('progressbar');
    // $6,000 on the $5,000 to $25,000 step: 5% of THAT step, read out as the sentence below it.
    expect(bar.getAttribute('aria-valuemin')).toBe('500000');
    expect(bar.getAttribute('aria-valuemax')).toBe('2500000');
    expect(bar.getAttribute('aria-valuenow')).toBe('600000');
    expect(bar.getAttribute('aria-valuetext')).toBe('$6,000.00 of $25,000.00 to reach Platinum');
    expect((bar.firstElementChild as HTMLElement).style.width).toBe('5%');
    expect(bar.getAttribute('aria-label')).toBe('Progress to Platinum');
    expect(ladder.textContent).toContain('Silver30%From day one');
    expect(ladder.textContent).toContain('Gold40%From $5,000You are here');
    expect(within(ladder).getByTestId('partner-tier-here').textContent).toBe('You are here');
  });

  it('Silver: only the first node reached, the Silver to Gold segment carries the progress', () => {
    renderDashboard(account({ standing: standing({ tier: 'silver', revenue_minor: 100_000, next_tier: 'gold', next_threshold_minor: 500_000 }) }), { tiers: TIERS });

    expect(reached()).toEqual(['true', 'false', 'false']);
    const bar = screen.getByRole('progressbar');
    expect(bar.getAttribute('aria-valuemin')).toBe('0');
    expect(bar.getAttribute('aria-valuenow')).toBe('100000');
    expect((bar.firstElementChild as HTMLElement).style.width).toBe('20%');
  });

  it('regression: a partner who has just reached Gold sees an empty Gold to Platinum segment, not one a fifth full', () => {
    renderDashboard(account({ standing: standing({ revenue_minor: 500_000 }) }), { tiers: TIERS });

    expect((screen.getByRole('progressbar').firstElementChild as HTMLElement).style.width).toBe('0%');
  });

  it('a founder is Platinum for good: every node reached, nothing left to progress toward', () => {
    renderDashboard(account({ standing: standing({ tier: 'platinum', founder: true, next_tier: null, next_threshold_minor: null }) }), { tiers: TIERS });

    expect(reached()).toEqual(['true', 'true', 'true']);
    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  it('without the program tiers (older terms) the single progress bar remains', () => {
    renderDashboard(account({ standing: standing({}) }));

    expect(screen.queryByTestId('partner-tier-ladder')).toBeNull();
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('24');
    // Read out as the sentence under it, like the ladder's bar.
    expect(screen.getByRole('progressbar').getAttribute('aria-valuetext')).toBe('$6,000.00 of $25,000.00 to reach Platinum');
  });
});
