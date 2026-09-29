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
vi.mock('@/lib/api/services/reward-api.service', async (orig) => ({
  ...(await orig<typeof import('@/lib/api/services/reward-api.service')>()),
  RewardApiService: class { redeem = redeem; },
}));

import { RewardCodeInline } from '../RewardCodeInline';

beforeEach(() => localStorage.clear());
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

  it('a code still waiting from a partner link is named and pre-filled', async () => {
    localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code: 'TECHDOX', savedAt: Date.now() }));
    render(<RewardCodeInline />);

    const toggle = await screen.findByRole('button', { name: /pending/ });
    expect(toggle).toHaveTextContent('TECHDOX');
    fireEvent.click(toggle);
    expect(screen.getByRole('textbox')).toHaveValue('TECHDOX');
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
