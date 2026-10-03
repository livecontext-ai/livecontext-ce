import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, getTranslations, setRequestLocale } from 'next-intl/server';
import type * as React from 'react';
import {
  Award,
  Check,
  ChevronDown,
  Crown,
  Handshake,
  LineChart,
  Search,
  Send,
  Sparkles,
  Store,
  Trophy,
  UserRound,
} from 'lucide-react';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';
import { LandingHeader, LandingFooter, landingChromeStyles } from '@/components/landing/LandingShell';
import LandingThemeProvider from '@/components/landing/LandingThemeProvider';
import { landingStyles } from '@/components/landing/landingStyles';
import { shellLabels } from '@/components/landing/shellLabels';
import { Section, SectionEyebrow, SectionH2, SectionLead } from '@/components/landing/LandingSections';
import FaqItem from '@/app/[locale]/_landing/FaqItem';
import { HERO_EXAMPLE_CLIENTS, PartnerHeroEarnings } from '@/components/partner/PartnerHeroEarnings';
import { PartnerScenarioCards, type Scenario } from '@/components/partner/PartnerScenarioCards';
import { PartnerEarningsCalculator } from '@/components/partner/PartnerEarningsCalculator';
import { PartnerTierTrack, type TierStop } from '@/components/partner/PartnerTierTrack';
import { PartnerFounderBand } from '@/components/partner/PartnerFounderBand';
import { PARTNER_EXAMPLE, PartnerBadgeSpotlight, pickNeighbours } from '@/components/partner/PartnerBadgeSpotlight';
import { PartnerBenefitsBento, type BenefitKey } from '@/components/partner/PartnerBenefitsBento';
import { PARTNER_DARK, PARTNER_GOLD_BG, PARTNER_GOLD_CTA, PARTNER_GOLD_TEXT, PartnerDarkEyebrow } from '@/components/partner/partnerTheme';
import { PartnerBadgeIcon } from '@/components/profile/PartnerBadgeIcon';
import { PartnerApplySection } from '@/components/partner/PartnerApplySection';
import { IS_CE } from '@/lib/edition';
import { socialCard } from '@/lib/seo/socialCard';
import { SITE_URL, homeHref, localizedPathAlternates, localizedPathHref } from '@/lib/seo/siteUrl';
import JsonLd from '@/components/seo/JsonLd';
import { CREDIT_TIERS, DEFAULT_MAX_TIER_INDEX } from '@/lib/billing/pricing-constants';
import { fetchAllPublicPublications } from '@/lib/marketplace/publicPublications';
import { fetchPartnerTerms } from '@/lib/partners/publicPartnerTerms';
import { formatPercent } from '@/lib/partners/formatAmounts';
import { partnerTermsPathFor } from '@/lib/partners/terms';
import {
  clientsForAYear,
  creditTierOf,
  daysUntil,
  estimateMonths,
  EXAMPLE_CLIENT_PLAN,
  maxTierPercent,
  monthlyBill,
  monthlyCommission,
  TIER_ORDER,
  tierTerm,
  wholeMoney,
  type ClientPlan,
  type PartnerTierKey,
} from '@/lib/partners/tiers';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import type { PartnerProgramTerms } from '@/lib/api/services/partner-program-api.service';

/** The partner program's shared look (dark bands, gold): the same as the partner dashboard. */
const DARK = PARTNER_DARK;
const GOLD_TEXT = PARTNER_GOLD_TEXT;
const GOLD_CTA = PARTNER_GOLD_CTA;
const GHOST_CTA = 'inline-flex items-center gap-2 h-11 px-6 rounded-xl text-sm font-medium transition-colors hover:bg-white/10 cursor-pointer';
const GOLD_BG = PARTNER_GOLD_BG;

/**
 * The three earnings examples, each on a real plan and credit tier: one large client (Team with
 * 5M credits a month), an agency (clients on the example plan) and a large agency (Team, 1M).
 */
const SCENARIOS: readonly { key: Scenario['key']; clients: number; plan: ClientPlan }[] = [
  { key: 'single', clients: 1, plan: { plan: 'team', creditTier: creditTierOf(5_000_000) } },
  { key: 'agency', clients: 30, plan: EXAMPLE_CLIENT_PLAN },
  { key: 'large', clients: 40, plan: { plan: 'team', creditTier: creditTierOf(1_000_000) } },
];

const BENEFITS: readonly BenefitKey[] = ['apps', 'credits', 'tracking', 'payout', 'hosting', 'team'];

