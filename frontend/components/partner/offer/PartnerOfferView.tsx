'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowRight, Gift, Handshake, PackageCheck, RotateCcw } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuth } from '@/lib/providers/smart-providers';
import { useSubscription } from '@/lib/hooks/smart-hooks-complete';
import { usePricingEvent } from '@/hooks/usePricingEvent';
import { calcPrice, creditFactsFor, CREDIT_TIERS, DEFAULT_MAX_TIER_INDEX, resolveMaxTierIndex } from '@/lib/billing/pricing-constants';
import { track } from '@/lib/analytics/analytics';
import { planFeatureLabels } from '@/lib/billing/planFeatureLabels';
import { readPendingRewardCode, rememberPendingRewardCode } from '@/lib/lifecycle/pendingRewardCode';
import { assignLocation } from '@/lib/navigation/assignLocation';
import { ApiError } from '@/lib/api/api-client';
import { markOfferResume, takeOfferResume } from '@/lib/partners/offerResume';
import { APP_SUGGESTIONS_FLAG } from '@/lib/onboarding/welcomeGiftHandoff';
import { rememberPostOnboardingReturn } from '@/lib/navigation/postOnboardingReturn';
import { highestRecommendableTier, partnerRecommendedLink } from '@/lib/partners/partnerLink';
import { PLAN_KEYS, wholeMoney, type PlanKey } from '@/lib/partners/tiers';
import type { PublicPartnerOffer } from '@/lib/partners/publicPartnerOffer';
import PlanGrid from '@/components/pricing/PlanGrid';
import PlanCardFrame from '@/components/pricing/PlanCardFrame';
import { OfferAppsSection } from './OfferAppsSection';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { PartnerBadgeIcon } from '@/components/profile/PartnerBadgeIcon';
import { PartnerTierChip } from '@/components/partner/PartnerTierChip';
import PendingRewardCodeRedeemer, { pendingRedeemQuery } from '@/components/reward/PendingRewardCodeRedeemer';
import { redeemErrorKey } from '@/components/reward/redeemMessages';
import {
  PARTNER_DISPLAY, PARTNER_GLASS, PARTNER_GOLD, PARTNER_GOLD_BG, PARTNER_GOLD_CTA, PARTNER_GOLD_TEXT, PARTNER_INK_FAINT,
  PARTNER_INK_MUTED, PartnerDarkEyebrow, PartnerDarkPanel,
} from '@/components/partner/partnerTheme';
import type { ResolvedPricingEvent } from '@/lib/billing/pricing-events';

type Cycle = 'monthly' | 'yearly';
interface Choice { plan: PlanKey; creditTier: number; cycle: Cycle }

/** The query the sign-in round trip comes back with: the visitor's choice, and "carry on". */
export function offerReturnPath(token: string, choice: Choice): string {
  const q = new URLSearchParams({ plan: choice.plan, tier: String(choice.creditTier), cycle: choice.cycle, continue: '1' });
  return `/offer/${encodeURIComponent(token)}?${q.toString()}`;
}

/** The choice a returning URL carries, when it is one the price list sells; the offer's otherwise. */
export function choiceFromSearch(search: URLSearchParams, fallback: Choice): Choice {
  const plan = (search.get('plan') ?? '') as PlanKey;
  const tierRaw = search.get('tier') ?? '';
  const tier = Number(tierRaw);
  const cycle = search.get('cycle');
  if (!(PLAN_KEYS as readonly string[]).includes(plan)) return fallback;
  // Digits only: a missing tier (Number('') is 0) must not read as the smallest one.
  if (!/^\d{1,2}$/.test(tierRaw) || tier > highestRecommendableTier(plan)) return fallback;
  if (cycle !== 'monthly' && cycle !== 'yearly') return fallback;
  return { plan, creditTier: tier, cycle };
}

/**
 * The full-screen page a partner's offer link opens (/offer/<token>): the partner (when their
 * profile is public), the plan they chose for this client already in front, its price at the
 * recommended credits, the credits the partner's code gives, and the way to pay. The client can
 * change plan, credits or cycle; one click brings the partner's choice back.
 *
 * <p>Paying from here: a visitor signs up first (the round trip comes back here with their choice
 * and carries on), a signed-in person who has not finished onboarding finishes it first (the
 * onboarding guard, as on the pricing page), the partner code is applied before the checkout opens
 * (attribution happens before the first payment), and an account that already pays is sent to its
 * pricing page, where plan changes are handled.
 */
