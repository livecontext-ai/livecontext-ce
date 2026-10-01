// @vitest-environment jsdom
//
// The "Have a code?" line in front of every Stripe checkout: collapsed by default, applies the
// code HERE (before Stripe), surfaces a code still waiting from a partner link, and is placed in
// all four components that start a checkout (every other upgrade button leads to one of them).
import '@testing-library/jest-dom/vitest';
import React from 'react';
import fs from 'node:fs';
import path from 'node:path';
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
const offer = vi.hoisted(() => ({
  current: null as null | { offerId: number; status: string; grantedCredits?: number; expiresAt?: string; sessionExpiresAt?: string },
  preview: null,
  candidateCode: null,
  errorCode: null,
  isError: false,
  isLoading: false,
  isAuthenticated: true,
  refresh: vi.fn(),
  clearCandidate: vi.fn(),
}));
vi.mock('@/lib/hooks/usePersonalOffer', () => ({ usePersonalOffer: () => offer }));
vi.mock('@/lib/api/services/reward-api.service', async (orig) => ({
  ...(await orig<typeof import('@/lib/api/services/reward-api.service')>()),
  RewardApiService: class { redeem = redeem; },
}));

import { RewardCodeInline } from '../RewardCodeInline';

beforeEach(() => {
  localStorage.clear();
  offer.current = null;
  offer.isError = false;
});
afterEach(() => {
  cleanup();
  redeem.mockReset();
});

describe('RewardCodeInline', () => {
  it('is one discreet link until clicked (it must not crowd the checkout)', () => {
    render(<RewardCodeInline />);

    expect(screen.getByRole('button', { name: /toggle/ })).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('applies the code before checkout and says what it gave', async () => {
    redeem.mockResolvedValue({ success: true, code: 'REDEEMED', grantedCredits: 50000 });
    render(<RewardCodeInline />);

    fireEvent.click(screen.getByRole('button', { name: /toggle/ }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'lc-abcd2222' } });
    fireEvent.click(screen.getByRole('button', { name: /apply/ }));

    expect(await screen.findByRole('status')).toHaveTextContent('successCredits');
    expect(redeem).toHaveBeenCalledWith('lc-abcd2222');
  });

  it('shows a typed refusal inline (e.g. an existing customer and a partner code)', async () => {
    redeem.mockRejectedValue(new ApiError('paid', 409, 'ALREADY_PAID'));
    render(<RewardCodeInline />);

    fireEvent.click(screen.getByRole('button', { name: /toggle/ }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'TECHDOX' } });
    fireEvent.click(screen.getByRole('button', { name: /apply/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('errors.alreadyPaid');
  });

  it('regression: the opened field can be closed again with its X button, back to the discreet link', () => {
    render(<RewardCodeInline />);

    fireEvent.click(screen.getByRole('button', { name: /toggle/ }));
    expect(screen.getByRole('textbox')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'close' }));

    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.getByRole('button', { name: /toggle/ })).toBeInTheDocument();
  });

  it('closing hands focus back to the Have a code? link', () => {
    render(<RewardCodeInline />);

    fireEvent.click(screen.getByRole('button', { name: /toggle/ }));
    fireEvent.click(screen.getByRole('button', { name: 'close' }));

    expect(screen.getByRole('button', { name: /toggle/ })).toHaveFocus();
  });

  it('cannot be closed while a code is being applied (its answer would land on a collapsed field)', async () => {
    let resolve!: (v: unknown) => void;
    redeem.mockReturnValue(new Promise((r) => { resolve = r; }));
    render(<RewardCodeInline />);

    fireEvent.click(screen.getByRole('button', { name: /toggle/ }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'TECHDOX' } });
    fireEvent.click(screen.getByRole('button', { name: /apply/ }));

    const closeButton = await screen.findByRole('button', { name: 'close' });
    await vi.waitFor(() => expect(closeButton).toBeDisabled());
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' });
    expect(screen.getByRole('textbox')).toBeInTheDocument();

    resolve({ success: true, code: 'X', grantedCredits: 10 });
    expect(await screen.findByRole('status')).toBeInTheDocument();
  });

  it('Escape in the field closes it too, and clears a shown error', async () => {
    redeem.mockRejectedValue(new ApiError('paid', 409, 'ALREADY_PAID'));
    render(<RewardCodeInline />);

    fireEvent.click(screen.getByRole('button', { name: /toggle/ }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'TECHDOX' } });
    fireEvent.click(screen.getByRole('button', { name: /apply/ }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();

    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' });

    expect(screen.queryByRole('textbox')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /toggle/ }));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('regression: on the pricing page the line keeps top padding, so the opened field is not clipped by the scroll container', () => {
    const src = fs.readFileSync(path.resolve(__dirname, '../../pricing/PricingPageContent.tsx'), 'utf-8');
    expect(src).toMatch(/<RewardCodeInline className="[^"]*\bpt-4\b[^"]*"/);
  });

  it('a code still waiting from a partner link is named and pre-filled', async () => {
    localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code: 'TECHDOX', savedAt: Date.now() }));
    render(<RewardCodeInline />);

    const toggle = await screen.findByRole('button', { name: /pending/ });
    expect(toggle).toHaveTextContent('TECHDOX');
    fireEvent.click(toggle);
    expect(screen.getByRole('textbox')).toHaveValue('TECHDOX');
  });

  it('reports a paid purchase with zero bonus as used, without claiming credits were granted', () => {
    offer.current = { offerId: 42, status: 'NO_BONUS' };
    render(<RewardCodeInline subscriptionCheckout />);

    expect(screen.getByRole('status')).toHaveTextContent('usedWithoutBonus');
    expect(screen.getByRole('status')).not.toHaveTextContent('granted');
  });

  it('shows review and used states without an applied-until claim or another code action', () => {
    offer.current = { offerId: 42, status: 'REVIEW_REQUIRED', expiresAt: '2026-09-01T00:00:00Z' };
    const { rerender } = render(<RewardCodeInline subscriptionCheckout />);
    expect(screen.getByRole('alert')).toHaveTextContent('reviewRequired');
    expect(screen.queryByRole('button', { name: 'changeCode' })).toBeNull();
    offer.current = { offerId: 42, status: 'ALREADY_USED', expiresAt: '2026-09-01T00:00:00Z' };
    rerender(<RewardCodeInline subscriptionCheckout />);
    expect(screen.getByRole('status')).toHaveTextContent('usedWithoutOffer');
    expect(screen.queryByRole('button', { name: 'changeCode' })).toBeNull();
  });

  it('shows the Stripe reservation deadline rather than a past marketing expiry', () => {
    offer.current = { offerId: 42, status: 'CHECKOUT_OPEN',
      expiresAt: '2026-09-01T00:00:00Z', sessionExpiresAt: '2026-10-01T00:00:00Z' };
    render(<RewardCodeInline subscriptionCheckout />);
    expect(screen.getByRole('status')).toHaveTextContent('reservedUntil');
    expect(screen.getByRole('status')).toHaveTextContent('2026');
    expect(screen.getByRole('status')).not.toHaveTextContent('appliedUntil');
  });
});

