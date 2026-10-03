'use client';

import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowRight, CircleAlert, Gift, Loader2, LogIn, RotateCcw } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuth } from '@/lib/providers/smart-providers';
import { useSubscription } from '@/lib/hooks/smart-hooks-complete';
import { usePersonalOffer } from '@/lib/hooks/usePersonalOffer';
import { usePricingEvent } from '@/hooks/usePricingEvent';
import { IS_CE } from '@/lib/edition';
import { ApiError } from '@/lib/api/api-client';
import type { PersonalOfferCurrent, PersonalOfferPreview, PersonalOfferStep } from '@/lib/api/services/reward-api.service';
import { calcPrice, creditFactsFor, CREDIT_TIERS, DEFAULT_MAX_TIER_INDEX, resolveMaxTierIndex } from '@/lib/billing/pricing-constants';
import { planFeatureLabels } from '@/lib/billing/planFeatureLabels';
import {
  hasAttachedPersonalOfferCheckout,
  PERSONAL_OFFER_STALE_CODES,
  personalOfferCheckoutApiError,
} from '@/lib/billing/personal-offer-checkout';
import { PERSONAL_OFFER_PARAM, readPendingPersonalOffer } from '@/lib/lifecycle/pendingPersonalOffer';
import { highestRecommendableTier } from '@/lib/partners/partnerLink';
import { PLAN_KEYS, wholeMoney, type PlanKey } from '@/lib/partners/tiers';
import { assignLocation } from '@/lib/navigation/assignLocation';
import { track } from '@/lib/analytics/analytics';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import PlanGrid from '@/components/pricing/PlanGrid';
import PlanCardFrame from '@/components/pricing/PlanCardFrame';
import { OfferCountdown } from './OfferCountdown';

type Cycle = 'monthly' | 'yearly';
export interface OfferChoice { plan: PlanKey; creditTier: number; cycle: Cycle }

/** The offer's page (and its sign-in return) for a code. */
export const PERSONAL_OFFER_PATH = '/offer/personal';

// The platform's own buttons: black on the light theme, white on the dark one.
const ACCENT_CTA = 'inline-flex items-center justify-center gap-2 rounded-xl px-5 py-3 text-sm font-semibold shadow-sm transition-colors bg-[var(--accent-primary)] text-[var(--accent-foreground)] hover:bg-[var(--accent-hover)] disabled:opacity-60 cursor-pointer';
const OUTLINE_CTA = 'inline-flex items-center rounded-xl border border-theme px-5 py-3 text-sm font-medium text-theme-primary transition-colors hover:bg-[var(--bg-secondary)]';
/** The one touch of colour on the page: the bonus, written in a warm gradient. */
// Light theme: 700 shades, which hold 4.5:1 on white and on the selected tier's tint (measured: 500s
// and a rose-600 middle did not, 4.07:1 on the tint).
const GRADIENT_TEXT = 'bg-gradient-to-r from-amber-700 via-rose-700 to-violet-700 bg-clip-text text-transparent dark:from-amber-300 dark:via-rose-300 dark:to-violet-300';
const DEFAULT_CHOICE: OfferChoice = { plan: 'starter', creditTier: 0, cycle: 'monthly' };
/** The longest delay a browser timer takes (about 24.8 days). */
const MAX_TIMER_MS = 2_147_483_647;
/** Re-reads after the deadline, past the first one at the deadline itself. */
const REREAD_AFTER_DEADLINE_MS = [3_000, 10_000, 30_000];

/**
 * A selection carried in the address (the pricing page's sign-in return carries one), when it is
 * one the price list sells; null otherwise, and the page then picks the best value itself.
 */
export function choiceFromUrl(search: URLSearchParams): OfferChoice | null {
  const tierRaw = search.get('creditTierIndex') ?? '';
  const cycle = search.get('billingCycle');
  if (!/^\d{1,2}$/.test(tierRaw)) return null;
  const creditTier = Number(tierRaw);
  if (creditTier >= CREDIT_TIERS.length || (cycle !== 'monthly' && cycle !== 'yearly')) return null;
  const requested = (search.get('planCode') ?? '').toLowerCase() as PlanKey;
  const plan = (PLAN_KEYS as readonly string[]).includes(requested) ? requested : 'starter';
  // A Starter choice above its credit cap moves to Pro, the smallest plan that sells that pack.
  return { plan: plan === 'starter' && creditTier > highestRecommendableTier('starter') ? 'pro' : plan, creditTier, cycle };
}

/**
 * The plan, pack and cycle an open checkout holds, when the server names one the price list
 * sells; null otherwise.
 */
function sameChoice(a: OfferChoice | null, b: OfferChoice): boolean {
  return !!a && a.plan === b.plan && a.creditTier === b.creditTier && a.cycle === b.cycle;
}

