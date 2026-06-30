// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

// Mock next-intl
vi.mock('next-intl', () => ({
  useTranslations: () => {
    const t = (key: string, params?: Record<string, any>) => {
      const translations: Record<string, string> = {
        title: 'Change Billing Cycle',
        changeScheduled: 'Change Scheduled',
        changeApplied: 'Change Applied',
        successMessage: 'Your billing cycle change has been scheduled!',
        successImmediateMessage: 'Your billing cycle has been changed immediately!',
        effective: `Effective: ${params?.date ?? ''}`,
        planWillSwitch: `Your ${params?.plan ?? ''} plan will switch to ${params?.cycle ?? ''} billing.`,
        current: 'Current',
        new: 'New',
        save20: 'Base -20%',
        monthly: 'Monthly',
        yearly: 'Yearly',
        mo: 'mo',
        scheduledFor: `Scheduled for ${params?.date ?? ''}`,
        yearlyBillingInfo: 'Your yearly billing will start at your next renewal.',
        monthlyBillingInfo: 'Your monthly billing will start at your next renewal.',
        effectiveImmediately: 'Effective immediately',
        yearlyImmediateInfo: 'Your yearly billing starts now with prorated charges.',
        endOfBillingPeriod: 'end of billing period',
        done: 'Done',
        cancel: 'Cancel',
        confirm: 'Confirm',
        confirming: 'Confirming...',
        scheduling: 'Scheduling...',
        switchTo: `Switch to ${params?.cycle ?? ''}`,
        scheduleFailed: 'Schedule failed',
        error: 'An error occurred',
        taxNote: 'Prices exclude tax. VAT/sales tax is calculated at checkout.',
      };
      return translations[key] || key;
    };
    return t;
  },
}));

// Mock unifiedApiService
const mockChangeBillingCycle = vi.fn();
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: {
    changeBillingCycle: (...args: any[]) => mockChangeBillingCycle(...args),
  },
}));

// Import after mocks
import BillingCycleChangeModal from '../BillingCycleChangeModal';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const defaultProps = {
  isOpen: true,
  onClose: vi.fn(),
  onSuccess: vi.fn(),
  currentCycle: 'monthly' as const,
  planName: 'Starter',
  monthlyPrice: 10,
  yearlyPrice: 96,
  currentPeriodEnd: '2026-03-24T00:00:00',
};

