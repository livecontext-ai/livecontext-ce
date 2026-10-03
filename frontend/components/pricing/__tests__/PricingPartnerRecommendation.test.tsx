// @vitest-environment jsdom
/**
 * The pricing page opened from a partner's recommended link: the banner is mounted, and every
 * plan card is told which plan the partner recommended (the card itself decides whether that is
 * still the selected one). An ordinary visit mounts no banner and names no partner.
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  cards: [] as { id: string; selected: string | null; recommended: string | null; label?: string }[],
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
vi.mock('@/components/pricing/PlanSelector', () => ({
  default: ({ plan, selectedPlanCode, partnerRecommendedPlanCode, partnerRecommendedLabel }: {
    plan: { id: string }; selectedPlanCode?: string | null; partnerRecommendedPlanCode?: string | null; partnerRecommendedLabel?: string;
  }) => {
    mocks.cards.push({ id: plan.id, selected: selectedPlanCode ?? null, recommended: partnerRecommendedPlanCode ?? null, label: partnerRecommendedLabel });
    return <div data-testid={`card-${plan.id}`} />;
  },
}));
vi.mock('@/components/partner/PartnerRecommendationBanner', () => ({
  PartnerRecommendationBanner: () => <div data-testid="partner-recommendation-banner" />,
}));
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

const lastCard = (id: string) => [...mocks.cards].reverse().find((c) => c.id === id);

describe('pricing page from a partner recommended link', () => {
  beforeEach(() => { mocks.cards = []; });
  afterEach(cleanup);

  it('mounts the banner, selects the recommended plan and tells the cards which plan the partner chose', () => {
    history.replaceState(null, '', '/en/app/settings/pricing?pricingMode=subscription&planCode=PRO&creditTierIndex=5&billingCycle=monthly&lc_ref=NORTHWIND&lc_rec=pro.5.monthly');
    render(<PricingPage />);

    expect(screen.getByTestId('partner-recommendation-banner')).toBeTruthy();
    expect(lastCard('pro')).toMatchObject({ selected: 'PRO', recommended: 'PRO', label: 'badge' });
    expect(lastCard('team')).toMatchObject({ selected: 'PRO', recommended: 'PRO' });
  });

  it('regression: back from sign-in with the visitor\'s own choice (Team), Pro is still the recommended plan, Team only selected', () => {
    history.replaceState(null, '', '/en/app/settings/pricing?pricingMode=subscription&planCode=TEAM&creditTierIndex=7&billingCycle=yearly&lc_ref=NORTHWIND&lc_rec=pro.5.monthly');
    render(<PricingPage />);

    // The banner still states the recommendation; no card is labelled with it, since the page no
    // longer shows what was recommended.
    expect(screen.getByTestId('partner-recommendation-banner')).toBeTruthy();
    expect(lastCard('team')).toMatchObject({ selected: 'TEAM', recommended: null });
    expect(lastCard('pro')).toMatchObject({ recommended: null });
  });

  it('regression: the recommended plan with other credits or another cycle is not labelled as the recommendation', () => {
    history.replaceState(null, '', '/en/app/settings/pricing?pricingMode=subscription&planCode=PRO&creditTierIndex=7&billingCycle=monthly&lc_ref=NORTHWIND&lc_rec=pro.5.monthly');
    render(<PricingPage />);
    expect(lastCard('pro')).toMatchObject({ selected: 'PRO', recommended: null });
    cleanup();
    mocks.cards = [];

    history.replaceState(null, '', '/en/app/settings/pricing?pricingMode=subscription&planCode=PRO&creditTierIndex=5&billingCycle=yearly&lc_ref=NORTHWIND&lc_rec=pro.5.monthly');
    render(<PricingPage />);
    expect(lastCard('pro')).toMatchObject({ selected: 'PRO', recommended: null });
  });

  it('an ordinary pricing visit mounts no banner and names no recommended plan', () => {
    history.replaceState(null, '', '/en/app/settings/pricing?pricingMode=subscription&planCode=PRO&creditTierIndex=5');
    render(<PricingPage />);

    expect(screen.queryByTestId('partner-recommendation-banner')).toBeNull();
    expect(lastCard('pro')?.recommended).toBeNull();
  });

  it('an incoherent recommendation (a tier Starter does not offer) is ignored rather than vouched for', () => {
    history.replaceState(null, '', '/en/app/settings/pricing?planCode=STARTER&creditTierIndex=9&lc_ref=NORTHWIND&lc_rec=starter.9.monthly');
    render(<PricingPage />);

    expect(screen.queryByTestId('partner-recommendation-banner')).toBeNull();
    expect(lastCard('starter')?.recommended).toBeNull();
  });
});
