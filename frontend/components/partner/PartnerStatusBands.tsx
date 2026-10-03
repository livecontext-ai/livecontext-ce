'use client';

import React from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowUpRight, Check, Handshake, Mail, Send } from 'lucide-react';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { formatPercent } from '@/lib/partners/formatAmounts';
import type { PartnerApplication, PartnerProgramTerms } from '@/lib/api/services/partner-program-api.service';
import { maxTierPercent, TIER_ORDER, TIER_STYLE, tierTerm, wholeMoney, type PartnerTierTerm } from '@/lib/partners/tiers';
import {
  PARTNER_DISPLAY, PARTNER_GHOST_ON_DARK, PARTNER_GLASS, PARTNER_GOLD, PARTNER_GOLD_BG, PARTNER_GOLD_CTA,
  PARTNER_GOLD_TEXT, PARTNER_INK_FAINT, PARTNER_INK_MUTED, PartnerDarkEyebrow, PartnerDarkPanel,
} from './partnerTheme';

/** Where applying happens: the form of the public page, the only one. */
export const PARTNER_APPLY_HREF = '/partners#apply';

/**
 * An application under review, as a three-step track: sent, under review, answer by e-mail. The
 * page becomes the partner's dashboard once the application is approved.
 */
export function PartnerPendingBand({ application }: { application: PartnerApplication }) {
  const t = useTranslations('partnerDashboard');
  const locale = useLocale();
  const steps = [
    { key: 'sent', state: 'done' as const },
    { key: 'review', state: 'current' as const },
    { key: 'reply', state: 'next' as const },
  ];
  return (
    <PartnerDarkPanel data-testid="partner-pending">
      <div className="grid gap-8 p-6 md:p-8 lg:grid-cols-5 lg:items-center">
        <div className="lg:col-span-3">
          <h2><PartnerDarkEyebrow icon={Handshake}>{t('title')}</PartnerDarkEyebrow></h2>
          <h3 className="mt-5 text-2xl md:text-3xl font-bold" style={PARTNER_DISPLAY}>{t('pending.title')}</h3>
          <p className="mt-4 text-base leading-relaxed" style={{ color: PARTNER_INK_MUTED }}>
            {t('pending.body', { company: application.company_name })}
          </p>
          <Link
            href="/partners"
            className="mt-6 inline-flex items-center gap-1 text-sm hover:underline"
            style={{ color: PARTNER_INK_FAINT }}
          >
            {t('learnMore')}
            <ArrowUpRight className="h-3.5 w-3.5" aria-hidden />
          </Link>
        </div>
        <ol className="rounded-3xl p-5 lg:col-span-2" style={PARTNER_GLASS} data-testid="partner-pending-steps">
          {steps.map((step, i) => (
            <li key={step.key} className="relative flex gap-3 pb-6 last:pb-0" aria-current={step.state === 'current' ? 'step' : undefined}>
              {i < steps.length - 1 && (
                <span
                  aria-hidden
                  className="absolute left-[13px] top-8 bottom-1 w-px"
                  style={{ background: step.state === 'done' ? PARTNER_GOLD : 'rgba(255,255,255,0.15)' }}
                />
              )}
              <StepDot state={step.state} />
              <div className="min-w-0 pt-0.5">
                <div className="text-sm font-semibold" style={{ color: step.state === 'next' ? PARTNER_INK_FAINT : '#fff' }}>
                  {t(`pending.steps.${step.key}`)}
                </div>
                {step.key === 'sent' && application.created_at && (
                  <div className="text-xs" style={{ color: PARTNER_INK_FAINT }}>
                    {t('pending.submittedOn', { date: formatUtcDate(application.created_at, { locale }) })}
                  </div>
                )}
              </div>
            </li>
          ))}
        </ol>
      </div>
    </PartnerDarkPanel>
  );
}

function StepDot({ state }: { state: 'done' | 'current' | 'next' }) {
  if (state === 'done') {
    return (
      <span className="relative grid h-7 w-7 shrink-0 place-items-center rounded-full" style={PARTNER_GOLD_BG} aria-hidden>
        <Check className="h-3.5 w-3.5" />
      </span>
    );
  }
  if (state === 'current') {
    return (
      <span className="relative grid h-7 w-7 shrink-0 place-items-center rounded-full" style={{ border: `2px solid ${PARTNER_GOLD}` }} aria-hidden>
        <span className="h-2.5 w-2.5 rounded-full animate-pulse" style={{ background: PARTNER_GOLD }} />
      </span>
    );
  }
  return (
    <span className="relative grid h-7 w-7 shrink-0 place-items-center rounded-full" style={{ border: '1px solid rgba(255,255,255,0.25)', color: PARTNER_INK_FAINT }} aria-hidden>
      <Mail className="h-3.5 w-3.5" />
    </span>
  );
}

/**
 * For a user who is not a partner (never applied, or refused): what the program pays and a way
 * to the application on /partners. There is no form here: /partners holds the only one.
 */
