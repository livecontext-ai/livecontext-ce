import { describe, it, expect, vi, beforeEach } from 'vitest';

// vi.hoisted so the spies exist before the hoisted vi.mock factories reference them.
const { showCeCloudCreditModal, showModelNotManagedModal, showCloudLinkPlanRequiredModal } = vi.hoisted(() => ({
  showCeCloudCreditModal: vi.fn(),
  showModelNotManagedModal: vi.fn(),
  showCloudLinkPlanRequiredModal: vi.fn(),
}));

// CE edition: the helper must act.
vi.mock('@/lib/edition', () => ({ IS_CE: true }));
vi.mock('@/components/billing/CeCloudCreditModal', () => ({ showCeCloudCreditModal }));
vi.mock('@/components/billing/ModelNotManagedModal', () => ({ showModelNotManagedModal }));
vi.mock('@/components/billing/CloudLinkPlanRequiredModal', () => ({ showCloudLinkPlanRequiredModal }));

import { handleCeRelayError } from '@/lib/billing/ceRelayErrorModals';

describe('handleCeRelayError (CE edition)', () => {
  beforeEach(() => {
    showCeCloudCreditModal.mockClear();
    showModelNotManagedModal.mockClear();
    showCloudLinkPlanRequiredModal.mockClear();
  });

  it('routes a CLOUD_LINK_PLAN_REQUIRED refusal to the paid-plan modal, and only that one', () => {
    const handled = handleCeRelayError('Cloud LLM relay returned 403: {"error":"CLOUD_LINK_PLAN_REQUIRED","planCode":"FREE"}');
    expect(handled).toBe(true);
    expect(showCloudLinkPlanRequiredModal).toHaveBeenCalledTimes(1);
    expect(showCeCloudCreditModal).not.toHaveBeenCalled();
    expect(showModelNotManagedModal).not.toHaveBeenCalled();
  });

  it('prefers the paid-plan modal when a message also carries a credit token (the plan is the cause)', () => {
    const handled = handleCeRelayError({ message: 'CLOUD_LINK_PLAN_REQUIRED after INSUFFICIENT_CREDITS' });
    expect(handled).toBe(true);
    expect(showCloudLinkPlanRequiredModal).toHaveBeenCalledTimes(1);
    expect(showCeCloudCreditModal).not.toHaveBeenCalled();
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
    expect(showCloudLinkPlanRequiredModal).not.toHaveBeenCalled();
  });
});
