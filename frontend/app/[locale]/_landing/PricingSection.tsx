'use client';

import { useCallback, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Check } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { useAuth } from '@/lib/providers/smart-providers';
import { setLandingIntent, track } from '@/lib/analytics/analytics';
import { calcPrice, creditFactsFor, CREDIT_TIERS, FREE_AI_CREDITS } from '@/lib/billing/pricing-constants';
import DeploymentBadge from '@/components/pricing/DeploymentBadge';
import ReferencePrice from '@/components/pricing/ReferencePrice';
import FoundingPriceNote from '@/components/pricing/FoundingPriceNote';
import { usePricingEvent } from '@/hooks/usePricingEvent';
import type { ResolvedPricingEvent } from '@/lib/billing/pricing-events';
import FeatureLabel from '@/components/pricing/FeatureLabel';
import { planFeatureLabels } from '@/lib/billing/planFeatureLabels';
import ComparePlansLink from '@/components/pricing/ComparePlansLink';
import PlanComparisonDialog from '@/components/pricing/PlanComparisonDialog';

type Cycle = 'monthly' | 'yearly';
// The landing has no credit slider (by design); cards show the entry tier (5,000 credits).
const TIER_INDEX = 0;

type PlanCard = {
  id: 'free' | 'starter' | 'pro' | 'team' | 'enterprise';
  name: string;
  priceLabel: string;
  showSuffix: boolean;
  features: string[];
  cta: string;
  badge?: string;
  disabled?: boolean;
  // Enterprise is "talk to us": the CTA deep-links to the contact form with this
  // predefined, localized subject pre-filled. Other plans stay self-serve.
  contactMessage?: string;
};

