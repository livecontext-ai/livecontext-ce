// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

vi.mock('next-intl', () => ({
  useTranslations: () => {
    const t = (key: string, params?: Record<string, any>) => {
      const translations: Record<string, string> = {
        title: 'Confirm Downgrade',
        titleScheduled: 'Downgrade Scheduled',
        current: 'Current',
        new: 'New',
        perMonth: `$${params?.price ?? 0}/mo`,
        perYear: `($${params?.price ?? 0}/yr)`,
        scheduledFor: 'Scheduled for end of billing period',
        scheduledForDate: `Effective: ${params?.date ?? ''}`,
        successMessage: `You will keep ${params?.plan ?? ''} until the end of your billing period.`,
        afterDate: `After that, you will be on ${params?.plan ?? ''}.`,
        endOfBillingPeriod: 'end of billing period',
        whatChanges: 'What changes',
        featuresRemoved: `Features from ${params?.plan ?? ''} will be removed`,
        dataArchived: 'Your data will be archived',
        upgradeAgain: 'You can upgrade again at any time',
        gotIt: 'Got it',
        cancel: 'Cancel',
        confirm: 'Confirm Downgrade',
        scheduling: 'Scheduling...',
        errorTitle: 'Downgrade failed',
        errorMessage: 'An error occurred',
        newRecurringMonthly: `Going forward: $${params?.amount ?? 0}/mo`,
        newRecurringYearly: `Going forward: $${params?.monthlyAmount ?? 0}/mo ($${params?.yearlyAmount ?? 0}/yr)`,
        taxNote: 'Prices exclude tax. VAT/sales tax is calculated at checkout.',
      };
      return translations[key] || key;
    };
    return t;
  },
}));

const mockScheduleDowngrade = vi.fn();
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: {
    scheduleDowngrade: (...args: any[]) => mockScheduleDowngrade(...args),
  },
}));

import DowngradeConfirmModal from '../DowngradeConfirmModal';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const defaultProps = {
  isOpen: true,
  onClose: vi.fn(),
  onSuccess: vi.fn(),
  currentPlan: { code: 'PRO', name: 'Pro', price: 24 },
  targetPlan: { code: 'STARTER', name: 'Starter', price: 10 },
  currentPeriodEnd: '2026-03-24T00:00:00',
  billingCycle: 'monthly' as const,
};

