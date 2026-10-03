// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import type { PartnerAgreement, PartnerDashboardResponse, PartnerProgramTerms } from '@/lib/api/services/partner-program-api.service';
import { PARTNER_TERMS_VERSION } from '@/lib/partners/terms';

let mockIsCe = false;
const me = vi.fn();
const apply = vi.fn();
const acceptTerms = vi.fn();

vi.mock('@/lib/edition', () => ({
  get IS_CE() { return mockIsCe; },
  get IS_MANAGED_CLOUD() { return !mockIsCe; },
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false, isLoading: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn() }),
  // The offer apps picker reads the account optionally.
  useOptionalAuth: () => ({ isAuthenticated: true, isReady: true, isLoading: false, numericUserId: 42 }),
}));
vi.mock('@/lib/api/services/partner-program-api.service', () => ({
  PARTNER_DASHBOARD_QUERY_KEY: ['partner-program', 'me'],
  PARTNER_OFFERS_QUERY_KEY: ['partner-program', 'offers'],
  partnerProgramApi: {
    me: () => me(),
    offers: async () => ({ offers: [] }),
    apply: (body: unknown) => apply(body),
    acceptTerms: (version: string) => acceptTerms(version),
  },
}));

import PartnerSettingsPage from '../page';

const TERMS: PartnerProgramTerms = {
  commission_percent: 50, commission_months: 12, hold_days: 14, audience_credits: 10000,
  tiers: [], tier_currency: 'usd', tier_settle_days: null, founder_until: null, founder_open: false,
};
const TIERS = [
  { tier: 'silver' as const, commission_percent: 30, threshold_minor: 0 },
  { tier: 'gold' as const, commission_percent: 40, threshold_minor: 500_000 },
  { tier: 'platinum' as const, commission_percent: 50, threshold_minor: 2_500_000 },
];

function dashboard(overrides: Partial<PartnerDashboardResponse>): PartnerDashboardResponse {
  return { state: 'none', terms: TERMS, application: null, partner: null, ...overrides };
}

