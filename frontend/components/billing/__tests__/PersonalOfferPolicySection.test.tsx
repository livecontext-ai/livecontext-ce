// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  getPolicies: vi.fn(), createPolicy: vi.fn(), updatePolicy: vi.fn(),
  activatePolicy: vi.fn(), pausePolicy: vi.fn(), getCodes: vi.fn(), disableCode: vi.fn(),
}));
vi.mock('@/lib/api/services/personal-offer-admin.service', () => ({ personalOfferAdminApi: api }));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) => values ? `${key}:${JSON.stringify(values)}` : key,
  useLocale: () => 'en',
}));

import PersonalOfferPolicySection from '../PersonalOfferPolicySection';

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><PersonalOfferPolicySection /></QueryClientProvider>);
}

beforeEach(() => {
  Object.values(api).forEach((mock) => mock.mockReset());
  api.getCodes.mockResolvedValue({ codes: [] });
  api.getPolicies.mockResolvedValue({ policies: [{
    id: 9, campaignKey: 'free-credit-upgrade', version: 2, state: 'ACTIVE', label: 'Version 2',
    waitHours: 4, validityHours: 72, checkoutHoldMinutes: 30, reminderEnabled: false,
    reminderHours: 12, paygCreditsPerUsd: 800, allowConversionStack: false,
    matrix: [{ planCode: 'PRO', monthlyCredits: 50000, bonusCredits: 10000 }],
  }] });
  api.createPolicy.mockResolvedValue({ policy: { id: 10 } });
});

describe('personal offer policy admin', () => {
  it('keeps a published version read-only and saves edits as a new inactive draft', async () => {
    renderPanel();
    const label = await screen.findByLabelText('label');
    expect(label).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'newDraftFromVersion' }));
    expect(label).toBeEnabled();
    fireEvent.change(label, { target: { value: 'Version 3 draft' } });
    fireEvent.click(screen.getByRole('button', { name: 'saveDraft' }));

    await waitFor(() => expect(api.createPolicy).toHaveBeenCalledWith(expect.objectContaining({
      campaignKey: 'free-credit-upgrade', label: 'Version 3 draft', allowConversionStack: false,
    })));
    expect(api.updatePolicy).not.toHaveBeenCalled();
    expect(api.activatePolicy).not.toHaveBeenCalled();
  });

  it('offers a resume action for a paused policy', async () => {
    api.getPolicies.mockResolvedValue({ policies: [{
      id: 9, campaignKey: 'free-credit-upgrade', version: 2, state: 'PAUSED', label: 'Version 2',
      waitHours: 4, validityHours: 72, checkoutHoldMinutes: 30, reminderEnabled: false,
      reminderHours: 12, paygCreditsPerUsd: 800, allowConversionStack: false, matrix: [],
    }] });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPanel();

    fireEvent.click(await screen.findByRole('button', { name: 'resume' }));
    await waitFor(() => expect(api.activatePolicy).toHaveBeenCalledWith(9));
    vi.restoreAllMocks();
  });

  it('names an issued offer awaiting payment review without showing it as granted', async () => {
    api.getCodes.mockResolvedValue({ codes: [{ id: 20, recipientUserId: 7, policyVersionId: 9,
      issuedAt: '2026-09-29T10:00:00Z', expiresAt: '2026-10-01T10:00:00Z',
      active: true, status: 'REVIEW_REQUIRED', bonusCredits: 10000 }] });
    renderPanel();
    expect(await screen.findByText('codeStatuses.review_required')).toBeInTheDocument();
    expect(screen.queryByText('codeStatuses.granted')).toBeNull();
  });
});