const BADGE_PLACES = [
  { key: 'profile', icon: UserRound },
  { key: 'apps', icon: Store },
  { key: 'search', icon: Search },
] as const;

const STEPS = ['apply', 'link', 'clients', 'earn'] as const;

/**
 * Where an example price can be checked: the in-app pricing page, preset to monthly billing and
 * that credit tier (the public /pricing redirects to the landing, which has no credit slider).
 * A tier above the default range (5M credits) is shown there on request (?tiers=full).
 */
function pricingHref(plan: ClientPlan, locale: string): string {
  const full = plan.creditTier > DEFAULT_MAX_TIER_INDEX ? '&tiers=full' : '';
  // In the page's language: an unprefixed app link would open the pricing in another one.
  return `/${locale}/app/settings/pricing?pricingMode=subscription&billingCycle=monthly&creditTierIndex=${plan.creditTier}${full}`;
}

/** A credit amount the way the locale abbreviates it: "250K", "250 k", "25万". */
function compactCredits(plan: ClientPlan, locale: string): string {
  return CREDIT_TIERS[plan.creditTier].toLocaleString(locale, { notation: 'compact' });
}

/**
 * Every figure the copy interpolates, formatted for the page's locale and read from the backend
 * terms: the rates, each threshold (in money and in clients on the example plan, at its real
 * price), the founder deadline. Nothing here is written in the copy itself, so the page can
 * never promise a rate the program does not pay, nor a bill the price list does not charge.
 */
function figures(terms: PartnerProgramTerms, locale: string) {
  const tiers = terms.tiers;
  const currency = terms.tier_currency;
  const pct = (tier: PartnerTierKey) => formatPercent(tierTerm(tiers, tier)?.commission_percent ?? 0, locale);
  const threshold = (tier: PartnerTierKey) => tierTerm(tiers, tier)?.threshold_minor ?? 0;
  const top = maxTierPercent(tiers) ?? terms.commission_percent;
  return {
    topPercent: top,
    max: formatPercent(top, locale),
    silver: pct('silver'),
    gold: pct('gold'),
    platinum: pct('platinum'),
    goldAmount: wholeMoney(threshold('gold') / 100, currency, locale),
    platinumAmount: wholeMoney(threshold('platinum') / 100, currency, locale),
    goldClients: clientsForAYear(threshold('gold')),
    platinumClients: clientsForAYear(threshold('platinum')),
    bill: wholeMoney(monthlyBill(EXAMPLE_CLIENT_PLAN), currency, locale),
    exampleCredits: compactCredits(EXAMPLE_CLIENT_PLAN, locale),
    zero: wholeMoney(0, currency, locale),
    months: terms.commission_months,
    days: terms.hold_days,
    settleDays: terms.tier_settle_days ?? 0,
    credits: terms.audience_credits.toLocaleString(locale),
    // Date only (the deadline is midnight UTC): formatted without a time zone shift.
    founderDate: terms.founder_until ? formatUtcDate(terms.founder_until.slice(0, 10), { locale }) : '',
  };
}

const DarkEyebrow = PartnerDarkEyebrow;

/** The page's path; each language has its own URL (see LOCALIZED_PUBLIC_PATHS). */
const PARTNERS_PATH = '/partners';

/**
 * The search snippet: the top rate the program pays when the terms say it, a figure-free sentence
 * otherwise (the page never quotes a rate the backend did not give).
 */
async function metaDescription(locale: string): Promise<string> {
  const [t, terms] = await Promise.all([
    getTranslations({ locale, namespace: 'partnersLanding.metadata' }),
    fetchPartnerTerms(),
  ]);
  const top = terms ? maxTierPercent(terms.tiers) ?? terms.commission_percent : null;
  return top ? t('descriptionRate', { rate: formatPercent(top, locale) }) : t('description');
}

export async function generateMetadata(): Promise<Metadata> {
  // The program is a cloud one: a self-hosted build serves no such page, and never indexes it.
  if (IS_CE) return { robots: { index: false, follow: false } };
  const locale = await resolveRequestLocale();
  const [t, description] = await Promise.all([
    getTranslations({ locale, namespace: 'partnersLanding.metadata' }),
    metaDescription(locale),
  ]);
  const path = localizedPathHref(PARTNERS_PATH, locale);
  return {
    title: t('title'),
    description,
    // One URL per language, each canonical to itself, the cluster in hreflang (x-default: English).
    alternates: { canonical: path, languages: localizedPathAlternates(PARTNERS_PATH) },
    ...socialCard({ title: t('title'), description, path, locale }),
    // No robots key on the cloud: an explicit 'robots: undefined' wiped the root layout's directives.
  };
}

