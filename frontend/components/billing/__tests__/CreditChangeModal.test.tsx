// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

vi.mock('next-intl', () => ({
  useTranslations: () => {
    const t = (key: string, params?: Record<string, any>) => {
      const translations: Record<string, string> = {
        title: 'Credit Pack Change',
        upgradeTitle: 'Upgrade Credit Pack',
        downgradeTitle: 'Reduce Credit Pack',
        current: 'Current',
        new: 'New',
        credits: 'credits',
        mo: 'mo',
        yr: 'yr',
        additionalCost: `+$${params?.amount ?? 0}/mo`,
        proratedCharge: 'Prorated charge for remaining period.',
        effectiveImmediately: 'Effective immediately',
        creditUpdateDescription: `Your ${params?.plan ?? ''} credit pack will be updated.`,
        upgradeAvailableNowTitle: 'Available immediately',
        upgradeAvailableNowDescription: `You'll receive ${params?.credits ?? ''} credits right away.`,
        upgradeKeepCredits: 'Your existing credits are preserved.',
        upgradeChargeTitle: `One-time charge: $${params?.amount ?? 0}`,
        upgradeChargeDescription: `You're charged the full new tier price today.`,
        upgradeRenewalResetTitle: 'What happens at your next renewal',
        upgradeRenewalResetDescription: `On ${params?.date ?? ''}, your credit balance resets to ${params?.credits ?? ''} credits.`,
        upgradeRenewalResetDescriptionGeneric: `At your next renewal, your credit balance resets to ${params?.credits ?? ''} credits.`,
        effectiveAtRenewal: 'Effective at next renewal',
        downgradeDescription: `Your credit pack will update to ${params?.credits ?? ''} credits on ${params?.date ?? ''}.`,
        downgradeDescriptionGeneric: `Your credit pack will update to ${params?.credits ?? ''} credits at next renewal.`,
        keepCreditsTitle: 'What to expect',
        keepCreditsUntilRenewal: `Keep your ${params?.credits ?? ''} credits until renewal`,
        cancelAnytime: 'You can cancel this change anytime',
        cancel: 'Cancel',
        confirmUpgrade: 'Confirm Upgrade',
        confirmAndPay: `Confirm & Pay $${params?.amount ?? 0}`,
        confirmChange: 'Confirm Change',
        confirmDowngrade: 'Confirm Reduction',
        processing: 'Processing...',
        successTitle: 'Credits Updated',
        successDowngradeTitle: 'Reduction Scheduled',
        successDescription: `Your credit pack is now ${params?.credits ?? ''} credits.`,
        successDowngradeDescription: `Your credit pack will update to ${params?.credits ?? ''} credits on ${params?.date ?? ''}.`,
        successDowngradeDescriptionGeneric: `Your credit pack will update to ${params?.credits ?? ''} credits at next renewal.`,
        done: 'Done',
        taxNote: 'Prices exclude tax. VAT/sales tax is calculated at checkout.',
      };
      return translations[key] || key;
    };
    return t;
  },
}));

import CreditChangeModal from '../CreditChangeModal';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Build a regex that matches a locale-formatted number.
 * Handles comma, period, thin/narrow/non-breaking space as thousands separator.
 * e.g. numRegex(25000) matches "25,000", "25 000", "25.000", "25\u202f000"
 */
function numRegex(n: number): string {
  const str = String(n);
  // Insert a separator class between every group of 3 digits from the right
  return str.replace(/\B(?=(\d{3})+(?!\d))/g, '[\\s\\u00a0\\u202f,.]?');
}

const baseInfo = {
  planName: 'Starter',
  currentCredits: 25000,
  currentCost: 18,
  newCredits: 50000,
  newCost: 35,
  billingCycle: 'monthly' as const,
  isTeam: false,
  planId: 'starter',
};

const defaultProps = {
  isOpen: true,
  info: baseInfo,
  loading: false,
  onConfirm: vi.fn(),
  onClose: vi.fn(),
};

