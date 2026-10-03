'use client';

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { ShieldAlert, User } from 'lucide-react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import { Button } from '@/components/ui/button';
import Toast, { useToast } from '@/components/Toast';
import { PARTNER_DASHBOARD_QUERY_KEY, partnerProgramApi } from '@/lib/api/services/partner-program-api.service';
import { PartnerProgramView } from '@/components/partner/PartnerProgramView';

/**
 * Settings -> Partner program: a partner's own space. For a partner, the dashboard with their
 * link, badge, tier, referrals and earnings; for an applicant, where the review stands. Anyone
 * else reaching it by URL sees what the program pays and a way to the application on /partners,
 * which holds the only form (the nav shows this entry to partners and applicants only). Cloud
 * only: the backend answers 503 on a self-hosted install.
 */
export default function PartnerSettingsPage() {
  const { isAuthenticated, isAuthChecking, isLoading: isAuthLoading } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const t = useTranslations('partnerDashboard');
  const tSettings = useTranslations('settings');
  const { toasts, addToast, removeToast } = useToast();

  const me = useQuery({
    queryKey: PARTNER_DASHBOARD_QUERY_KEY,
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
    return <div className="bg-theme-secondary rounded-3xl p-6 animate-pulse h-64" />;
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

  return (
    <div className="max-w-5xl space-y-6">
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-50 flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast key={toast.id} id={toast.id} type={toast.type} title={toast.title} message={toast.message} onClose={removeToast} />
          ))}
        </div>
      )}

      {me.isLoading && <div className="bg-theme-secondary rounded-3xl p-6 animate-pulse h-64" />}
      {me.isError && <p role="alert" className="text-sm text-red-500">{t('error')}</p>}
      {me.data && <PartnerProgramView data={me.data} onCopy={copy} />}
    </div>
  );
}