/**
 * The public partner program page. It answers, in this order: how much (a partner's month in
 * money, examples, then your own numbers), how the rate climbs (Silver to Gold to Platinum, and
 * the founders who start at the top), what the badge looks like where clients see it, how it
 * works, and the application form itself (no detour through the app: a visitor fills it, signs
 * in on send, and it goes).
 *
 * <p>Every figure is read from the backend terms (`fetchPartnerTerms`); when that read fails, or
 * the tier ladder is incomplete, the figures and the sections built on them are left out rather
 * than guessed.
 *
 * <p>Cloud only. The program has no billing to share on a self-hosted install, so the route does
 * not exist there. The page lives outside the `[locale]` tree: its client islands get their
 * messages through a scoped `NextIntlClientProvider`.
 */
export default async function PartnersPage() {
  if (IS_CE) notFound();
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const [t, tProfile, shell, terms, messages, marketplace] = await Promise.all([
    getTranslations({ locale, namespace: 'partnersLanding' }),
    // The zoomed profile shows the real profile's own labels (tier chip, Subscribe, Message).
    getTranslations({ locale, namespace: 'profile' }),
    shellLabels(locale),
    fetchPartnerTerms(),
    getMessages({ locale }),
    // The first page of the public marketplace, the same read (and cache entry) the marketplace
    // page itself makes: its real listings are the scenery around the partner's app. A failed
    // read is an empty page, and the spotlight falls back to quiet placeholders.
    fetchAllPublicPublications({ maxPages: 1 }),
  ]);
  const f = terms ? figures(terms, locale) : null;
  const hasTiers = terms?.tiers.length === TIER_ORDER.length;
  // Every section that quotes a tier needs the whole ladder: never "0% as Silver".
  const ladder = hasTiers && terms && f ? { terms, f, tiers: terms.tiers } : null;
  // The founder offer quotes the Platinum rate, so it needs the ladder too. The terms are cached
  // for an hour: the deadline is checked again at render, so the offer never outlives it.
  const founderOpen = Boolean(ladder && terms?.founder_open && f?.founderDate && terms.founder_until
    && Date.now() < Date.parse(terms.founder_until));
  const founderDays = founderOpen ? daysUntil(terms?.founder_until) : 0;

  // Only what the client islands read, not the whole catalogue.
  const landing = messages.partnersLanding as Record<string, unknown>;
  const dashboard = messages.partnerDashboard as Record<string, unknown>;
  const clientMessages = {
    partnersLanding: { calculator: landing.calculator, tiers: landing.tiers, apply: landing.apply },
    partnerDashboard: { apply: dashboard.apply, errors: dashboard.errors, pending: dashboard.pending },
  };

  const tierName = (tier: PartnerTierKey) => t(`tiers.${tier}.name`);
  const planName = (plan: ClientPlan) => t(`calculator.plans.${plan.plan}`);
  const examplePlan = EXAMPLE_CLIENT_PLAN;

  // The hero's partner: a founding partner while founders are named, otherwise a partner
  // climbing the ladder on revenue. Both on the real rates and the real price of the example plan.
  const hero = ladder
    ? estimateMonths({
      tiers: ladder.tiers,
      clientsByMonth: HERO_EXAMPLE_CLIENTS,
      monthlySpend: monthlyBill(examplePlan),
      founder: founderOpen,
      settleDays: ladder.f.settleDays,
    })
    : null;
  const heroLast = hero?.months[hero.months.length - 1];

  const scenarios: Scenario[] = ladder
    ? SCENARIOS.map(({ key, clients, plan }) => {
      const bill = monthlyBill(plan);
      const perMonth = monthlyCommission(clients, bill, ladder.f.topPercent);
      const money = (major: number) => wholeMoney(major, ladder.terms.tier_currency, locale);
      return {
        key,
        persona: t(`earnings.${key}`),
        clients: t('earnings.clientsOn', { count: clients, plan: planName(plan), credits: compactCredits(plan, locale) }),
        bill: t('earnings.billEach', { bill: money(bill) }),
        pricingHref: pricingHref(plan, locale),
        perMonth: money(perMonth),
        // From the month as shown, so "$2,025 a month" reads as "$24,300 a year", not $24,294.
        perYear: t('earnings.perYear', { amount: money(Math.round(perMonth) * 12) }),
      };
    })
    : [];

  const stops: TierStop[] = ladder
    ? TIER_ORDER.map((tier) => ({
      tier,
      name: tierName(tier),
      rate: t('tiers.rate', { percent: ladder.f[tier] }),
      when: tier === 'silver'
        ? t('tiers.silver.when')
        : t(`tiers.${tier}.when`, { amount: tier === 'gold' ? ladder.f.goldAmount : ladder.f.platinumAmount }),
      equivalent: tier === 'silver'
        ? undefined
        : t('tiers.equivalent', {
          count: tier === 'gold' ? ladder.f.goldClients : ladder.f.platinumClients,
          plan: planName(examplePlan),
          credits: ladder.f.exampleCredits,
          bill: ladder.f.bill,
        }),
    }))
    : [];

  const faqKeys = [
    'cost',
    ...(ladder ? ['tiers'] : []),
    ...(founderOpen ? ['founder'] : []),
    'commission',
    'payout',
    'clients',
    'hosting',
    'credits',
    'badge',
  ];
  // An answer that quotes the tier rates needs the whole ladder: without it, the figure-free
  // variant, never "0% as Silver".
  const TIER_ANSWERS = new Set(['commission', 'tiers', 'founder']);
  const faqAnswer = (key: string) => {
    const figuresUsable = f && (hasTiers || !TIER_ANSWERS.has(key));
    if (!figuresUsable) return t.has(`faq.${key}.answerGeneric`) ? t(`faq.${key}.answerGeneric`) : t(`faq.${key}.answer`);
    return t(`faq.${key}.answer`, { ...f, date: f.founderDate });
  };

  const origin = SITE_URL.replace(/\/$/, '');
  const url = `${origin}${localizedPathHref(PARTNERS_PATH, locale)}`;
  const jsonLd = {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'WebPage',
        '@id': `${url}#webpage`,
        url,
        name: t('metadata.title'),
        description: await metaDescription(locale),
        inLanguage: locale,
        breadcrumb: { '@id': `${url}#breadcrumb` },
      },
      {
        '@type': 'BreadcrumbList',
        '@id': `${url}#breadcrumb`,
        itemListElement: [
          { '@type': 'ListItem', position: 1, name: 'LiveContext', item: `${origin}${homeHref(locale) || '/'}` },
          { '@type': 'ListItem', position: 2, name: t('metadata.title'), item: url },
        ],
      },
    ],
  };

  return (
    <LandingThemeProvider respectStored lang={locale} className="min-h-screen">
      <JsonLd data={jsonLd} />
      <style>{landingChromeStyles + landingStyles}</style>
      <LandingHeader labels={shell} />
      <NextIntlClientProvider locale={locale} messages={clientMessages}>
        <main>
          {/* ── Hero: the promise, and one partner's month in money ── */}
          <section id="hero" className="relative overflow-hidden" style={{ background: DARK, color: '#fff' }}>
            <div
              aria-hidden
              className="pointer-events-none absolute inset-0"
              style={{
                background: 'radial-gradient(45% 55% at 80% 20%, rgba(242,182,64,0.22), transparent 70%), radial-gradient(40% 50% at 10% 90%, rgba(169,180,214,0.14), transparent 70%)',
              }}
            />
            <div className={`relative max-w-6xl mx-auto px-6 pt-16 pb-14 md:pt-24 md:pb-20 grid grid-cols-1 gap-14 lg:items-center${hero ? ' lg:grid-cols-2' : ''}`}>
              <div>
                <DarkEyebrow icon={Handshake}>{t('hero.eyebrow')}</DarkEyebrow>
                <h1 className="hero-h1 mt-5" style={{ color: '#fff', fontSize: 'clamp(30px, 5vw, 60px)' }}>
                  {ladder
                    ? t.rich('hero.title', { max: ladder.f.max, rate: (chunks) => <span style={GOLD_TEXT}>{chunks}</span> })
                    : t('hero.titleGeneric')}
                </h1>
                <p className="mt-6 text-lg leading-relaxed" style={{ color: 'rgba(255,255,255,0.72)' }}>
                  {f ? t('hero.lead', f) : t('hero.leadGeneric')}
                </p>
                {founderOpen && ladder && (
                  <a
                    href="#founder"
                    className="mt-6 inline-flex items-start gap-2 rounded-2xl px-4 py-2 text-sm font-medium"
                    style={{ background: 'linear-gradient(135deg, #f4f6fb, #d9def0 45%, #a9b4d6)', color: '#1f2340' }}
                    data-testid="partner-founder-banner"
                  >
                    <Crown className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                    <span>
                      {t('hero.founder', { percent: ladder.f.platinum })}
                      {founderDays > 0 && <span className="ml-2 whitespace-nowrap opacity-70">{t('hero.founderDays', { days: founderDays })}</span>}
                    </span>
                  </a>
                )}
                <div className="mt-8 flex flex-wrap items-center gap-3">
                  <a href="#apply" className={GOLD_CTA} style={GOLD_BG}>
                    <Send className="h-3.5 w-3.5" aria-hidden />
                    {t('hero.apply')}
                  </a>
                  {ladder && (
                    <a href="#earnings" className={GHOST_CTA} style={{ border: '1px solid rgba(255,255,255,0.25)', color: '#fff' }}>
                      <LineChart className="h-3.5 w-3.5" aria-hidden />
                      {t('hero.estimate')}
                    </a>
                  )}
                </div>
                <p className="mt-5 text-sm" style={{ color: 'rgba(255,255,255,0.55)' }}>{t('hero.note')}</p>
              </div>
              {hero && heroLast && ladder && (
                <div className="flex flex-col items-center lg:items-end">
                  <PartnerHeroEarnings
                    values={hero.months.map((m) => m.commission)}
                    avatarSrc={PARTNER_EXAMPLE.avatar}
                    currency={ladder.terms.tier_currency}
                    locale={locale}
                    copy={{
                      name: PARTNER_EXAMPLE.publisher,
                      role: founderOpen ? t('heroCard.roleFounder') : t('heroCard.role'),
                      thisMonth: t('heroCard.thisMonth', { month: hero.months.length }),
                      perMonth: t('heroCard.perMonth'),
                      fromClients: t('heroCard.fromClients', {
                        count: HERO_EXAMPLE_CLIENTS[HERO_EXAMPLE_CLIENTS.length - 1],
                        plan: planName(examplePlan),
                        credits: ladder.f.exampleCredits,
                      }),
                      yearTotal: t('heroCard.yearTotal'),
                      rate: t('heroCard.rate'),
                      rateValue: t('heroCard.rateValue', {
                        percent: formatPercent(tierTerm(ladder.tiers, heroLast.tier)?.commission_percent ?? 0, locale),
                        tier: tierName(heroLast.tier),
                      }),
                      chartLabel: t('heroCard.chartLabel', {
                        from: HERO_EXAMPLE_CLIENTS[0],
                        to: HERO_EXAMPLE_CLIENTS[HERO_EXAMPLE_CLIENTS.length - 1],
                      }),
                      months: [
                        t('heroCard.month', { month: 1 }),
                        t('heroCard.month', { month: Math.floor((hero.months.length - 1) / 2) + 1 }),
                        t('heroCard.month', { month: hero.months.length }),
                      ],
                      badge: t('badge.label'),
                    }}
                  />
                  {/* The partner is fictional and the figures are computed: say so, next to them. */}
                  <p className="mt-3 max-w-md text-sm text-center lg:text-right" style={{ color: 'rgba(255,255,255,0.5)' }} data-testid="partner-hero-disclosure">
                    {t('heroCard.disclosure')}
                  </p>
                </div>
              )}
            </div>

            {/* The program in four figures. */}
            {f && (
              <dl
                className="relative max-w-6xl mx-auto px-6 pb-16 grid gap-6 grid-cols-2 lg:grid-cols-4"
                aria-label={t('stats.label')}
              >
                {(ladder ? (['commission', 'months', 'join', 'credits'] as const) : (['months', 'join', 'credits'] as const)).map((key, i) => (
                  // The label comes first in the markup (a <dt> precedes its <dd>) and shows under the value.
                  <div key={key} className="flex flex-col-reverse rounded-2xl p-5" style={{ background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <dt className="mt-1 text-sm" style={{ color: 'rgba(255,255,255,0.6)' }}>{t(`stats.${key}Label`)}</dt>
                    <dd className="text-3xl md:text-4xl font-bold tracking-tight tabular-nums" style={i === 0 && ladder ? GOLD_TEXT : { color: '#fff' }}>
                      {t(`stats.${key}Value`, f)}
                    </dd>
                  </div>
                ))}
              </dl>
            )}
          </section>

          {/* ── What a partner earns, then your own numbers ── */}
          {ladder && (
            <Section id="earnings">
              <div className="text-center">
                <SectionEyebrow icon={LineChart}>{t('earnings.eyebrow')}</SectionEyebrow>
                <SectionH2>{t('earnings.title')}</SectionH2>
                <SectionLead centered>{t('earnings.lead', { percent: ladder.f.platinum })}</SectionLead>
              </div>
              <PartnerScenarioCards scenarios={scenarios} upTo={t('earnings.upTo')} perMonth={t('earnings.perMonth')} />

              <div id="calculator" className="mt-24 scroll-mt-24">
                <div className="text-center">
                  <h3
                    className="text-2xl md:text-3xl font-bold tracking-tight"
                    style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif' }}
                  >
                    {t('earnings.ownTitle')}
                  </h3>
                  <p className="mt-3 text-base" style={{ color: 'var(--text-secondary)' }}>{t('earnings.ownLead')}</p>
                </div>
                <PartnerEarningsCalculator
                  tiers={ladder.tiers}
                  currency={ladder.terms.tier_currency}
                  settleDays={ladder.f.settleDays}
                  founderOpen={founderOpen}
                  founderUntilLabel={ladder.f.founderDate || null}
                />
              </div>
            </Section>
          )}

          {/* ── How the rate climbs ── */}
          {ladder && (
            <Section alt id="tiers">
              <div className="text-center">
                <SectionEyebrow icon={Trophy}>{t('tiers.eyebrow')}</SectionEyebrow>
                <SectionH2>{t('tiers.title')}</SectionH2>
                <SectionLead centered>{t('tiers.lead')}</SectionLead>
              </div>
              <PartnerTierTrack
                stops={stops}
                showFounder={founderOpen}
                copy={{
                  perInvoice: t('tiers.perInvoice', { months: ladder.f.months }),
                  founderShortcut: t('tiers.founderShortcut'),
                  topTier: t('tiers.topTier'),
                  rules: (['auto', 'never', 'settle'] as const).map((rule) => ({
                    title: t(`tiers.rules.${rule}.title`, { days: ladder.f.settleDays }),
                    body: t(`tiers.rules.${rule}.body`, { days: ladder.f.settleDays }),
                  })),
                }}
              />
            </Section>
          )}

          {/* ── Founding partners: straight to Platinum ── */}
          {founderOpen && ladder && (
            <section id="founder" className="relative overflow-hidden" style={{ background: DARK }}>
              <div
                aria-hidden
                className="pointer-events-none absolute inset-0"
                style={{ background: 'radial-gradient(50% 70% at 85% 50%, rgba(169,180,214,0.22), transparent 70%)' }}
              />
              <div className="relative max-w-6xl mx-auto px-6 py-24 md:py-32">
                <PartnerFounderBand
                  days={founderDays}
                  copy={{
                    eyebrow: t('founder.eyebrow', { date: ladder.f.founderDate }),
                    title: t('founder.title'),
                    titleRate: t('founder.titleRate', { percent: ladder.f.platinum }),
                    body: t('founder.body', { date: ladder.f.founderDate }),
                    points: (['first', 'life', 'badge'] as const).map((p) => t(`founder.points.${p}`, { percent: ladder.f.platinum })),
                    cta: t('founder.cta'),
                    daysLeft: t('founder.daysLeft', { days: founderDays }),
                    closes: t('founder.closes', { date: ladder.f.founderDate }),
                    fine: t('founder.fine', { date: ladder.f.founderDate }),
                    termsLink: t('founder.termsLink'),
                    termsHref: partnerTermsPathFor(locale),
                    plate: t('founder.plate'),
                    plateRate: t('tiers.rate', { percent: ladder.f.platinum }),
                    plateTier: tierName('platinum'),
                  }}
                />
              </div>
            </section>
          )}

          {/* ── The badge where clients see it ── */}
          <Section id="badge">
            <div className="grid grid-cols-1 gap-14 lg:grid-cols-5 lg:items-center">
              <div className="lg:col-span-2">
                <SectionEyebrow icon={Award}>{t('badge.eyebrow')}</SectionEyebrow>
                <SectionH2>{t('badge.title')}</SectionH2>
                <p className="mt-6 text-lg leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{t('badge.body')}</p>
                <div className="mt-8 flex items-center gap-4">
                  <span
                    className="grid h-16 w-16 place-items-center rounded-2xl"
                    style={{ background: 'radial-gradient(circle, rgba(242,182,64,0.28), transparent 70%)' }}
                  >
                    <PartnerBadgeIcon partner px={44} label={t('badge.label')} />
                  </span>
                  <div>
                    <div className="text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{t('badge.label')}</div>
                    <div className="text-sm" style={{ color: 'var(--text-muted)' }}>{t('badge.oneBadge')}</div>
                  </div>
                </div>
                <ul className="mt-8 space-y-3">
                  {BADGE_PLACES.map(({ key, icon: Icon }) => (
                    <li key={key} className="flex items-center gap-3 text-base" style={{ color: 'var(--text-primary)' }}>
                      <Icon className="h-5 w-5 shrink-0" style={{ color: '#d99a1e' }} aria-hidden />
                      {t(`badge.places.${key}`)}
                    </li>
                  ))}
                </ul>
              </div>
              <div className="lg:col-span-3">
                <PartnerBadgeSpotlight
                  neighbours={pickNeighbours(marketplace.publications)}
                  locale={locale}
                  copy={{
                    badge: t('badge.label'),
                    marketplace: t('badge.marketplace'),
                    search: t('badge.search'),
                    you: t('badge.you'),
                    appTitle: t('badge.appTitle'),
                    appDescription: t('badge.appDescription'),
                    profile: t('badge.profile'),
                    profileTier: tProfile('partnerTier.platinum'),
                    follow: tProfile('follow'),
                    message: tProfile('message'),
                    profileApps: t('badge.profileApps', { count: PARTNER_EXAMPLE.apps }),
                    profileRating: t('badge.profileRating', {
                      rating: PARTNER_EXAMPLE.rating.toLocaleString(locale, { minimumFractionDigits: 1 }),
                      count: PARTNER_EXAMPLE.reviews,
                    }),
                    screen: {
                      running: t('badge.screen.running'),
                      processed: t('badge.screen.processed'),
                      booked: t('badge.screen.booked'),
                      statusBooked: t('badge.screen.statusBooked'),
                      statusReview: t('badge.screen.statusReview'),
                    },
                  }}
                />
                {/* The partner is fictional: say so, next to it (fake reviews and earnings claims). */}
                <p className="mt-3 text-sm" style={{ color: 'var(--text-muted)' }} data-testid="partner-spotlight-disclosure">
                  {t('badge.disclosure')}
                </p>
              </div>
            </div>
          </Section>

          {/* ── How it works ── */}
          <Section alt id="how">
            <div className="text-center">
              <SectionEyebrow icon={Sparkles}>{t('how.eyebrow')}</SectionEyebrow>
              <SectionH2>{t('how.title')}</SectionH2>
            </div>
            {/* The connector line sits beside the list: an <ol> may only hold <li>. */}
            <div className="relative mt-14">
              <div aria-hidden className="absolute left-[12%] right-[12%] top-6 hidden h-px lg:block" style={{ background: 'linear-gradient(90deg, transparent, #f2b640, transparent)' }} />
              <ol className="relative grid gap-8 md:grid-cols-2 lg:grid-cols-4">
              {STEPS.map((step, i) => (
                <li key={step} className="relative text-center">
                  <span
                    className="mx-auto grid h-12 w-12 place-items-center rounded-full text-base font-bold shadow-lg"
                    style={{ ...GOLD_BG, boxShadow: '0 0 0 6px var(--bg-secondary)' }}
                    aria-hidden
                  >
                    {i + 1}
                  </span>
                  <h3 className="mt-5 text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{t(`how.${step}.title`)}</h3>
                  <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                    {step === 'earn' ? (ladder ? t('how.earn.body', ladder.f) : t('how.earn.bodyGeneric')) : t(`how.${step}.body`)}
                  </p>
                </li>
              ))}
              </ol>
            </div>
          </Section>

          {/* ── What else you get ── */}
          <Section id="benefits">
            <div className="text-center">
              <SectionEyebrow icon={Sparkles}>{t('benefits.eyebrow')}</SectionEyebrow>
              <SectionH2>{t('benefits.title')}</SectionH2>
              <SectionLead centered>{t('benefits.lead')}</SectionLead>
            </div>
            <PartnerBenefitsBento
              copy={{
                items: Object.fromEntries(BENEFITS.map((key) => [key, {
                  title: t(`benefits.${key}.title`),
                  body: t(`benefits.${key}.body`),
                }])) as Record<BenefitKey, { title: string; body: string }>,
                visual: {
                  // The figures of the pictures come from the terms and the price list, or stay out.
                  creditsAmount: f ? t('benefits.visual.creditsAmount', { credits: f.credits }) : null,
                  creditsUnit: t('benefits.visual.creditsUnit'),
                  signups: t('benefits.visual.signups'),
                  paying: t('benefits.visual.paying'),
                  earned: t('benefits.visual.earned'),
                  earnedValue: hero && ladder ? wholeMoney(hero.months[hero.months.length - 1].commission, ladder.terms.tier_currency, locale) : null,
                  previousValue: hero && ladder ? wholeMoney(hero.months[hero.months.length - 2].commission, ladder.terms.tier_currency, locale) : null,
                  olderValue: hero && ladder ? wholeMoney(hero.months[hero.months.length - 3].commission, ladder.terms.tier_currency, locale) : null,
                  payingCount: String(HERO_EXAMPLE_CLIENTS[HERO_EXAMPLE_CLIENTS.length - 1]),
                  payout: t('benefits.visual.payout'),
                  paid: t('benefits.visual.paid'),
                  clients: [
                    t('benefits.visual.client', { letter: 'A' }),
                    t('benefits.visual.client', { letter: 'B' }),
                    t('benefits.visual.client', { letter: 'C' }),
                  ],
                  running: t('benefits.visual.running'),
                  ask: t('benefits.visual.ask'),
                  reply: t('benefits.visual.reply'),
                  team: t('benefits.visual.team'),
                },
              }}
            />
          </Section>

          {/* ── FAQ ── */}
          <Section alt id="faq">
            <div className="text-center">
              <SectionEyebrow icon={Sparkles}>{t('faq.eyebrow')}</SectionEyebrow>
              <SectionH2>{t('faq.title')}</SectionH2>
            </div>
            <div className="mt-12 max-w-3xl mx-auto space-y-4">
              {faqKeys.map((key, faqIndex) => (
                <FaqItem key={key} faqIndex={faqIndex} className="faq-item rounded-2xl p-6" style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}>
                  <summary className="flex items-center justify-between gap-4 cursor-pointer text-base font-semibold list-none" style={{ color: 'var(--text-primary)' }}>
                    <span>{t(`faq.${key}.question`)}</span>
                    <ChevronDown className="faq-chevron h-5 w-5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
                  </summary>
                  <p className="mt-3 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{faqAnswer(key)}</p>
                </FaqItem>
              ))}
            </div>
          </Section>

          {/* ── Apply, right here ── */}
          <section id="apply" className="relative overflow-hidden scroll-mt-16" style={{ background: DARK }}>
            <div
              aria-hidden
              className="pointer-events-none absolute inset-0"
              style={{ background: 'radial-gradient(45% 60% at 15% 30%, rgba(242,182,64,0.18), transparent 70%)' }}
            />
            <div className="relative max-w-6xl mx-auto px-6 py-24 md:py-32 grid grid-cols-1 gap-12 lg:grid-cols-5 lg:items-start">
              {/* White on the dark band; the form card keeps the page ink, or its labels vanish. */}
              <div className="lg:col-span-2" style={{ color: '#fff' }}>
                <DarkEyebrow icon={Send}>{t('apply.eyebrow')}</DarkEyebrow>
                <h2
                  className="mt-5 text-3xl md:text-5xl font-bold tracking-tight"
                  style={{ fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em', lineHeight: 1.1 }}
                >
                  {t('apply.title')}
                </h2>
                <p className="mt-6 text-lg" style={{ color: 'rgba(255,255,255,0.72)' }}>{t('apply.body')}</p>
                <ul className="mt-6 space-y-3">
                  {(['free', 'reviewed', 'reply'] as const).map((point) => (
                    <li key={point} className="flex items-center gap-3 text-base">
                      <span className="grid h-5 w-5 shrink-0 place-items-center rounded-full" style={{ background: '#f2b640' }}>
                        <Check className="h-3 w-3" style={{ color: '#2a1a00' }} aria-hidden />
                      </span>
                      {t(`apply.points.${point}`)}
                    </li>
                  ))}
                </ul>
                {founderOpen && ladder && (
                  <p
                    className="mt-6 inline-flex items-start gap-2 rounded-2xl px-4 py-3 text-sm"
                    style={{ background: 'rgba(169,180,214,0.12)', border: '1px solid rgba(169,180,214,0.3)', color: '#d9def0' }}
                  >
                    <Crown className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                    {t('apply.founderNote', { date: ladder.f.founderDate, percent: ladder.f.platinum })}
                  </p>
                )}
                <p className="mt-8 text-sm" style={{ color: 'rgba(255,255,255,0.55)' }}>
                  {t('apply.question')}{' '}
                  <Link href="/contact" className="underline" style={{ color: '#fff' }}>{t('apply.contact')}</Link>
                </p>
              </div>
              <div className="lg:col-span-3">
                <PartnerApplySection />
              </div>
            </div>
          </section>
        </main>
      </NextIntlClientProvider>
      <LandingFooter labels={shell} />
    </LandingThemeProvider>
  );
}
