// @vitest-environment jsdom
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  createSubscription: vi.fn(),
  offer: {
    current: { status: 'AVAILABLE', offerId: 42 },
    preview: { offerId: 42, offerVersion: 5, plans: [
      { planCode: 'PRO', bonusCredits: 10000, paygFaceValueUsd: 10, status: 'ELIGIBLE' },
    ] },
    candidateCode: null, isLoading: false, isError: false,
    refresh: vi.fn(),
  },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) => values ? `${key}:${JSON.stringify(values)}` : key,
  useLocale: () => 'en',
}));
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('next/link', () => ({ default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a> }));
vi.mock('@/lib/hooks/usePersonalOffer', () => ({ usePersonalOffer: () => mocks.offer }));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({
    createSubscription: mocks.createSubscription,
    subscription: { subscription: { planCode: 'FREE', creditTierIndex: 0 } },
    isLoading: false, isProcessingCheckout: false, forceLoadSubscription: vi.fn(),
  }),
  usePlans: () => ({ isUpgrade: () => true, isDowngrade: () => false, getPlanOrder: () => 0,
    getPlansByOrder: () => [], plans: null, isLoading: false }),
  usePaygTiers: () => ({ tiers: [], configured: false, isLoading: false }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isAuthenticated: true, user: { sub: 'account-a' } }) }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ loginWithRedirect: vi.fn() }) }));
vi.mock('@/hooks/usePricingEvent', () => ({ usePricingEvent: () => ({ event: null }) }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/components/pricing/PlanSelector', () => ({ default: ({ plan, billingCycle, onPlanSelect }: {
  plan: { id: string }; billingCycle: 'monthly' | 'yearly';
  onPlanSelect: (id: string, cycle: 'monthly' | 'yearly') => void;
}) => <button type="button" onClick={() => onPlanSelect(plan.id, billingCycle)}>{`choose-${plan.id}`}</button> }));
vi.mock('@/components/pricing/ComparePlansLink', () => ({ default: () => null }));
vi.mock('@/components/billing/TopUpModal', () => ({ default: () => null }));
vi.mock('@/components/common/Notification', () => ({ default: () => null }));
vi.mock('@/components/EnterprisePricingModal', () => ({ default: () => null }));
vi.mock('@/components/UpgradeModal', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ default: () => null }));
vi.mock('@/components/ui/slider', () => ({ Slider: () => null }));
vi.mock('@/components/reward/RewardCodeInline', () => ({ RewardCodeInline: () => null }));
vi.mock('@/components/billing', () => ({
  ScheduledChangeAlert: () => null, DowngradeConfirmModal: () => null,
  BillingCycleChangeModal: () => null, CreditChangeModal: () => null,
}));

import PricingPage from '../PricingPageContent';

beforeEach(() => {
  history.replaceState(null, '', '/en/app/settings/pricing?billingCycle=monthly&creditTierIndex=2');
  mocks.createSubscription.mockReset().mockResolvedValue({ offerStatus: 'ATTACHED', url: window.location.href });
});

describe('personal offer checkout from pricing', () => {
  it('sends the selected plan, pack, cadence, and verified offer version to checkout', async () => {
    render(<PricingPage />);
    fireEvent.click(screen.getByRole('button', { name: 'choose-pro' }));

    await waitFor(() => expect(mocks.createSubscription).toHaveBeenCalledWith(expect.objectContaining({
      planCode: 'PRO', billingCycle: 'monthly', creditTierIndex: '2',
      personalOfferId: 42, offerVersion: 5,
    })));
  });
});
