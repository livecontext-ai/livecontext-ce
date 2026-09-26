import { describe, it, expect, vi } from 'vitest';

// vi.hoisted so the spies exist before the hoisted vi.mock factories reference them.
const { showCeCloudCreditModal, showModelNotManagedModal, showCloudLinkPlanRequiredModal } = vi.hoisted(() => ({
  showCeCloudCreditModal: vi.fn(),
  showModelNotManagedModal: vi.fn(),
  showCloudLinkPlanRequiredModal: vi.fn(),
}));

// Cloud edition: the helper must be a strict no-op so the existing Cloud Stripe flow stays in charge.
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/components/billing/CeCloudCreditModal', () => ({ showCeCloudCreditModal }));
vi.mock('@/components/billing/ModelNotManagedModal', () => ({ showModelNotManagedModal }));
vi.mock('@/components/billing/CloudLinkPlanRequiredModal', () => ({ showCloudLinkPlanRequiredModal }));

import { handleCeRelayError } from '@/lib/billing/ceRelayErrorModals';

describe('handleCeRelayError (Cloud edition)', () => {
  it('is a no-op even for a recognizable relay error', () => {
    const handled = handleCeRelayError('Cloud LLM relay returned 402: {"error":"INSUFFICIENT_CREDITS"}');
    expect(handled).toBe(false);
    expect(showCeCloudCreditModal).not.toHaveBeenCalled();
    expect(showModelNotManagedModal).not.toHaveBeenCalled();
  });

  it('is a no-op for a CLOUD_LINK_PLAN_REQUIRED refusal too (the cloud never relays to itself)', () => {
    expect(handleCeRelayError('{"error":"CLOUD_LINK_PLAN_REQUIRED"}')).toBe(false);
    expect(showCloudLinkPlanRequiredModal).not.toHaveBeenCalled();
  });
});
