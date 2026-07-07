'use client';

import { useCallback, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Check } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { useAuth } from '@/lib/providers/smart-providers';
import { calcPrice, CREDIT_TIERS, PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';
import DeploymentBadge from '@/components/pricing/DeploymentBadge';
import FeatureLabel from '@/components/pricing/FeatureLabel';

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
  const tCards = useTranslations('pricing.planCards');
  const tBilling = useTranslations('pricing.billing');
  const tBiz = useTranslations('pricing.businessPlans');
  // useLocale(), NOT getClientLocale(): this section server-renders on the
  // landing (under NextIntlClientProvider). getClientLocale() resolves 'en' on
  // the server but the visitor's locale on the client, so a /fr visitor got
  // "5,000" in the server HTML vs "5 000" after hydration - a React #418
  // hydration mismatch on the whole landing page.
  const locale = useLocale();

  // Entry tier credits, shared with the settings page so both stay in sync.
  const landingCredits = CREDIT_TIERS[TIER_INDEX].toLocaleString(locale);
  // Same coherent superset feature lists + i18n as the settings page (PlanSelector).
  const featuresFor = (id: string): string[] =>
    (PLAN_FEATURE_KEYS[id] || []).map((k) => {
      if (k === 'creditsDynamic') {
        return tCards('features.creditsPerMonth', { credits: landingCredits });
      }
      // Free monthly credits carry an info tooltip (workflows only; chat/agents
      // need a paid plan) via the "label||tooltip" convention rendered by FeatureLabel.
      if (k === 'creditsFree') {
        return `${tCards('features.creditsFree')}||${tCards('features.creditsFreeTooltip')}`;
      }
      // Managed integration credentials for cloud-linked self-hosted installs carry
      // an info tooltip (relay + per-call credit markup), same "label||tooltip" convention.
      if (k === 'cePlatformCreds') {
        return `${tCards('features.cePlatformCreds')}||${tCards('features.cePlatformCredsTooltip')}`;
      }
      return tCards(`features.${k}`);
    });

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
          className="relative inline-flex items-center gap-1 p-1.5 rounded-full"
          style={{ background: 'var(--bg-tertiary)' }}
        >
          <CycleButton active={cycle === 'monthly'} onClick={() => setCycle('monthly')}>
            {tBilling('monthly')}
          </CycleButton>
          <CycleButton active={cycle === 'yearly'} onClick={() => setCycle('yearly')}>
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
          <PlanCardView key={p.id} plan={p} />
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
          <PlanCardView key={p.id} plan={p} />
        ))}
      </div>

      <p className="mt-8 text-center text-sm" style={{ color: 'var(--text-muted)' }}>
        {tBilling('taxNote')}
      </p>
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
      className="relative z-10 flex items-center justify-center px-6 py-2 rounded-full text-sm font-medium transition-colors duration-200"
      style={{
        background: active ? 'var(--bg-primary)' : 'transparent',
        color: active ? 'var(--text-primary)' : 'var(--text-secondary)',
      }}
    >
      {children}
    </button>
  );
}

function PlanCardView({ plan }: { plan: PlanCard }) {
  const isRecommended = !!plan.badge;
  const router = useRouter();
  const { isAuthenticated, isLoading, loginWithRedirect } = useAuth();

  const handleSignIn = useCallback(
    async (e: React.MouseEvent) => {
      e.preventDefault();
      if (isLoading) return;
      const returnTo = '/app/settings/pricing';
      if (isAuthenticated) {
        router.push(returnTo);
        return;
      }
      await loginWithRedirect({ appState: { returnTo } });
    },
    [isAuthenticated, isLoading, loginWithRedirect, router]
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
        <div className="flex items-baseline justify-center gap-1.5">
          <span className="text-3xl font-bold" style={{ color: 'var(--text-primary)' }}>
            {plan.priceLabel}
          </span>
          {plan.showSuffix && (
            <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>
              /month
            </span>
          )}
        </div>
      </div>

      <div className="flex justify-center flex-1">
        <ul className="space-y-2.5 text-sm inline-flex flex-col">
          <DeploymentBadge />
          {plan.features.map((f) => (
            <li key={f} className="flex items-center gap-2">
              <Check className="w-4 h-4 flex-shrink-0" style={{ color: '#10b981' }} />
              <span style={{ color: 'var(--text-secondary)' }}>
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
        onClick={plan.id === 'enterprise' ? undefined : handleSignIn}
        className="mt-6 inline-flex items-center justify-center w-full h-10 rounded-full text-sm font-medium transition-all duration-200 hover:brightness-110 active:scale-[0.98] cursor-pointer"
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
