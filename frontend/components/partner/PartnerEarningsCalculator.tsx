'use client';

import React, { useMemo, useState } from 'react';
import { Crown } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { CREDIT_TIERS } from '@/lib/billing/pricing-constants';
import { formatPercent } from '@/lib/partners/formatAmounts';
import {
  EXAMPLE_CLIENT_PLAN,
  estimateYearOne,
  maxCreditTier,
  monthlyBill,
  niceCeiling,
  PLAN_KEYS,
  TIER_ORDER,
  TIER_STYLE,
  tierTerm,
  wholeMoney,
  type PartnerTierKey,
  type PartnerTierTerm,
  type PlanKey,
} from '@/lib/partners/tiers';

const CLIENTS = { min: 1, max: 200, initial: 20 } as const;

/**
 * "Run your own numbers": how many clients, which plan they take and how many credits a month,
 * and, while the founder window is open, a founding-partner switch. A client's bill is the real
 * list price of that plan and those credits (monthly billing), so every figure here is one the
 * pricing page would bill. The estimate runs on the real tiers from the backend terms. The answer
 * leads with the monthly figure a partner reaches, then the year, then a chart in money whose
 * bars take the metal of the tier each month was earned at.
 */
export function PartnerEarningsCalculator({
  tiers,
  currency,
  founderOpen,
  founderUntilLabel,
  settleDays,
}: {
  tiers: readonly PartnerTierTerm[];
  currency: string;
  /** How old an invoice must be to count toward a tier (the program terms). */
  settleDays: number;
  founderOpen: boolean;
  /** The founder deadline, already formatted for the locale. */
  founderUntilLabel: string | null;
}) {
  const t = useTranslations('partnersLanding.calculator');
  const tTier = useTranslations('partnersLanding.tiers');
  const locale = useLocale();
  const [clients, setClients] = useState<number>(CLIENTS.initial);
  const [plan, setPlan] = useState<PlanKey>(EXAMPLE_CLIENT_PLAN.plan);
  const [creditTier, setCreditTier] = useState<number>(EXAMPLE_CLIENT_PLAN.creditTier);
  const [founder, setFounder] = useState(false);

  const bill = monthlyBill({ plan, creditTier });
  const estimate = useMemo(
    () => estimateYearOne({ tiers, clients, monthlySpend: bill, founder: founderOpen && founder, settleDays }),
    [tiers, clients, bill, founder, founderOpen, settleDays],
  );

  const money = (major: number) => wholeMoney(major, currency, locale);
  const credits = (tier: number) => CREDIT_TIERS[tier].toLocaleString(locale, { notation: 'compact' });
  const tierName = (tier: PartnerTierKey) => tTier(`${tier}.name`);
  const choosePlan = (next: PlanKey) => {
    setPlan(next);
    // Starter stops at its credit cap: a larger tier is not something it can be billed.
    setCreditTier((current) => Math.min(current, maxCreditTier(next)));
  };

  const first = estimate.months[0];
  const last = estimate.months[estimate.months.length - 1];
  const top = niceCeiling(Math.max(...estimate.months.map((m) => m.commission)));
  const middle = Math.ceil(estimate.months.length / 2);
  // The money axis makes room for its longest label ("$1,000,000", "100 000 $US"): about 7px a
  // character at text-xs, plus a gap before the bars.
  const axisLeft = Math.max(...[top, top / 2, 0].map((v) => money(v).length)) * 7 + 12;

  return (
    <div
      className="mt-10 grid grid-cols-1 gap-8 rounded-3xl p-6 md:p-8 lg:grid-cols-5"
      style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}
      data-testid="partner-calculator"
    >
      {/* ── Inputs ── */}
      <div className="space-y-8 lg:col-span-2">
        <div>
          <div className="flex items-baseline justify-between gap-3">
            <label htmlFor="partner-calc-clients" className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
              {t('clients')}
            </label>
            <span className="text-2xl font-bold tabular-nums" style={{ color: 'var(--text-primary)' }}>
              {clients.toLocaleString(locale)}
            </span>
          </div>
          <input
            id="partner-calc-clients"
            type="range"
            min={CLIENTS.min}
            max={CLIENTS.max}
            value={clients}
            onChange={(e) => setClients(Number(e.target.value))}
            className="mt-3 w-full accent-[#d99a1e]"
          />
        </div>

        <div>
          <div id="partner-calc-plan" className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>{t('plan')}</div>
          <div className="mt-3 grid grid-cols-3 gap-2" role="group" aria-labelledby="partner-calc-plan">
            {PLAN_KEYS.map((key) => (
              <button
                key={key}
                type="button"
                onClick={() => choosePlan(key)}
                aria-pressed={plan === key}
                className="rounded-xl px-3 py-2 text-sm font-semibold transition-colors"
                style={plan === key
                  ? { background: 'linear-gradient(135deg, #fde68a, #f2b640 55%, #d99a1e)', color: '#2a1a00' }
                  : { border: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}
              >
                {t(`plans.${key}`)}
              </button>
            ))}
          </div>
        </div>

        <div>
          <div className="flex items-baseline justify-between gap-3">
            <label htmlFor="partner-calc-credits" className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
              {t('credits')}
            </label>
            <span className="text-2xl font-bold tabular-nums" style={{ color: 'var(--text-primary)' }}>{credits(creditTier)}</span>
          </div>
          <input
            id="partner-calc-credits"
            type="range"
            min={0}
            max={maxCreditTier(plan)}
            step={1}
            value={creditTier}
            aria-valuetext={CREDIT_TIERS[creditTier].toLocaleString(locale)}
            onChange={(e) => setCreditTier(Number(e.target.value))}
            className="mt-3 w-full accent-[#d99a1e]"
          />
          <div className="mt-3 rounded-2xl px-4 py-3" style={{ background: 'var(--bg-secondary)' }}>
            <div className="text-base font-semibold" style={{ color: 'var(--text-primary)' }} data-testid="partner-calc-bill">
              {t('bill', { bill: money(bill) })}
            </div>
            <div className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>{t('billHint')}</div>
          </div>
        </div>

        {founderOpen && (
          <label
            className="flex cursor-pointer items-start gap-3 rounded-2xl p-4"
            style={{ background: founder ? TIER_STYLE.platinum.gradient : 'var(--bg-secondary)', color: founder ? TIER_STYLE.platinum.ink : 'var(--text-primary)' }}
          >
            <input
              type="checkbox"
              checked={founder}
              onChange={(e) => setFounder(e.target.checked)}
              className="mt-0.5 h-4 w-4 accent-[#8b93c2]"
            />
            <span className="text-sm">
              <Crown className="mr-1.5 inline h-3.5 w-3.5 align-[-2px]" aria-hidden />
              {founderUntilLabel ? t('founder', { date: founderUntilLabel }) : t('founderGeneric')}
            </span>
          </label>
        )}
      </div>

      {/* ── Results ── */}
      <div className="lg:col-span-3">
        <div className="relative overflow-hidden rounded-2xl p-6" style={{ background: '#0b0d12', color: '#fff' }}>
          <div aria-hidden className="absolute -right-12 -top-12 h-40 w-40 rounded-full" style={{ background: 'radial-gradient(circle, rgba(242,182,64,0.35), transparent 70%)' }} />
          <div className="relative text-xs uppercase tracking-wider" style={{ color: 'rgba(255,255,255,0.6)' }}>{t('byMonth12')}</div>
          <div className="relative mt-1 flex flex-wrap items-baseline gap-x-2">
            <span className="text-3xl sm:text-4xl md:text-5xl font-bold tracking-tight tabular-nums" style={{ color: '#f2b640' }} data-testid="partner-calc-lastMonth">
              {money(last.commission)}
            </span>
            <span className="text-base" style={{ color: 'rgba(255,255,255,0.6)' }}>{t('perMonth')}</span>
          </div>
          <dl className="relative mt-5 grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="rounded-xl p-3" style={{ background: 'rgba(255,255,255,0.07)' }}>
              <dt className="text-xs" style={{ color: 'rgba(255,255,255,0.6)' }}>{t('yearOne')}</dt>
              <dd className="mt-0.5 text-lg sm:text-2xl font-bold tabular-nums" data-testid="partner-calc-yearOne">{money(estimate.total)}</dd>
            </div>
            <div className="rounded-xl p-3" style={{ background: 'rgba(255,255,255,0.07)' }}>
              <dt className="text-xs" style={{ color: 'rgba(255,255,255,0.6)' }}>{t('firstMonth')}</dt>
              <dd className="mt-0.5 text-lg sm:text-2xl font-bold tabular-nums" data-testid="partner-calc-firstMonth">{money(first.commission)}</dd>
            </div>
          </dl>
        </div>

        <figure className="mt-6" aria-label={t('chartLabel')}>
          <div className="relative h-44" style={{ paddingLeft: axisLeft }} aria-hidden>
            {[top, top / 2, 0].map((tick, i) => (
              <div key={tick} className="absolute left-0 right-0" style={{ top: `${i * 50}%` }}>
                <span className="absolute left-0 -translate-y-1/2 text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>{money(tick)}</span>
                <div className="border-t" style={{ marginLeft: axisLeft, borderColor: 'var(--border-color)' }} />
              </div>
            ))}
            <div className="relative flex h-full items-end gap-1.5">
              {estimate.months.map((m) => (
                <div
                  key={m.month}
                  className="flex-1 rounded-t-md"
                  style={{ height: `${Math.max(2, (m.commission / top) * 100)}%`, background: TIER_STYLE[m.tier].gradient }}
                  title={`${t('month', { month: m.month })}: ${money(m.commission)} (${tierName(m.tier)})`}
                />
              ))}
            </div>
          </div>
          <div className="mt-2 flex justify-between text-xs" style={{ paddingLeft: axisLeft, color: 'var(--text-muted)' }} aria-hidden>
            <span>{t('month', { month: 1 })}</span>
            <span>{t('month', { month: middle })}</span>
            <span>{t('month', { month: estimate.months.length })}</span>
          </div>
          <ul className="mt-4 flex flex-wrap gap-x-5 gap-y-2" aria-hidden>
            {TIER_ORDER.map((tier) => (
              <li key={tier} className="flex items-center gap-2 text-sm" style={{ color: 'var(--text-secondary)' }}>
                <span className="h-3 w-3 rounded-sm" style={{ background: TIER_STYLE[tier].gradient }} />
                {t('legend', { tier: tierName(tier), percent: formatPercent(tierTerm(tiers, tier)?.commission_percent ?? 0, locale) })}
              </li>
            ))}
          </ul>
          <figcaption className="mt-4 text-sm font-medium" style={{ color: 'var(--text-primary)' }} data-testid="partner-calc-path">
            {founderOpen && founder
              ? t('pathFounder', { tier: tierName('platinum') })
              : estimate.upgrades.length === 0
                ? t('pathStays', { tier: tierName(first.tier) })
                : estimate.upgrades
                  .map((u) => t('pathReached', { tier: tierName(u.tier), month: u.month }))
                  .join(' ')}
          </figcaption>
        </figure>

        <p className="mt-4 text-sm leading-relaxed" style={{ color: 'var(--text-muted)' }}>{t('disclaimer')}</p>
      </div>
    </div>
  );
}

export default PartnerEarningsCalculator;
