// @vitest-environment jsdom
/**
 * The admin side of the partner tiers: each partner code shows its owner's tier and the rate it
 * earns now, and the founder grant (Platinum for life) takes two clicks and exists only while
 * the founder window is open.
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';

const overview = vi.fn();
const grantFounder = vi.fn();
const endFounder = vi.fn();
const applications = vi.fn();
const approveApplication = vi.fn();

vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false, isLoading: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: () => true, loginWithRedirect: vi.fn() }),
}));
vi.mock('@/lib/api/services/partner-admin-api.service', () => ({
  partnerAdminApi: {
    overview: () => overview(),
    grantFounder: (id: number) => grantFounder(id),
    endFounder: (id: number) => endFounder(id),
    createPartnerCode: vi.fn(),
    createCreatorCode: vi.fn(),
    setActive: vi.fn(),
    markPaid: vi.fn(),
    applications: () => applications(),
    approveApplication: (id: number, body: unknown) => approveApplication(id, body),
    rejectApplication: vi.fn(),
  },
}));

import PartnersAdminPage from '../page';

const DEFAULTS = {
  creatorPlanCode: 'PRO', creatorPlanDays: 90, creatorCredits: 50000, creatorMaxUses: 1, creatorValidDays: 60,
  audienceCredits: 10000, commissionBps: 3000, commissionMonths: 12, holdDays: 14,
};

function partnerRow(standing: Record<string, unknown>) {
  return {
    id: 9, code: 'AGENCY', kind: 'partner', label: null, owner_user_id: 42, owner_email: 'p@acme.io',
    credits: 10000, plan_code: null, plan_days: 0, max_uses: null, commission_percent: 30, commission_months: 12,
    hold_days: 14, active: true, valid_until: null, created_at: null, redemptions: 3, paying_customers: 2,
    commissions: { on_hold: {}, payable: {}, paid: {}, voided: {} },
    standing, effective_commission_percent: standing.commission_percent,
  };
}

const GOLD = { tier: 'gold', founder: false, revenue_minor: 600_000, currency: 'usd', next_tier: 'platinum', next_threshold_minor: 2_500_000, commission_percent: 40 };

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <PartnersAdminPage />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('Admin > Partners: tiers and founders', () => {
  beforeEach(() => {
    [overview, grantFounder, endFounder, applications, approveApplication].forEach((m) => m.mockReset());
    applications.mockResolvedValue({ applications: [] });
    overview.mockResolvedValue({ codes: [partnerRow(GOLD)], defaults: DEFAULTS, founder_open: true });
    grantFounder.mockResolvedValue({ success: true, standing: { ...GOLD, tier: 'platinum', founder: true } });
  });
  afterEach(cleanup);

  it('a partner code shows its owner\'s tier and the rate its next commission earns', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByTestId('partner-code-tier')).toBeTruthy());
    expect(within(screen.getByTestId('partner-code-tier')).getByText('Gold')).toBeTruthy();
    // The code was created at 30%, the Gold tier lifts it to 40%.
    expect(screen.getByText('10,000 credits per new user, 40% for 12 months')).toBeTruthy();
  });

  it('granting the founder tier takes two clicks: the first only asks to confirm', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Make founder' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Make founder' }));
    expect(grantFounder).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Confirm: Platinum for life' }));
    await waitFor(() => expect(grantFounder).toHaveBeenCalledWith(9));
  });

  it('no founder action once the window has closed, nor for a partner who already is one', async () => {
    overview.mockResolvedValue({ codes: [partnerRow(GOLD)], defaults: DEFAULTS, founder_open: false });
    renderPage();
    await waitFor(() => expect(screen.getByTestId('partner-code-tier')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Make founder' })).toBeNull();
    cleanup();

    overview.mockResolvedValue({
      codes: [partnerRow({ ...GOLD, tier: 'platinum', founder: true, commission_percent: 50 })],
      defaults: DEFAULTS, founder_open: true,
    });
    renderPage();
    await waitFor(() => expect(within(screen.getByTestId('partner-code-tier')).getByText('Founder')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Make founder' })).toBeNull();
  });

  it('V557: a founder can be returned to the earned tier (terms 7.5): two clicks, at any time, never offered to a non-founder', async () => {
    overview.mockResolvedValue({
      codes: [partnerRow({ ...GOLD, tier: 'platinum', founder: true, commission_percent: 50 })],
      defaults: DEFAULTS, founder_open: false,
    });
    endFounder.mockResolvedValue({ success: true, standing: GOLD });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'End founder status' }));
    expect(endFounder).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm: back to the earned tier' }));
    await waitFor(() => expect(endFounder).toHaveBeenCalledWith(9));
    expect(await screen.findByText('Founder status ended')).toBeTruthy();
    cleanup();

    overview.mockResolvedValue({ codes: [partnerRow(GOLD)], defaults: DEFAULTS, founder_open: true });
    renderPage();
    await waitFor(() => expect(screen.getByTestId('partner-code-tier')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'End founder status' })).toBeNull();
  });

  it('V557: ending founder status for a partner who no longer is one says so', async () => {
    overview.mockResolvedValue({
      codes: [partnerRow({ ...GOLD, tier: 'platinum', founder: true, commission_percent: 50 })],
      defaults: DEFAULTS, founder_open: true,
    });
    const { ApiError } = await import('@/lib/api/api-client');
    endFounder.mockRejectedValue(new ApiError('conflict', 409, 'not_founder'));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'End founder status' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm: back to the earned tier' }));

    expect(await screen.findByText('This partner is not a founding partner.')).toBeTruthy();
  });

  it('approving an application as a founder sends founder: true, offered only while the window is open', async () => {
    applications.mockResolvedValue({
      applications: [{
        id: 5, status: 'pending', company_name: 'Acme Automation', website: null, audience: null, message: null,
        decision_note: null, created_at: null, reviewed_at: null, user_id: 7, email: 'p@acme.io', reward_code_id: null,
      }],
    });
    approveApplication.mockResolvedValue({ success: true, application: {}, code: 'ACME', mailed: true });
    renderPage();

    await waitFor(() => expect(screen.getByLabelText('Founding partner (Platinum for life)')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Founding partner (Platinum for life)'));
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() => expect(approveApplication).toHaveBeenCalledWith(5, expect.objectContaining({ founder: true })));
  });

  it('without the window, approving never sends a founder flag', async () => {
    overview.mockResolvedValue({ codes: [], defaults: DEFAULTS, founder_open: false });
    applications.mockResolvedValue({
      applications: [{
        id: 5, status: 'pending', company_name: 'Acme Automation', website: null, audience: null, message: null,
        decision_note: null, created_at: null, reviewed_at: null, user_id: 7, email: 'p@acme.io', reward_code_id: null,
      }],
    });
    approveApplication.mockResolvedValue({ success: true, application: {}, code: 'ACME', mailed: true });
    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Approve' })).toBeTruthy());
    expect(screen.queryByLabelText('Founding partner (Platinum for life)')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() => expect(approveApplication).toHaveBeenCalled());
    expect(approveApplication.mock.calls[0][1].founder).toBeUndefined();
  });
});