export function PartnerJoinBand({ terms, rejected }: { terms: PartnerProgramTerms; rejected: PartnerApplication | null }) {
  const t = useTranslations('partnerDashboard');
  const tTerms = useTranslations('partnerDashboard.terms');
  const tTier = useTranslations('partnerDashboard.dashboard.tier');
  const locale = useLocale();
  const tiers = terms.tiers ?? [];
  const top = maxTierPercent(tiers);
  const ladder = TIER_ORDER.map((key) => tierTerm(tiers, key)).filter((x): x is PartnerTierTerm => Boolean(x));
  const items = [
    // With tiers: "30% to 50%", the entry rate up to the top tier; otherwise the one rate.
    top !== null && top > terms.commission_percent
      ? tTerms('commissionRange', { from: formatPercent(terms.commission_percent, locale), to: formatPercent(top, locale) })
      : tTerms('commission', { percent: formatPercent(terms.commission_percent, locale) }),
    tTerms('duration', { months: terms.commission_months }),
    tTerms('credits', { credits: terms.audience_credits.toLocaleString(locale) }),
    tTerms('hold', { days: terms.hold_days }),
  ];
  return (
    <PartnerDarkPanel data-testid="partner-join">
      <div className="grid gap-8 p-6 md:p-8 lg:grid-cols-5 lg:items-center">
        <div className={ladder.length > 0 ? 'lg:col-span-3' : 'lg:col-span-5'}>
          <h2><PartnerDarkEyebrow icon={Handshake}>{t('title')}</PartnerDarkEyebrow></h2>
          <h3 className="mt-5 text-2xl md:text-4xl font-bold" style={PARTNER_DISPLAY}>
            {t.rich('join.title', {
              percent: formatPercent(top ?? terms.commission_percent, locale),
              rate: (chunks) => <span style={PARTNER_GOLD_TEXT}>{chunks}</span>,
            })}
          </h3>
          <p className="mt-4 text-base leading-relaxed" style={{ color: PARTNER_INK_MUTED }}>{t('subtitle')}</p>

          {rejected && (
            <div
              className="mt-5 rounded-2xl p-4 text-sm"
              style={{ background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.14)' }}
              data-testid="partner-rejected"
            >
              <p className="font-semibold">{t('rejected.title')}</p>
              {rejected.decision_note && (
                <p className="mt-1" style={{ color: PARTNER_INK_MUTED }}>{t('rejected.note', { note: rejected.decision_note })}</p>
              )}
              <p className="mt-1" style={{ color: PARTNER_INK_FAINT }}>{t('rejected.again')}</p>
            </div>
          )}

          <ul className="mt-6 grid gap-2.5 sm:grid-cols-2" data-testid="partner-terms">
            {items.map((item) => (
              <li key={item} className="flex items-start gap-2.5 text-sm">
                <span className="mt-0.5 grid h-4 w-4 shrink-0 place-items-center rounded-full" style={PARTNER_GOLD_BG} aria-hidden>
                  <Check className="h-3 w-3" />
                </span>
                <span>{item}</span>
              </li>
            ))}
          </ul>

          <div className="mt-8 flex flex-wrap items-center gap-3">
            <Link href={PARTNER_APPLY_HREF} className={PARTNER_GOLD_CTA} style={PARTNER_GOLD_BG} data-testid="partner-join-apply">
              <Send className="h-3.5 w-3.5" aria-hidden />
              {rejected ? t('join.reapply') : t('join.apply')}
            </Link>
            <Link href="/partners" className={PARTNER_GOLD_CTA} style={PARTNER_GHOST_ON_DARK}>
              {t('learnMore')}
            </Link>
          </div>
        </div>

        {ladder.length > 0 && (
          <ol className="space-y-3 lg:col-span-2" data-testid="partner-join-tiers">
            {ladder.map((term) => (
              <li
                key={term.tier}
                className="flex items-center justify-between gap-4 rounded-2xl px-5 py-4"
                style={{ background: TIER_STYLE[term.tier].gradient, color: TIER_STYLE[term.tier].ink, boxShadow: `inset 0 0 0 1px ${TIER_STYLE[term.tier].ring}` }}
              >
                <div className="min-w-0">
                  <div className="text-base font-semibold">{tTier(`names.${term.tier}`)}</div>
                  <div className="text-sm opacity-80">
                    {term.threshold_minor > 0
                      ? tTier('from', { amount: wholeMoney(term.threshold_minor / 100, terms.tier_currency, locale) })
                      : tTier('start')}
                  </div>
                </div>
                <div className="text-3xl font-bold tabular-nums" style={PARTNER_DISPLAY}>
                  {tTier('rateShort', { percent: formatPercent(term.commission_percent, locale) })}
                </div>
              </li>
            ))}
          </ol>
        )}
      </div>
    </PartnerDarkPanel>
  );
}
