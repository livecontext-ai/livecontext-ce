// @vitest-environment jsdom
//
// The code a visitor arrived with is applied once they are signed in. A typed answer from the
// redeem endpoint (applied, or refused with 404/409) is final and forgets the code; anything that
// says nothing about the code (no token yet, network, 5xx) keeps it for the next page load.
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/api/api-client';
import { PENDING_REWARD_CODE_KEY } from '@/lib/lifecycle/pendingRewardCode';

const authMock = vi.hoisted(() => ({
  current: { isAuthenticated: true, isReady: true, isLoading: false, numericUserId: 7 } as Record<string, unknown> | undefined,
}));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => authMock.current }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
  useLocale: () => 'en',
}));

const redeem = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/services/reward-api.service', async (orig) => ({
  ...(await orig<typeof import('@/lib/api/services/reward-api.service')>()),
  rewardApi: { redeem },
}));

import PendingRewardCodeRedeemer from '../PendingRewardCodeRedeemer';

function renderIt() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PendingRewardCodeRedeemer />
    </QueryClientProvider>,
  );
}

function storeCode(code: string) {
  localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code, savedAt: Date.now() }));
}

beforeEach(() => {
  localStorage.clear();
  authMock.current = { isAuthenticated: true, isReady: true, isLoading: false, numericUserId: 7 };
});
afterEach(() => {
  cleanup();
  redeem.mockReset();
});

describe('PendingRewardCodeRedeemer', () => {
  it('applies the waiting code once signed in, says what it gave, and forgets it', async () => {
    storeCode('TECHDOX');
    redeem.mockResolvedValue({ success: true, code: 'REDEEMED', grantedCredits: 10000 });

    renderIt();

    expect(await screen.findByText(/successCredits/)).toBeInTheDocument();
    expect(redeem).toHaveBeenCalledWith('TECHDOX');
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).toBeNull();
  });

  it('a typed refusal (409 already attributed) is final: shows why and forgets the code', async () => {
    storeCode('TECHDOX');
    redeem.mockRejectedValue(new ApiError('used', 409, 'ALREADY_ATTRIBUTED'));

    renderIt();

    expect(await screen.findByText('errors.alreadyAttributed')).toBeInTheDocument();
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).toBeNull();
  });

  it('a partner following another partner\'s link is told why (409 PARTNER_ACCOUNT), and the code is dropped', async () => {
    storeCode('TECHDOX');
    redeem.mockRejectedValue(new ApiError('partner', 409, 'PARTNER_ACCOUNT'));

    renderIt();

    expect(await screen.findByText('errors.partnerAccount')).toBeInTheDocument();
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).toBeNull();
  });

  it('no token yet / network / 5xx keeps the code for the next page load and shows nothing', async () => {
    storeCode('TECHDOX');
    redeem.mockRejectedValue(new ApiError('No authentication token available', 401, 'NO_TOKEN'));

    renderIt();

    await vi.waitFor(() => expect(redeem).toHaveBeenCalled());
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).not.toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('does nothing while signed out, even with a code waiting', async () => {
    storeCode('TECHDOX');
    authMock.current = { isAuthenticated: false, isReady: true, isLoading: false, numericUserId: null };

    renderIt();

    await new Promise((r) => setTimeout(r, 20));
    expect(redeem).not.toHaveBeenCalled();
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).not.toBeNull();
  });

  it('an unknown code (404) is dropped silently: no notice about a code the person never typed', async () => {
    storeCode('PRODUCTHUNT');
    redeem.mockRejectedValue(new ApiError('unknown', 404, 'INVALID_CODE'));

    renderIt();

    await vi.waitFor(() => expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).toBeNull());
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('a signed-in person landing straight on /app?lc_ref=CODE gets it applied on that same load', async () => {
    window.history.replaceState({}, '', '/app?lc_ref=techdox');
    redeem.mockResolvedValue({ success: true, code: 'REDEEMED', grantedCredits: 10000 });

    renderIt();

    await vi.waitFor(() => expect(redeem).toHaveBeenCalledWith('TECHDOX'));
    window.history.replaceState({}, '', '/');
  });

  it('an unverified email is told why, and the code is KEPT for after verification', async () => {
    storeCode('TECHDOX');
    redeem.mockRejectedValue(new ApiError('verify', 403, 'EMAIL_NOT_VERIFIED'));

    renderIt();

    expect(await screen.findByText('errors.emailNotVerified')).toBeInTheDocument();
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).not.toBeNull();
  });

  it('a code already redeemed by hand on /redeem is dropped silently (no "already redeemed" for a code that worked)', async () => {
    storeCode('TECHDOX');
    redeem.mockRejectedValue(new ApiError('used', 409, 'ALREADY_REDEEMED'));

    renderIt();

    await vi.waitFor(() => expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).toBeNull());
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('does nothing when no code is waiting', async () => {
    renderIt();

    await new Promise((r) => setTimeout(r, 20));
    expect(redeem).not.toHaveBeenCalled();
  });
});
