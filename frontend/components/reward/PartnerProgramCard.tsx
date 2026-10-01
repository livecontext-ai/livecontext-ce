'use client';

import React from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowRight, Handshake } from 'lucide-react';
import { IS_CE } from '@/lib/edition';
import { partnerProgramApi } from '@/lib/api/services/partner-program-api.service';
import { formatPercent } from '@/lib/partners/formatAmounts';
import { maxTierPercent, TIER_STYLE } from '@/lib/partners/tiers';

/**
 * Settings -> Refer & earn: the way to the partner program, next to the friend referral. The two
 * are different things and the card says so: a referral earns credits for bringing a friend, a
 * partner earns money on the invoices of the clients they bring. Leads to the public /partners
 * page, where the program is explained and the application form lives.
 *
 * <p>Cloud only (the program has no billing to share on a self-hosted install). The top rate is
 * read from the public terms; while it loads, or if it cannot be read, the card speaks without a
 * figure rather than guessing one.
 */
export function PartnerProgramCard() {
  const t = useTranslations('reward.partnerProgram');
  const locale = useLocale();
  const terms = useQuery({
    queryKey: ['partner-program', 'terms'],
    queryFn: () => partnerProgramApi.terms(),
    enabled: !IS_CE,
    retry: false,
    staleTime: 60 * 60 * 1000,
  });
  if (IS_CE) return null;

  const top = terms.data ? maxTierPercent(terms.data.tiers ?? []) ?? terms.data.commission_percent : null;
  return (
    <div className="relative overflow-hidden rounded-xl border border-theme p-6" data-testid="partner-program-card">
      <div aria-hidden className="absolute inset-x-0 top-0 h-1" style={{ background: TIER_STYLE.gold.gradient }} />
      <div className="flex items-center gap-3 mb-3">
        <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center">
          <Handshake className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
          <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
        </div>
      </div>
      <p className="text-sm text-theme-primary">
        {top !== null
          ? t('body', { percent: formatPercent(top, locale), months: terms.data?.commission_months ?? 12 })
          : t('bodyGeneric')}
      </p>
      <Link
        href="/partners"
        className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-theme-primary underline underline-offset-2"
      >
        {t('cta')}
        <ArrowRight className="h-3.5 w-3.5" aria-hidden />
      </Link>
    </div>
  );
}

export default PartnerProgramCard;
