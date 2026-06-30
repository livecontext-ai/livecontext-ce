import { describe, it, expect, vi, beforeEach } from 'vitest';

// vi.hoisted so the spies exist before the hoisted vi.mock factories reference them.
const { showCeCloudCreditModal, showModelNotManagedModal } = vi.hoisted(() => ({
  showCeCloudCreditModal: vi.fn(),
  showModelNotManagedModal: vi.fn(),
}));

// CE edition: the helper must act.
vi.mock('@/lib/edition', () => ({ IS_CE: true }));
vi.mock('@/components/billing/CeCloudCreditModal', () => ({ showCeCloudCreditModal }));
vi.mock('@/components/billing/ModelNotManagedModal', () => ({ showModelNotManagedModal }));

import { handleCeRelayError } from '@/lib/billing/ceRelayErrorModals';

describe('handleCeRelayError (CE edition)', () => {
  beforeEach(() => {
    showCeCloudCreditModal.mockClear();
    showModelNotManagedModal.mockClear();
  });

  it('routes an INSUFFICIENT_CREDITS relay error to the cloud-credit modal', () => {
    const handled = handleCeRelayError('Cloud LLM relay returned 402: {"error":"INSUFFICIENT_CREDITS"}');
    expect(handled).toBe(true);
    expect(showCeCloudCreditModal).toHaveBeenCalledTimes(1);
    expect(showModelNotManagedModal).not.toHaveBeenCalled();
  });

  it('routes a MODEL_NOT_SUPPORTED relay error to the model-not-managed modal', () => {
    const handled = handleCeRelayError({ message: 'Cloud LLM relay returned 400: {"error":"MODEL_NOT_SUPPORTED"}' });
    expect(handled).toBe(true);
    expect(showModelNotManagedModal).toHaveBeenCalledTimes(1);
    expect(showCeCloudCreditModal).not.toHaveBeenCalled();
  });

  it('returns false and shows nothing for an unrelated error', () => {
    const handled = handleCeRelayError(new Error('network timeout'));
    expect(handled).toBe(false);
    expect(showCeCloudCreditModal).not.toHaveBeenCalled();
    expect(showModelNotManagedModal).not.toHaveBeenCalled();
  });
});
