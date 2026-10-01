'use client';

import React from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { AlertTriangle, Copy, Landmark } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { PartnerBadgeIcon } from '@/components/profile/PartnerBadgeIcon';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { formatAmounts, formatMinor, formatPercent } from '@/lib/partners/formatAmounts';
import { PARTNER_LINK_PARAM } from '@/lib/lifecycle/pendingRewardCode';
import type { PartnerAccount, PartnerCommissionLine, PartnerStanding } from '@/lib/api/services/partner-program-api.service';
import { TIER_STYLE } from '@/lib/partners/tiers';
import { PartnerTierChip } from './PartnerTierChip';

/** Where a partner sends the invoice and bank details that trigger a payout. */
export const PARTNER_PAYOUT_EMAIL = 'contact@livecontext.ai';

/** The link a partner shares; the same shape the admin report copies. */
export function partnerLink(origin: string, code: string): string {
  return `${origin}/?${PARTNER_LINK_PARAM}=${encodeURIComponent(code)}`;
}

/**
 * An approved partner's dashboard: their link and code, their badge, what they brought and
 * what they earned, line by line. Every figure comes from the partner endpoint, which uses the
 * same bucketing as the admin report, so the partner and the admin always read one number.
 */
export function PartnerDashboard({
  partner,
  active,
  onCopy,
  settleDays = null,
}: {
  partner: PartnerAccount;
  active: boolean;
  onCopy: (text: string) => void;
  /** How old an invoice must be to count toward a tier (program terms); null when unknown. */
  settleDays?: number | null;
}) {
  const t = useTranslations('partnerDashboard.dashboard');
  const locale = useLocale();
  const origin = typeof window !== 'undefined' ? window.location.origin : '';
  const link = partnerLink(origin, partner.code);
  const credits = partner.audience_credits.toLocaleString(locale);
  const stats = [
    { key: 'signups', value: partner.redemptions.toLocaleString(locale) },
    { key: 'paying', value: partner.paying_customers.toLocaleString(locale) },
    { key: 'onHold', value: formatAmounts(partner.commissions.on_hold, locale) || '-' },
    { key: 'payable', value: formatAmounts(partner.commissions.payable, locale) || '-' },
    { key: 'paid', value: formatAmounts(partner.commissions.paid, locale) || '-' },
  ] as const;

  return (
    <div className="space-y-6">
      {!active && (
        <div role="status" className="flex items-start gap-2 rounded-xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-theme-primary">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-600" aria-hidden />
          {t('inactive')}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="bg-theme-secondary rounded-xl p-6 lg:col-span-2 space-y-3">
          <h3 className="text-base font-semibold text-theme-primary">{t('linkTitle')}</h3>
          <p className="text-sm text-theme-secondary">{t('linkHint', { credits })}</p>
          <div className="flex flex-wrap items-center gap-2">
            <code className="min-w-0 flex-1 truncate rounded-lg bg-theme-tertiary px-3 py-2 text-sm text-theme-primary" data-testid="partner-link">
              {link}
            </code>
            <Button size="sm" variant="outline" className="gap-1" onClick={() => onCopy(link)}>
              <Copy className="h-3.5 w-3.5" />
              {t('copyLink')}
            </Button>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-sm text-theme-secondary">
            {t('code')}
            <span className="font-mono text-theme-primary">{partner.code}</span>
            <Button size="sm" variant="outline" className="h-7 gap-1 px-2" onClick={() => onCopy(partner.code)}>
              <Copy className="h-3 w-3" />
              {t('copyCode')}
            </Button>
          </div>
          {partner.commission_percent != null && partner.commission_months != null && (
            <p className="text-sm text-theme-primary">
              {t('yourRate', { percent: formatPercent(partner.commission_percent, locale), months: partner.commission_months })}
            </p>
          )}
          {/* The conditions specific to this code (terms clause 6.1): shown, so they can bind. */}
          {partner.valid_until && (
            <p className="text-sm text-theme-secondary" data-testid="partner-code-valid-until">
              {t('validUntil', { date: formatUtcDate(partner.valid_until, { locale }) })}
            </p>
          )}
          {partner.max_uses != null && (
            <p className="text-sm text-theme-secondary" data-testid="partner-code-max-uses">
              {t('maxUses', { count: partner.max_uses })}
            </p>
          )}
        </div>

        <div className="bg-theme-secondary rounded-xl p-6 space-y-3">
          <div className="flex items-center gap-2">
            <PartnerBadgeIcon partner={active} size="lg" label={t('badgeTitle')} />
            <h3 className="text-base font-semibold text-theme-primary">{t('badgeTitle')}</h3>
          </div>
          <p className="text-sm text-theme-secondary">{active ? t('badgeBody') : t('badgeInactive')}</p>
        </div>
      </div>

      {partner.standing && (
        <TierCard
          standing={partner.standing}
          // What the next commission earns: an older, higher code rate beats the tier rate.
          ratePercent={partner.commission_percent ?? partner.standing.commission_percent}
          settleDays={settleDays}
          locale={locale}
        />
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        {stats.map((s) => (
          <div key={s.key} className="bg-theme-secondary rounded-xl p-4" data-testid={`partner-stat-${s.key}`}>
            <div className="text-xs text-theme-secondary">{t(`stats.${s.key}`)}</div>
            <div className="mt-1 text-base font-semibold text-theme-primary">{s.value}</div>
            {s.key === 'onHold' && (
              <div className="text-xs text-theme-secondary">{t('stats.onHoldHint', { days: partner.hold_days })}</div>
            )}
          </div>
        ))}
      </div>

      <div className="bg-theme-secondary rounded-xl p-6">
        <h3 className="text-base font-semibold text-theme-primary mb-4">{t('lines.title')}</h3>
        {partner.lines.length === 0 ? (
          <p className="text-sm text-theme-secondary">{t('lines.empty')}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-theme-secondary border-b border-theme">
                  <th className="py-2 pr-4 font-medium">{t('lines.date')}</th>
                  <th className="py-2 pr-4 font-medium">{t('lines.invoice')}</th>
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

      <div className="bg-theme-secondary rounded-xl p-6 flex items-start gap-3">
        <Landmark className="mt-0.5 h-3.5 w-3.5 shrink-0 text-theme-secondary" aria-hidden />
        <div>
          <h3 className="text-sm font-semibold text-theme-primary">{t('payoutTitle')}</h3>
          <p className="text-sm text-theme-secondary">
            {t.rich('payoutBody', {
              days: partner.hold_days,
              email: () => <a href={`mailto:${PARTNER_PAYOUT_EMAIL}`} className="underline">{PARTNER_PAYOUT_EMAIL}</a>,
            })}
          </p>
        </div>
      </div>
    </div>
  );
}

/**
 * The partner's tier: where they are, the rate it pays, and how far the next tier is on the
 * revenue their clients have paid. Tiers only go up, so the bar only ever fills.
 */
function TierCard({ standing, ratePercent, settleDays, locale }: {
  standing: PartnerStanding;
  ratePercent: number;
  settleDays: number | null;
  locale: string;
}) {
  const t = useTranslations('partnerDashboard.dashboard.tier');
  const next = standing.next_tier;
  const threshold = standing.next_threshold_minor;
  const progress = next && threshold ? Math.min(100, Math.round((standing.revenue_minor / threshold) * 100)) : 100;
  const money = (minor: number) => formatMinor(minor, standing.currency, locale);
  return (
    <div className="bg-theme-secondary rounded-xl p-6 space-y-4" data-testid="partner-tier">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-base font-semibold text-theme-primary">{t('title')}</h3>
        <PartnerTierChip tier={standing.tier} label={t(`names.${standing.tier}`)} />
        {standing.founder && <PartnerTierChip tier="platinum" label={t('founder')} />}
        <span className="ml-auto text-sm text-theme-primary">
          {t('rate', { percent: formatPercent(ratePercent, locale) })}
        </span>
      </div>
      {next && threshold ? (
        <div className="space-y-2">
          <div className="h-2 w-full overflow-hidden rounded-full bg-theme-tertiary" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100} aria-label={t('progressLabel', { tier: t(`names.${next}`) })}>
            <div className="h-full rounded-full" style={{ width: `${progress}%`, background: TIER_STYLE[next].gradient }} />
          </div>
          <p className="text-sm text-theme-primary">
            {t('progress', { revenue: money(standing.revenue_minor), threshold: money(threshold), tier: t(`names.${next}`) })}
          </p>
        </div>
      ) : (
        <p className="text-sm text-theme-primary">{standing.founder ? t('topFounder') : t('top')}</p>
      )}
      <p className="text-xs text-theme-secondary">{settleDays != null ? t('hint', { days: settleDays }) : t('hintGeneric')}</p>
    </div>
  );
}

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
    <tr className="border-b border-theme" data-testid="partner-line">
      <td className="py-2 pr-4 text-theme-primary">{date(line.invoice_paid_at)}</td>
      <td className="py-2 pr-4 text-theme-secondary">{formatMinor(line.base_amount_minor, line.currency, locale)}</td>
      <td className={`py-2 pr-4 ${line.status === 'void' ? 'text-theme-secondary line-through' : 'text-theme-primary'}`}>
        {formatMinor(line.commission_minor, line.currency, locale)}
      </td>
      <td className="py-2 text-theme-secondary">{status}</td>
    </tr>
  );
}