export default function PricingSection() {
  const [cycle, setCycle] = useState<Cycle>('yearly');
  const tPricing = useTranslations('pricing');
  const tCards = useTranslations('pricing.planCards');
  const tBilling = useTranslations('pricing.billing');
  const tBiz = useTranslations('pricing.businessPlans');
  // useLocale(), NOT getClientLocale(): this section server-renders on the
  // landing (under NextIntlClientProvider). getClientLocale() resolves 'en' on
  // the server but the visitor's locale on the client, so a /fr visitor got
  // "5,000" in the server HTML vs "5 000" after hydration - a React #418
  // hydration mismatch on the whole landing page.
  const locale = useLocale();
  // Window resolved against the SERVER clock, so every visitor sees the same deadline
  // and the section cannot disagree with itself between server HTML and hydration.
  const { event: pricingEvent } = usePricingEvent();

  const selectCycle = (next: Cycle) => {
    setCycle(next);
    track('landing_pricing_cycle_toggled', { cycle: next });
  };

  // Entry tier credits, shared with the settings page so both stay in sync.
  const landingCredits = CREDIT_TIERS[TIER_INDEX].toLocaleString(locale);
  // The SAME mapping the settings page uses, not a second copy of it. The two
  // switches were identical, so nothing had diverged YET - what they cost was
  // every future edit twice, and adding the credits tooltip was one of them.
  // Computed once per render, not once per card: five cards each scanning the
  // examples list and re-formatting the same figures is work for one answer.
  const creditFacts = useMemo(() => creditFactsFor(locale), [locale]);
  const featuresFor = (id: string): string[] =>
    planFeatureLabels(id, { tCards, tPricing, credits: landingCredits, creditFacts,
      // Public page, no authenticated plans fetch: the seeded default. The settings
      // pricing page shows the live configured value.
      // The seeded figure, not the live plan row: the plans endpoint is not in the
      // gateway's public allow-list, so this surface cannot read it at all. If an admin
      // changes the allowance, the landing keeps quoting the shipped default until the
      // reader signs in - stale rather than wrong, and the only option available here.
      aiCredits: FREE_AI_CREDITS.toLocaleString(locale) });

  const plans: PlanCard[] = [
    {
      id: 'free',
      name: tCards('free.name'),
      priceLabel: tCards('price.free'),
      showSuffix: false,
      features: featuresFor('free'),
      cta: tCards('actions.startFree'),
    },
    {
      id: 'starter',
      name: tCards('starter.name'),
      priceLabel: `$${calcPrice('starter', cycle, TIER_INDEX)}`,
      showSuffix: true,
      badge: tCards('badges.recommended'),
      features: featuresFor('starter'),
      cta: tCards('starter.cta'),
    },
    {
      id: 'pro',
      name: tCards('pro.name'),
      priceLabel: `$${calcPrice('pro', cycle, TIER_INDEX)}`,
      showSuffix: true,
      features: featuresFor('pro'),
      cta: tCards('pro.cta'),
    },
  ];

  const businessPlans: PlanCard[] = [
    {
      id: 'team',
      name: tCards('team.name'),
      priceLabel: `$${calcPrice('team', cycle, TIER_INDEX)}`,
      showSuffix: true,
      features: featuresFor('team'),
      cta: tCards('team.cta'),
    },
    {
      id: 'enterprise',
      name: tCards('enterprise.name'),
      priceLabel: tCards('price.contactSales'),
      showSuffix: false,
      features: featuresFor('enterprise'),
      cta: tCards('enterprise.cta'),
      contactMessage: tCards('enterprise.contactMessage'),
    },
  ];

  return (
    <div className="w-full">
      <div className="flex justify-center">
        <div
          className="relative inline-flex items-center gap-1 p-1 rounded-xl"
          style={{ background: 'var(--bg-tertiary)' }}
        >
          <CycleButton active={cycle === 'monthly'} onClick={() => selectCycle('monthly')}>
            {tBilling('monthly')}
          </CycleButton>
          <CycleButton active={cycle === 'yearly'} onClick={() => selectCycle('yearly')}>
            {tBilling('yearly')}
            <span
              className="ml-2 px-2 py-0.5 text-[10px] font-bold rounded-full"
              style={{ background: 'rgba(16, 185, 129, 0.18)', color: '#34d399' }}
            >
              {tBilling('yearlyBadge')}
            </span>
          </CycleButton>
        </div>
      </div>

      <p className="mt-6 text-center text-sm" style={{ color: 'var(--text-muted)' }}>
        {tCards('startingNote', { credits: landingCredits })}
      </p>

      <div className="mt-10 grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4 md:gap-6 max-w-5xl mx-auto">
        {plans.map((p) => (
          <PlanCardView key={p.id} plan={p} cycle={cycle} event={pricingEvent} />
        ))}
      </div>

      <div className="mt-16 max-w-5xl mx-auto text-center">
        <h3
          className="text-2xl font-bold"
          style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif' }}
        >
          {tBiz('title')}
        </h3>
        <p className="mt-2 text-sm" style={{ color: 'var(--text-secondary)' }}>
          {tBiz('subtitle')}
        </p>
      </div>

      <div className="mt-6 grid grid-cols-1 sm:grid-cols-2 gap-4 md:gap-6 max-w-5xl mx-auto">
        {businessPlans.map((p) => (
          <PlanCardView key={p.id} plan={p} cycle={cycle} event={pricingEvent} />
        ))}
      </div>

      {/* The five cards say what each plan contains; this says it across plans,
          which is the question a visitor comparing three of them is actually
          asking. No current plan is marked here: a visitor has none. */}
      <div className="mt-10 flex justify-center">
        <ComparePlansLink />
      </div>

      <p className="mt-6 text-center text-sm" style={{ color: 'var(--text-muted)' }}>
        {tBilling('taxNote')}
      </p>

      <PlanComparisonDialog />
    </div>
  );
}

function CycleButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="relative z-10 flex items-center justify-center px-5 py-1.5 rounded-lg text-sm font-medium transition-colors duration-200"
      style={{
        background: active ? 'var(--bg-primary)' : 'transparent',
        color: active ? 'var(--text-primary)' : 'var(--text-secondary)',
      }}
    >
      {children}
    </button>
  );
}