export function PartnerOfferView({ offer }: { offer: PublicPartnerOffer }) {
  const t = useTranslations('partnerOffer');
  const tCards = useTranslations('pricing.planCards');
  const tPricing = useTranslations('pricing');
  const tBilling = useTranslations('pricing.billing');
  const tTier = useTranslations('partnerDashboard.dashboard.tier.names');
  const locale = useLocale();
  const { event: pricingEvent } = usePricingEvent();
  const { isAuthenticated, isReady, numericUserId, user, isLoading: authLoading, loginWithRedirect } = useAuth();
  const {
    subscription, isLoading: subscriptionLoading, error: subscriptionError, createSubscription, forceLoadSubscription,
  } = useSubscription();
  const queryClient = useQueryClient();
  // The redeem of the waiting code needs the account's numeric id (see PendingRewardCodeRedeemer).
  const authReady = isAuthenticated && isReady && numericUserId != null;

  const recommended: Choice = useMemo(
    () => ({ plan: offer.plan, creditTier: offer.creditTier, cycle: offer.cycle }),
    [offer.plan, offer.creditTier, offer.cycle],
  );
  const [choice, setChoice] = useState<Choice>(recommended);
  const [focusNonce, setFocusNonce] = useState(0);
  const [checkoutState, setCheckoutState] = useState<'idle' | 'opening' | 'error' | 'planTooLow' | 'alreadySubscribed'>('idle');
  const [resume, setResume] = useState<Choice | null>(null);

  // The partner's code waits like any partner link, and is applied once the account exists. It is
  // stored while this renders, before the redeemer below reads the store on its first render: an
  // effect would run after it, and a signed-in visitor would then pay with the code never applied.
  // The first code a visitor followed wins: when an older one is waiting, this code will not be
  // the one applied, so its credits are not promised (decided after mount, the server has no store).
  const [codeThatApplies] = useState(() => (typeof window === 'undefined' ? null : rememberPendingRewardCode(window, offer.code)));
  const [otherCodeWaiting, setOtherCodeWaiting] = useState(false);
  useEffect(() => {
    setOtherCodeWaiting(codeThatApplies != null && codeThatApplies !== offer.code.toUpperCase());
  }, [codeThatApplies, offer.code]);

  // Back from the checkout through the browser's page cache, the page is restored as it was left:
  // "opening". It opens nothing now, so the buttons come back.
  useEffect(() => {
    const onShow = (e: PageTransitionEvent) => { if (e.persisted) setCheckoutState('idle'); };
    window.addEventListener('pageshow', onShow);
    return () => window.removeEventListener('pageshow', onShow);
  }, []);

  // Back from sign-in (or onboarding): the visitor's choice, and whether to carry on to payment.
  // The choice to resume is state, not read back from `choice`: in the commit that restores it,
  // `choice` still holds the partner's, and the checkout would open for the wrong plan.
  useEffect(() => {
    const search = new URLSearchParams(window.location.search);
    const restored = choiceFromSearch(search, recommended);
    setChoice(restored);
    if (search.get('continue') !== '1') return;
    // Only a round trip this page started carries on by itself: "continue=1" can be written into
    // any link, and a signed-in person opening one must not land on a checkout without a click.
    if (takeOfferResume(window, offer.token)) setResume(restored);
    // Used once: going Back from the checkout must land on the offer, not bounce to it again.
    search.delete('continue');
    const query = search.toString();
    window.history.replaceState(window.history.state, '', `${window.location.pathname}${query ? `?${query}` : ''}`);
  }, [recommended, offer.token]);

  const partnerName = offer.partner?.name ?? null;
  const isPartnerChoice = choice.plan === recommended.plan && choice.creditTier === recommended.creditTier
    && choice.cycle === recommended.cycle;
  // Until a signed-in person's subscription is read, nothing is decided: not "free", not "paying".
  const subscriptionKnown = isAuthenticated && !subscriptionLoading && subscription != null;
  // The server's own test (a Stripe subscription), so the page never hides a payment the server
  // would take, nor offers one it would refuse.
  const alreadyPaying = subscriptionKnown
    && !!(subscription as { subscription?: { providerSubscriptionId?: string | null } } | null)?.subscription?.providerSubscriptionId;
  const credits = (n: number) => n.toLocaleString(locale);
  const money = (major: number) => wholeMoney(major, 'usd', locale);
  const creditFacts = useMemo(() => creditFactsFor(locale), [locale]);
  const price = calcPrice(choice.plan, choice.cycle, choice.creditTier);
  // Starter has a credit cap: above it the card is shown but cannot be chosen (and a Starter choice
  // moves to Pro, see the credits control).
  const starterAvailable = choice.creditTier <= highestRecommendableTier('starter');
  // The apps the offer gives install from one plan up: a smaller plan cannot be paid through the
  // offer (the server refuses it), since the client would pay and never get them.
  const appsPlan = offer.apps.length > 0 ? offer.appsPlan : null;
  const belowApps = (plan: PlanKey) => !!appsPlan && PLAN_KEYS.indexOf(plan) < PLAN_KEYS.indexOf(appsPlan);
  const appsNeedPlan = appsPlan ? t('appsNeedPlan', { plan: tCards(`${appsPlan}.name`) }) : '';

  const resetToPartner = () => {
    setChoice(recommended);
    setFocusNonce((n) => n + 1);
  };

  const checkout = useCallback(async (picked: Choice) => {
    if (!isAuthenticated) {
      markOfferResume(window, offer.token);
      try {
        await loginWithRedirect({ appState: { returnTo: offerReturnPath(offer.token, picked) } });
      } catch {
        // The sign-in page could not be reached: no round trip started, so no marker left behind,
        // and the click says it failed rather than doing nothing.
        takeOfferResume(window, offer.token);
        setCheckoutState('error');
      }
      return;
    }
    if (!authReady || !subscriptionKnown || alreadyPaying) return;
    const trackCheckout = (outcome: 'redirect' | 'onboarding' | 'error') => track('checkout_started', {
      plan_code: picked.plan.toUpperCase(),
      billing_cycle: picked.cycle,
      credit_tier_index: picked.creditTier,
      outcome,
      source: 'partner_offer',
    });
    setCheckoutState('opening');
    try {
      // The partner code is applied BEFORE the checkout opens (attribution happens before the first
      // payment): wait for the very redeem the notice runs, joined in flight or started here. A
      // redeem that fails for a reason that is not final (network, 5xx) stops here, to try again.
      // The stored code, or (storage blocked, so nothing is stored and the notice never runs) the
      // one this page remembered: the redeem is the same query either way, never run twice.
      const notVerified = redeemErrorKey('EMAIL_NOT_VERIFIED');
      const apply = (code: string) => queryClient.fetchQuery(pendingRedeemQuery(queryClient, numericUserId, code));
      const pending = readPendingRewardCode(window) ?? codeThatApplies;
      let outcome = pending ? await apply(pending) : null;
      // An older code (first code wins) refused for good credits nobody: this offer's code then gets
      // its turn, rather than a payment no partner is attributed for.
      if (outcome && !outcome.ok && outcome.errorKey !== notVerified && pending !== offer.code.toUpperCase()) {
        outcome = await apply(rememberPendingRewardCode(window, offer.code) ?? offer.code.toUpperCase());
      }
      // The email is not verified yet (onboarding normally settles it before this page): paying
      // now would never attribute the client. Finish onboarding first, then come back here and
      // carry on with this choice.
      const toOnboarding = () => {
        if (user?.sub) rememberPostOnboardingReturn(window, offerReturnPath(offer.token, picked), user.sub);
        markOfferResume(window, offer.token);
        trackCheckout('onboarding');
        assignLocation(`/${locale}/onboarding`);
      };
      if (outcome && !outcome.ok && outcome.errorKey === notVerified) {
        toOnboarding();
        return;
      }
      // The server checks the offer is still live and attributes the client to its partner itself,
      // before Stripe: the attribution never rests on the redeem above having gone through. The
      // session carries the offer, so what it brings after payment follows Stripe's webhook.
      let result: unknown;
      try {
        result = await createSubscription({
          planCode: picked.plan.toUpperCase(),
          billingCycle: picked.cycle,
          creditTierIndex: String(picked.creditTier),
          partnerOfferToken: offer.token,
        });
      } catch (e) {
        if (e instanceof ApiError && e.code === 'email_not_verified') {
          toOnboarding();
          return;
        }
        // Gone since the page loaded (deactivated, its code used up): the page says so on reload.
        if (e instanceof ApiError && e.code === 'offer_unavailable') {
          trackCheckout('error');
          assignLocation(`/offer/${encodeURIComponent(offer.token)}`);
          return;
        }
        // The account pays already (another tab): said, and the account read again, so the page
        // turns to its "already pays" view rather than reloading into the same button.
        if (e instanceof ApiError && e.code === 'offer_already_subscribed') {
          trackCheckout('error');
          setCheckoutState('alreadySubscribed');
          void forceLoadSubscription();
          return;
        }
        // The apps need a bigger plan than this one (it may have changed since the page loaded).
        if (e instanceof ApiError && e.code === 'offer_plan_too_low') {
          trackCheckout('error');
          setCheckoutState('planTooLow');
          return;
        }
        throw e;
      }
      const url = (result as { url?: unknown } | null)?.url;
      if (typeof url !== 'string' || !url) throw new Error('no checkout url');
      trackCheckout('redirect');
      // Back from paying, the offer's own welcome (its apps, its partner) replaces the app
      // suggestions onboarding armed: two overlays would stack on the same page.
      try {
        window.sessionStorage.removeItem(APP_SUGGESTIONS_FLAG);
      } catch {
        // Storage blocked: nothing was stored to show.
      }
      assignLocation(url);
    } catch {
      trackCheckout('error');
      setCheckoutState('error');
    }
  }, [isAuthenticated, authReady, subscriptionKnown, alreadyPaying, loginWithRedirect, offer.token, offer.code, codeThatApplies, queryClient, numericUserId, user?.sub, locale, createSubscription]);

  // A click (on the hero card or a plan card): the offer funnel on the pricing page's events, the
  // source telling them apart, tracked before any navigation (the sign-in redirect unloads the
  // page). An automatic resume is not a click and is not counted as one.
  const choose = (picked: Choice) => {
    track('pricing_plan_clicked', {
      plan_id: picked.plan,
      billing_cycle: picked.cycle,
      outcome: isAuthenticated ? 'checkout' : 'sign_in',
      source: 'partner_offer',
      is_partner_choice: picked.plan === recommended.plan && picked.creditTier === recommended.creditTier
        && picked.cycle === recommended.cycle,
    });
    void checkout(picked);
  };

  // Carry on to payment once, on the way back from sign-up and onboarding, as soon as the account
  // is known and is not already paying (the checkout itself waits for the partner code).
  useEffect(() => {
    if (!resume || authLoading || !authReady || !subscriptionKnown) return;
    setResume(null);
    if (!alreadyPaying) void checkout(resume);
  }, [resume, authLoading, authReady, subscriptionKnown, alreadyPaying, checkout]);

  // The credits the code gives are promised only while they can still come: not when an older code
  // takes its place, not to an account that already pays, and not once the redeem of this code was
  // refused for good (an account that is not new, one already attributed). The redeem outcome is
  // read from the cache the notice and the checkout share; it is never started from here.
  const { data: redeemOutcome } = useQuery({
    ...pendingRedeemQuery(queryClient, numericUserId, codeThatApplies),
    enabled: false,
  });
  const codeRefused = !!redeemOutcome && !redeemOutcome.ok && redeemOutcome.errorKey !== redeemErrorKey('EMAIL_NOT_VERIFIED');
  const showGift = offer.credits > 0 && !otherCodeWaiting && !alreadyPaying && !codeRefused;

  // The pricing page opens on the visitor's selection, and still names the partner's own choice.
  const pricingLink = partnerRecommendedLink('', offer.code, recommended, choice);
  // An account that could not be read is not "still loading" for ever: it says so. Either its
  // subscription failed to load, or its numeric id never came (it arrives together with readiness,
  // so ready without it means the account status call failed, and the code could not be applied).
  const subscriptionFailed = isAuthenticated && !subscriptionLoading && subscription == null && !!subscriptionError;
  const accountIdMissing = isAuthenticated && isReady && !authLoading && numericUserId == null;
  const accountUnreadable = subscriptionFailed || accountIdMissing;
  const accountLoading = isAuthenticated && !accountUnreadable && (!authReady || !subscriptionKnown);
  const opening = checkoutState === 'opening';
  // Nothing can be started while a checkout opens or the account is still being read.
  const busy = opening || accountLoading;
  const retryAccount = () => {
    // A missing account id is only read again by a fresh page load; a subscription, by a refetch.
    if (accountIdMissing) assignLocation(window.location.href);
    else void forceLoadSubscription();
  };
  // Starter above its cap is shown at the most it sells (its price and features at the cap, with
  // the note saying so), never at a price for credits it does not offer.
  const starterCap = highestRecommendableTier('starter');
  // The largest tiers stay out of the list, as on the pricing page, unless the partner chose one.
  const maxTier = resolveMaxTierIndex(Math.max(offer.creditTier, choice.creditTier) > DEFAULT_MAX_TIER_INDEX);
  const cards = PLAN_KEYS.map((plan) => {
    const capped = plan === 'starter' && !starterAvailable;
    const tooSmall = belowApps(plan);
    const tier = capped ? starterCap : choice.creditTier;
    return (
      <OfferPlanCard
        key={plan}
        plan={plan}
        name={tCards(`${plan}.name`)}
        features={planFeatureLabels(plan, { tCards, tPricing, credits: credits(CREDIT_TIERS[tier]), creditFacts })}
        price={money(calcPrice(plan, choice.cycle, tier))}
        period={tCards('period')}
        cycle={choice.cycle}
        creditTier={tier}
        event={pricingEvent}
        recommended={plan === recommended.plan}
        recommendedLabel={partnerName ? t('badge', { partner: partnerName }) : t('badgeGeneric')}
        selected={plan === choice.plan}
        disabled={capped || tooSmall}
        disabledNote={tooSmall ? appsNeedPlan : t('starterCap', { credits: credits(CREDIT_TIERS[starterCap]) })}
        cta={t('choose', { plan: tCards(`${plan}.name`) })}
        onChoose={() => {
          const picked = { ...choice, plan };
          setChoice(picked);
          choose(picked);
        }}
        // An account that already pays changes plan on its pricing page (the hero card says so).
        busy={busy || alreadyPaying || accountUnreadable}
      />
    );
  });

  return (
    <main className="min-h-screen bg-theme-primary pb-16" data-testid="partner-offer">
      <PendingRewardCodeRedeemer />
      <PartnerDarkPanel className="mx-4 max-w-6xl md:mx-6 xl:mx-auto">
        <div className="grid gap-8 px-6 py-10 md:px-12 md:py-14 lg:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)] lg:items-center lg:gap-12">
          <div className="min-w-0">
            <PartnerDarkEyebrow icon={Handshake}>{t('eyebrow')}</PartnerDarkEyebrow>
            {offer.partner && (
              <div className="mt-6 flex items-center gap-3" data-testid="partner-offer-partner">
                <span className="rounded-full p-[3px]" style={PARTNER_GOLD_BG}>
                  <PublisherAvatar userId={null} name={offer.partner.name} src={offer.partner.avatarUrl ?? undefined} size={44} variant="neutral" />
                </span>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2 text-base font-semibold">
                    <span className="truncate">{offer.partner.name}</span>
                    <PartnerBadgeIcon partner px={18} label={t('officialPartner')} />
                    {offer.partner.tier && <PartnerTierChip tier={offer.partner.tier} label={tTier(offer.partner.tier)} />}
                  </div>
                  {offer.partner.handle && <div className="text-sm" style={{ color: PARTNER_INK_FAINT }}>@{offer.partner.handle}</div>}
                </div>
              </div>
            )}
            <h1 className="mt-6 text-3xl font-bold md:text-5xl" style={PARTNER_DISPLAY}>
              {t.rich(partnerName ? 'title' : 'titleGeneric', {
                partner: partnerName ?? '',
                plan: tCards(`${recommended.plan}.name`),
                gold: (chunks) => <span style={PARTNER_GOLD_TEXT}>{chunks}</span>,
              })}
            </h1>
            <p className="mt-4 text-lg" style={{ color: PARTNER_INK_MUTED }}>
              {t('lead', { credits: credits(CREDIT_TIERS[recommended.creditTier]), cycle: recommended.cycle })}
            </p>
            {showGift && (
              <p
                className="mt-6 inline-flex items-center gap-2 rounded-full px-4 py-2 text-sm font-medium"
                style={{ background: 'rgba(242,182,64,0.14)', border: '1px solid rgba(242,182,64,0.35)', color: PARTNER_GOLD }}
                data-testid="partner-offer-gift"
              >
                <Gift className="h-3.5 w-3.5 flex-shrink-0" aria-hidden />
                {t('gift', { credits: credits(offer.credits), code: offer.code })}
              </p>
            )}
          </div>

          {/* The price of the current choice, and the way to pay, in front from the first screen. */}
          <div className="rounded-2xl p-6 md:p-8" style={PARTNER_GLASS} data-testid="offer-summary">
            <div className="text-sm" style={{ color: PARTNER_INK_FAINT }}>{t('summaryTitle')}</div>
            <div className="mt-1 text-base font-semibold">
              {t('summaryPlan', { plan: tCards(`${choice.plan}.name`), credits: credits(CREDIT_TIERS[choice.creditTier]) })}
            </div>
            <div className="mt-4 flex flex-wrap items-baseline gap-x-2" data-testid="offer-price">
              <span className="text-5xl font-bold tabular-nums" style={{ ...PARTNER_DISPLAY, ...PARTNER_GOLD_TEXT }}>{money(price)}</span>{' '}
              <span className="text-sm" style={{ color: PARTNER_INK_MUTED }}>{t('pricePeriod', { cycle: choice.cycle })}</span>
            </div>
            {offer.apps.length > 0 && !alreadyPaying && (
              <a href="#offer-apps" className="mt-2 inline-flex items-center gap-1.5 text-sm font-medium hover:underline" style={{ color: PARTNER_GOLD }} data-testid="offer-apps-included">
                <PackageCheck className="h-3.5 w-3.5" aria-hidden />
                {t('appsIncluded', { count: offer.apps.length })}
              </a>
            )}
            {alreadyPaying ? (
              <div className="mt-6 text-sm" style={{ color: PARTNER_INK_MUTED }} data-testid="offer-already-paying">
                <p>{t('alreadySubscribed')}</p>
                <Link href={pricingLink} className="mt-2 inline-flex items-center gap-1 font-medium text-white underline">
                  {t('alreadySubscribedLink')}
                </Link>
              </div>
            ) : (
              <button
                type="button"
                className={cn(PARTNER_GOLD_CTA, 'mt-6 w-full disabled:opacity-70')}
                style={PARTNER_GOLD_BG}
                disabled={busy || accountUnreadable || belowApps(choice.plan)}
                onClick={() => choose(choice)}
                data-testid="offer-continue"
              >
                {opening ? t('opening') : t('continue', { plan: tCards(`${choice.plan}.name`) })}
                {!opening && <ArrowRight className="h-3.5 w-3.5" aria-hidden />}
              </button>
            )}
            {!alreadyPaying && belowApps(choice.plan) && (
              <p className="mt-3 text-sm" style={{ color: PARTNER_INK_MUTED }} data-testid="offer-apps-need-plan">{appsNeedPlan}</p>
            )}
            {accountUnreadable && (
              <div className="mt-3 text-sm" style={{ color: PARTNER_INK_MUTED }} data-testid="offer-account-error">
                <p>{t('accountError')}</p>
                <button type="button" onClick={retryAccount} className="mt-1 font-medium text-white underline cursor-pointer">
                  {t('retry')}
                </button>
              </div>
            )}
            {/* The price list pays without the offer, so without its apps: not offered when there are any. */}
            {offer.apps.length === 0 && (
              <div className="mt-4 text-center">
                <Link href={pricingLink} className="text-sm underline" style={{ color: PARTNER_INK_FAINT }} data-testid="offer-all-plans">{t('allPlans')}</Link>
              </div>
            )}
          </div>
        </div>
      </PartnerDarkPanel>

      <OfferAppsSection
        apps={offer.apps}
        partnerName={partnerName}
        partnerVerified={offer.partner?.verified ?? false}
        note={alreadyPaying ? t('appsNotForSubscribers') : appsPlan ? appsNeedPlan : null}
      />

      <section className="mx-auto max-w-6xl px-4 pt-10 md:px-6 md:pt-14">
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
                data-testid={`offer-cycle-${cycle}`}
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
                // More credits than Starter sells move a Starter choice to Pro, the smallest plan
                // that does: the choice in front is always one that can be paid for.
                setChoice((c) => ({ ...c, creditTier, plan: c.plan === 'starter' && creditTier > starterCap ? 'pro' : c.plan }));
              }}
              data-testid="offer-credits"
            >
              {CREDIT_TIERS.slice(0, maxTier + 1).map((amount, index) => (
                <option key={index} value={index}>{t('creditsOption', { credits: credits(amount) })}</option>
              ))}
            </select>
          </label>
          {!isPartnerChoice && (
            <button
              type="button"
              onClick={resetToPartner}
              className="inline-flex items-center gap-1.5 rounded-xl border border-[#d99a1e]/60 bg-[#f2b640]/10 px-3 py-1.5 text-sm font-medium text-theme-primary cursor-pointer"
              data-testid="offer-reset"
            >
              <RotateCcw className="h-3.5 w-3.5" aria-hidden />
              {partnerName ? t('reset', { partner: partnerName }) : t('resetGeneric')}
            </button>
          )}
        </div>

        <div className="mt-10">
          <PlanGrid
            groups={[{ key: 'offer', cards }]}
            fitThree
            focus={{ index: Math.max(0, PLAN_KEYS.indexOf(choice.plan)), nonce: focusNonce }}
          />
        </div>
      </section>

      {/* A checkout can start from the hero or from any card: the failure is said where the
          visitor is, whichever they used. */}
      {(checkoutState === 'error' || checkoutState === 'planTooLow' || checkoutState === 'alreadySubscribed') && (
        <p
          role="alert"
          className="fixed inset-x-4 top-4 z-50 mx-auto max-w-md rounded-xl border border-red-500/40 bg-theme-secondary px-4 py-3 text-center text-sm text-red-500 shadow-lg"
        >
          {checkoutState === 'planTooLow' ? t('planTooLow') : checkoutState === 'alreadySubscribed' ? t('alreadySubscribed') : t('checkoutError')}
        </p>
      )}
    </main>
  );
}

