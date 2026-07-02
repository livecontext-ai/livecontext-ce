// @vitest-environment jsdom
/**
 * Tests for the Cloud insufficient-credits modal: closed by default, opens on
 * its window event (via showInsufficientCreditsModal), RE-opens on every
 * dispatch after being closed (regression: no persistent dismiss), is a no-op
 * in CE mode, gates the "Top up instead" CTA on PAYG tier configuration, and
 * scopes the Free-plan note to the FREE plan only.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

const mocks = vi.hoisted(() => ({
  useSubscription: vi.fn(),
  usePaygTiers: vi.fn(),
  usePaygCheckout: vi.fn(),
  push: vi.fn(),
  // Mutable CE flag: the component reads isCeMode at render/effect time, so a
  // getter on the mocked module lets individual tests flip the edition.
  ceMode: { value: false },
}));

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => (ns ? `${ns}.${key}` : key),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mocks.push }),
}));

vi.mock('@/lib/utils/locale', () => ({
  getClientLocale: () => 'en',
}));

vi.mock('@/lib/format-cost', () => ({
  get isCeMode() {
    return mocks.ceMode.value;
  },
  // Needed by the nested TopUpModal import chain.
  formatCreditsCompact: (v: number) => String(v),
}));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => mocks.useSubscription(),
  usePaygTiers: () => mocks.usePaygTiers(),
  usePaygCheckout: () => mocks.usePaygCheckout(),
}));

// Radix Slider requires ResizeObserver, which jsdom does not provide.
vi.mock('@/components/ui/slider', () => ({
  Slider: () => <div data-testid="credit-slider" />,
}));

vi.mock('@/components/pricing/DeploymentBadge', () => ({ default: () => null }));
vi.mock('@/components/pricing/FeatureLabel', () => ({
  default: ({ feature }: { feature: string }) => <span>{feature}</span>,
}));
vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="loading-spinner" />,
}));

import InsufficientCreditsModal, {
  INSUFFICIENT_CREDITS_EVENT,
  showInsufficientCreditsModal,
} from '../InsufficientCreditsModal';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const TITLE = 'modals.insufficientCredits.title';
const TOP_UP_CTA = 'billing.payg.topUpInstead';
const FREE_SCOPE_NOTE = 'modals.insufficientCredits.freeScopeNote';
const TOP_UP_MODAL_TITLE = 'billing.payg.modal.title';

const configuredTiers = [
  { tier: 'small', credits: 5_000, amountCents: 500, currency: 'USD', configured: true },
  { tier: 'medium', credits: 12_000, amountCents: 1250, currency: 'USD', configured: true },
  { tier: 'large', credits: 30_000, amountCents: 2500, currency: 'USD', configured: true },
];

const openViaEvent = () => {
  act(() => {
    showInsufficientCreditsModal();
  });
};

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('InsufficientCreditsModal', () => {
  beforeEach(() => {
    mocks.ceMode.value = false;
    mocks.push.mockReset();
    mocks.useSubscription
      .mockReset()
      .mockReturnValue({ createSubscription: vi.fn(), subscription: null });
    mocks.usePaygTiers
      .mockReset()
      .mockReturnValue({ tiers: [], configured: false, isLoading: false });
    mocks.usePaygCheckout
      .mockReset()
      .mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
  });

  afterEach(() => cleanup());

  describe('event-driven open/close', () => {
    it('is closed by default and opens when showInsufficientCreditsModal() dispatches the event', () => {
      render(<InsufficientCreditsModal />);
      expect(screen.queryByText(TITLE)).toBeNull();

      openViaEvent();

      expect(screen.getByText(TITLE)).toBeTruthy();
    });

    it('also opens on a raw CustomEvent dispatch of the exported event name', () => {
      render(<InsufficientCreditsModal />);

      fireEvent(window, new CustomEvent(INSUFFICIENT_CREDITS_EVENT));

      expect(screen.getByText(TITLE)).toBeTruthy();
    });

    it('re-opens on every dispatch after being closed (no persistent dismiss)', () => {
      render(<InsufficientCreditsModal />);

      // First attempt: open then close via the dialog close button.
      openViaEvent();
      expect(screen.getByText(TITLE)).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'Close' }));
      expect(screen.queryByText(TITLE)).toBeNull();

      // Second attempt MUST re-open the modal - dismissal is never persisted.
      openViaEvent();
      expect(screen.getByText(TITLE)).toBeTruthy();
    });
  });

  describe('CE mode', () => {
    it('renders nothing and ignores the event in CE mode', () => {
      mocks.ceMode.value = true;
      render(<InsufficientCreditsModal />);

      openViaEvent();

      expect(screen.queryByText(TITLE)).toBeNull();
    });
  });

  describe('Top up CTA gating', () => {
    it('shows the "Top up instead" CTA when PAYG tiers are configured', () => {
      mocks.usePaygTiers.mockReturnValue({
        tiers: configuredTiers,
        configured: true,
        isLoading: false,
      });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByRole('button', { name: new RegExp(TOP_UP_CTA) })).toBeTruthy();
    });

    it('hides the "Top up instead" CTA when no PAYG tier is configured', () => {
      mocks.usePaygTiers.mockReturnValue({ tiers: [], configured: false, isLoading: false });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByText(TITLE)).toBeTruthy();
      expect(screen.queryByText(TOP_UP_CTA)).toBeNull();
    });

    it('opens the nested TopUpModal when the "Top up instead" CTA is clicked', () => {
      mocks.usePaygTiers.mockReturnValue({
        tiers: configuredTiers,
        configured: true,
        isLoading: false,
      });
      render(<InsufficientCreditsModal />);
      openViaEvent();
      expect(screen.queryByText(TOP_UP_MODAL_TITLE)).toBeNull();

      fireEvent.click(screen.getByRole('button', { name: new RegExp(TOP_UP_CTA) }));

      expect(screen.getByText(TOP_UP_MODAL_TITLE)).toBeTruthy();
    });
  });

  describe('Free-plan scoping note', () => {
    it('shows the Free-plan note when the subscription has no plan code (defaults to FREE)', () => {
      mocks.useSubscription.mockReturnValue({ createSubscription: vi.fn(), subscription: null });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByText(FREE_SCOPE_NOTE)).toBeTruthy();
    });

    it('shows the Free-plan note when the plan code is FREE (case-insensitive)', () => {
      mocks.useSubscription.mockReturnValue({
        createSubscription: vi.fn(),
        subscription: { subscription: { planCode: 'free' } },
      });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByText(FREE_SCOPE_NOTE)).toBeTruthy();
    });

    it('hides the Free-plan note for a paid plan', () => {
      mocks.useSubscription.mockReturnValue({
        createSubscription: vi.fn(),
        subscription: { subscription: { planCode: 'PRO' } },
      });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByText(TITLE)).toBeTruthy();
      expect(screen.queryByText(FREE_SCOPE_NOTE)).toBeNull();
    });
  });
});
