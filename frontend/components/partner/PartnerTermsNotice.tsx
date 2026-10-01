'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { FileSignature } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/lib/api/api-client';
import {
  PARTNER_DASHBOARD_QUERY_KEY,
  partnerProgramApi,
  type PartnerAgreement,
} from '@/lib/api/services/partner-program-api.service';
import { PARTNER_TERMS_VERSION, partnerTermsPathFor } from '@/lib/partners/terms';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

/**
 * Where a partner stands with the Partner Program Terms (V557), on their partner page.
 *
 * <p>When the backend says acceptance is {@code required} (a partner whose code predates the
 * terms, or who accepted an older version), a banner asks them to read and accept the current
 * text, and says plainly that no commission is paid out until they accept any version. Once
 * accepted, a single line records which version and when.
 */
export function PartnerTermsNotice({ agreement }: { agreement: PartnerAgreement | null | undefined }) {
  const t = useTranslations('partnerDashboard.agreement');
  const tErrors = useTranslations('partnerDashboard.errors');
  const locale = useLocale();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const accept = useMutation({
    mutationFn: () => partnerProgramApi.acceptTerms(PARTNER_TERMS_VERSION),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: PARTNER_DASHBOARD_QUERY_KEY });
    },
    onError: (err: unknown) => {
      const code = err instanceof ApiError ? err.code : undefined;
      setError(code && tErrors.has(code) ? tErrors(code) : tErrors('generic'));
    },
  });

  if (!agreement) return null;
  const termsLink = (chunks: React.ReactNode) => (
    <Link href={partnerTermsPathFor(locale)} target="_blank" rel="noopener noreferrer" className="underline">
      {chunks}
    </Link>
  );

  if (!agreement.required) {
    if (!agreement.accepted_version || !agreement.accepted_at) return null;
    return (
      <p className="text-sm text-theme-secondary" data-testid="partner-terms-accepted">
        {t.rich('accepted', {
          version: agreement.accepted_version,
          date: formatUtcDate(agreement.accepted_at, { locale }),
          link: termsLink,
        })}
      </p>
    );
  }

  return (
    <div
      role="region"
      aria-labelledby="partner-terms-notice-title"
      className="rounded-xl border border-amber-500/40 bg-amber-500/10 p-5 space-y-3"
      data-testid="partner-terms-notice"
    >
      <div className="flex items-start gap-3">
        <FileSignature className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" aria-hidden />
        <div className="space-y-1">
          <h3 id="partner-terms-notice-title" className="text-base font-semibold text-theme-primary">
            {agreement.payouts_blocked ? t('titleFirst') : t('titleUpdated')}
          </h3>
          <p className="text-sm text-theme-secondary">
            {agreement.payouts_blocked
              ? t.rich('bodyFirst', { link: termsLink })
              : t.rich('bodyUpdated', { version: PARTNER_TERMS_VERSION, link: termsLink })}
          </p>
        </div>
      </div>
      {error && <p role="alert" className="text-sm text-red-500">{error}</p>}
      <Button size="sm" onClick={() => accept.mutate()} disabled={accept.isPending} data-testid="partner-terms-accept">
        {accept.isPending ? t('accepting') : t('accept', { version: PARTNER_TERMS_VERSION })}
      </Button>
    </div>
  );
}

export default PartnerTermsNotice;