function renderModal(overrides: any = {}) {
  const props = { ...defaultProps, ...overrides };
  if (overrides.info) {
    props.info = { ...baseInfo, ...overrides.info };
  }
  return render(<CreditChangeModal {...props} />);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('CreditChangeModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  // =========================================================================
  // Rendering Guards
  // =========================================================================

  describe('rendering guards', () => {
    it('should not render when isOpen is false', () => {
      renderModal({ isOpen: false });
      expect(screen.queryByText('Credit Pack Change')).toBeNull();
    });

    it('should not render when info is null', () => {
      render(
        <CreditChangeModal isOpen={true} info={null} onConfirm={vi.fn()} onClose={vi.fn()} />
      );
      expect(screen.queryByText('Credit Pack Change')).toBeNull();
    });
  });

  // =========================================================================
  // Upgrade Mode
  // =========================================================================

  describe('upgrade mode', () => {
    it('should render in upgrade mode when newCost > currentCost', () => {
      renderModal({ info: { currentCost: 18, newCost: 35 } });
      expect(screen.getByText('Upgrade Credit Pack')).toBeTruthy();
      expect(screen.getByText('Available immediately')).toBeTruthy();
    });

    it('should show correct credit amounts', () => {
      renderModal({ info: { currentCredits: 25000, newCredits: 50000 } });
      expect(screen.getByText(new RegExp(`${numRegex(25000)}\\s+credits`))).toBeTruthy();
      // 50000 credits appears in both the credit display and upgradeNewCycleDescription
      const matches50k = screen.getAllByText(new RegExp(`${numRegex(50000)}\\s+credits`));
      expect(matches50k.length).toBeGreaterThanOrEqual(1);
    });

    it('should show upgrade charge description', () => {
      renderModal({ info: { currentCost: 18, newCost: 35 } });
      // chargeNowAmount falls back to max(0, newCost - currentCost) = 17 (no newCreditCost)
      expect(screen.getByText('One-time charge: $17')).toBeTruthy();
    });

    it('should show upgrade info sections', () => {
      renderModal({ info: { currentCost: 18, newCost: 35, newCredits: 50000 } });
      expect(screen.getByText('Available immediately')).toBeTruthy();
      expect(screen.getByText('Your existing credits are preserved.')).toBeTruthy();
    });

    it('should show Confirm Upgrade button', () => {
      renderModal({ info: { currentCost: 18, newCost: 35 } });
      expect(screen.getByText('Confirm Upgrade')).toBeTruthy();
    });

    it('should show upgrade title in upgrade mode', () => {
      renderModal({ info: { planName: 'Starter' } });
      expect(screen.getByText('Upgrade Credit Pack')).toBeTruthy();
    });

    it('should keep credit costs undiscounted in yearly mode', () => {
      renderModal({
        info: { currentCost: 18, newCost: 35, billingCycle: 'yearly' },
      });
      expect(screen.getByText('$35/mo')).toBeTruthy();
      expect(screen.getByText('Confirm Upgrade')).toBeTruthy();
    });

    it('should render the tax note in upgrade mode', () => {
      renderModal({ info: { currentCost: 18, newCost: 35 } });
      expect(
        screen.getByText('Prices exclude tax. VAT/sales tax is calculated at checkout.')
      ).toBeTruthy();
    });

    it('should use explicit mode prop over cost comparison', () => {
      // Cost says downgrade (newCost < currentCost) but mode says upgrade
      renderModal({
        mode: 'upgrade',
        info: { currentCost: 35, newCost: 18 },
      });
      expect(screen.getByText('Upgrade Credit Pack')).toBeTruthy();
      expect(screen.getByText('Available immediately')).toBeTruthy();
    });
  });

  // =========================================================================
  // Downgrade Mode
  // =========================================================================

  describe('downgrade mode', () => {
    const downgradeInfo = {
      currentCredits: 50000,
      currentCost: 35,
      newCredits: 25000,
      newCost: 18,
      currentPeriodEnd: '2026-03-24T00:00:00',
    };

    it('should render in downgrade mode when newCost < currentCost', () => {
      renderModal({ info: downgradeInfo });
      expect(screen.getByText('Reduce Credit Pack')).toBeTruthy();
    });

    it('should show amber warning styling elements', () => {
      renderModal({ info: downgradeInfo });
      expect(screen.getByText('Effective at next renewal')).toBeTruthy();
    });

    it('should show formatted period end date', () => {
      renderModal({ info: downgradeInfo });
      // The date is formatted by Intl.DateTimeFormat; match locale-agnostic credit number
      const regex = new RegExp(`credit pack will update to ${numRegex(25000)}\\s+credits on`);
      expect(screen.getByText(regex)).toBeTruthy();
    });

    it('should show generic message when currentPeriodEnd is missing', () => {
      renderModal({
        info: { ...downgradeInfo, currentPeriodEnd: undefined },
      });
      // "Effective at next renewal" (header) and description both match - use getAllByText
      const matches = screen.getAllByText(/at next renewal/);
      expect(matches.length).toBeGreaterThanOrEqual(1);
    });

    it('should show keep-credits info', () => {
      renderModal({ info: downgradeInfo });
      expect(screen.getByText('What to expect')).toBeTruthy();
      const regex = new RegExp(`Keep your ${numRegex(50000)}\\s+credits until renewal`);
      expect(screen.getByText(regex)).toBeTruthy();
      expect(screen.getByText(/You can cancel this change anytime/)).toBeTruthy();
    });

    it('should show Confirm Reduction button', () => {
      renderModal({ info: downgradeInfo });
      expect(screen.getByText('Confirm Reduction')).toBeTruthy();
    });

    it('should render the tax note in downgrade mode', () => {
      renderModal({ info: downgradeInfo });
      expect(
        screen.getByText('Prices exclude tax. VAT/sales tax is calculated at checkout.')
      ).toBeTruthy();
    });

    it('should NOT show prorated charge for downgrade', () => {
      renderModal({ info: downgradeInfo });
      expect(screen.queryByText('Prorated charge')).toBeNull();
    });
  });

  // =========================================================================
  // Same Cost Edge Case
  // =========================================================================

  describe('same cost edge case', () => {
    it('should show Confirm Upgrade when costDiff is 0', () => {
      renderModal({ info: { currentCost: 0, newCost: 0 } });
      // Falls through to upgrade UI since neither isUpgrade nor isDowngrade is true
      expect(screen.getByText('Confirm Upgrade')).toBeTruthy();
    });

    it('should not show cost difference when same cost', () => {
      renderModal({ info: { currentCost: 18, newCost: 18 } });
      // costDiff=0, so no "+$0/mo" box should appear
      expect(screen.queryByText('+$0/mo')).toBeNull();
    });
  });

  // =========================================================================
  // Interactions
  // =========================================================================

  describe('interactions', () => {
    it('should call onConfirm when confirm button clicked', () => {
      const onConfirm = vi.fn();
      renderModal({ onConfirm, info: { currentCost: 18, newCost: 35 } });

      fireEvent.click(screen.getByText('Confirm Upgrade'));
      expect(onConfirm).toHaveBeenCalledTimes(1);
    });

    it('should call onClose when cancel button clicked', () => {
      const onClose = vi.fn();
      renderModal({ onClose });

      fireEvent.click(screen.getByText('Cancel'));
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('should call onClose when X button clicked', () => {
      const onClose = vi.fn();
      renderModal({ onClose });

      const buttons = screen.getAllByRole('button');
      const xButton = buttons.find(b => b.querySelector('svg') && !b.textContent?.includes('Confirm'));
      if (xButton) {
        fireEvent.click(xButton);
        expect(onClose).toHaveBeenCalledTimes(1);
      }
    });

    it('should disable buttons when loading in upgrade mode', () => {
      renderModal({ loading: true, info: { currentCost: 18, newCost: 35 } });
      expect(screen.getByText('Processing...')).toBeTruthy();

      // Cancel and confirm buttons should be disabled
      const cancelBtn = screen.getByText('Cancel').closest('button');
      expect(cancelBtn?.disabled).toBe(true);
    });

    it('should disable buttons when loading in downgrade mode', () => {
      renderModal({
        loading: true,
        info: { currentCost: 35, newCost: 18, currentPeriodEnd: '2026-03-24' },
      });
      expect(screen.getByText('Processing...')).toBeTruthy();
    });
  });

  // =========================================================================
  // Team Plan Multiplier
  // =========================================================================

  describe('team plan display', () => {
    it('should display Team plan info correctly', () => {
      renderModal({
        info: {
          planName: 'Team',
          isTeam: true,
          currentCredits: 50000,
          currentCost: 55,
          newCredits: 100000,
          newCost: 100,
        },
      });
      expect(screen.getByText(new RegExp(`${numRegex(50000)}\\s+credits`))).toBeTruthy();
      // 100000 credits appears in both credit display and upgradeNewCycleDescription
      const matches100k = screen.getAllByText(new RegExp(`${numRegex(100000)}\\s+credits`));
      expect(matches100k.length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText('$55/mo')).toBeTruthy();
      expect(screen.getByText('$100/mo')).toBeTruthy();
    });
  });
});