function renderModal(overrides = {}) {
  const props = { ...defaultProps, ...overrides };
  return render(<BillingCycleChangeModal {...props} />);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('BillingCycleChangeModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockChangeBillingCycle.mockReset();
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
      expect(screen.queryByText('Change Billing Cycle')).toBeNull();
    });

    it('should render confirmation state when isOpen is true', () => {
      renderModal();
      expect(screen.getByText('Change Billing Cycle')).toBeTruthy();
      expect(screen.getByText('Current')).toBeTruthy();
      expect(screen.getByText('New')).toBeTruthy();
    });

    it('should show Monthly -> Yearly when current is monthly', () => {
      renderModal({ currentCycle: 'monthly' });
      expect(screen.getByText('Monthly')).toBeTruthy();
      expect(screen.getByText('Yearly')).toBeTruthy();
      expect(screen.getByText('Base -20%')).toBeTruthy();
    });

    it('should show Yearly -> Monthly when current is yearly', () => {
      renderModal({ currentCycle: 'yearly' });
      // Should NOT show the yearly base discount when downgrading to monthly
      const save20Elements = screen.queryAllByText('Base -20%');
      expect(save20Elements.length).toBe(0);
    });

    it('should show Cancel and Confirm buttons in confirmation state (monthly -> yearly)', () => {
      renderModal();
      expect(screen.getByText('Cancel')).toBeTruthy();
      expect(screen.getByText('Confirm')).toBeTruthy();
    });

    it('should show Effective immediately for monthly -> yearly', () => {
      renderModal({ currentCycle: 'monthly', currentPeriodEnd: '2026-03-24T00:00:00' });
      // monthly->yearly shows immediate change info, not scheduled
      expect(screen.getByText('Effective immediately')).toBeTruthy();
    });

    it('should format currentPeriodEnd date for yearly -> monthly', () => {
      renderModal({ currentCycle: 'yearly', currentPeriodEnd: '2026-03-24T00:00:00' });
      // yearly->monthly shows scheduled info with date
      const modal = screen.getByText(/Scheduled for/);
      expect(modal).toBeTruthy();
    });

    it('should handle undefined currentPeriodEnd with fallback', () => {
      renderModal({ currentCycle: 'yearly', currentPeriodEnd: undefined });
      expect(screen.getByText(/end of billing period/)).toBeTruthy();
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
    it('should always show confirmation state when re-opened after success', async () => {
      // Simulate: open → confirm → success → close → re-open
      mockChangeBillingCycle.mockResolvedValueOnce({
        success: true,
        effectiveDate: '2026-03-24T00:00:00',
      });

      const onClose = vi.fn();
      const onSuccess = vi.fn();
      const { rerender } = render(
        <BillingCycleChangeModal {...defaultProps} onClose={onClose} onSuccess={onSuccess} />
      );

      // Step 1: Confirm (monthly->yearly uses "Confirm" button)
      fireEvent.click(screen.getByText('Confirm'));
      await waitFor(() => {
        expect(screen.getByText('Change Applied')).toBeTruthy();
      });

      // Step 2: Click Done to close
      fireEvent.click(screen.getByText('Done'));
      expect(onSuccess).toHaveBeenCalledWith('2026-03-24T00:00:00');

      // Step 3: Re-open (simulate parent setting isOpen=true again)
      rerender(
        <BillingCycleChangeModal {...defaultProps} isOpen={false} onClose={onClose} onSuccess={onSuccess} />
      );
      rerender(
        <BillingCycleChangeModal {...defaultProps} isOpen={true} onClose={onClose} onSuccess={onSuccess} />
      );

      // Step 4: Should be in confirmation state, NOT success state
      await waitFor(() => {
        expect(screen.getByText('Change Billing Cycle')).toBeTruthy();
        expect(screen.queryByText('Change Applied')).toBeNull();
        expect(screen.getByText('Cancel')).toBeTruthy();
        expect(screen.getByText('Confirm')).toBeTruthy();
      });
    });

    it('should reset error state when re-opened after error', async () => {
      mockChangeBillingCycle.mockRejectedValueOnce(new Error('Network error'));

      const onClose = vi.fn();
      const { rerender } = render(
        <BillingCycleChangeModal {...defaultProps} onClose={onClose} />
      );

      // Step 1: Trigger error (monthly->yearly uses "Confirm")
      fireEvent.click(screen.getByText('Confirm'));
      await waitFor(() => {
        expect(screen.getByText('Network error')).toBeTruthy();
      });

      // Step 2: Close and re-open
      rerender(<BillingCycleChangeModal {...defaultProps} isOpen={false} onClose={onClose} />);
      rerender(<BillingCycleChangeModal {...defaultProps} isOpen={true} onClose={onClose} />);

      // Step 3: No error should be shown
      await waitFor(() => {
        expect(screen.queryByText('Network error')).toBeNull();
        expect(screen.getByText('Confirm')).toBeTruthy();
      });
    });
  });

  // =========================================================================
  // Callback Timing (critical fix)
  // =========================================================================

  describe('callback timing', () => {
    it('should NOT call onSuccess immediately on API success', async () => {
      mockChangeBillingCycle.mockResolvedValueOnce({
        success: true,
        effectiveDate: '2026-03-24T00:00:00',
      });

      const onSuccess = vi.fn();
      renderModal({ onSuccess });

      fireEvent.click(screen.getByText('Confirm'));

      await waitFor(() => {
        expect(screen.getByText('Change Applied')).toBeTruthy();
      });

      // onSuccess should NOT have been called yet
      expect(onSuccess).not.toHaveBeenCalled();
    });

    it('should call onSuccess when user clicks Done after success', async () => {
      mockChangeBillingCycle.mockResolvedValueOnce({
        success: true,
        effectiveDate: '2026-03-24T00:00:00',
      });

      const onSuccess = vi.fn();
      const onClose = vi.fn();
      renderModal({ onSuccess, onClose });

      // Confirm (monthly->yearly uses "Confirm")
      fireEvent.click(screen.getByText('Confirm'));
      await waitFor(() => expect(screen.getByText('Done')).toBeTruthy());

      // Click Done
      fireEvent.click(screen.getByText('Done'));

      expect(onSuccess).toHaveBeenCalledTimes(1);
      expect(onSuccess).toHaveBeenCalledWith('2026-03-24T00:00:00');
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('should call onSuccess when user clicks X after success', async () => {
      mockChangeBillingCycle.mockResolvedValueOnce({
        success: true,
        effectiveDate: '2026-03-24T00:00:00',
      });

      const onSuccess = vi.fn();
      renderModal({ onSuccess });

      // Confirm (monthly->yearly uses "Confirm")
      fireEvent.click(screen.getByText('Confirm'));
      await waitFor(() => expect(screen.getByText('Change Applied')).toBeTruthy());

      // Click X (close) button - should still trigger onSuccess since change was made
      const closeButtons = screen.getAllByRole('button');
      const xButton = closeButtons.find(b => b.querySelector('svg'));
      if (xButton) fireEvent.click(xButton);

      expect(onSuccess).toHaveBeenCalledTimes(1);
    });

    it('should NOT call onSuccess when closing without confirming', () => {
      const onSuccess = vi.fn();
      const onClose = vi.fn();
      renderModal({ onSuccess, onClose });

      // Click Cancel without confirming
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
      mockChangeBillingCycle.mockResolvedValueOnce({
        success: false,
        message: 'Stripe error: card declined',
      });

      renderModal();
      fireEvent.click(screen.getByText('Confirm'));

      await waitFor(() => {
        expect(screen.getByText('Stripe error: card declined')).toBeTruthy();
      });

      // Should still be in confirmation state (not success)
      expect(screen.queryByText('Change Applied')).toBeNull();
      expect(screen.getByText('Confirm')).toBeTruthy();
    });

    it('should show error when API throws an exception', async () => {
      mockChangeBillingCycle.mockRejectedValueOnce(new Error('Network failure'));

      renderModal();
      fireEvent.click(screen.getByText('Confirm'));

      await waitFor(() => {
        expect(screen.getByText('Network failure')).toBeTruthy();
      });
    });

    it('should show fallback error when API throws non-Error', async () => {
      mockChangeBillingCycle.mockRejectedValueOnce('unknown');

      renderModal();
      fireEvent.click(screen.getByText('Confirm'));

      await waitFor(() => {
        expect(screen.getByText('An error occurred')).toBeTruthy();
      });
    });

    it('should handle missing effectiveDate in success response', async () => {
      mockChangeBillingCycle.mockResolvedValueOnce({
        success: true,
        // effectiveDate is undefined
      });

      const onSuccess = vi.fn();
      renderModal({ onSuccess });

      fireEvent.click(screen.getByText('Confirm'));
      await waitFor(() => expect(screen.getByText('Change Applied')).toBeTruthy());

      // For monthly->yearly (isUpgradingToYearly=true), the effective date line is not shown
      // The success message uses successImmediateMessage instead
      expect(screen.getByText('Your billing cycle has been changed immediately!')).toBeTruthy();

      // Click Done - onSuccess should be called with fallback ISO string (since effectiveDate is null, handleClose uses new Date().toISOString())
      fireEvent.click(screen.getByText('Done'));
      expect(onSuccess).toHaveBeenCalledTimes(1);
    });
  });

  // =========================================================================
  // Loading State
  // =========================================================================

  describe('loading state', () => {
    it('should disable buttons during loading', async () => {
      // Simulate a slow API call
      let resolvePromise: (value: any) => void;
      mockChangeBillingCycle.mockReturnValueOnce(
        new Promise(resolve => { resolvePromise = resolve; })
      );

      renderModal();
      fireEvent.click(screen.getByText('Confirm'));

      // Should show loading state (monthly->yearly uses "Confirming...")
      await waitFor(() => {
        expect(screen.getByText('Confirming...')).toBeTruthy();
      });

      // Cancel button should be disabled
      const cancelBtn = screen.getByText('Cancel');
      expect(cancelBtn.closest('button')?.disabled).toBe(true);

      // Resolve the promise
      await act(async () => {
        resolvePromise!({ success: true, effectiveDate: '2026-03-24' });
      });
    });

    it('should prevent double submit', async () => {
      let resolvePromise: (value: any) => void;
      mockChangeBillingCycle.mockReturnValueOnce(
        new Promise(resolve => { resolvePromise = resolve; })
      );

      renderModal();

      // Click twice rapidly (monthly->yearly uses "Confirm")
      const confirmBtn = screen.getByText('Confirm');
      fireEvent.click(confirmBtn);
      fireEvent.click(confirmBtn);

      // API should only be called once (second click ignored due to loading state)
      expect(mockChangeBillingCycle).toHaveBeenCalledTimes(1);

      await act(async () => {
        resolvePromise!({ success: true, effectiveDate: '2026-03-24' });
      });
    });
  });

  // =========================================================================
  // Edge Cases
  // =========================================================================

  describe('edge cases', () => {
    it('should handle onSuccess being undefined', async () => {
      mockChangeBillingCycle.mockResolvedValueOnce({
        success: true,
        effectiveDate: '2026-03-24',
      });

      // Render without onSuccess
      renderModal({ onSuccess: undefined });

      fireEvent.click(screen.getByText('Confirm'));
      await waitFor(() => expect(screen.getByText('Done')).toBeTruthy());

      // Should not crash when clicking Done
      expect(() => fireEvent.click(screen.getByText('Done'))).not.toThrow();
    });

    it('should show correct prices: monthly $10/mo -> yearly $8/mo', () => {
      renderModal({
        currentCycle: 'monthly',
        monthlyPrice: 10,
        yearlyPrice: 96, // 96/12 = $8/mo
      });

      expect(screen.getByText('$10/mo')).toBeTruthy();
      expect(screen.getByText('$8/mo')).toBeTruthy();
    });

    it('should show correct prices: yearly $8/mo -> monthly $10/mo', () => {
      renderModal({
        currentCycle: 'yearly',
        monthlyPrice: 10,
        yearlyPrice: 96,
      });

      expect(screen.getByText('$8/mo')).toBeTruthy();
      expect(screen.getByText('$10/mo')).toBeTruthy();
    });

    it('should call API with correct target cycle', async () => {
      mockChangeBillingCycle.mockResolvedValueOnce({ success: true });

      renderModal({ currentCycle: 'monthly' });
      fireEvent.click(screen.getByText('Confirm'));

      await waitFor(() => {
        expect(mockChangeBillingCycle).toHaveBeenCalledWith('yearly');
      });
    });

    it('should call API with monthly when current is yearly', async () => {
      mockChangeBillingCycle.mockResolvedValueOnce({ success: true });

      renderModal({ currentCycle: 'yearly' });
      fireEvent.click(screen.getByText('Switch to Monthly'));

      await waitFor(() => {
        expect(mockChangeBillingCycle).toHaveBeenCalledWith('monthly');
      });
    });
  });
});