export function reservedChoice(current: PersonalOfferCurrent | null | undefined): OfferChoice | null {
  if (current?.status !== 'CHECKOUT_OPEN') return null;
  const plan = (current.reservedPlanCode ?? '').toLowerCase() as PlanKey;
  const creditTier = current.reservedCreditTierIndex;
  const cycle = current.reservedBillingCycle;
  if (!(PLAN_KEYS as readonly string[]).includes(plan) || typeof creditTier !== 'number' || !Number.isInteger(creditTier)
    || creditTier < 0 || creditTier >= CREDIT_TIERS.length || (cycle !== 'monthly' && cycle !== 'yearly')) return null;
  return { plan, creditTier, cycle };
}

/**
 * Where the preview of the pack in front leads: the plan to show (the smallest one that gets a
 * bonus at this pack), a bigger pack to try (none here gets a bonus, a bigger one does), or done
 * (no pack gets one: the choice stays as it is).
 */
export function bestValueStep(preview: PersonalOfferPreview, choice: OfferChoice):
  { kind: 'plan'; plan: PlanKey } | { kind: 'tier'; creditTier: number } | { kind: 'done' } {
  const eligible = PLAN_KEYS.find((plan) => preview.plans.find((p) => p.planCode === plan.toUpperCase())?.status === 'ELIGIBLE');
  if (eligible) return { kind: 'plan', plan: eligible };
  const next = preview.nextEligibleMonthlyCredits;
  const nextTier = next == null ? -1 : CREDIT_TIERS.indexOf(next);
  if (nextTier > choice.creditTier) return { kind: 'tier', creditTier: nextTier };
  return { kind: 'done' };
}

/**
 * The full-screen page a personal offer's link opens (the email a free account receives once its
 * credits run out): the price first, the bonus the first subscription brings, the time left on
 * the offer, and the way to pay. The bonus grows by tier: the page says the most it gives and
 * draws every tier as a ladder, each one a click away. It opens on the entry tier (the smallest
 * plan and pack that get a bonus), or on the client's own choice when one comes back (an open
 * checkout, a cancelled payment, the pricing page's sign-in); every plan, pack and cycle can be
 * changed below. Past the offer's deadline, an open checkout is the one thing
 * still payable: the page then shows that choice alone. The bonus never lowers the price: it is
 * credited once the first payment is confirmed.
 */