function renderModal(overrides = {}) {
  const props = { ...defaultProps, ...overrides };
  return render(<DowngradeConfirmModal {...props} />);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('DowngradeConfirmModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockScheduleDowngrade.mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  // =========================================================================
  // Rendering
  // =========================================================================

  describe('rendering', () => {
    it('should not render when isOpen is false', () => {
      renderModal({ isOpen: false });
      expect(screen.queryByText('Confirm Downgrade')).toBeNull();
    });

    it('should render confirmation state with plan details', () => {
      renderModal();
      // "Confirm Downgrade" appears as both the header title and the button text
      const confirmTexts = screen.getAllByText('Confirm Downgrade');
      expect(confirmTexts.length).toBe(2); // header + button
      expect(screen.getByText('Pro')).toBeTruthy();
      expect(screen.getByText('Starter')).toBeTruthy();
      expect(screen.getByText('$24/mo')).toBeTruthy();
      expect(screen.getByText('$10/mo')).toBeTruthy();
    });

    it('should render correctly when billingCycle is yearly', () => {
      renderModal({
        billingCycle: 'yearly',
        currentPlan: { code: 'PRO', name: 'Pro', price: 24 },
        targetPlan: { code: 'STARTER', name: 'Starter', price: 10 },
      });
      // Component shows plan names and per-month pricing
      expect(screen.getByText('Pro')).toBeTruthy();
      expect(screen.getByText('Starter')).toBeTruthy();
      expect(screen.getByText('$24/mo')).toBeTruthy();
      expect(screen.getByText('$10/mo')).toBeTruthy();
    });

    it('should handle undefined price gracefully', () => {
      renderModal({
        currentPlan: { code: 'PRO', name: 'Pro' },
        targetPlan: { code: 'FREE', name: 'Free' },
      });
      // Should not crash; "Confirm Downgrade" appears as header + button
      const confirmTexts = screen.getAllByText('Confirm Downgrade');
      expect(confirmTexts.length).toBe(2);
    });

    it('should handle undefined currentPeriodEnd', () => {
      renderModal({ currentPeriodEnd: undefined });
      // "end of billing period" appears in both the scheduled-for header and the effective date line
      const matches = screen.getAllByText(/end of billing period/);
      expect(matches.length).toBeGreaterThanOrEqual(1);
    });

    it('should render the tax note in the confirmation state', () => {
      renderModal();
      expect(
        screen.getByText('Prices exclude tax. VAT/sales tax is calculated at checkout.')
      ).toBeTruthy();
    });
  });

  // =========================================================================
  // State Reset on Open (critical fix)
  // =========================================================================

  describe('state reset on open', () => {
    it('should show confirmation state when re-opened after success', async () => {
      mockScheduleDowngrade.mockResolvedValueOnce({
        success: true,
        effectiveDate: '2026-03-24T00:00:00',
      });

      const onClose = vi.fn();
      const onSuccess = vi.fn();
      const { rerender } = render(
        <DowngradeConfirmModal {...defaultProps} onClose={onClose} onSuccess={onSuccess} />
      );

      // Confirm downgrade
      const confirmBtn = screen.getAllByText('Confirm Downgrade');
      fireEvent.click(confirmBtn[confirmBtn.length - 1]); // click the button, not header
      await waitFor(() => expect(screen.getByText('Downgrade Scheduled')).toBeTruthy());

      // Click "Got it" to close
      fireEvent.click(screen.getByText('Got it'));

      // Close and re-open
      rerender(<DowngradeConfirmModal {...defaultProps} isOpen={false} onClose={onClose} onSuccess={onSuccess} />);
      rerender(<DowngradeConfirmModal {...defaultProps} isOpen={true} onClose={onClose} onSuccess={onSuccess} />);

      // Should be back in confirmation state
      await waitFor(() => {
        expect(screen.queryByText('Downgrade Scheduled')).toBeNull();
        expect(screen.getByText('Cancel')).toBeTruthy();
      });
    });

    it('should reset error state when re-opened', async () => {
      mockScheduleDowngrade.mockRejectedValueOnce(new Error('API error'));

      const onClose = vi.fn();
      const { rerender } = render(
        <DowngradeConfirmModal {...defaultProps} onClose={onClose} />
      );

      const confirmBtns = screen.getAllByText('Confirm Downgrade');
      fireEvent.click(confirmBtns[confirmBtns.length - 1]);
      await waitFor(() => expect(screen.getByText('API error')).toBeTruthy());

      // Close and re-open
      rerender(<DowngradeConfirmModal {...defaultProps} isOpen={false} onClose={onClose} />);
      rerender(<DowngradeConfirmModal {...defaultProps} isOpen={true} onClose={onClose} />);

      await waitFor(() => {
        expect(screen.queryByText('API error')).toBeNull();
      });
    });
  });

  // =========================================================================
  // Callback Timing (critical fix)
  // =========================================================================

  describe('callback timing', () => {
    it('should NOT call onSuccess immediately on API success', async () => {
      mockScheduleDowngrade.mockResolvedValueOnce({
        success: true,
        effectiveDate: '2026-03-24T00:00:00',
      });

      const onSuccess = vi.fn();
      renderModal({ onSuccess });

      const confirmBtns = screen.getAllByText('Confirm Downgrade');
      fireEvent.click(confirmBtns[confirmBtns.length - 1]);

      await waitFor(() => expect(screen.getByText('Downgrade Scheduled')).toBeTruthy());
      expect(onSuccess).not.toHaveBeenCalled();
    });

    it('should call onSuccess only when user clicks Got it', async () => {
      mockScheduleDowngrade.mockResolvedValueOnce({
        success: true,
        effectiveDate: '2026-03-24T00:00:00',
      });

      const onSuccess = vi.fn();
      const onClose = vi.fn();
      renderModal({ onSuccess, onClose });

      const confirmBtns = screen.getAllByText('Confirm Downgrade');
      fireEvent.click(confirmBtns[confirmBtns.length - 1]);
      await waitFor(() => expect(screen.getByText('Got it')).toBeTruthy());

      fireEvent.click(screen.getByText('Got it'));
      expect(onSuccess).toHaveBeenCalledTimes(1);
      expect(onSuccess).toHaveBeenCalledWith('2026-03-24T00:00:00');
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('should NOT call onSuccess when cancelling before confirm', () => {
      const onSuccess = vi.fn();
      const onClose = vi.fn();
      renderModal({ onSuccess, onClose });

      fireEvent.click(screen.getByText('Cancel'));
      expect(onSuccess).not.toHaveBeenCalled();
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  // =========================================================================
  // API Error Handling
  // =========================================================================

  describe('API error handling', () => {
    it('should show error when API returns success=false', async () => {
      mockScheduleDowngrade.mockResolvedValueOnce({
        success: false,
        message: 'Not a valid downgrade',
      });

      renderModal();
      const confirmBtns = screen.getAllByText('Confirm Downgrade');
      fireEvent.click(confirmBtns[confirmBtns.length - 1]);

      await waitFor(() => {
        expect(screen.getByText('Not a valid downgrade')).toBeTruthy();
      });
      expect(screen.queryByText('Downgrade Scheduled')).toBeNull();
    });

    it('should show error when API throws', async () => {
      mockScheduleDowngrade.mockRejectedValueOnce(new Error('Stripe error'));

      renderModal();
      const confirmBtns = screen.getAllByText('Confirm Downgrade');
      fireEvent.click(confirmBtns[confirmBtns.length - 1]);

      await waitFor(() => {
        expect(screen.getByText('Stripe error')).toBeTruthy();
      });
    });

    it('should call scheduleDowngrade with correct plan code', async () => {
      mockScheduleDowngrade.mockResolvedValueOnce({ success: true });

      renderModal({ targetPlan: { code: 'STARTER', name: 'Starter', price: 10 } });
      const confirmBtns = screen.getAllByText('Confirm Downgrade');
      fireEvent.click(confirmBtns[confirmBtns.length - 1]);

      await waitFor(() => {
        expect(mockScheduleDowngrade).toHaveBeenCalledWith('STARTER');
      });
    });
  });

  // =========================================================================
  // Edge Cases
  // =========================================================================

  describe('edge cases', () => {
    it('should handle onSuccess being undefined', async () => {
      mockScheduleDowngrade.mockResolvedValueOnce({
        success: true,
        effectiveDate: '2026-03-24',
      });

      renderModal({ onSuccess: undefined });
      const confirmBtns = screen.getAllByText('Confirm Downgrade');
      fireEvent.click(confirmBtns[confirmBtns.length - 1]);
      await waitFor(() => expect(screen.getByText('Got it')).toBeTruthy());

      expect(() => fireEvent.click(screen.getByText('Got it'))).not.toThrow();
    });

    it('should handle zero-price target plan', () => {
      renderModal({
        targetPlan: { code: 'FREE', name: 'Free', price: 0 },
      });
      expect(screen.getByText('$0/mo')).toBeTruthy();
    });

    it('should show correct info for downgrade to free', () => {
      renderModal({
        targetPlan: { code: 'FREE', name: 'Free', price: 0 },
        billingCycle: 'monthly',
      });
      // Component shows the target plan price via perMonth
      expect(screen.getByText('$0/mo')).toBeTruthy();
      expect(screen.getByText('Free')).toBeTruthy();
    });
  });
});
