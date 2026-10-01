// @vitest-environment jsdom
/**
 * The admin side of the Partner Program Terms (V557): each partner code says whether its owner
 * accepted the terms (and which version), and a payout to a partner who never accepted is
 * refused with a reason the admin can act on, never a generic failure.
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import { PARTNER_TERMS_VERSION } from '@/lib/partners/terms';

const overview = vi.fn();
const markPaid = vi.fn();

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
    markPaid: (id: number) => markPaid(id),
    grantFounder: vi.fn(),
    createPartnerCode: vi.fn(),
    createCreatorCode: vi.fn(),
    setActive: vi.fn(),
    applications: () => Promise.resolve({ applications: [] }),
    approveApplication: vi.fn(),
    rejectApplication: vi.fn(),
  },
}));

import PartnersAdminPage from '../page';

const DEFAULTS = {
  creatorPlanCode: 'PRO', creatorPlanDays: 90, creatorCredits: 50000, creatorMaxUses: 1, creatorValidDays: 60,
  audienceCredits: 8000, commissionBps: 3000, commissionMonths: 12, holdDays: 14,
};

function row(overrides: Record<string, unknown>) {
  return {
    id: 9, code: 'AGENCY', kind: 'partner', label: null, owner_user_id: 42, owner_email: 'p@acme.io',
    credits: 8000, plan_code: null, plan_days: 0, max_uses: null, commission_percent: 30, commission_months: 12,
    hold_days: 14, active: true, valid_until: null, created_at: null, redemptions: 3, paying_customers: 2,
    commissions: { on_hold: {}, payable: { usd: 1200 }, paid: {}, voided: {} },
    standing: null, effective_commission_percent: 30,
    terms_accepted_version: null, terms_accepted_at: null,
    ...overrides,
  };
}

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

describe('Admin > Partners: Partner Program Terms', () => {
  beforeEach(() => {
    overview.mockReset();
    markPaid.mockReset();
  });
  afterEach(cleanup);

  it('a partner who never accepted the terms is flagged: no payout is possible', async () => {
    overview.mockResolvedValue({ codes: [row({})], defaults: DEFAULTS, founder_open: false });
    renderPage();

    await waitFor(() => expect(screen.getByTestId('partner-code-terms').textContent)
      .toBe(enMessages.partnerProgram.list.termsMissing));
  });

  it('a partner who accepted shows the version and the date', async () => {
    overview.mockResolvedValue({
      codes: [row({ terms_accepted_version: PARTNER_TERMS_VERSION, terms_accepted_at: '2026-10-02T09:00:00Z' })],
      defaults: DEFAULTS, founder_open: false,
    });
    renderPage();

    const terms = await screen.findByTestId('partner-code-terms');
    expect(terms.textContent).toContain(`Terms ${PARTNER_TERMS_VERSION} accepted on`);
    expect(terms.textContent).toContain('2026');
  });

  it('a partner bound by an older version is flagged: still paid, but due to accept the current one', async () => {
    overview.mockResolvedValue({
      codes: [row({ terms_accepted_version: '2026-01-01', terms_accepted_at: '2026-01-02T09:00:00Z' })],
      defaults: DEFAULTS, founder_open: false,
    });
    renderPage();

    const terms = await screen.findByTestId('partner-code-terms');
    expect(terms.textContent).toContain('Terms 2026-01-01 accepted on');
    expect(terms.textContent).toContain('not the current version');
    expect(terms.className).toContain('text-amber-600');
  });

  it('a creator code carries no terms line: only partners sign them', async () => {
    overview.mockResolvedValue({ codes: [row({ kind: 'creator', commissions: { on_hold: {}, payable: {}, paid: {}, voided: {} } })], defaults: DEFAULTS, founder_open: false });
    renderPage();

    await waitFor(() => expect(screen.getByText('AGENCY')).toBeTruthy());
    expect(screen.queryByTestId('partner-code-terms')).toBeNull();
  });

  it('marking a payout for a partner without the terms explains the refusal instead of a generic error', async () => {
    overview.mockResolvedValue({ codes: [row({})], defaults: DEFAULTS, founder_open: false });
    const { ApiError } = await import('@/lib/api/api-client');
    markPaid.mockRejectedValue(new ApiError('conflict', 409, 'terms_not_accepted'));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: enMessages.partnerProgram.list.markPaid }));

    await waitFor(() => expect(markPaid).toHaveBeenCalledWith(9));
    expect(await screen.findByText(enMessages.partnerProgram.errors.terms_not_accepted)).toBeTruthy();
  });
});
