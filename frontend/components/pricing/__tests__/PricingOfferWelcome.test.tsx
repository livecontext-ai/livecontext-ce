// @vitest-environment jsdom
/**
 * Back from paying through a partner's offer (Stripe's success URL carries ?offer=<token>): the
 * offer's welcome replaces the plain upgrade confirmation, and the token leaves the address bar
 * with the other Stripe markers. Any other return keeps the upgrade confirmation.
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  offer: { current: null, preview: null, candidateCode: null, isLoading: false, isError: false, refresh: () => {} },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
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
    createSubscription: vi.fn(),
    subscription: { subscription: { planCode: 'FREE', creditTierIndex: 0 } },
    isLoading: false, isProcessingCheckout: false, forceLoadSubscription: vi.fn(),
  }),
  usePlans: () => ({ isUpgrade: () => true, isDowngrade: () => false, getPlanOrder: () => 0,
    getPlansByOrder: () => [], plans: null, isLoading: false }),
  usePaygTiers: () => ({ tiers: [], configured: false, isLoading: false }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isAuthenticated: false, user: null }) }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ loginWithRedirect: vi.fn() }) }));
vi.mock('@/hooks/usePricingEvent', () => ({ usePricingEvent: () => ({ event: null }) }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/components/pricing/PlanSelector', () => ({ default: () => null }));
vi.mock('@/components/partner/PartnerRecommendationBanner', () => ({ PartnerRecommendationBanner: () => null }));
vi.mock('@/components/pricing/ComparePlansLink', () => ({ default: () => null }));
vi.mock('@/components/billing/TopUpModal', () => ({ default: () => null }));
vi.mock('@/components/common/Notification', () => ({ default: () => null }));
vi.mock('@/components/EnterprisePricingModal', () => ({ default: () => null }));
vi.mock('@/components/UpgradeModal', () => ({ default: ({ open, state }: { open: boolean; state: string }) => (open ? <div data-testid="upgrade-modal" data-state={state} /> : null) }));
vi.mock('@/components/partner/offer/PartnerOfferWelcomeModal', () => ({
  PartnerOfferWelcomeModal: ({ token, planState }: { token: string; planState: string }) => <div data-testid="offer-welcome" data-token={token} data-state={planState} />,
}));
vi.mock('@/components/Toast', () => ({ default: () => null }));
vi.mock('@/components/ui/slider', () => ({ Slider: () => null }));
vi.mock('@/components/reward/RewardCodeInline', () => ({ RewardCodeInline: () => null }));
vi.mock('@/components/billing', () => ({
  ScheduledChangeAlert: () => null, DowngradeConfirmModal: () => null,
  BillingCycleChangeModal: () => null, CreditChangeModal: () => null,
}));

import PricingPage from '../PricingPageContent';

describe('pricing page, back from a partner offer checkout', () => {
  afterEach(cleanup);

  it('the offer\'s welcome opens instead of the upgrade confirmation, and the token leaves the URL', () => {
    history.replaceState(null, '', '/en/app/settings/pricing?checkout=success&session_id=cs_1&offer=Abc23XyZ9k');
    render(<PricingPage />);

    const welcome = screen.getByTestId('offer-welcome');
    expect(welcome.dataset.token).toBe('Abc23XyZ9k');
    expect(welcome.dataset.state).toBe('processing');
    expect(screen.queryByTestId('upgrade-modal')).toBeNull();
    expect(window.location.search).toBe('');
  });

  it('an ordinary checkout return keeps the upgrade confirmation', () => {
    history.replaceState(null, '', '/en/app/settings/pricing?checkout=success&session_id=cs_1');
    render(<PricingPage />);

    expect(screen.getByTestId('upgrade-modal').dataset.state).toBe('processing');
    expect(screen.queryByTestId('offer-welcome')).toBeNull();
  });

  it('a token that is not one (written into the URL by hand) opens no welcome', () => {
    history.replaceState(null, '', '/en/app/settings/pricing?checkout=success&session_id=cs_1&offer=%3Cscript%3E');
    render(<PricingPage />);

    expect(screen.queryByTestId('offer-welcome')).toBeNull();
    expect(screen.getByTestId('upgrade-modal')).toBeTruthy();
    expect(window.location.search).toBe('');
  });
});