function OfferPlanCard({
  plan, name, features, price, period, cycle, creditTier, event, recommended, recommendedLabel, selected,
  disabled, disabledNote, cta, onChoose, busy,
}: {
  plan: PlanKey;
  name: string;
  features: string[];
  price: string;
  period: string;
  cycle: Cycle;
  creditTier: number;
  event: ResolvedPricingEvent | null;
  recommended: boolean;
  recommendedLabel: string;
  selected: boolean;
  disabled: boolean;
  disabledNote: string;
  cta: string;
  onChoose: () => void;
  busy: boolean;
}) {
  return (
    <PlanCardFrame
      planId={plan}
      name={name}
      priceLabel={price}
      period={period}
      cycle={cycle}
      creditTierIndex={creditTier}
      event={event}
      features={features}
      defaultOpen={recommended}
      frameStyle={{
        border: recommended ? `2px solid ${PARTNER_GOLD}` : selected ? '2px solid var(--text-primary)' : '1px solid var(--border-color)',
        // A halo that stays within the rail's padding: a drop shadow below the card was clipped flat.
        boxShadow: recommended ? '0 0 0 4px rgba(242,182,64,0.18)' : undefined,
        background: 'var(--plan-card-bg, transparent)',
        opacity: disabled ? 0.6 : 1,
      }}
      badge={recommended && (
        <div
          className="absolute -top-3 left-1/2 -translate-x-1/2 whitespace-nowrap rounded-full px-3 py-1 text-xs font-semibold"
          style={PARTNER_GOLD_BG}
          data-testid="offer-recommended-badge"
        >
          {recommendedLabel}
        </div>
      )}
      footer={(collapsed) => (disabled ? (
        <p className={`${collapsed} mt-5 text-center text-sm text-theme-secondary`}>{disabledNote}</p>
      ) : (
        <button
          type="button"
          onClick={onChoose}
          disabled={busy}
          className={`${collapsed} mt-5 md:mt-6 inline-flex items-center justify-center w-full h-9 rounded-xl text-sm font-medium transition-colors duration-200 active:scale-[0.98] cursor-pointer disabled:opacity-60`}
          style={recommended ? PARTNER_GOLD_BG : { background: 'var(--accent-primary)', color: 'var(--accent-foreground)' }}
          data-testid={`offer-choose-${plan}`}
        >
          {cta}
        </button>
      ))}
      rootProps={{
        'data-testid': `offer-plan-${plan}`,
        'data-recommended': recommended ? 'true' : 'false',
        'data-selected': selected ? 'true' : 'false',
      }}
    />
  );
}

export default PartnerOfferView;
