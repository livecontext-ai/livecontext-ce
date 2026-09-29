// @vitest-environment jsdom
//
// A code redeemed by hand on the card is forgotten from the pending slot, so the /app shell never
// auto-redeems it a second time; the capture component stores a code from the landing link.
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/api/api-client';
import { PENDING_REWARD_CODE_KEY } from '@/lib/lifecycle/pendingRewardCode';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
  useLocale: () => 'en',
}));

const redeem = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/services/reward-api.service', async (orig) => ({
  ...(await orig<typeof import('@/lib/api/services/reward-api.service')>()),
  RewardApiService: class { redeem = redeem; },
}));

import { RewardRedeemCard } from '../RewardRedeemCard';
import PendingRewardCodeCapture from '../PendingRewardCodeCapture';

function storeCode(code: string) {
  localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code, savedAt: Date.now() }));
}

beforeEach(() => localStorage.clear());
afterEach(() => {
  cleanup();
  redeem.mockReset();
  window.history.replaceState({}, '', '/');
});

describe('RewardRedeemCard and the pending code', () => {
  it('a manual success forgets the same pending code', async () => {
    storeCode('TECHDOX');
    redeem.mockResolvedValue({ success: true, code: 'REDEEMED', grantedCredits: 10000 });
    render(<RewardRedeemCard prefilledCode="techdox" />);

    fireEvent.click(screen.getByRole('button'));

    expect(await screen.findByText(/successCredits/)).toBeInTheDocument();
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).toBeNull();
  });

  it('a manual redeem of ANOTHER code leaves the pending one alone', async () => {
    storeCode('TECHDOX');
    redeem.mockResolvedValue({ success: true, code: 'REDEEMED', grantedCredits: 5 });
    render(<RewardRedeemCard prefilledCode="OTHER1" />);

    fireEvent.click(screen.getByRole('button'));

    await screen.findByText(/successCredits/);
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).not.toBeNull();
  });

  it('a manual final refusal (409) of the same code forgets it too; a 403 unverified keeps it', async () => {
    storeCode('TECHDOX');
    redeem.mockRejectedValueOnce(new ApiError('verify', 403, 'EMAIL_NOT_VERIFIED'));
    render(<RewardRedeemCard prefilledCode="TECHDOX" />);
    fireEvent.click(screen.getByRole('button'));
    expect(await screen.findByText('errors.emailNotVerified')).toBeInTheDocument();
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).not.toBeNull();

    redeem.mockRejectedValueOnce(new ApiError('used', 409, 'ALREADY_ATTRIBUTED'));
    fireEvent.click(screen.getByRole('button'));
    expect(await screen.findByText('errors.alreadyAttributed')).toBeInTheDocument();
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).toBeNull();
  });
});

describe('PendingRewardCodeCapture', () => {
  it('stores the code of the landing link on mount', () => {
    window.history.replaceState({}, '', '/fr?lc_ref=techdox');

    render(<PendingRewardCodeCapture />);

    expect(JSON.parse(localStorage.getItem(PENDING_REWARD_CODE_KEY) as string).code).toBe('TECHDOX');
  });
});
