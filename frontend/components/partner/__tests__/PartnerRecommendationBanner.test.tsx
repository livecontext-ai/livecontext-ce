// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import { PENDING_REWARD_CODE_KEY } from '@/lib/lifecycle/pendingRewardCode';

const state = vi.hoisted(() => ({ query: '', ce: false }));
const codeOffer = vi.fn();

vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams(state.query) }));
vi.mock('@/lib/edition', () => ({ get IS_CE() { return state.ce; } }));
vi.mock('@/lib/api/services/partner-program-api.service', () => ({
  partnerProgramApi: { codeOffer: (code: string) => codeOffer(code) },
}));

import { PartnerRecommendationBanner } from '../PartnerRecommendationBanner';

const LINK = 'pricingMode=subscription&planCode=PRO&creditTierIndex=5&billingCycle=monthly&lc_ref=NORTHWIND&lc_rec=pro.5.monthly';

function renderBanner() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <PartnerRecommendationBanner />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('PartnerRecommendationBanner (pricing page)', () => {
  beforeEach(() => {
    state.query = LINK;
    state.ce = false;
    codeOffer.mockReset();
    window.localStorage.clear();
  });
  afterEach(cleanup);

  it('names the plan the partner chose and what the code really gives, read from the server', async () => {
    codeOffer.mockResolvedValue({ code: 'NORTHWIND', credits: 8000 });
    renderBanner();

    const banner = screen.getByTestId('partner-recommendation');
    expect(banner.textContent).toContain('Recommended by your partner');
    expect(banner.textContent).toContain('Pro with 250,000 credits a month');
    await waitFor(() => expect(screen.getByTestId('partner-recommendation-offer').textContent)
      .toBe('With the code NORTHWIND, your new account gets 8,000 free credits.'));
    expect(codeOffer).toHaveBeenCalledWith('NORTHWIND');
  });

  it('regression: an older code already waiting wins, so this code\'s credits are not promised (the recommendation stays)', async () => {
    window.localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code: 'CREATOR1', savedAt: Date.now() }));
    codeOffer.mockResolvedValue({ code: 'NORTHWIND', credits: 8000 });
    renderBanner();

    await waitFor(() => expect(codeOffer).toHaveBeenCalled());
    expect(screen.getByTestId('partner-recommendation').textContent).toContain('Pro with 250,000 credits a month');
    expect(screen.queryByTestId('partner-recommendation-offer')).toBeNull();
  });

  it('the same code already waiting (a second visit of the link) keeps the offer', async () => {
    window.localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code: 'NORTHWIND', savedAt: Date.now() }));
    codeOffer.mockResolvedValue({ code: 'NORTHWIND', credits: 8000 });
    renderBanner();

    expect(await screen.findByTestId('partner-recommendation-offer')).toBeTruthy();
  });

  it('a code that offers nothing (unknown, disabled: 404) keeps the recommendation and promises no credits', async () => {
    codeOffer.mockRejectedValue(new Error('404'));
    renderBanner();

    await waitFor(() => expect(codeOffer).toHaveBeenCalled());
    expect(screen.getByTestId('partner-recommendation')).toBeTruthy();
    expect(screen.queryByTestId('partner-recommendation-offer')).toBeNull();
  });

  it('a yearly recommendation says so, even after the visitor moved the toggle', () => {
    state.query = LINK.replace('lc_rec=pro.5.monthly', 'lc_rec=pro.5.yearly');
    codeOffer.mockResolvedValue({ code: 'NORTHWIND', credits: 8000 });
    renderBanner();

    expect(screen.getByTestId('partner-recommendation').textContent).toContain('Pro with 250,000 credits a month, billed yearly: the plan your partner chose for you.');
  });

  it('a plain pricing link (no recommendation mark) shows nothing and asks nothing', () => {
    state.query = LINK.replace('&lc_rec=pro.5.monthly', '');
    renderBanner();

    expect(screen.queryByTestId('partner-recommendation')).toBeNull();
    expect(codeOffer).not.toHaveBeenCalled();
  });

  it('never on a self-hosted install, where the program does not exist', () => {
    state.ce = true;
    renderBanner();

    expect(screen.queryByTestId('partner-recommendation')).toBeNull();
    expect(codeOffer).not.toHaveBeenCalled();
  });
});
