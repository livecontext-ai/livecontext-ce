'use client';

import React, { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { AppWindow, Copy, Gift, Link2, Trash2, Wallet } from 'lucide-react';
import { cn } from '@/lib/utils';
import { calcPrice, CREDIT_TIERS } from '@/lib/billing/pricing-constants';
import { formatPercent } from '@/lib/partners/formatAmounts';
import {
  highestRecommendableTier,
  partnerOfferLink,
  type PartnerRecommendation,
} from '@/lib/partners/partnerLink';
import { ApiError } from '@/lib/api/api-client';
import {
  PARTNER_OFFERS_QUERY_KEY,
  partnerProgramApi,
  type PartnerOffer,
} from '@/lib/api/services/partner-program-api.service';
import { EXAMPLE_CLIENT_PLAN, PLAN_KEYS, wholeMoney, type PlanKey } from '@/lib/partners/tiers';
import { SITE_URL } from '@/lib/seo/siteUrl';
import { PARTNER_GOLD_BG, PARTNER_GOLD_CTA } from './partnerTheme';
import { OfferAppsPicker } from './OfferAppsPicker';

/**
 * A link for one client: the partner picks the plan, its monthly credits and the billing cycle
 * they recommend, names the client if they like, and creates an offer: a short link
 * (/offer/<token>) to a full-screen page with that choice in front and their code in it. Beside
 * it, what the client will see (the plan, its list price, the credits the code gives a new
 * account) and what the partner earns on it, so the partner recommends knowingly; under it, the
 * partner's live links, to copy again or deactivate. Prices come from the real price list
 * (`calcPrice`), the same the pricing page bills with.
 */
export function PartnerLinkBuilder({
  code,
  audienceCredits,
  ratePercent,
  commissionMonths,
  onCopy,
}: {
  code: string;
  /** The credits a new account gets with the code. */
  audienceCredits: number;
  /** The rate the partner's next commission earns (null when unknown). */
  ratePercent: number | null;
  commissionMonths: number | null;
  onCopy: (text: string) => void;
}) {
  const t = useTranslations('partnerDashboard.dashboard.builder');
  const tPlans = useTranslations('partnersLanding.calculator.plans');
  const locale = useLocale();
  const [rec, setRec] = useState<PartnerRecommendation>({
    plan: EXAMPLE_CLIENT_PLAN.plan,
    creditTier: EXAMPLE_CLIENT_PLAN.creditTier,
    cycle: 'monthly',
  });

  const [label, setLabel] = useState('');
  const [appIds, setAppIds] = useState<string[]>([]);
  const [created, setCreated] = useState<PartnerOffer | null>(null);
  const queryClient = useQueryClient();
  const offers = useQuery({ queryKey: PARTNER_OFFERS_QUERY_KEY, queryFn: () => partnerProgramApi.offers(), retry: false });
  const create = useMutation({
    mutationFn: () => partnerProgramApi.createOffer({
      plan_code: rec.plan.toUpperCase() as PartnerOffer['plan_code'],
      credit_tier_index: rec.creditTier,
      billing_cycle: rec.cycle,
      label: label.trim() || undefined,
      app_ids: appIds.length > 0 ? appIds : undefined,
    }),
    onSuccess: (res) => {
      setCreated(res.offer);
      setLabel('');
      setAppIds([]);
      void queryClient.invalidateQueries({ queryKey: PARTNER_OFFERS_QUERY_KEY });
    },
  });
  // Deactivating breaks a link a client may already have: asked once more, inline.
  const [confirming, setConfirming] = useState<string | null>(null);
  const deactivate = useMutation({
    mutationFn: (token: string) => partnerProgramApi.deactivateOffer(token),
    onSuccess: (_res, token) => {
      setConfirming(null);
      if (created?.token === token) setCreated(null);
      void queryClient.invalidateQueries({ queryKey: PARTNER_OFFERS_QUERY_KEY });
    },
  });
  const createError = create.error instanceof ApiError && create.error.code && t.has(`errors.${create.error.code}`)
    ? t(`errors.${create.error.code}`)
    : create.isError ? t('errors.generic') : null;

  const setPlan = (plan: PlanKey) => {
    setCreated(null);
    setRec((r) => ({ ...r, plan, creditTier: Math.min(r.creditTier, highestRecommendableTier(plan)) }));
  };
  const change = (next: Partial<PartnerRecommendation>) => {
    setCreated(null);
    setRec((r) => ({ ...r, ...next }));
  };
  const price = calcPrice(rec.plan, rec.cycle, rec.creditTier);
  const money = (major: number) => wholeMoney(major, 'usd', locale);
  const credits = (n: number) => n.toLocaleString(locale);
  const tiers = useMemo(
    () => CREDIT_TIERS.slice(0, highestRecommendableTier(rec.plan) + 1).map((amount, index) => ({ amount, index })),
    [rec.plan],
  );
  // A yearly plan is one invoice a year (the price shown is its monthly equivalent): the
  // commission is taken on that invoice, so it is stated per invoice, not per month.
  const commission = ratePercent != null
    ? (price * (rec.cycle === 'yearly' ? 12 : 1) * ratePercent) / 100
    : null;

  return (
    <div className="rounded-2xl border border-theme bg-theme-secondary p-6" data-testid="partner-link-builder">
      <h3 className="flex items-center gap-2 text-base font-semibold text-theme-primary">
        <Link2 className="h-3.5 w-3.5 text-theme-secondary" aria-hidden />
        {t('title')}
      </h3>
      <p className="mt-1 text-sm text-theme-secondary">{t('intro')}</p>

      <div className="mt-5 grid gap-6 lg:grid-cols-5">
        <div className="space-y-4 lg:col-span-3">
          <fieldset>
            <legend className="text-sm font-medium text-theme-primary">{t('plan')}</legend>
            <div className="mt-2 grid grid-cols-3 gap-2">
              {PLAN_KEYS.map((plan) => {
                const selected = rec.plan === plan;
                return (
                  <button
                    key={plan}
                    type="button"
                    aria-pressed={selected}
                    onClick={() => setPlan(plan)}
                    className={cn(
                      'rounded-xl border px-3 py-2 text-left text-sm transition-colors cursor-pointer',
                      selected ? 'border-[#d99a1e] bg-[#f2b640]/10 text-theme-primary' : 'border-theme text-theme-secondary hover:text-theme-primary',
                    )}
                    data-testid={`builder-plan-${plan}`}
                  >
                    <span className="block font-semibold">{tPlans(plan)}</span>
                    <span className="block">{t('from', { price: money(calcPrice(plan, rec.cycle, 0)) })}</span>
                  </button>
                );
              })}
            </div>
          </fieldset>

          <label className="block">
            <span className="text-sm font-medium text-theme-primary">{t('credits')}</span>
            <select
              className="mt-2 w-full rounded-xl border border-theme bg-theme-primary px-3 py-2 text-sm text-theme-primary"
              value={rec.creditTier}
              onChange={(e) => change({ creditTier: Number(e.target.value) })}
              data-testid="builder-credits"
            >
              {tiers.map(({ amount, index }) => (
                <option key={index} value={index}>{t('creditsOption', { credits: credits(amount) })}</option>
              ))}
            </select>
          </label>

          <fieldset>
            <legend className="text-sm font-medium text-theme-primary">{t('cycle')}</legend>
            <div className="mt-2 inline-flex rounded-xl bg-theme-tertiary p-1">
              {(['monthly', 'yearly'] as const).map((cycle) => (
                <button
                  key={cycle}
                  type="button"
                  aria-pressed={rec.cycle === cycle}
                  onClick={() => change({ cycle })}
                  className={cn(
                    'rounded-lg px-3 py-1.5 text-sm transition-colors cursor-pointer',
                    rec.cycle === cycle ? 'bg-theme-primary font-medium text-theme-primary shadow-sm' : 'text-theme-secondary',
                  )}
                  data-testid={`builder-cycle-${cycle}`}
                >
                  {t(cycle)}
                </button>
              ))}
            </div>
          </fieldset>

          <OfferAppsPicker selected={appIds} onChange={(ids) => { setCreated(null); setAppIds(ids); }} />
        </div>

        <div className="space-y-3 lg:col-span-2">
          <div className="rounded-xl border border-theme bg-theme-primary p-4" data-testid="builder-client-sees">
            <div className="text-sm text-theme-secondary">{t('clientSees')}</div>
            <div className="mt-1 text-sm font-semibold text-theme-primary">
              {t('planLine', { plan: tPlans(rec.plan), credits: credits(CREDIT_TIERS[rec.creditTier]) })}
            </div>
            <div className="mt-1 text-xl font-bold text-theme-primary tabular-nums" data-testid="builder-price">
              {rec.cycle === 'yearly' ? t('priceYearly', { price: money(price) }) : t('price', { price: money(price) })}
            </div>
            <div className="mt-2 flex items-start gap-2 text-sm text-theme-primary" data-testid="builder-offer">
              <Gift className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#a8700a] dark:text-[#f2b640]" aria-hidden />
              {t('offer', { credits: credits(audienceCredits), code })}
            </div>
            {appIds.length > 0 && (
              <div className="mt-2 flex items-start gap-2 text-sm text-theme-primary" data-testid="builder-apps-line">
                <AppWindow className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#a8700a] dark:text-[#f2b640]" aria-hidden />
                {t('appsLine', { count: appIds.length })}
              </div>
            )}
          </div>
          {commission != null && (
            <div className="rounded-xl border border-[#d99a1e]/40 bg-[#f2b640]/10 p-4" data-testid="builder-commission">
              <div className="flex items-center gap-2 text-sm text-theme-secondary">
                <Wallet className="h-3.5 w-3.5 text-[#a8700a] dark:text-[#f2b640]" aria-hidden />
                {t('forYou')}
              </div>
              <div className="mt-1 text-sm font-semibold text-theme-primary">
                {rec.cycle === 'yearly' ? t('commissionYearly', { amount: money(commission) }) : t('commission', { amount: money(commission) })}
              </div>
              {commissionMonths != null && (
                <div className="text-sm text-theme-secondary">
                  {t('commissionDetail', { percent: formatPercent(ratePercent ?? 0, locale), months: commissionMonths })}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      <div className="mt-5">
        {/* A form, so Enter in the client field creates the link too. */}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (!create.isPending) create.mutate();
          }}
        >
          <label htmlFor="builder-label" className="text-sm font-medium text-theme-primary">{t('labelField')}</label>
          <div className="mt-2 flex flex-col gap-2 sm:flex-row sm:items-center">
            <input
              id="builder-label"
              type="text"
              maxLength={120}
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder={t('labelPlaceholder')}
              className="min-w-0 flex-1 rounded-xl border border-theme bg-theme-primary px-3 py-2.5 text-sm text-theme-primary"
              data-testid="builder-label"
            />
            <button
              type="submit"
              className={PARTNER_GOLD_CTA}
              style={PARTNER_GOLD_BG}
              disabled={create.isPending}
              data-testid="builder-create"
            >
              <Link2 className="h-3.5 w-3.5" aria-hidden />
              {create.isPending ? t('creating') : t('create')}
            </button>
          </div>
        </form>
        {createError && <p role="alert" className="mt-2 text-sm text-red-500">{createError}</p>}
        {created && (
          <div className="mt-3 flex flex-col gap-2 rounded-xl border border-[#d99a1e]/50 bg-[#f2b640]/10 p-3 sm:flex-row sm:items-center" data-testid="builder-created">
            <span className="text-sm font-medium text-theme-primary">{t('created')}</span>
            <code className="min-w-0 flex-1 truncate text-sm text-theme-primary" data-testid="builder-link">
              {partnerOfferLink(SITE_URL, created.token)}
            </code>
            <button type="button" className="inline-flex h-8 items-center gap-1 rounded-lg border border-theme px-2.5 text-sm text-theme-primary cursor-pointer" onClick={() => onCopy(partnerOfferLink(SITE_URL, created.token))} data-testid="builder-copy">
              <Copy className="h-3.5 w-3.5" aria-hidden />
              {t('copy')}
            </button>
          </div>
        )}
      </div>

      <div className="mt-6 border-t border-theme pt-5" data-testid="builder-links">
        <h4 className="text-sm font-semibold text-theme-primary">{t('offersTitle')}</h4>
        {offers.isPending ? (
          <p className="mt-2 text-sm text-theme-secondary" data-testid="builder-links-loading">{t('offersLoading')}</p>
        ) : offers.isError ? (
          <p role="alert" className="mt-2 text-sm text-red-500" data-testid="builder-links-error">{t('offersError')}</p>
        ) : (offers.data?.offers ?? []).length === 0 ? (
          <p className="mt-2 text-sm text-theme-secondary">{t('offersEmpty')}</p>
        ) : (
          <ul className="mt-3 space-y-2">
            {(offers.data?.offers ?? []).map((o) => (
              <li key={o.token} className="flex flex-col gap-2 rounded-xl border border-theme bg-theme-primary p-3 sm:flex-row sm:items-center" data-testid="builder-link-row">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-theme-primary">{o.label || t('unnamed')}</div>
                  <div className="text-sm text-theme-secondary">
                    {confirming === o.token
                      ? t('deactivateConfirm')
                      : t('offerLine', {
                        plan: tPlans(o.plan_code.toLowerCase() as PlanKey),
                        credits: credits(CREDIT_TIERS[o.credit_tier_index] ?? 0),
                        cycle: o.billing_cycle,
                      })}
                    {confirming !== o.token && (o.app_ids?.length ?? 0) > 0 && (
                      <span data-testid="builder-link-apps">{` · ${t('appsCount', { count: o.app_ids?.length ?? 0 })}`}</span>
                    )}
                  </div>
                </div>
                {confirming === o.token ? (
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      className="inline-flex h-8 items-center gap-1 rounded-lg bg-red-500 px-2.5 text-sm font-medium text-white cursor-pointer disabled:opacity-60"
                      disabled={deactivate.isPending}
                      onClick={() => deactivate.mutate(o.token)}
                      data-testid="builder-deactivate-confirm"
                    >
                      <Trash2 className="h-3.5 w-3.5" aria-hidden />
                      {t('deactivateYes')}
                    </button>
                    <button
                      type="button"
                      className="inline-flex h-8 items-center rounded-lg border border-theme px-2.5 text-sm text-theme-primary cursor-pointer"
                      onClick={() => setConfirming(null)}
                    >
                      {t('cancel')}
                    </button>
                  </div>
                ) : (
                  <div className="flex items-center gap-2">
                    <button type="button" className="inline-flex h-8 items-center gap-1 rounded-lg border border-theme px-2.5 text-sm text-theme-primary cursor-pointer" onClick={() => onCopy(partnerOfferLink(SITE_URL, o.token))}>
                      <Copy className="h-3.5 w-3.5" aria-hidden />
                      {t('copy')}
                    </button>
                    <button
                      type="button"
                      className="inline-flex h-8 items-center gap-1 rounded-lg px-2.5 text-sm text-theme-secondary hover:text-red-500 cursor-pointer"
                      onClick={() => { deactivate.reset(); setConfirming(o.token); }}
                      data-testid="builder-deactivate"
                    >
                      <Trash2 className="h-3.5 w-3.5" aria-hidden />
                      {t('deactivate')}
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
        {deactivate.isError && <p role="alert" className="mt-2 text-sm text-red-500">{t('deactivateError')}</p>}
      </div>
    </div>
  );
}

export default PartnerLinkBuilder;
