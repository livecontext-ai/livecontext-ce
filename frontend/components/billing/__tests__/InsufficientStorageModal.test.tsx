// @vitest-environment jsdom
/**
 * The storage upsell modal's Free column, and specifically that it states BOTH
 * of the Free plan's monthly pots.
 *
 * The column lists what a reader keeps if they do not upgrade. Naming only the
 * workflow credits there reads as "and chat comes out of the same pot", which
 * is not what happens: chat and agent turns draw the separate AI allowance
 * (V494). The figure is admin-configurable, so the line is also the one place
 * this modal can quietly go stale.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup, act } from '@testing-library/react';

const mocks = vi.hoisted(() => ({
  createSubscription: vi.fn(),
  push: vi.fn(),
  getQuota: vi.fn(),
  getBreakdown: vi.fn(),
  plans: { value: undefined as unknown },
}));

vi.mock('next-intl', () => ({
  useTranslations:
    (ns?: string) =>
    (key: string, values?: Record<string, unknown>) => {
      const path = ns ? `${ns}.${key}` : key;
      // Values echoed so a test can see WHICH figure a line was handed: a
      // column that had the shipped default baked in would otherwise render
      // identically to one reading the live plan row.
      return values ? `${path}(${Object.values(values).join(',')})` : path;
    },
  useLocale: () => 'en',
}));

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: mocks.push }) }));

vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({ createSubscription: mocks.createSubscription }),
  usePlans: () => ({ plans: mocks.plans.value }),
}));

vi.mock('@/lib/api/storage-api', () => ({
  storageApi: { getQuota: mocks.getQuota, getBreakdown: mocks.getBreakdown },
  STORAGE_CATEGORY_COLORS: {},
}));

vi.mock('@/hooks/usePricingEvent', () => ({ usePricingEvent: () => ({ event: null }) }));

import InsufficientStorageModal, { INSUFFICIENT_STORAGE_EVENT } from '../InsufficientStorageModal';
import { FREE_AI_CREDITS } from '@/lib/billing/pricing-constants';

function openViaEvent() {
  act(() => {
    window.dispatchEvent(new CustomEvent(INSUFFICIENT_STORAGE_EVENT));
  });
}

const dialogText = () => document.body.textContent ?? '';

describe('InsufficientStorageModal', () => {
  beforeEach(() => {
    mocks.push.mockReset();
    mocks.createSubscription.mockReset();
    // The quota fetch is not what this file is about, and a rejected promise
    // here is swallowed by the component exactly as in production.
    mocks.getQuota.mockReset().mockResolvedValue(null);
    mocks.getBreakdown.mockReset().mockResolvedValue([]);
    mocks.plans.value = undefined;
  });

  afterEach(() => cleanup());

  it('is closed until its event asks for it', () => {
    render(<InsufficientStorageModal />);

    expect(screen.queryByText('modals.insufficientStorage.features.freeStorage')).toBeNull();
  });

  it('states both Free pots: the storage it keeps, and each monthly allowance', () => {
    mocks.plans.value = [{ code: 'FREE', includedAiCredits: 250 }];
    render(<InsufficientStorageModal />);
    openViaEvent();

    const text = dialogText();
    expect(text).toContain('modals.insufficientStorage.features.freeStorage');
    expect(text).toContain('modals.insufficientStorage.features.freeCredits');
    // The live figure, not the shipped one: reading the plan row is the point.
    expect(text).toContain('modals.insufficientStorage.features.freeAiCredits(250)');
  });

  it('drops the allowance line when the free tier is closed', () => {
    // Allowance 0 is how an admin closes the free tier. A "0 AI credits" bullet
    // would look like a feature while advertising nothing, the same rule the
    // plan cards apply.
    mocks.plans.value = [{ code: 'FREE', includedAiCredits: 0 }];
    render(<InsufficientStorageModal />);
    openViaEvent();

    expect(dialogText()).toContain('modals.insufficientStorage.features.freeCredits');
    expect(dialogText()).not.toContain('modals.insufficientStorage.features.freeAiCredits');
  });

  it('falls back to the shipped figure while the plans request is in flight', () => {
    // No row yet is not "no allowance": showing nothing would tell the reader
    // the free tier is closed for as long as the request takes.
    render(<InsufficientStorageModal />);
    openViaEvent();

    expect(dialogText()).toContain(
      `modals.insufficientStorage.features.freeAiCredits(${FREE_AI_CREDITS})`,
    );
  });
});
