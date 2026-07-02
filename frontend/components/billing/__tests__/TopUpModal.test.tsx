// @vitest-environment jsdom
/**
 * Tests for the PAYG top-up modal: tier rendering, selection, checkout
 * trigger (mutation called with the chosen tier + Stripe redirect), loading
 * and error states, unconfigured gating, and close behavior.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import type { PaygTier } from '@/lib/api/services/billing-api.service';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

const mocks = vi.hoisted(() => ({
  usePaygTiers: vi.fn(),
  checkout: vi.fn(),
  // Mutable pending flag so tests can render the "redirecting" state.
  isCheckingOut: { value: false },
}));

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => (ns ? `${ns}.${key}` : key),
}));

vi.mock('@/lib/format-cost', () => ({
  isCeMode: false,
  formatCreditsCompact: (v: number) => String(v),
}));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  usePaygTiers: () => mocks.usePaygTiers(),
  usePaygCheckout: () => ({
    mutateAsync: mocks.checkout,
    isPending: mocks.isCheckingOut.value,
  }),
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="loading-spinner" />,
}));

import TopUpModal from '../TopUpModal';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const TITLE = 'billing.payg.modal.title';
const CONFIRM = /billing\.payg\.modal\.confirm/;
const CANCEL = /billing\.payg\.modal\.cancel/;

const TIERS: PaygTier[] = [
  { tier: 'small', credits: 5_000, amountCents: 500, currency: 'USD', configured: true },
  { tier: 'medium', credits: 12_000, amountCents: 1250, currency: 'USD', configured: true },
  { tier: 'large', credits: 30_000, amountCents: 2500, currency: 'USD', configured: true },
];

function renderModal(overrides: Partial<React.ComponentProps<typeof TopUpModal>> = {}) {
  const props = { isOpen: true, onClose: vi.fn(), ...overrides };
  return { onClose: props.onClose, ...render(<TopUpModal {...props} />) };
}

const tierCard = (tier: string) =>
  screen.getByRole('button', { name: new RegExp(`tiers\\.${tier}`) }) as HTMLButtonElement;
const confirmButton = () =>
  screen.getByRole('button', { name: CONFIRM }) as HTMLButtonElement;

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('TopUpModal', () => {
  const originalLocation = window.location;

  beforeEach(() => {
    mocks.isCheckingOut.value = false;
    mocks.checkout.mockReset();
    mocks.usePaygTiers
      .mockReset()
      .mockReturnValue({ tiers: TIERS, configured: true, isLoading: false });
    // Stub location so the Stripe redirect is observable (and jsdom does not
    // attempt a real navigation).
    Object.defineProperty(window, 'location', {
      value: { href: '' },
      writable: true,
      configurable: true,
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', {
      value: originalLocation,
      writable: true,
      configurable: true,
    });
    cleanup();
  });

  describe('rendering', () => {
    it('renders nothing when closed', () => {
      renderModal({ isOpen: false });
      expect(screen.queryByText(TITLE)).toBeNull();
    });

    it('renders one card per PAYG tier with its formatted price and credits', () => {
      renderModal();

      expect(screen.getByText(TITLE)).toBeTruthy();
      expect(tierCard('small')).toBeTruthy();
      expect(tierCard('medium')).toBeTruthy();
      expect(tierCard('large')).toBeTruthy();
      // Whole dollars drop the decimals; fractional amounts keep two.
      expect(screen.getByText('$5')).toBeTruthy();
      expect(screen.getByText('$12.50')).toBeTruthy();
      expect(screen.getByText('$25')).toBeTruthy();
      // Credits go through formatCreditsCompact (mocked to String).
      expect(screen.getByText(/5000/)).toBeTruthy();
    });

    it('shows a spinner instead of tier cards while tiers load', () => {
      mocks.usePaygTiers.mockReturnValue({ tiers: [], configured: false, isLoading: true });
      renderModal();

      expect(screen.getByTestId('loading-spinner')).toBeTruthy();
      expect(screen.queryByRole('button', { name: /tiers\./ })).toBeNull();
    });

    it('shows the unconfigured notice and disables confirm when no tier is configured', () => {
      mocks.usePaygTiers.mockReturnValue({ tiers: [], configured: false, isLoading: false });
      renderModal();

      expect(screen.getByText('billing.payg.modal.unconfigured.title')).toBeTruthy();
      expect(screen.getByText('billing.payg.modal.unconfigured.body')).toBeTruthy();
      expect(confirmButton().disabled).toBe(true);
    });
  });

  describe('tier selection and checkout', () => {
    it('keeps confirm disabled until a tier is selected', () => {
      renderModal();
      expect(confirmButton().disabled).toBe(true);

      fireEvent.click(tierCard('small'));

      expect(confirmButton().disabled).toBe(false);
    });

    it('calls checkout with the selected tier and redirects to the returned Stripe URL', async () => {
      mocks.checkout.mockResolvedValue({ url: 'https://stripe.test/checkout', tier: 'medium' });
      renderModal();

      fireEvent.click(tierCard('medium'));
      fireEvent.click(confirmButton());

      await waitFor(() => expect(mocks.checkout).toHaveBeenCalledWith('medium'));
      expect(mocks.checkout).toHaveBeenCalledTimes(1);
      await waitFor(() => expect(window.location.href).toBe('https://stripe.test/checkout'));
    });

    it('pre-selects the initialTier so confirm works without an extra click', async () => {
      mocks.checkout.mockResolvedValue({ url: 'https://stripe.test/checkout', tier: 'large' });
      renderModal({ initialTier: 'large' });

      expect(confirmButton().disabled).toBe(false);
      fireEvent.click(confirmButton());

      await waitFor(() => expect(mocks.checkout).toHaveBeenCalledWith('large'));
    });

    it('does not select an unconfigured tier card', () => {
      mocks.usePaygTiers.mockReturnValue({
        tiers: [
          { tier: 'small', credits: 5_000, amountCents: 500, currency: 'USD', configured: true },
          { tier: 'medium', credits: 12_000, amountCents: 1250, currency: 'USD', configured: false },
        ],
        configured: true,
        isLoading: false,
      });
      renderModal();

      const mediumCard = tierCard('medium');
      expect(mediumCard.disabled).toBe(true);
      fireEvent.click(mediumCard);

      expect(confirmButton().disabled).toBe(true);
    });
  });

  describe('error handling', () => {
    it('surfaces the backend error message verbatim when checkout rejects', async () => {
      mocks.checkout.mockRejectedValue(new Error('Only the workspace owner can top up'));
      renderModal();

      fireEvent.click(tierCard('small'));
      fireEvent.click(confirmButton());

      expect(await screen.findByText('Only the workspace owner can top up')).toBeTruthy();
      expect(window.location.href).toBe('');
    });

    it('shows the noUrl error when checkout resolves without a URL', async () => {
      mocks.checkout.mockResolvedValue({});
      renderModal();

      fireEvent.click(tierCard('small'));
      fireEvent.click(confirmButton());

      expect(await screen.findByText('billing.payg.modal.errors.noUrl')).toBeTruthy();
      expect(window.location.href).toBe('');
    });
  });

  describe('pending and close behavior', () => {
    it('shows the redirecting state and disables cancel while checkout is pending', () => {
      mocks.isCheckingOut.value = true;
      const { onClose } = renderModal();

      expect(screen.getByText('billing.payg.modal.redirecting')).toBeTruthy();
      const cancelBtn = screen.getByRole('button', { name: CANCEL }) as HTMLButtonElement;
      expect(cancelBtn.disabled).toBe(true);

      fireEvent.click(cancelBtn);
      expect(onClose).not.toHaveBeenCalled();
    });

    it('calls onClose when cancel is clicked', () => {
      const { onClose } = renderModal();

      fireEvent.click(screen.getByRole('button', { name: CANCEL }));

      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });
});