function PlanCardView({
  plan,
  cycle,
  event,
}: {
  plan: PlanCard;
  cycle: Cycle;
  event: ResolvedPricingEvent | null;
}) {
  const isRecommended = !!plan.badge;
  const router = useRouter();
  const { isAuthenticated, isLoading, loginWithRedirect } = useAuth();

  // Tracked BEFORE navigating (the auth redirect unloads the page). plan.id is
  // the typed plan id, never the localized name.
  const trackPlanClick = useCallback(() => {
    track('landing_plan_clicked', {
      plan_id: plan.id,
      cycle,
      is_recommended: isRecommended,
      is_authenticated: isAuthenticated,
    });
    setLandingIntent('landing_plan', plan.id);
  }, [plan.id, cycle, isRecommended, isAuthenticated]);

  const handleSignIn = useCallback(
    async (e: React.MouseEvent) => {
      e.preventDefault();
      if (isLoading) return;
      trackPlanClick();
      const returnTo = '/app/settings/pricing';
      if (isAuthenticated) {
        router.push(returnTo);
        return;
      }
      await loginWithRedirect({ appState: { returnTo } });
    },
    [isAuthenticated, isLoading, loginWithRedirect, router, trackPlanClick]
  );

  return (
    <div
      className="relative p-5 rounded-3xl transition-colors duration-300 flex flex-col"
      style={{
        border: isRecommended ? `2px solid var(--text-primary)` : `1px solid var(--border-color)`,
        background: 'transparent',
        opacity: plan.disabled ? 0.7 : 1,
        pointerEvents: plan.disabled ? 'none' : 'auto',
      }}
    >
      {plan.badge && (
        <div
          className="absolute -top-3 left-1/2 -translate-x-1/2 px-3 py-1 text-[11px] font-semibold rounded-full"
          style={{ background: 'var(--text-primary)', color: 'var(--bg-primary)' }}
        >
          {plan.badge}
        </div>
      )}

      <div className="text-center mb-6">
        <h3
          className="text-xl font-bold mb-3"
          style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif' }}
        >
          {plan.name}
        </h3>
        <div className="flex items-baseline justify-center gap-2">
          <ReferencePrice
            planId={plan.id}
            cycle={cycle}
            creditTierIndex={TIER_INDEX}
            event={event}
          />
          <span className="flex items-baseline gap-1.5">
            <span className="text-3xl font-bold" style={{ color: 'var(--text-primary)' }}>
              {plan.priceLabel}
            </span>
            {plan.showSuffix && (
              <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>
                /month
              </span>
            )}
          </span>
        </div>
        <FoundingPriceNote
          planId={plan.id}
          cycle={cycle}
          creditTierIndex={TIER_INDEX}
          event={event}
        />
      </div>

      <div className="flex justify-center flex-1">
        <ul className="space-y-2.5 text-sm inline-flex flex-col">
          <DeploymentBadge />
          {plan.features.map((f) => (
            <li key={f} className="flex items-start gap-2">
              <Check className="w-4 h-4 flex-shrink-0 mt-0.5" style={{ color: '#10b981' }} />
              {/* `flex-1` so FeatureLabel owns the rest of the row and can pin
                  its "i" to the right edge; `items-start` so a label that wraps
                  keeps the check and the "i" on its first line. */}
              <span className="flex flex-1 min-w-0" style={{ color: 'var(--text-secondary)' }}>
                <FeatureLabel feature={f} />
              </span>
            </li>
          ))}
        </ul>
      </div>

      <a
        href={
          plan.id === 'enterprise'
            ? `/contact?category=other&message=${encodeURIComponent(plan.contactMessage ?? '')}`
            : '/app/settings/pricing'
        }
        onClick={plan.id === 'enterprise' ? trackPlanClick : handleSignIn}
        className="mt-6 inline-flex items-center justify-center w-full h-9 rounded-xl text-sm font-medium transition-colors duration-200 hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
        style={{
          background: 'var(--accent-primary)',
          color: 'var(--accent-foreground)',
        }}
      >
        {plan.cta}
      </a>
    </div>
  );
}
