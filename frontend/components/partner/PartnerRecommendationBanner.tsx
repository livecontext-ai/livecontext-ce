'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { Award, Gift } from 'lucide-react';
import { IS_CE } from '@/lib/edition';
import { CREDIT_TIERS } from '@/lib/billing/pricing-constants';
import { partnerProgramApi } from '@/lib/api/services/partner-program-api.service';
import { partnerRecommendationFromSearch } from '@/lib/partners/partnerLink';
import { readPendingRewardCode } from '@/lib/lifecycle/pendingRewardCode';
import { PARTNER_GOLD_BG } from './partnerTheme';

/**
 * Where a partner's recommended link lands (the pricing page): says the plan already selected
 * is the one the partner chose for this client, and what the partner's code gives a new
 * account. The offer is read from the public endpoint, never from the link, so a hand-edited
 * link cannot make the page promise credits the code does not give; a code that offers nothing
 * (unknown, disabled) shows the recommendation alone. Nothing on a self-hosted install.
 */
export function PartnerRecommendationBanner({ className = '' }: { className?: string }) {
  const t = useTranslations('pricing.partnerRecommendation');
  const tPlans = useTranslations('partnersLanding.calculator.plans');
  const locale = useLocale();
  const search = useSearchParams();
  const rec = useMemo(
    () => (IS_CE ? null : partnerRecommendationFromSearch(new URLSearchParams(search?.toString() ?? ''))),
    [search],
  );
  const offer = useQuery({
    queryKey: ['partner-program', 'code-offer', rec?.code ?? null],
    queryFn: () => partnerProgramApi.codeOffer(rec!.code),
    enabled: !!rec,
    staleTime: 10 * 60_000,
    retry: false,
  });
  // The first code a visitor followed wins (a passing link never replaces a waiting one): when an
  // older code is waiting, this code will not be the one applied, so its credits are not promised.
  // Read after mount: browser storage, and the server render has none.
  const [otherCodeWaiting, setOtherCodeWaiting] = useState(false);
  useEffect(() => {
    const waiting = rec ? readPendingRewardCode(window) : null;
    setOtherCodeWaiting(!!waiting && waiting !== rec?.code);
  }, [rec]);

  if (!rec) return null;
  return (
    <div className={className}>
      <div
        role="note"
        className="mx-auto flex max-w-3xl items-start gap-3 rounded-2xl border border-[#d99a1e]/50 bg-[#f2b640]/10 p-4"
        data-testid="partner-recommendation"
      >
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full" style={PARTNER_GOLD_BG} aria-hidden>
          <Award className="h-3.5 w-3.5" />
        </span>
        <div className="min-w-0 text-sm">
          <p className="font-semibold text-theme-primary">{t('title')}</p>
          <p className="mt-0.5 text-theme-primary">
            {t('body', { plan: tPlans(rec.plan), credits: CREDIT_TIERS[rec.creditTier].toLocaleString(locale), cycle: rec.cycle })}
          </p>
          {offer.data && offer.data.credits > 0 && !otherCodeWaiting && (
            <p className="mt-1 flex items-start gap-1.5 text-theme-secondary" data-testid="partner-recommendation-offer">
              <Gift className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#a8700a] dark:text-[#f2b640]" aria-hidden />
              {t('offer', { code: offer.data.code, credits: offer.data.credits.toLocaleString(locale) })}
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

export default PartnerRecommendationBanner;