const APPLICATION = {
  id: 3, status: 'pending' as const, company_name: 'Acme Automation', website: null, audience: null,
  message: null, decision_note: null, created_at: '2026-09-20T10:00:00Z', reviewed_at: null,
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <PartnerSettingsPage />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('Settings > Partner program', () => {
  beforeEach(() => {
    mockIsCe = false;
    me.mockReset();
    apply.mockReset();
    acceptTerms.mockReset();
  });
  afterEach(cleanup);

  it('self-hosted: explains the program is a cloud one and never calls the backend', () => {
    mockIsCe = true;
    renderPage();

    expect(screen.getByText('The partner program is a cloud feature')).toBeTruthy();
    expect(me).not.toHaveBeenCalled();
  });

  it('never applied: what the program pays and a way to the form on /partners, and no form here', async () => {
    me.mockResolvedValue(dashboard({ state: 'none' }));
    renderPage();

    await waitFor(() => expect(screen.getByTestId('partner-terms')).toBeTruthy());
    expect(screen.getByText('50% of every paid invoice')).toBeTruthy();
    expect(screen.getByText('For 12 months per customer')).toBeTruthy();
    expect(screen.getByText('10,000 free credits for each client you bring')).toBeTruthy();
    expect(screen.getByTestId('partner-join').textContent).toContain('Earn up to 50% of every invoice your clients pay');
    const cta = screen.getByTestId('partner-join-apply');
    expect(cta.textContent).toBe('Apply');
    expect(cta.getAttribute('href')).toBe('/partners#apply');
    // /partners holds the only application form.
    expect(screen.queryByLabelText('Company or brand')).toBeNull();
    expect(screen.queryByRole('button', { name: /Submit my application/ })).toBeNull();
    expect(apply).not.toHaveBeenCalled();
  });

  it('with tiers, the band headlines the top rate and lays out the three tiers with their thresholds', async () => {
    me.mockResolvedValue(dashboard({ state: 'none', terms: { ...TERMS, commission_percent: 30, tiers: TIERS } }));
    renderPage();

    const tiers = await screen.findByTestId('partner-join-tiers');
    expect(screen.getByTestId('partner-join').textContent).toContain('Earn up to 50% of every invoice your clients pay');
    expect(tiers.querySelectorAll('li')).toHaveLength(3);
    expect(tiers.textContent).toContain('SilverFrom day one30%');
    expect(tiers.textContent).toContain('GoldFrom $5,000 in client revenue40%');
    expect(tiers.textContent).toContain('PlatinumFrom $25,000 in client revenue50%');
  });

  describe('V557 Partner Program Terms on the partner page', () => {
    const PARTNER = {
      code: 'ACME', commission_percent: 30, commission_months: 12, hold_days: 14, audience_credits: 10000,
      valid_until: null, redemptions: 0, paying_customers: 0,
      commissions: { on_hold: {}, payable: {}, paid: {}, voided: {} }, lines: [], standing: null,
    };
    const agreement = (overrides: Partial<PartnerAgreement>): PartnerAgreement => ({
      current_version: PARTNER_TERMS_VERSION, accepted_version: null, accepted_at: null,
      accepted_current: false, required: true, payouts_blocked: true, ...overrides,
    });

    it('a partner who never accepted is asked to, and told plainly that nothing is paid until then', async () => {
      me.mockResolvedValue(dashboard({ state: 'active', partner: PARTNER as never, agreement: agreement({}) }));
      renderPage();

      const notice = await screen.findByTestId('partner-terms-notice');
      expect(notice.textContent).toContain('Accept the Partner Program Terms');
      expect(notice.textContent).toContain('no commission can be paid out to you');
      expect(screen.getByRole('link', { name: 'Partner Program Terms' }).getAttribute('href')).toBe('/legal/partners');
    });

    it('accepting sends the version the partner read, then the page reloads the standing', async () => {
      me.mockResolvedValueOnce(dashboard({ state: 'active', partner: PARTNER as never, agreement: agreement({}) }))
        .mockResolvedValue(dashboard({ state: 'active', partner: PARTNER as never, agreement: agreement({
          accepted_version: PARTNER_TERMS_VERSION, accepted_at: '2026-10-02T09:00:00Z', accepted_current: true,
          required: false, payouts_blocked: false,
        }) }));
      acceptTerms.mockResolvedValue({ success: true });
      renderPage();

      fireEvent.click(await screen.findByTestId('partner-terms-accept'));

      await waitFor(() => expect(acceptTerms).toHaveBeenCalledWith(PARTNER_TERMS_VERSION));
      await waitFor(() => expect(screen.queryByTestId('partner-terms-notice')).toBeNull());
      expect(screen.getByTestId('partner-terms-accepted').textContent).toContain(PARTNER_TERMS_VERSION);
    });

    it('a partner on an older version sees the update notice, and stays paid meanwhile (no blocked wording)', async () => {
      me.mockResolvedValue(dashboard({ state: 'active', partner: PARTNER as never, agreement: agreement({
        accepted_version: '2026-01-01', accepted_at: '2026-01-02T00:00:00Z', payouts_blocked: false,
      }) }));
      renderPage();

      const notice = await screen.findByTestId('partner-terms-notice');
      expect(notice.textContent).toContain('The Partner Program Terms have changed');
      expect(notice.textContent).not.toContain('no commission can be paid out');
    });

    it('a refused acceptance (terms changed meanwhile) is explained, not swallowed', async () => {
      me.mockResolvedValue(dashboard({ state: 'active', partner: PARTNER as never, agreement: agreement({}) }));
      const { ApiError } = await import('@/lib/api/api-client');
      acceptTerms.mockRejectedValue(new ApiError('conflict', 409, 'terms_outdated'));
      renderPage();

      fireEvent.click(await screen.findByTestId('partner-terms-accept'));

      await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(enMessages.partnerDashboard.errors.terms_outdated));
    });

    it('an older backend without the agreement block shows no notice at all', async () => {
      me.mockResolvedValue(dashboard({ state: 'active', partner: PARTNER as never }));
      renderPage();

      await waitFor(() => expect(me).toHaveBeenCalled());
      expect(screen.queryByTestId('partner-terms-notice')).toBeNull();
      expect(screen.queryByTestId('partner-terms-accepted')).toBeNull();
    });
  });

  it('V556: with tiers, the terms state the range from the entry rate to the top tier', async () => {
    me.mockResolvedValue(dashboard({ state: 'none', terms: { ...TERMS, commission_percent: 30, tiers: TIERS } }));
    renderPage();

    await waitFor(() => expect(screen.getByText('30% to 50% of every paid invoice, by tier')).toBeTruthy());
  });

  it('shows a fractional rate in the reader\'s number format', async () => {
    me.mockResolvedValue(dashboard({ state: 'none', terms: { ...TERMS, commission_percent: 12.5 } }));
    renderPage();

    await waitFor(() => expect(screen.getByText('12.5% of every paid invoice')).toBeTruthy());
  });

  it('rejected: shows the admin note and sends the user back to the form on /partners', async () => {
    me.mockResolvedValue(dashboard({
      state: 'rejected',
      application: { ...APPLICATION, status: 'rejected', decision_note: 'Come back with a first client' },
    }));
    renderPage();

    await waitFor(() => expect(screen.getByTestId('partner-rejected')).toBeTruthy());
    expect(screen.getByText('Our note: Come back with a first client')).toBeTruthy();
    expect(screen.getByText('You can apply again whenever you like: tell us what has changed since.')).toBeTruthy();
    const cta = screen.getByTestId('partner-join-apply');
    expect(cta.textContent).toBe('Apply again');
    expect(cta.getAttribute('href')).toBe('/partners#apply');
    expect(screen.queryByLabelText('Company or brand')).toBeNull();
  });

  it('pending: the review as a track, the current step marked, and no form', async () => {
    me.mockResolvedValue(dashboard({ state: 'pending', application: APPLICATION }));
    renderPage();

    await waitFor(() => expect(screen.getByTestId('partner-pending')).toBeTruthy());
    expect(screen.getByText(/We are reviewing the application for Acme Automation/)).toBeTruthy();
    const steps = screen.getByTestId('partner-pending-steps').querySelectorAll('li');
    expect([...steps].map((li) => li.querySelector('.font-semibold')?.textContent))
      .toEqual(['Application sent', 'Under review', 'Answer by e-mail']);
    expect(steps[1].getAttribute('aria-current')).toBe('step');
    expect(steps[0].textContent).toContain('Submitted on');
    expect(screen.queryByTestId('partner-join-apply')).toBeNull();
  });

  it('active partner: link, per-partner rate, counters and commission lines, and no form', async () => {
    me.mockResolvedValue(dashboard({
      state: 'active',
      partner: {
        code: 'ACME', commission_percent: 40, commission_months: 12, hold_days: 14, audience_credits: 10000,
        valid_until: '2027-06-30T00:00:00Z', max_uses: 25, redemptions: 5, paying_customers: 2,
        commissions: { on_hold: { usd: 800 }, payable: { usd: 1200 }, paid: { usd: 300 }, voided: {} },
        months: [
          { month: '2026-08', commissions: {} },
          { month: '2026-09', commissions: { usd: 2000 } },
          { month: '2026-10', commissions: { usd: 800 } },
        ],
        lines: [
          { invoice_paid_at: '2026-09-01T10:00:00Z', currency: 'usd', base_amount_minor: 2000, commission_minor: 800,
            status: 'on_hold', due_at: '2026-09-15T10:00:00Z', paid_at: null },
          { invoice_paid_at: '2026-08-01T10:00:00Z', currency: 'usd', base_amount_minor: 1000, commission_minor: 400,
            status: 'void', due_at: '2026-08-15T10:00:00Z', paid_at: null },
        ],
      },
    }));
    renderPage();

    await waitFor(() => expect(screen.getByTestId('partner-link')).toBeTruthy());
    expect(screen.getByTestId('partner-link').textContent).toMatch(/\/\?lc_ref=ACME$/);
    expect(screen.getByText('Your rate: 40% of every paid invoice, for 12 months per customer.')).toBeTruthy();
    // The conditions specific to this code are shown (terms 6.1: a specific condition binds once shown).
    expect(screen.getByTestId('partner-code-valid-until').textContent).toMatch(/^Your code brings new sign-ups until .*2027.$/);
    expect(screen.getByTestId('partner-code-max-uses').textContent).toBe('Your code can be used by up to 25 new accounts.');
    // How payouts work, as the terms set them (clauses 8 and 9): monthly, from USD 50, self-billed by default.
    expect(document.body.textContent).toContain('We pay by bank transfer, monthly, once your payable balance reaches USD 50, and issue the invoice for you (self-billing)');
    expect(screen.getByTestId('partner-stat-signups').textContent).toContain('5');
    expect(screen.getByTestId('partner-stat-payable').textContent).toContain('$12.00');
    expect(screen.getAllByTestId('partner-line')).toHaveLength(2);
    expect(screen.getByText('Voided (refund or dispute)')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Official partner' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Submit my application/ })).toBeNull();
    // The hero: what this month earned, what is ready to be paid, and the year as a chart.
    expect(screen.getByTestId('partner-month-earned').textContent).toBe('$8.00');
    expect(screen.getByTestId('partner-hero-payable').textContent).toBe('Ready to be paid: $12.00');
    expect(screen.getByTestId('partner-earnings-total').textContent).toBe('$28.00 over 12 months');
    expect(screen.getByRole('img', { name: 'Your commission each month, from Aug 26 to Oct 26' })).toBeTruthy();
    expect(screen.queryByTestId('partner-earnings-empty')).toBeNull();
    expect(screen.getAllByTestId('partner-line').map((row) => row.querySelector('[data-status]')?.getAttribute('data-status')))
      .toEqual(['on_hold', 'void']);
  });

  it('inactive partner: warns that sign-ups are no longer attributed and hides the badge', async () => {
    me.mockResolvedValue(dashboard({
      state: 'inactive',
      partner: {
        code: 'ACME', commission_percent: 50, commission_months: 12, hold_days: 14, audience_credits: 10000,
        valid_until: null, redemptions: 0, paying_customers: 0,
        commissions: { on_hold: {}, payable: {}, paid: {}, voided: {} }, lines: [],
      },
    }));
    renderPage();

    await waitFor(() => expect(screen.getByRole('status').textContent).toMatch(/Your partner code is inactive/));
    expect(screen.queryByRole('img', { name: 'Official partner' })).toBeNull();
    expect(screen.getByText(/No commission yet/)).toBeTruthy();
  });

  it('a failed load says so instead of rendering an empty page', async () => {
    me.mockRejectedValue(new Error('boom'));
    renderPage();

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/Could not load your partner program/));
  });
});
