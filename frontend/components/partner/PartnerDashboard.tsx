'use client';

import React from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import {
  AlertTriangle, ArrowUpRight, Award, Check, CheckCircle2, Copy, Crown, Handshake, Hourglass, Landmark,
  ListChecks, Lock, UserCheck, Users, Wallet,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { PartnerBadgeIcon } from '@/components/profile/PartnerBadgeIcon';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { formatAmounts, formatMinor, formatPercent } from '@/lib/partners/formatAmounts';
import { partnerLink } from '@/lib/partners/partnerLink';
import type { AmountsByCurrency } from '@/lib/partners/formatAmounts';
import { SITE_URL } from '@/lib/seo/siteUrl';
import type {
  PartnerAccount, PartnerCommissionLine, PartnerMonth, PartnerStanding,
} from '@/lib/api/services/partner-program-api.service';
import { TIER_ORDER, TIER_STYLE, tierTerm, wholeMoney, type PartnerTierTerm } from '@/lib/partners/tiers';
import { PartnerTierChip } from './PartnerTierChip';
import { PartnerEarningsArea } from './PartnerEarningsArea';
import { PartnerLinkBuilder } from './PartnerLinkBuilder';
import {
  PARTNER_GLASS, PARTNER_GOLD_BG, PARTNER_GOLD_CTA, PARTNER_GOLD_TEXT, PARTNER_GHOST_ON_DARK, PARTNER_INK_FAINT,
  PARTNER_INK_MUTED, PartnerDarkEyebrow, PartnerDarkPanel,
} from './partnerTheme';

/** Where a partner sends the invoice and bank details that trigger a payout. */
export const PARTNER_PAYOUT_EMAIL = 'contact@livecontext.ai';

/**
 * The partner's year: the series the chart draws, and the figures the hero prints.
 *
 * <p>A chart has one money axis, so it draws one currency: the one the partner earned most in.
 * The printed figures keep every currency (`thisMonth`, `totals`), like the stat tiles: a partner
 * paid in euros and in dollars never reads "0" for a month that paid in the other one.
 */
export interface EarningsSeries {
  /** The charted currency (lower case, as the backend sends it). */
  currency: string;
  /** `yyyy-MM`, oldest first. */
  months: string[];
  /** The charted currency's commission per month, in MINOR units. */
  minor: number[];
  /** The current (last) month, every currency. */
  thisMonth: AmountsByCurrency;
  /** The whole year, every currency. */
  totals: AmountsByCurrency;
}

/**
 * The partner's months, charted in the currency they earned most in (the standing's currency on
 * a tie or an empty year). Null when the backend sent no months (an older backend) or too few to
 * draw a line.
 */
export function earningsSeries(months: readonly PartnerMonth[] | undefined, preferred: string | undefined): EarningsSeries | null {
  if (!months || months.length < 2) return null;
  const totals: AmountsByCurrency = {};
  for (const m of months) {
    for (const [currency, minor] of Object.entries(m.commissions ?? {})) totals[currency] = (totals[currency] ?? 0) + minor;
  }
  let currency = (preferred ?? 'usd').toLowerCase();
  let best = totals[currency] ?? 0;
  for (const [c, total] of Object.entries(totals)) {
    if (total > best) { currency = c; best = total; }
  }
  return {
    currency,
    months: months.map((m) => m.month),
    minor: months.map((m) => m.commissions?.[currency] ?? 0),
    thisMonth: { ...(months[months.length - 1].commissions ?? {}) },
    totals,
  };
}

/** "Nov 25", "nov. 25": a `yyyy-MM` month in the app locale, for the chart's axis. */
export function monthLabel(month: string, locale: string): string {
  const date = new Date(`${month}-01T00:00:00Z`);
  if (Number.isNaN(date.getTime())) return month;
  return date.toLocaleString(locale, { month: 'short', year: '2-digit', timeZone: 'UTC' });
}

const STAT_ICONS = { signups: Users, paying: UserCheck, onHold: Hourglass, payable: Wallet, paid: CheckCircle2 } as const;

/**
 * An approved partner's space, in the look of /partners: a dark band with their badge, tier,
 * this month's commission, their link and twelve months of earnings; then the counters, the tier
 * ladder, their conditions, how they are paid and every commission line. Every figure comes from
 * the partner endpoint, which uses the same bucketing as the admin report, so the partner and
 * the admin always read one number.
 */
export function PartnerDashboard({
  partner,
  active,
  onCopy,
  settleDays = null,
  tiers = [],
}: {
  partner: PartnerAccount;
  active: boolean;
  onCopy: (text: string) => void;
  /** How old an invoice must be to count toward a tier (program terms); null when unknown. */
  settleDays?: number | null;
  /** The program's tiers (program terms), for the ladder; empty draws a single progress bar. */
  tiers?: readonly PartnerTierTerm[];
}) {
  const t = useTranslations('partnerDashboard.dashboard');
  const tRoot = useTranslations('partnerDashboard');
  const tTier = useTranslations('partnerDashboard.dashboard.tier');
  const tTerms = useTranslations('partnerDashboard.terms');
  const locale = useLocale();
  const link = partnerLink(SITE_URL, partner.code);
  const credits = partner.audience_credits.toLocaleString(locale);
  const standing = partner.standing ?? null;
  const series = earningsSeries(partner.months, standing?.currency);
  const payable = formatAmounts(partner.commissions.payable, locale);
  const stats = [
    { key: 'signups', value: partner.redemptions.toLocaleString(locale) },
    { key: 'paying', value: partner.paying_customers.toLocaleString(locale) },
    { key: 'onHold', value: formatAmounts(partner.commissions.on_hold, locale) || '-' },
    { key: 'payable', value: payable || '-' },
    { key: 'paid', value: formatAmounts(partner.commissions.paid, locale) || '-' },
  ] as const;
  const conditions = [
    partner.commission_months != null && { key: 'duration', text: tTerms('duration', { months: partner.commission_months }) },
    { key: 'credits', text: tTerms('credits', { credits }) },
    { key: 'hold', text: tTerms('hold', { days: partner.hold_days }) },
    // The conditions specific to this code (terms clause 6.1): shown, so they can bind.
    partner.valid_until && { key: 'valid-until', text: t('validUntil', { date: formatUtcDate(partner.valid_until, { locale }) }) },
    partner.max_uses != null && { key: 'max-uses', text: t('maxUses', { count: partner.max_uses }) },
  ].filter((c): c is { key: string; text: string } => Boolean(c));

  return (
    <div className="space-y-6">
      {!active && (
        <div role="status" className="flex items-start gap-2 rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-theme-primary">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-600" aria-hidden />
          {t('inactive')}
        </div>
      )}

      <PartnerDarkPanel data-testid="partner-hero">
        <div className="grid gap-8 p-6 md:p-8 lg:grid-cols-5 lg:items-center">
          <div className={cn('min-w-0', series ? 'lg:col-span-3' : 'lg:col-span-5')}>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2><PartnerDarkEyebrow icon={Handshake}>{tRoot('title')}</PartnerDarkEyebrow></h2>
              <Link href="/partners" className="inline-flex items-center gap-1 text-sm hover:underline" style={{ color: PARTNER_INK_FAINT }}>
                {tRoot('learnMore')}
                <ArrowUpRight className="h-3.5 w-3.5" aria-hidden />
              </Link>
            </div>

            <div className="mt-6 flex flex-wrap items-center gap-2">
              {active ? (
                <>
                  <PartnerBadgeIcon partner px={22} label={t('badgeTitle')} />
                  <span className="text-base font-semibold">{t('badgeTitle')}</span>
                </>
              ) : (
                <span className="text-sm" style={{ color: PARTNER_INK_MUTED }}>{t('badgeInactive')}</span>
              )}
              {standing && <PartnerTierChip tier={standing.tier} label={tTier(`names.${standing.tier}`)} />}
              {standing?.founder && <PartnerTierChip tier="platinum" label={tTier('founder')} />}
            </div>
            {active && <p className="mt-2 text-sm" style={{ color: PARTNER_INK_FAINT }}>{t('badgeBody')}</p>}

            {series && (
              <div className="mt-6">
                <div className="text-sm" style={{ color: PARTNER_INK_FAINT }}>{t('hero.thisMonth')}</div>
                <div className="mt-1 text-4xl sm:text-5xl font-bold tracking-tight tabular-nums" data-testid="partner-month-earned">
                  <span style={PARTNER_GOLD_TEXT}>{formatAmounts(series.thisMonth, locale) || formatMinor(0, series.currency, locale)}</span>
                </div>
              </div>
            )}
            {payable && (
              <p className="mt-2 text-sm" style={{ color: PARTNER_INK_MUTED }} data-testid="partner-hero-payable">
                {t('hero.payable', { amount: payable })}
              </p>
            )}
            {partner.commission_percent != null && partner.commission_months != null && (
              <p className="mt-2 text-sm" style={{ color: PARTNER_INK_MUTED }}>
                {t('yourRate', { percent: formatPercent(partner.commission_percent, locale), months: partner.commission_months })}
              </p>
            )}

            <div className="mt-6 rounded-2xl p-4" style={PARTNER_GLASS}>
              <h3 className="text-sm font-semibold">{t('linkTitle')}</h3>
              <p className="mt-1 text-sm" style={{ color: PARTNER_INK_FAINT }}>{t('linkHint', { credits })}</p>
              <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-center">
                <code
                  className="min-w-0 flex-1 truncate rounded-xl px-3 py-2.5 text-sm"
                  style={{ background: 'rgba(0,0,0,0.35)', border: '1px solid rgba(255,255,255,0.12)', color: '#fff' }}
                  data-testid="partner-link"
                >
                  {link}
                </code>
                <button type="button" className={PARTNER_GOLD_CTA} style={PARTNER_GOLD_BG} onClick={() => onCopy(link)}>
                  <Copy className="h-3.5 w-3.5" aria-hidden />
                  {t('copyLink')}
                </button>
              </div>
              <div className="mt-3 flex flex-wrap items-center gap-2 text-sm" style={{ color: PARTNER_INK_MUTED }}>
                {t('code')}
                <span className="font-mono font-semibold text-white">{partner.code}</span>
                <button
                  type="button"
                  className="inline-flex h-8 items-center gap-1 rounded-lg px-2.5 text-sm cursor-pointer"
                  style={PARTNER_GHOST_ON_DARK}
                  onClick={() => onCopy(partner.code)}
                >
                  <Copy className="h-3.5 w-3.5" aria-hidden />
                  {t('copyCode')}
                </button>
              </div>
            </div>
          </div>

          {series && <EarningsCard series={series} locale={locale} />}
        </div>
      </PartnerDarkPanel>

      {/* A link per client, with the plan the partner recommends: only while the code can still
          bring sign-ups (live, and not used up: a used-up code would promise credits it no longer gives). */}
      {active && !(partner.max_uses != null && partner.redemptions >= partner.max_uses) && (
        <PartnerLinkBuilder
          code={partner.code}
          audienceCredits={partner.audience_credits}
          ratePercent={partner.commission_percent ?? standing?.commission_percent ?? null}
          commissionMonths={partner.commission_months}
          onCopy={onCopy}
        />
      )}

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        {stats.map((s) => {
          const Icon = STAT_ICONS[s.key];
          const ready = s.key === 'payable' && s.value !== '-';
          return (
            <div
              key={s.key}
              className={cn(
                'rounded-2xl border bg-theme-secondary p-4 last:col-span-2 sm:last:col-span-1',
                ready ? 'border-[#d99a1e]/60' : 'border-theme',
              )}
              data-testid={`partner-stat-${s.key}`}
            >
              <div className="flex items-center gap-2 text-sm text-theme-secondary">
                <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-[#f2b640]/15 text-[#a8700a] dark:text-[#f2b640]">
                  <Icon className="h-3.5 w-3.5" aria-hidden />
                </span>
                {t(`stats.${s.key}`)}
              </div>
              <div className="mt-2 text-xl font-semibold text-theme-primary tabular-nums">{s.value}</div>
              {s.key === 'onHold' && (
                <div className="mt-0.5 text-sm text-theme-secondary">{t('stats.onHoldHint', { days: partner.hold_days })}</div>
              )}
            </div>
          );
        })}
      </div>

      {standing && (
        <TierCard
          standing={standing}
          // What the next commission earns: an older, higher code rate beats the tier rate.
          ratePercent={partner.commission_percent ?? standing.commission_percent}
          settleDays={settleDays}
          tiers={tiers}
          locale={locale}
        />
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-2xl border border-theme bg-theme-secondary p-6" data-testid="partner-conditions">
          <h3 className="flex items-center gap-2 text-base font-semibold text-theme-primary">
            <ListChecks className="h-3.5 w-3.5 text-theme-secondary" aria-hidden />
            {t('conditions.title')}
          </h3>
          <ul className="mt-4 space-y-3">
            {conditions.map((c) => (
              <li key={c.key} className="flex items-start gap-2.5 text-sm text-theme-primary" data-testid={`partner-code-${c.key}`}>
                <span className="mt-0.5 grid h-4 w-4 shrink-0 place-items-center rounded-full" style={PARTNER_GOLD_BG} aria-hidden>
                  <Check className="h-3 w-3" />
                </span>
                <span>{c.text}</span>
              </li>
            ))}
          </ul>
        </div>

        <div className="rounded-2xl border border-theme bg-theme-secondary p-6">
          <h3 className="flex items-center gap-2 text-base font-semibold text-theme-primary">
            <Landmark className="h-3.5 w-3.5 text-theme-secondary" aria-hidden />
            {t('payoutTitle')}
          </h3>
          <p className="mt-4 text-sm leading-relaxed text-theme-secondary">
            {t.rich('payoutBody', {
              days: partner.hold_days,
              email: () => <a href={`mailto:${PARTNER_PAYOUT_EMAIL}`} className="font-medium text-theme-primary underline">{PARTNER_PAYOUT_EMAIL}</a>,
            })}
          </p>
        </div>
      </div>

      <div className="rounded-2xl border border-theme bg-theme-secondary p-6">
        <h3 className="mb-4 text-base font-semibold text-theme-primary">{t('lines.title')}</h3>
        {partner.lines.length === 0 ? (
          <p className="text-sm text-theme-secondary">{t('lines.empty')}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-theme text-left text-theme-secondary">
                  <th className="py-2 pr-4 font-medium">{t('lines.date')}</th>
                  <th className="hidden py-2 pr-4 font-medium sm:table-cell">{t('lines.invoice')}</th>
                  <th className="py-2 pr-4 font-medium">{t('lines.commission')}</th>
                  <th className="py-2 font-medium">{t('lines.status')}</th>
                </tr>
              </thead>
              <tbody>
                {partner.lines.map((line, i) => (
                  <CommissionRow key={`${line.invoice_paid_at}-${i}`} line={line} locale={locale} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

/** Twelve months of commission as a gold area, in the glass card of the /partners hero. */
function EarningsCard({ series, locale }: { series: EarningsSeries; locale: string }) {
  const t = useTranslations('partnerDashboard.dashboard.earnings');
  const total = formatAmounts(series.totals, locale);
  const empty = !total;
  const first = monthLabel(series.months[0], locale);
  const last = monthLabel(series.months[series.months.length - 1], locale);
  const middle = monthLabel(series.months[Math.floor((series.months.length - 1) / 2)], locale);
  return (
    <figure
      className="min-w-0 rounded-3xl p-5 lg:col-span-2"
      style={{ ...PARTNER_GLASS, boxShadow: '0 40px 100px -40px rgba(242,182,64,0.45)' }}
      data-testid="partner-earnings"
    >
      <figcaption className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span className="text-sm font-semibold">{t('title')}</span>
        {!empty && (
          <span className="text-sm tabular-nums" style={{ color: PARTNER_INK_MUTED }} data-testid="partner-earnings-total">
            {t('total', { amount: total })}
          </span>
        )}
      </figcaption>
      <div className="relative">
        <PartnerEarningsArea
          values={series.minor.map((m) => m / 100)}
          money={(major) => wholeMoney(major, series.currency, locale)}
          labels={[first, middle, last]}
          label={t('chartLabel', { from: first, to: last })}
          idPrefix="dash-earn"
          className={cn('mt-4 w-full', empty && 'opacity-40')}
        />
        {empty && (
          <p className="absolute inset-0 flex items-center justify-center px-6 pb-6 text-center text-sm font-medium" data-testid="partner-earnings-empty">
            {t('empty')}
          </p>
        )}
      </div>
    </figure>
  );
}

/**
 * The partner's tier: where they are on the ladder, the rate it pays, and how far the next tier
 * is on the revenue their clients have paid. Tiers only go up, so the ladder only ever fills.
 */
function TierCard({ standing, ratePercent, settleDays, tiers, locale }: {
  standing: PartnerStanding;
  ratePercent: number;
  settleDays: number | null;
  tiers: readonly PartnerTierTerm[];
  locale: string;
}) {
  const t = useTranslations('partnerDashboard.dashboard.tier');
  const next = standing.next_tier;
  const threshold = standing.next_threshold_minor;
  const progress = next && threshold ? Math.min(100, Math.round((standing.revenue_minor / threshold) * 100)) : 100;
  const money = (minor: number) => formatMinor(minor, standing.currency, locale);
  const ladder = TIER_ORDER.map((key) => tierTerm(tiers, key)).filter((x): x is PartnerTierTerm => Boolean(x));
  const progressLabel = next ? t('progressLabel', { tier: t(`names.${next}`) }) : '';
  const progressText = next && threshold
    ? t('progress', { revenue: money(standing.revenue_minor), threshold: money(threshold), tier: t(`names.${next}`) })
    : '';
  return (
    <div className="rounded-2xl border border-theme bg-theme-secondary p-6 space-y-5" data-testid="partner-tier">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-base font-semibold text-theme-primary">{t('title')}</h3>
        <PartnerTierChip tier={standing.tier} label={t(`names.${standing.tier}`)} />
        {standing.founder && <PartnerTierChip tier="platinum" label={t('founder')} />}
        <span className="ml-auto text-sm font-medium text-theme-primary">
          {t('rate', { percent: formatPercent(ratePercent, locale) })}
        </span>
      </div>

      {ladder.length === TIER_ORDER.length ? (
        <TierLadder
          ladder={ladder}
          current={standing.tier}
          founder={standing.founder}
          next={next}
          revenue={standing.revenue_minor}
          nextThreshold={threshold}
          progressLabel={progressLabel}
          progressText={progressText}
          currency={standing.currency}
          locale={locale}
        />
      ) : next && threshold ? (
        <div className="h-2 w-full overflow-hidden rounded-full bg-theme-tertiary" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100} aria-label={progressLabel} aria-valuetext={progressText}>
          <div className="h-full rounded-full" style={{ width: `${progress}%`, background: TIER_STYLE[next].gradient }} />
        </div>
      ) : null}

      {next && threshold ? (
        <p className="text-sm text-theme-primary">{progressText}</p>
      ) : (
        <p className="text-sm text-theme-primary">{standing.founder ? t('topFounder') : t('top')}</p>
      )}
      <p className="text-sm text-theme-secondary">{settleDays != null ? t('hint', { days: settleDays }) : t('hintGeneric')}</p>
    </div>
  );
}

/**
 * Silver, Gold and Platinum as three metal stops on one track: the tiers passed and the current
 * one in their metal, the next ones locked. The segment toward the next tier is the progress bar,
 * filled with the share of THAT step: from the current tier's threshold to the next one's (a
 * partner who has just reached Gold sees an empty Gold-to-Platinum segment, not one already a
 * fifth full because the count started at zero).
 */
function TierLadder({ ladder, current, founder, next, revenue, nextThreshold, progressLabel, progressText, currency, locale }: {
  ladder: readonly PartnerTierTerm[];
  current: PartnerStanding['tier'];
  founder: boolean;
  next: PartnerStanding['next_tier'];
  revenue: number;
  nextThreshold: number | null;
  progressLabel: string;
  progressText: string;
  currency: string;
  locale: string;
}) {
  const t = useTranslations('partnerDashboard.dashboard.tier');
  const currentIndex = TIER_ORDER.indexOf(current);
  const from = ladder[currentIndex]?.threshold_minor ?? 0;
  const to = nextThreshold ?? from;
  const step = to > from ? Math.min(100, Math.max(0, Math.round(((revenue - from) / (to - from)) * 100))) : 100;
  return (
    <div data-testid="partner-tier-ladder">
      <div className="flex items-center">
        {ladder.map((term, i) => {
          const style = TIER_STYLE[term.tier];
          const reached = i <= currentIndex;
          const isCurrent = i === currentIndex;
          const towardNext = i === currentIndex + 1 && next === term.tier;
          const fill = i <= currentIndex ? 100 : towardNext ? step : 0;
          const NodeIcon = !reached ? Lock : isCurrent ? (founder ? Crown : Award) : Check;
          return (
            <React.Fragment key={term.tier}>
              {i > 0 && (
                <div
                  className="mx-2 h-2 flex-1 overflow-hidden rounded-full bg-theme-tertiary"
                  {...(towardNext
                    ? {
                        role: 'progressbar',
                        'aria-label': progressLabel,
                        // In money (minor units) from this tier's threshold to the next one's, read out as the sentence below.
                        'aria-valuemin': from,
                        'aria-valuemax': to,
                        'aria-valuenow': Math.min(to, Math.max(from, revenue)),
                        'aria-valuetext': progressText,
                      }
                    : {})}
                >
                  <div className="h-full rounded-full" style={{ width: `${fill}%`, background: style.gradient }} />
                </div>
              )}
              <span
                className="grid h-10 w-10 shrink-0 place-items-center rounded-full"
                style={reached
                  ? { background: style.gradient, color: style.ink, boxShadow: isCurrent ? `0 0 0 4px ${style.ring}` : `inset 0 0 0 1px ${style.ring}` }
                  : { border: '2px dashed var(--border-color)', color: 'var(--text-muted)' }}
                data-tier-node={term.tier}
                data-reached={reached ? 'true' : 'false'}
                aria-hidden
              >
                <NodeIcon className="h-3.5 w-3.5" />
              </span>
            </React.Fragment>
          );
        })}
      </div>
      <div className="mt-3 grid grid-cols-3 gap-2">
        {ladder.map((term, i) => (
          <div key={term.tier} className={i === 0 ? 'text-left' : i === ladder.length - 1 ? 'text-right' : 'text-center'}>
            <div className="text-sm font-semibold text-theme-primary">{t(`names.${term.tier}`)}</div>
            <div className="text-sm text-theme-primary tabular-nums">
              {t('rateShort', { percent: formatPercent(term.commission_percent, locale) })}
            </div>
            <div className="text-sm text-theme-secondary">
              {term.threshold_minor > 0
                ? t('fromShort', { amount: wholeMoney(term.threshold_minor / 100, currency, locale) })
                : t('start')}
            </div>
            {i === currentIndex && (
              <span
                className="mt-1 inline-block rounded-full px-2 py-0.5 text-xs font-semibold"
                style={{ background: TIER_STYLE[term.tier].gradient, color: TIER_STYLE[term.tier].ink }}
                data-testid="partner-tier-here"
              >
                {t('here')}
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

const STATUS_PILL: Record<PartnerCommissionLine['status'], string> = {
  on_hold: 'bg-amber-500/10 text-amber-700 ring-amber-500/30 dark:text-amber-400',
  payable: 'bg-emerald-500/10 text-emerald-700 ring-emerald-500/30 dark:text-emerald-400',
  paid: 'bg-[#f2b640]/15 text-[#8a5a00] ring-[#d99a1e]/40 dark:text-[#f2b640]',
  void: 'bg-theme-tertiary text-theme-secondary ring-transparent',
};

function CommissionRow({ line, locale }: { line: PartnerCommissionLine; locale: string }) {
  const t = useTranslations('partnerDashboard.dashboard.lines');
  const date = (iso: string | null) => (iso ? formatUtcDate(iso, { locale }) : '-');
  const status = line.status === 'on_hold'
    ? t('statusOnHold', { date: date(line.due_at) })
    : line.status === 'payable'
      ? t('statusPayable')
      : line.status === 'paid'
        ? t('statusPaid', { date: date(line.paid_at) })
        : t('statusVoid');
  return (
    <tr className="border-b border-theme last:border-b-0" data-testid="partner-line">
      <td className="py-2.5 pr-4 whitespace-nowrap text-theme-primary">{date(line.invoice_paid_at)}</td>
      <td className="hidden py-2.5 pr-4 whitespace-nowrap text-theme-secondary tabular-nums sm:table-cell">{formatMinor(line.base_amount_minor, line.currency, locale)}</td>
      <td className={cn('py-2.5 pr-4 whitespace-nowrap font-medium tabular-nums', line.status === 'void' ? 'text-theme-secondary line-through' : 'text-theme-primary')}>
        {formatMinor(line.commission_minor, line.currency, locale)}
      </td>
      <td className="py-2.5">
        <span className={cn('inline-flex rounded-full px-2 py-0.5 text-xs font-medium ring-1 sm:whitespace-nowrap', STATUS_PILL[line.status])} data-status={line.status}>
          {status}
        </span>
      </td>
    </tr>
  );
}