describe('placement: every component that starts a Stripe checkout offers the code first', () => {
  const root = path.resolve(__dirname, '../../..');
  for (const file of [
    'components/pricing/PricingPageContent.tsx',
    'components/billing/TopUpModal.tsx',
    'components/billing/InsufficientCreditsModal.tsx',
    'components/billing/InsufficientStorageModal.tsx',
  ]) {
    it(`${file} renders <RewardCodeInline`, () => {
      expect(fs.readFileSync(path.join(root, file), 'utf-8')).toContain('<RewardCodeInline');
    });
  }

  it('no other component creates a Stripe checkout without it (a new checkout site must add the line)', () => {
    // The two checkout hooks; any component calling them must be one of the four above.
    const allowed = new Set([
      'components/pricing/PricingPageContent.tsx',
      'components/billing/TopUpModal.tsx',
      'components/billing/InsufficientCreditsModal.tsx',
      'components/billing/InsufficientStorageModal.tsx',
    ]);
    const offenders: string[] = [];
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (entry.name === 'node_modules' || entry.name.startsWith('.') || entry.name === '__tests__') continue;
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full);
        else if (/\.tsx$/.test(entry.name)) {
          const src = fs.readFileSync(full, 'utf-8');
          if (/\bcreateSubscription\(|\busePaygCheckout\(/.test(src)) {
            const rel = path.relative(root, full).split(path.sep).join('/');
            if (!allowed.has(rel)) offenders.push(rel);
          }
        }
      }
    };
    walk(path.join(root, 'components'));
    walk(path.join(root, 'app'));
    expect(offenders).toEqual([]);
  });
});