export function PersonalOfferView() {
  const t = useTranslations('personalOfferPage');
  const tOffer = useTranslations('reward.personalOffer');
  const tCards = useTranslations('pricing.planCards');
  const tPricing = useTranslations('pricing');
  const tBilling = useTranslations('pricing.billing');
  const locale = useLocale();
  const { event: pricingEvent } = usePricingEvent();
  const { isAuthenticated, isReady, numericUserId, isLoading: authLoading, loginWithRedirect } = useAuth();
  const { createSubscription } = useSubscription();

  const [urlChoice] = useState<OfferChoice | null>(() => (typeof window === 'undefined' ? null : choiceFromUrl(new URLSearchParams(window.location.search))));
  // The search for the best value walks the packs from the smallest; the client's own choice (an
  // open checkout's, or the address's) is shown once the best value is known.
  const [choice, setChoice] = useState<OfferChoice>(DEFAULT_CHOICE);
  // The best value, once the previews found it: null while looking.
  const [best, setBest] = useState<OfferChoice | null>(null);
  const [focusNonce, setFocusNonce] = useState(0);
  const tiersLabelId = useId();
  const [opening, setOpening] = useState(false);
  const [checkoutError, setCheckoutError] = useState<string | null>(null);

  const offer = usePersonalOffer(choice.creditTier, choice.cycle);
  const preview = offer.preview;
  // The last preview read stays on screen while the next pack's is read: changing a pack must not
  // blank the page. Its figures are only used for the selection they answer (previewFits).
  const [shownPreview, setShownPreview] = useState<PersonalOfferPreview | null>(null);
  useEffect(() => {
    if (preview) setShownPreview(preview);
  }, [preview]);
  // A preview answers for one pack and cycle: one for another selection is not this one's.
  const previewFits = !!preview && preview.monthlyCredits === CREDIT_TIERS[choice.creditTier] && preview.billingCycle === choice.cycle;

  const status = offer.current?.status ?? null;
  const reserved = reservedChoice(offer.current);
  const offerExpiresAt = offer.current?.expiresAt ?? null;
  // The clock the deadline is read against, moved on when the deadline comes: the page changes
  // at that moment, not at the next unrelated render.
  const [clock, setClock] = useState(() => Date.now());
  useEffect(() => {
    const at = offerExpiresAt ? Date.parse(offerExpiresAt) : Number.NaN;
    // Past a timer's 24.8-day limit it would fire at once; the countdown's re-read covers it then.
    if (!Number.isFinite(at) || at <= Date.now() || at - Date.now() > MAX_TIMER_MS) return;
    const id = window.setTimeout(() => setClock(Date.now()), at - Date.now());
    return () => window.clearTimeout(id);
  }, [offerExpiresAt]);
  // The server refusing a pack as expired over an open checkout settles it whatever this clock
  // says (it may run behind, or an admin may have disabled the code): kept once seen.
  const [serverExpired, setServerExpired] = useState(false);
  useEffect(() => {
    if (reserved && offer.errorCode === 'OFFER_EXPIRED') setServerExpired(true);
  }, [reserved, offer.errorCode]);
  // Past the offer's own deadline, the open checkout is the only choice the server still prices.
  const locked = !!reserved && (serverExpired
    || (!!offerExpiresAt && Date.parse(offerExpiresAt) <= Math.max(clock, Date.now())));

  // Find the best value from the previews: the first plan with a bonus at this pack, else the
  // next pack that has one. Each step reads the server's own matrix: nothing is assumed here.
  // It waits for the account's offer, which says whether a checkout is already open.
  useEffect(() => {
    // Past the deadline the page moves to the reservation, also when the deadline comes while it
    // is open (the best value found before is no longer priced).
    if (locked && reserved) {
      if (!sameChoice(best, reserved) || !sameChoice(choice, reserved)) {
        setChoice(reserved);
        setBest(reserved);
        setFocusNonce((n) => n + 1);
      }
      return;
    }
    if (best || !offer.current) return;
    if (!preview || !previewFits) return;
    const step = bestValueStep(preview, choice);
    if (step.kind === 'tier') {
      setChoice((c) => ({ ...c, creditTier: step.creditTier }));
      return;
    }
    const found = step.kind === 'plan' ? { ...choice, plan: step.plan } : choice;
    setBest(found);
    setChoice(reserved ?? urlChoice ?? found);
    setFocusNonce((n) => n + 1);
  }, [best, offer.current, locked, reserved, urlChoice, preview, previewFits, choice]);

  // Moved to the reservation (past the deadline): a refusal shown for the choice before it no
  // longer applies.
  useEffect(() => {
    if (locked) setCheckoutError(null);
  }, [locked]);

  // Back from a cancelled payment: counted as the pricing page counts it, then the marker leaves
  // the address (a reload is not a second cancellation).
  useEffect(() => {
    const url = new URL(window.location.href);
    if (url.searchParams.get('checkout') !== 'cancelled') return;
    track('checkout_returned', { status: 'cancelled', from_plan: 'FREE', source: 'personal_offer' });
    url.searchParams.delete('checkout');
    window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
  }, []);

  // Back from the checkout through the browser's page cache, the page is restored as it was left:
  // "opening". It opens nothing now, so the buttons come back.
  useEffect(() => {
    const onShow = (e: PageTransitionEvent) => { if (e.persisted) setOpening(false); };
    window.addEventListener('pageshow', onShow);
    return () => window.removeEventListener('pageshow', onShow);
  }, []);

  const errorCode = offer.errorCode;
  const credits = (n: number) => n.toLocaleString(locale);
  const money = (major: number) => wholeMoney(major, 'usd', locale);
  const creditFacts = useMemo(() => creditFactsFor(locale), [locale]);
  const planName = (plan: PlanKey) => tCards(`${plan}.name`);
  const bonusFor = (plan: PlanKey) => (previewFits ? preview?.plans.find((p) => p.planCode === plan.toUpperCase()) : undefined);
  const selected = bonusFor(choice.plan);
  const starterCap = highestRecommendableTier('starter');
  const expiresAt = offerExpiresAt ?? shownPreview?.expiresAt ?? null;
  const sessionExpiresAt = offer.current?.sessionExpiresAt ?? null;
  // The offer's own deadline; once past, a checkout already reserved under it runs to its own.
  const onReservation = locked && !!sessionExpiresAt;
  const deadline = onReservation ? sessionExpiresAt : expiresAt;
  const usable = !!shownPreview && (status === null || status === 'AVAILABLE' || status === 'CHECKOUT_OPEN');

  // The deadline came: the server says what the offer has become. At once, then a few more times
  // over half a minute, in case this clock runs ahead of the server's.
  const rereads = useRef<number[]>([]);
  useEffect(() => () => rereads.current.forEach((id) => window.clearTimeout(id)), []);
  const readAfterDeadline = () => {
    void offer.refresh();
    for (const delay of REREAD_AFTER_DEADLINE_MS) rereads.current.push(window.setTimeout(() => void offer.refresh(), delay));
  };

  const checkout = async (picked: OfferChoice) => {
    const plan = bonusFor(picked.plan);
    if (!preview || !previewFits || opening) return;
    if (!plan || plan.status === 'UNAVAILABLE') {
      setCheckoutError(tOffer('planUnavailable'));
      return;
    }
    const trackCheckout = (outcome: 'redirect' | 'error') => track('checkout_started', {
      plan_code: picked.plan.toUpperCase(),
      billing_cycle: picked.cycle,
      credit_tier_index: picked.creditTier,
      outcome,
      source: 'personal_offer',
    });
    setCheckoutError(null);
    setOpening(true);
    try {
      const result = await createSubscription({
        planCode: picked.plan.toUpperCase(),
        billingCycle: picked.cycle,
        creditTierIndex: String(picked.creditTier),
        personalOfferId: preview.offerId,
        offerVersion: preview.offerVersion,
      });
      // Only a session that carries the offer may be paid from here: paying without it would
      // spend the one first purchase the bonus depends on.
      if (!hasAttachedPersonalOfferCheckout(result)) throw new Error('offer not attached');
      trackCheckout('redirect');
      assignLocation(result.url);
    } catch (e) {
      trackCheckout('error');
      const code = e instanceof ApiError ? e.code ?? null : null;
      if (code && PERSONAL_OFFER_STALE_CODES.includes(code)) void offer.refresh();
      const known = personalOfferCheckoutApiError(code);
      setCheckoutError(known ? tOffer(known) : e instanceof ApiError ? t('checkoutError') : tOffer('attachFailed'));
      setOpening(false);
    }
  };

  const choose = (picked: OfferChoice) => {
    track('pricing_plan_clicked', {
      plan_id: picked.plan,
      billing_cycle: picked.cycle,
      outcome: 'checkout',
      source: 'personal_offer',
      is_best_value: !!best && picked.plan === best.plan && picked.creditTier === best.creditTier && picked.cycle === best.cycle,
    });
    void checkout(picked);
  };

  const signIn = async () => {
    const code = offer.candidateCode ?? (typeof window === 'undefined' ? null : readPendingPersonalOffer(window));
    const returnTo = code ? `${PERSONAL_OFFER_PATH}?${PERSONAL_OFFER_PARAM}=${encodeURIComponent(code)}` : PERSONAL_OFFER_PATH;
    try {
      await loginWithRedirect({ appState: { returnTo } });
    } catch {
      setCheckoutError(t('signInError'));
    }
  };

  // ---- The states that are not an offer to take ----
  // Signed in, but the account's id never came (its status call failed): said, with a reload.
  const accountIdMissing = isAuthenticated && isReady && !authLoading && numericUserId == null;
  if (!accountIdMissing && (authLoading || (isAuthenticated && !offer.isAuthenticated))) return <Shell><Loading label={t('loading')} /></Shell>;
  // Self-hosted: no personal offer exists, so there is no sign-in to ask for one.
  if (!isAuthenticated && !IS_CE) {
    return (
      <Shell>
        <Hero>
          <Eyebrow>{t('eyebrow')}</Eyebrow>
          <h1 className="mt-5 text-3xl font-bold text-theme-primary md:text-4xl" style={DISPLAY}>{t('signInTitle')}</h1>
          <p className="mt-3 max-w-xl text-base text-theme-secondary">{t('signInLead')}</p>
          <button type="button" className={cn(ACCENT_CTA, 'mt-6')} onClick={() => void signIn()} data-testid="personal-offer-sign-in">
            <LogIn className="h-3.5 w-3.5" aria-hidden />
            {t('signIn')}
          </button>
          {checkoutError && <p role="alert" className="mt-3 text-sm text-red-500">{checkoutError}</p>}
        </Hero>
      </Shell>
    );
  }
  const found: OfferMessage | null = accountIdMissing ? { ns: 'offer', key: 'errors.generic', retry: true } : statusMessage(status, errorCode, offer, !!reserved);
  if (found) {
    const message = found.ns === 'page' ? t(found.key) : tOffer(found.key, found.key === 'granted' ? { credits: credits(offer.current?.grantedCredits ?? 0) } : undefined);
    return (
      <Shell>
        <Hero>
          <Eyebrow>{t('eyebrow')}</Eyebrow>
          <div className="mt-5 flex items-start gap-3" data-testid="personal-offer-status" data-status={status ?? errorCode ?? 'NONE'}>
            <CircleAlert className="mt-1 h-5 w-5 shrink-0 text-theme-secondary" aria-hidden />
            <div>
              <h1 className="text-2xl font-bold text-theme-primary md:text-3xl" style={DISPLAY}>{message}</h1>
            </div>
          </div>
          <div className="mt-6 flex flex-wrap gap-3">
            {found.retry && (
              <button type="button" className={ACCENT_CTA} onClick={() => (accountIdMissing ? assignLocation(window.location.href) : void offer.refresh())} data-testid="personal-offer-retry">
                <RotateCcw className="h-3.5 w-3.5" aria-hidden />
                {t('retry')}
              </button>
            )}
            <Link href={`/${locale}/app/settings/pricing`} className={found.retry ? OUTLINE_CTA : ACCENT_CTA} data-testid="personal-offer-pricing">
              {t('seePlans')}
              <ArrowRight className="h-3.5 w-3.5" aria-hidden />
            </Link>
            <Link href={`/${locale}/app/chat`} className={OUTLINE_CTA} data-testid="personal-offer-back">
              {t('backToApp')}
            </Link>
          </div>
        </Hero>
      </Shell>
    );
  }
  if (!usable || !best) return <Shell><Loading label={t('loading')} /></Shell>;

  // ---- The offer ----
  const price = calcPrice(choice.plan, choice.cycle, choice.creditTier);
  const maxTier = resolveMaxTierIndex(choice.creditTier > DEFAULT_MAX_TIER_INDEX);
  // The tiers, from the server's matrix: the page says the most they give, and each is a click away.
  const steps: PersonalOfferStep[] = (shownPreview?.steps ?? []).filter((s) => CREDIT_TIERS.includes(s.monthlyCredits));
  const topBonus = steps.reduce((most, s) => Math.max(most, s.bonusCredits), 0);
  const currentStep = [...steps].reverse().find((s) => s.monthlyCredits <= CREDIT_TIERS[choice.creditTier]) ?? null;
  const pickStep = (step: PersonalOfferStep) => {
    const creditTier = CREDIT_TIERS.indexOf(step.monthlyCredits);
    setChoice((c) => ({ ...c, creditTier, plan: c.plan === 'starter' && creditTier > starterCap ? 'pro' : c.plan }));
    setFocusNonce((n) => n + 1);
  };
  const busy = opening || !previewFits;
  // A bigger pack to aim for, only while packs can still be changed and this plan is priced.
  const nextEligible = previewFits && !locked && selected?.status === 'NO_BONUS' ? preview?.nextEligibleMonthlyCredits : null;

  const cards = PLAN_KEYS.map((plan) => {
    const overCap = plan === 'starter' && choice.creditTier > starterCap;
    const tier = overCap ? starterCap : choice.creditTier;
    const bonus = bonusFor(plan);
    const unavailable = overCap || bonus?.status === 'UNAVAILABLE';
    return (
      <PlanCardFrame
        key={plan}
        planId={plan}
        name={planName(plan)}
        priceLabel={money(calcPrice(plan, choice.cycle, tier))}
        period={tCards('period')}
        cycle={choice.cycle}
        creditTierIndex={tier}
        event={pricingEvent}
        features={planFeatureLabels(plan, { tCards, tPricing, credits: credits(CREDIT_TIERS[tier]), creditFacts })}
        defaultOpen={plan === choice.plan}
        frameStyle={{
          border: plan === choice.plan ? '2px solid var(--text-primary)' : '1px solid var(--border-color)',
          background: 'var(--plan-card-bg, transparent)',
          opacity: unavailable ? 0.6 : 1,
        }}
        badge={bonus?.status === 'ELIGIBLE' && !overCap && (
          <div
            className="absolute -top-3 left-1/2 -translate-x-1/2 whitespace-nowrap rounded-full bg-[var(--accent-primary)] px-3 py-1 text-xs font-semibold text-[var(--accent-foreground)] shadow-sm"
            data-testid={`personal-offer-card-bonus-${plan}`}
          >
            {t('cardBonus', { credits: credits(bonus.bonusCredits) })}
          </div>
        )}
        footer={(collapsed) => (unavailable ? (
          <p className={`${collapsed} mt-5 text-center text-sm text-theme-secondary`}>
            {overCap ? t('starterCap', { credits: credits(CREDIT_TIERS[starterCap]) }) : tOffer('planUnavailable')}
          </p>
        ) : (
          <>
            {bonus?.status === 'NO_BONUS' && (
              <div className={`${collapsed} mt-4 text-center text-sm text-theme-secondary`} data-testid={`personal-offer-card-no-bonus-${plan}`}>
                <p>{t('cardNoBonus')}</p>
                <p className="mt-1">{tOffer('firstPurchaseUsed')}</p>
              </div>
            )}
            <button
              type="button"
              onClick={() => {
                const picked = { ...choice, plan };
                setChoice(picked);
                choose(picked);
              }}
              disabled={busy}
              className={`${collapsed} mt-5 md:mt-6 inline-flex items-center justify-center w-full h-9 rounded-xl text-sm font-medium transition-colors duration-200 active:scale-[0.98] cursor-pointer disabled:opacity-60`}
              style={plan === choice.plan
                ? { background: 'var(--accent-primary)', color: 'var(--accent-foreground)' }
                : { background: 'transparent', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}
              data-testid={`personal-offer-choose-${plan}`}
            >
              {t('choose', { plan: planName(plan) })}
            </button>
          </>
        ))}
        rootProps={{
          'data-testid': `personal-offer-plan-${plan}`,
          'data-selected': plan === choice.plan ? 'true' : 'false',
          // Said to a screen reader too, not only by the card's border.
          'aria-current': plan === choice.plan ? 'true' : undefined,
        }}
      />
    );
  });

  return (
    <Shell>
      <Hero>
        <div className="grid gap-8 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)] lg:items-center lg:gap-12">
          <div className="min-w-0">
            <Eyebrow>{t('eyebrow')}</Eyebrow>
            <h1 className="mt-5 text-3xl font-bold text-theme-primary md:text-5xl" style={DISPLAY} data-testid="personal-offer-title">
              {/* Locked on a reservation, the page leads with what that reservation gives, never with a
                  bigger tier it can no longer change to. */}
              {topBonus > 0 && !locked
                ? t.rich('titleUpTo', { credits: credits(topBonus), accent: (chunks) => <span className={GRADIENT_TEXT}>{chunks}</span> })
                : selected?.status === 'ELIGIBLE'
                  ? t.rich('title', { credits: credits(selected.bonusCredits), accent: (chunks) => <span className={GRADIENT_TEXT}>{chunks}</span> })
                  : t('titleNoBonus')}
            </h1>
            <p className="mt-4 max-w-xl text-base text-theme-secondary md:text-lg">{t('lead')}</p>
            {steps.length > 0 && !locked && (
              <div className="mt-6" data-testid="personal-offer-tiers">
                <div className="text-sm font-medium text-theme-secondary" id={tiersLabelId}>{t('tiersTitle')}</div>
                <div className="mt-2 grid gap-2 sm:grid-cols-3" role="group" aria-labelledby={tiersLabelId}>
                  {steps.map((step) => {
                    const active = currentStep?.monthlyCredits === step.monthlyCredits;
                    return (
                      <button
                        key={step.monthlyCredits}
                        type="button"
                        aria-pressed={active}
                        disabled={opening}
                        onClick={() => pickStep(step)}
                        className={cn(
                          'relative overflow-hidden rounded-2xl border px-4 py-3 text-left transition-colors disabled:cursor-default',
                          active
                            ? 'border-[var(--text-primary)] bg-theme-primary shadow-sm'
                            : 'border-theme bg-[var(--bg-primary)]/70 hover:bg-[var(--bg-secondary)] cursor-pointer',
                        )}
                        data-testid={`personal-offer-tier-${step.monthlyCredits}`}
                      >
                        {active && (
                          <span aria-hidden className="pointer-events-none absolute inset-0 bg-gradient-to-br from-amber-400/10 via-rose-400/10 to-violet-400/10" />
                        )}
                        <span className="relative block text-sm text-theme-secondary">{t('tierFrom', { credits: credits(step.monthlyCredits) })}</span>
                        {' '}
                        <span className={cn('relative mt-0.5 block text-lg font-semibold tabular-nums', active ? GRADIENT_TEXT : 'text-theme-primary')}>
                          {t('tierBonus', { credits: credits(step.bonusCredits) })}
                        </span>
                      </button>
                    );
                  })}
                </div>
              </div>
            )}
            {deadline && (
              <OfferCountdown expiresAt={deadline} onElapsed={readAfterDeadline} className="mt-6" />
            )}
            {deadline && (
              <p className="mt-2 text-sm text-theme-secondary" data-testid="personal-offer-until">
                {onReservation
                  ? tOffer('reservedUntil', { date: formatUtcDateTime(deadline, { locale }) })
                  : t('until', { date: formatUtcDateTime(deadline, { locale }) })}
              </p>
            )}
          </div>

          {/* The price first: what the chosen plan costs, what the first payment adds, and the way to pay. */}
          <div className="rounded-2xl border border-theme bg-[var(--bg-primary)]/90 p-6 shadow-sm backdrop-blur md:p-8" data-testid="personal-offer-summary">
            <div className="text-sm text-theme-secondary">{t('summaryTitle')}</div>
            <div className="mt-1 text-base font-semibold text-theme-primary">
              {t('summaryPlan', { plan: planName(choice.plan), credits: credits(CREDIT_TIERS[choice.creditTier]) })}
            </div>
            <div className="mt-4 flex flex-wrap items-baseline gap-x-2" data-testid="personal-offer-price">
              <span className="text-5xl font-bold tabular-nums text-theme-primary" style={DISPLAY}>{money(price)}</span>{' '}
              <span className="text-sm text-theme-secondary">{t('pricePeriod', { cycle: choice.cycle })}</span>
            </div>
            {choice.cycle === 'yearly' && (
              <p className="mt-1 text-sm text-theme-secondary" data-testid="personal-offer-annual">{tOffer('annualTotal', { amount: money(price * 12) })}</p>
            )}
            {previewFits && selected?.status === 'ELIGIBLE' ? (
              <p className="mt-3 inline-flex items-center gap-2 rounded-full bg-emerald-500/10 px-3 py-1.5 text-sm font-medium text-emerald-700 dark:text-emerald-300" data-testid="personal-offer-bonus">
                <Gift className="h-3.5 w-3.5 shrink-0" aria-hidden />
                {t('bonus', { credits: credits(selected.bonusCredits), value: wholeMoney(selected.paygFaceValueUsd, 'usd', locale) })}
              </p>
            ) : previewFits ? (
              <>
                <p className="mt-3 text-sm text-theme-secondary" data-testid="personal-offer-no-bonus">
                  {selected?.status === 'UNAVAILABLE' || !selected
                    ? tOffer('planUnavailable')
                    : nextEligible ? tOffer('nextEligible', { credits: credits(nextEligible) }) : t('cardNoBonus')}
                </p>
                {/* Paying without a bonus still spends the one first purchase the offer is for. */}
                {selected?.status === 'NO_BONUS' && (
                  <p className="mt-2 text-sm text-theme-secondary" data-testid="personal-offer-first-purchase">{tOffer('firstPurchaseUsed')}</p>
                )}
              </>
            ) : null}
            <button
              type="button"
              className={cn(ACCENT_CTA, 'mt-6 w-full')}
              disabled={busy || selected?.status === 'UNAVAILABLE'}
              onClick={() => choose(choice)}
              data-testid="personal-offer-continue"
            >
              {opening ? <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden /> : null}
              {opening ? t('opening') : t('continue', { plan: planName(choice.plan) })}
              {!opening && <ArrowRight className="h-3.5 w-3.5" aria-hidden />}
            </button>
            <p className="mt-3 text-center text-sm text-theme-secondary">{t('afterPayment')}</p>
            <div className="mt-3 text-center">
              {locked ? (
                <Link href={`/${locale}/app/chat`} className="text-sm text-theme-secondary underline" data-testid="personal-offer-back-locked">{t('backToApp')}</Link>
              ) : (
                <Link href={`/${locale}/app/settings/pricing`} className="text-sm text-theme-secondary underline" data-testid="personal-offer-all-plans">{t('allPlans')}</Link>
              )}
            </div>
          </div>
        </div>
      </Hero>

      <section className="mx-auto max-w-6xl px-4 pt-10 md:px-6 md:pt-14">
        {/* Past the deadline only the open checkout is still priced: nothing else to choose. */}
        {!locked && (
        <div className="flex flex-wrap items-end justify-center gap-4">
          <div className="inline-flex items-center gap-1 rounded-xl bg-theme-tertiary p-1" role="group" aria-label={t('cycleLabel')}>
            {(['monthly', 'yearly'] as const).map((cycle) => (
              <button
                key={cycle}
                type="button"
                aria-pressed={choice.cycle === cycle}
                onClick={() => setChoice((c) => ({ ...c, cycle }))}
                className={cn(
                  'flex items-center rounded-lg px-4 py-1.5 text-sm font-medium transition-colors cursor-pointer',
                  choice.cycle === cycle ? 'bg-theme-primary text-theme-primary shadow-sm' : 'text-theme-secondary',
                )}
                data-testid={`personal-offer-cycle-${cycle}`}
              >
                {tBilling(cycle)}
                {cycle === 'yearly' && (
                  <span className="ml-2 rounded-full bg-emerald-500/15 px-2 py-0.5 text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                    {tBilling('yearlyBadge')}
                  </span>
                )}
              </button>
            ))}
          </div>
          <label className="flex items-center gap-2 text-sm text-theme-secondary">
            {t('creditsLabel')}
            <select
              className="rounded-xl border border-theme bg-theme-primary px-3 py-1.5 text-sm text-theme-primary"
              value={choice.creditTier}
              onChange={(e) => {
                const creditTier = Number(e.target.value);
                setChoice((c) => ({ ...c, creditTier, plan: c.plan === 'starter' && creditTier > starterCap ? 'pro' : c.plan }));
              }}
              data-testid="personal-offer-credits"
            >
              {CREDIT_TIERS.slice(0, maxTier + 1).map((amount, index) => (
                <option key={index} value={index}>{t('creditsOption', { credits: credits(amount) })}</option>
              ))}
            </select>
          </label>
        </div>
        )}

        <div className="mt-10">
          <PlanGrid groups={[{ key: 'personal-offer', cards }]} fitThree focus={{ index: Math.max(0, PLAN_KEYS.indexOf(choice.plan)), nonce: focusNonce }} />
        </div>
      </section>

      {checkoutError && (
        <p role="alert" className="fixed inset-x-4 top-4 z-50 mx-auto max-w-md rounded-xl border border-red-500/40 bg-theme-secondary px-4 py-3 text-center text-sm text-red-500 shadow-lg">
          {checkoutError}
        </p>
      )}
    </Shell>
  );
}

const DISPLAY: React.CSSProperties = { fontFamily: 'var(--font-outfit), Outfit, sans-serif' };

function Shell({ children }: { children: React.ReactNode }) {
  return <main className="min-h-screen bg-theme-primary pb-16" data-testid="personal-offer">{children}</main>;
}

/**
 * The hero: the platform's neutral surface, with soft colour glowing behind it (warm, then cool),
 * fainter on the dark theme.
 */
function Hero({ children }: { children: React.ReactNode }) {
  return (
    <section className="relative mx-4 max-w-6xl overflow-hidden rounded-3xl border border-theme bg-theme-primary px-6 py-10 md:mx-6 md:px-12 md:py-14 xl:mx-auto">
      <div aria-hidden className="pointer-events-none absolute -right-16 -top-24 h-72 w-72 rounded-full bg-amber-300/30 blur-3xl dark:bg-amber-500/15" />
      <div aria-hidden className="pointer-events-none absolute -bottom-28 left-1/3 h-72 w-72 rounded-full bg-rose-300/25 blur-3xl dark:bg-rose-500/10" />
      <div aria-hidden className="pointer-events-none absolute -left-24 top-1/3 h-64 w-64 rounded-full bg-sky-300/25 blur-3xl dark:bg-sky-500/10" />
      <div className="relative">{children}</div>
    </section>
  );
}

function Eyebrow({ children }: { children: React.ReactNode }) {
  return (
    <span className="inline-flex items-center gap-2 rounded-full border border-theme bg-[var(--bg-secondary)]/80 px-3 py-1 text-xs font-semibold uppercase tracking-wide text-theme-secondary">
      <Gift className="h-3 w-3 text-rose-500 dark:text-rose-300" aria-hidden />
      {children}
    </span>
  );
}

function Loading({ label }: { label: string }) {
  return (
    <Hero>
      <div className="flex items-center gap-3 text-sm text-theme-secondary" data-testid="personal-offer-loading" role="status">
        <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
        {label}
      </div>
    </Hero>
  );
}

type OfferState = ReturnType<typeof usePersonalOffer>;

/** A message key: `offer` keys live under reward.personalOffer (shared with the pricing page), `page` keys under personalOfferPage. */
export type OfferMessage = { ns: 'offer' | 'page'; key: string; retry?: boolean };

/**
 * What to say instead of an offer, when there is none to take: expired, already used (with or
 * without the bonus), a payment still settling, another reward in the way, a code that is not
 * this account's, no offer at all, or an offer that could not be read (with a retry). Null when
 * the offer can be taken, or is still being read.
 *
 * <p>A pack refused as expired is not the offer expiring while a checkout holds a reservation
 * ({@code reserved}): past the deadline only that reservation is priced, and the page moves to it.
 * Before the account's offer is read, that cannot be told yet, so nothing is said.
 */
export function statusMessage(
  status: string | null,
  errorCode: string | null,
  offer: Pick<OfferState, 'current' | 'candidateCode' | 'isLoading' | 'isError'>,
  reserved = false,
): OfferMessage | null {
  // What the account's offer has become comes first: a bonus granted, a payment settling, a code
  // disabled... A pack refused as expired (the link reopened after the deadline) says nothing
  // about those, and must not hide them.
  if (status === 'GRANTED') return { ns: 'offer', key: 'granted' };
  if (status === 'NO_BONUS') return { ns: 'offer', key: 'usedWithoutBonus' };
  if (status === 'CLAWED_BACK') return { ns: 'offer', key: 'reversed' };
  if (status === 'PENDING_PAYMENT') return { ns: 'offer', key: 'paymentPending' };
  if (status === 'PROCESSING') return { ns: 'offer', key: 'processing' };
  if (status === 'ALREADY_USED') return { ns: 'offer', key: 'errors.alreadyUsed' };
  if (status === 'REVIEW_REQUIRED') return { ns: 'offer', key: 'reviewRequired' };
  if (status === 'CONFLICT') return { ns: 'offer', key: 'errors.conflict' };
  // A checkout being created: settles within minutes, and the account's offer is read every 2 s meanwhile.
  if (status === 'CHECKOUT_CREATING') return { ns: 'offer', key: 'errors.checkoutActive' };
  if (status === 'DISABLED') return { ns: 'offer', key: 'errors.unavailable' };
  if (status === 'EXPIRED') return { ns: 'offer', key: 'errors.expired' };
  if (errorCode === 'OFFER_EXPIRED' && !reserved) {
    if (!offer.current && offer.isLoading) return null;
    return { ns: 'offer', key: 'errors.expired' };
  }
  if (errorCode === 'OFFER_ALREADY_USED') return { ns: 'offer', key: 'errors.alreadyUsed' };
  if (errorCode === 'OFFER_REVIEW_REQUIRED') return { ns: 'offer', key: 'reviewRequired' };
  if (errorCode === 'OFFER_CONFLICT') return { ns: 'offer', key: 'errors.conflict' };
  if (errorCode === 'OFFER_UNAVAILABLE') return { ns: 'offer', key: 'errors.notEligible' };
  if (offer.isLoading) return null;
  // A pack refused as expired over a reservation is not a failure: the page moves to the reservation.
  if (offer.isError && !(reserved && errorCode === 'OFFER_EXPIRED')) return { ns: 'offer', key: 'errors.generic', retry: true };
  if ((status === 'NONE' || status === null) && !offer.candidateCode && !offer.current?.offerId) {
    return { ns: 'page', key: 'noOffer' };
  }
  return null;
}

export default PersonalOfferView;
