'use client';

import React from 'react';
import Link from 'next/link';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { Award, Clock, ShieldAlert, User } from 'lucide-react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import { Button } from '@/components/ui/button';
import Toast, { useToast } from '@/components/Toast';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { formatPercent } from '@/lib/partners/formatAmounts';
import {
  PARTNER_DASHBOARD_QUERY_KEY,
  partnerProgramApi,
  type PartnerProgramTerms,
} from '@/lib/api/services/partner-program-api.service';
import { maxTierPercent } from '@/lib/partners/tiers';
import { PartnerApplyForm } from '@/components/partner/PartnerApplyForm';
import { PartnerDashboard } from '@/components/partner/PartnerDashboard';
import { PartnerTermsNotice } from '@/components/partner/PartnerTermsNotice';

const PARTNER_DASHBOARD_KEY = PARTNER_DASHBOARD_QUERY_KEY;

/**
 * Settings -> Partner program: the one page a partner needs. Before approval it is the
 * application (or its pending / rejected state); after, the dashboard with the partner's link,
 * badge, referrals and earnings. Cloud only: the backend answers 503 on a self-hosted install.
 */
export default function PartnerSettingsPage() {
  const { isAuthenticated, isAuthChecking, isLoading: isAuthLoading } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const t = useTranslations('partnerDashboard');
  const tSettings = useTranslations('settings');
  const locale = useLocale();
  const queryClient = useQueryClient();
  const { toasts, addToast, removeToast } = useToast();

  const me = useQuery({
    queryKey: PARTNER_DASHBOARD_KEY,
    queryFn: () => partnerProgramApi.me(),
    enabled: !IS_CE && isAuthenticated,
    retry: false,
  });

  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      addToast({ type: 'success', title: t('toasts.copied'), message: text, duration: 3000 });
    } catch {
      addToast({ type: 'error', title: t('toasts.copyFailed'), message: text, duration: 6000 });
    }
  };

  if (IS_CE) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <ShieldAlert className="h-12 w-12 text-theme-tertiary mb-4" />
        <h2 className="text-base font-medium text-theme-primary mb-2">{t('selfHosted.title')}</h2>
        <p className="text-sm text-theme-secondary">{t('selfHosted.body')}</p>
      </div>
    );
  }
  if (isAuthChecking || isAuthLoading) {
    return <div className="bg-theme-secondary rounded-xl p-6 animate-pulse h-24" />;
  }
  if (!isAuthenticated) {
    return (
      <div className="min-h-[300px] flex items-center justify-center text-center">
        <div>
          <h1 className="text-2xl font-bold text-theme-primary mb-4">{tSettings('unauthorized')}</h1>
          <p className="text-theme-secondary mb-6">{tSettings('mustBeLoggedIn')}</p>
          <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
            <User className="h-3.5 w-3.5 mr-1" />
            {tSettings('signIn')}
          </Button>
        </div>
      </div>
    );
  }

  const data = me.data;
  const onApplied = () => {
    addToast({ type: 'success', title: t('toasts.applied'), message: t('toasts.appliedBody'), duration: 6000 });
    void queryClient.invalidateQueries({ queryKey: PARTNER_DASHBOARD_KEY });
  };

  return (
    <div className="max-w-5xl space-y-6">
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-50 flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast key={toast.id} id={toast.id} type={toast.type} title={toast.title} message={toast.message} onClose={removeToast} />
          ))}
        </div>
      )}

      <div className="bg-theme-secondary rounded-xl p-6 flex items-center gap-3">
        <div className="w-10 h-10 bg-theme-tertiary rounded-xl flex items-center justify-center">
          <Award className="w-5 h-5 text-theme-primary" />
        </div>
        <div className="min-w-0">
          <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
          <p className="text-sm text-theme-secondary">
            {t('subtitle')}{' '}
            <Link href="/partners" className="underline">{t('learnMore')}</Link>
          </p>
        </div>
      </div>

      {me.isLoading && <div className="bg-theme-secondary rounded-xl p-6 animate-pulse h-24" />}
      {me.isError && <p role="alert" className="text-sm text-red-500">{t('error')}</p>}

      {data && (data.state === 'active' || data.state === 'inactive') && data.partner && (
        <PartnerTermsNotice agreement={data.agreement} />
      )}

      {data && (data.state === 'active' || data.state === 'inactive') && data.partner && (
        <PartnerDashboard
          partner={data.partner}
          active={data.state === 'active'}
          onCopy={copy}
          settleDays={data.terms?.tier_settle_days ?? null}
        />
      )}

      {data && data.state === 'pending' && data.application && (
        <div className="bg-theme-secondary rounded-xl p-6 flex items-start gap-3" data-testid="partner-pending">
          <Clock className="mt-0.5 h-3.5 w-3.5 shrink-0 text-theme-secondary" aria-hidden />
          <div>
            <h3 className="text-base font-semibold text-theme-primary">{t('pending.title')}</h3>
            <p className="text-sm text-theme-secondary">{t('pending.body', { company: data.application.company_name })}</p>
            {data.application.created_at && (
              <p className="mt-1 text-xs text-theme-secondary">
                {t('pending.submittedOn', { date: formatUtcDate(data.application.created_at, { locale }) })}
              </p>
            )}
          </div>
        </div>
      )}

      {data && (data.state === 'none' || data.state === 'rejected') && (
        <>
          <TermsStrip terms={data.terms} />
          {data.state === 'rejected' && data.application && (
            <div className="rounded-xl border border-theme p-4 text-sm" data-testid="partner-rejected">
              <p className="font-medium text-theme-primary">{t('rejected.title')}</p>
              {data.application.decision_note && (
                <p className="text-theme-secondary">{t('rejected.note', { note: data.application.decision_note })}</p>
              )}
              <p className="text-theme-secondary">{t('rejected.again')}</p>
            </div>
          )}
          <PartnerApplyForm onApplied={onApplied} />
        </>
      )}
    </div>
  );
}

/** The program terms, as the backend states them, above the application form. */
function TermsStrip({ terms }: { terms: PartnerProgramTerms }) {
  const t = useTranslations('partnerDashboard.terms');
  const locale = useLocale();
  const top = maxTierPercent(terms.tiers ?? []);
  const items = [
    // With tiers: "30% to 50%", the entry rate up to the top tier; otherwise the one rate.
    top !== null && top > terms.commission_percent
      ? t('commissionRange', { from: formatPercent(terms.commission_percent, locale), to: formatPercent(top, locale) })
      : t('commission', { percent: formatPercent(terms.commission_percent, locale) }),
    t('duration', { months: terms.commission_months }),
    t('credits', { credits: terms.audience_credits.toLocaleString(locale) }),
    t('hold', { days: terms.hold_days }),
  ];
  return (
    <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" data-testid="partner-terms">
      {items.map((item) => (
        <li key={item} className="bg-theme-secondary rounded-xl p-4 text-sm text-theme-primary">{item}</li>
      ))}
    </ul>
  );
}
