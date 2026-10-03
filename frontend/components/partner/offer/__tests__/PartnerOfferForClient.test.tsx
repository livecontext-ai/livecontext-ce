// @vitest-environment jsdom
/**
 * An offer the anonymous read calls gone, read again for the signed-in visitor: a client already
 * attributed to the partner gets it back once the partner's code is used up; anyone else, and a
 * failed read, keep the "no longer available" page.
 */
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const state = vi.hoisted(() => ({
  auth: { isAuthenticated: true, isReady: true, isLoading: false, numericUserId: 7 as number | null } as Record<string, unknown> | undefined,
  ce: false,
}));
const offerView = vi.hoisted(() => vi.fn());
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => state.auth }));
vi.mock('@/lib/edition', () => ({ get IS_CE() { return state.ce; } }));
vi.mock('@/lib/api/services/partner-program-api.service', () => ({ partnerProgramApi: { offerView: (t: string) => offerView(t) } }));
vi.mock('../PartnerOfferView', () => ({
  PartnerOfferView: ({ offer }: { offer: { plan: string; credits: number } }) => <div data-testid="offer-view">{`${offer.plan}:${offer.credits}`}</div>,
}));

import { PartnerOfferForClient } from '../PartnerOfferForClient';

const PAYLOAD = {
  token: 'Abc23XyZ9k', code: 'NORTHWIND', credits: 0, plan_code: 'PRO', credit_tier_index: 3, billing_cycle: 'monthly',
  partner: { name: 'Northwind Studio', handle: 'northwind', avatar_url: null, tier: 'gold', verified: true }, apps: [], apps_plan: null,
};

function renderIt() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PartnerOfferForClient token="Abc23XyZ9k"><p data-testid="gone">gone</p></PartnerOfferForClient>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  state.auth = { isAuthenticated: true, isReady: true, isLoading: false, numericUserId: 7 };
  state.ce = false;
  offerView.mockReset();
});
afterEach(() => cleanup());

describe('PartnerOfferForClient', () => {
  it('regression: a signed-in client the server still shows the offer to gets the offer page, without the code\'s credits', async () => {
    offerView.mockResolvedValue(PAYLOAD);
    renderIt();

    expect((await screen.findByTestId('offer-view')).textContent).toBe('pro:0');
    expect(offerView).toHaveBeenCalledWith('Abc23XyZ9k');
    expect(screen.queryByTestId('gone')).toBeNull();
  });

  it('the offer gone for this account too (404), or a payload that cannot be read, keeps the unavailable page', async () => {
    offerView.mockRejectedValue(new Error('404'));
    renderIt();
    expect(await screen.findByTestId('gone')).toBeTruthy();
    cleanup();

    offerView.mockReset().mockResolvedValue({ token: 'Abc23XyZ9k' });
    renderIt();
    expect(await screen.findByTestId('gone')).toBeTruthy();
    expect(screen.queryByTestId('offer-view')).toBeNull();
  });

  it('a visitor who is not signed in, or a self-hosted build, asks nothing and keeps the unavailable page', async () => {
    state.auth = { isAuthenticated: false, isReady: true, isLoading: false, numericUserId: null };
    renderIt();
    expect(screen.getByTestId('gone')).toBeTruthy();
    cleanup();

    state.ce = true;
    state.auth = { isAuthenticated: true, isReady: true, isLoading: false, numericUserId: 7 };
    renderIt();
    expect(screen.getByTestId('gone')).toBeTruthy();
    expect(offerView).not.toHaveBeenCalled();
  });

  it('while the account or the offer is being read, it says nothing yet (no flash of "unavailable")', async () => {
    state.auth = { isAuthenticated: false, isReady: false, isLoading: true, numericUserId: null };
    renderIt();
    expect(screen.getByTestId('partner-offer-for-client-loading')).toBeTruthy();
    expect(screen.queryByTestId('gone')).toBeNull();
    cleanup();

    state.auth = { isAuthenticated: true, isReady: true, isLoading: false, numericUserId: 7 };
    let release: (v: unknown) => void = () => {};
    offerView.mockReturnValue(new Promise((resolve) => { release = resolve; }));
    renderIt();
    expect(screen.getByTestId('partner-offer-for-client-loading')).toBeTruthy();
    release(PAYLOAD);
    await waitFor(() => expect(screen.getByTestId('offer-view')).toBeTruthy());
  });
});
