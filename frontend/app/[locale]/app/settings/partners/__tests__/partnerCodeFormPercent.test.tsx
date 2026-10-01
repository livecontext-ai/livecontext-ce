// @vitest-environment jsdom
/**
 * The admin "partner code" form, on the one field that moves money: the commission rate.
 * `Number("12,5")` is NaN, JSON sends NaN as null, and the backend reads null as "use the
 * default": a French admin typing 12,5 created a code at the default rate. These cases pin that
 * the form now reads the decimal comma and refuses anything else.
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';

const overview = vi.fn();
const createPartnerCode = vi.fn();

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
    createPartnerCode: (body: unknown) => createPartnerCode(body),
    createCreatorCode: vi.fn(),
    setActive: vi.fn(),
    markPaid: vi.fn(),
    applications: () => Promise.resolve({ applications: [] }),
    approveApplication: vi.fn(),
    rejectApplication: vi.fn(),
  },
}));

import PartnersAdminPage from '../page';

const DEFAULTS = {
  creatorPlanCode: 'PRO', creatorPlanDays: 90, creatorCredits: 50000, creatorMaxUses: 1, creatorValidDays: 60,
  audienceCredits: 10000, commissionBps: 5000, commissionMonths: 12, holdDays: 14,
};

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

async function fillPartnerForm(percent: string) {
  await waitFor(() => expect(screen.getByLabelText('Commission (%)')).toBeTruthy());
  fireEvent.change(screen.getByLabelText(/Partner account email/), { target: { value: 'p@acme.io' } });
  fireEvent.change(screen.getByLabelText('Commission (%)'), { target: { value: percent } });
}

describe('Admin > Partners > partner code form: commission rate', () => {
  beforeEach(() => {
    overview.mockReset();
    createPartnerCode.mockReset();
    overview.mockResolvedValue({ codes: [], defaults: DEFAULTS });
    createPartnerCode.mockResolvedValue({ success: true, code: { code: 'ACME' } });
  });
  afterEach(cleanup);

  it('starts from the program default of 50', async () => {
    renderPage();
    await waitFor(() => expect((screen.getByLabelText('Commission (%)') as HTMLInputElement).value).toBe('50'));
  });

  it('reads "12,5" as 12.5 when the code is created', async () => {
    renderPage();
    await fillPartnerForm('12,5');
    fireEvent.click(screen.getByRole('button', { name: 'Create partner code' }));

    await waitFor(() => expect(createPartnerCode).toHaveBeenCalledWith(
      expect.objectContaining({ partner_email: 'p@acme.io', commission_percent: 12.5 }),
    ));
  });

  it('refuses an invalid rate even when the form is submitted with Enter (implicit submit)', async () => {
    renderPage();
    await fillPartnerForm('abc');

    const form = screen.getByLabelText('Commission (%)').closest('form') as HTMLFormElement;
    fireEvent.submit(form);

    await new Promise((r) => setTimeout(r, 20));
    expect(createPartnerCode).not.toHaveBeenCalled();
  });

  it('refuses a rate that is not a number: the submit is disabled and nothing is sent', async () => {
    renderPage();
    await fillPartnerForm('abc');

    expect((screen.getByRole('button', { name: 'Create partner code' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/Enter a percentage between 0 and 100/)).toBeTruthy();
    expect(createPartnerCode).not.toHaveBeenCalled();
  });
});
