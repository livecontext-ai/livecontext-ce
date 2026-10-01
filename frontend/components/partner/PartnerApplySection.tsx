'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Award, CheckCircle2, Clock } from 'lucide-react';
import { useAuth } from '@/lib/providers/smart-providers';
import { setLandingIntent, track } from '@/lib/analytics/analytics';
import { IS_CE } from '@/lib/edition';
import {
  PARTNER_DASHBOARD_QUERY_KEY,
  partnerProgramApi,
} from '@/lib/api/services/partner-program-api.service';
import {
  clearApplyDraft,
  loadApplyDraft,
  PARTNER_APPLY_RETURN_TO,
  saveApplyDraft,
  type PartnerApplyDraft,
} from '@/lib/partners/applyDraft';
import { PartnerApplyForm } from './PartnerApplyForm';

const DASHBOARD_PATH = '/app/settings/partner';

/**
 * The application form, on the public /partners page itself. A visitor fills it without an
 * account; "Sign in and send" keeps what they typed ({@link saveApplyDraft}) and starts the
 * sign-in, which comes back here and sends it. A signed-in visitor who already applied or is
 * already a partner sees where they stand instead of a form the backend would refuse.
 */
export function PartnerApplySection() {
  const t = useTranslations('partnersLanding.apply');
  const tDashboard = useTranslations('partnerDashboard');
  const { isAuthenticated, isLoading, loginWithRedirect } = useAuth();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<PartnerApplyDraft | null>(null);
  const [resume, setResume] = useState(false);
  const [sent, setSent] = useState(false);

  // Browser state (storage, the URL), read after mount so the server render and the first
  // client render match.
  useEffect(() => {
    const returning = new URLSearchParams(window.location.search).get('apply') === '1';
    // Back from the sign-in: bring the form into view (the return address carries no fragment).
    if (returning) document.getElementById('apply')?.scrollIntoView?.({ block: 'start' });
    const saved = loadApplyDraft();
    if (!saved) return;
    setDraft(saved);
    setResume(returning);
  }, []);

  const me = useQuery({
    queryKey: PARTNER_DASHBOARD_QUERY_KEY,
    queryFn: () => partnerProgramApi.me(),
    enabled: !IS_CE && isAuthenticated,
    retry: false,
  });

  const onSignInToSubmit = (typed: PartnerApplyDraft) => {
    saveApplyDraft(typed);
    // Before the redirect unloads the page, or the event is never flushed.
    track('landing_cta_clicked', { cta: 'partners_apply_submit', return_to: PARTNER_APPLY_RETURN_TO, is_authenticated: false });
    setLandingIntent('landing_cta', 'partners_apply_submit');
    void loginWithRedirect({ appState: { returnTo: PARTNER_APPLY_RETURN_TO } });
  };

  const onApplied = () => {
    track('partner_application_submitted', { source: 'partners_page' });
    clearApplyDraft();
    setSent(true);
    void queryClient.invalidateQueries({ queryKey: PARTNER_DASHBOARD_QUERY_KEY });
  };

  if (sent) {
    return (
      <StatusCard icon={CheckCircle2} title={t('sentTitle')} body={t('sentBody')} link={t('openDashboard')} testId="partner-apply-sent" />
    );
  }
  if (isLoading || (isAuthenticated && me.isLoading)) {
    return <div className="h-64 animate-pulse rounded-3xl" style={{ background: 'var(--bg-tertiary)' }} />;
  }
  const state = isAuthenticated ? me.data?.state : undefined;
  if (state === 'pending' && me.data?.application) {
    return (
      <StatusCard
        icon={Clock}
        title={tDashboard('pending.title')}
        body={tDashboard('pending.body', { company: me.data.application.company_name })}
        link={t('openDashboard')}
        testId="partner-apply-pending"
      />
    );
  }
  if (state === 'active' || state === 'inactive') {
    return (
      <StatusCard icon={Award} title={t('alreadyPartnerTitle')} body={t('alreadyPartnerBody')} link={t('openDashboard')} testId="partner-apply-partner" />
    );
  }

  const canResume = resume && isAuthenticated && (state === 'none' || state === 'rejected');
  return (
    <div
      className="rounded-3xl p-2 shadow-xl"
      style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}
      data-testid="partner-apply-section"
    >
      {!isAuthenticated && (
        <p className="px-6 pt-5 text-sm" style={{ color: 'var(--text-secondary)' }}>{t('signInNote')}</p>
      )}
      <PartnerApplyForm
        // Remount once a saved draft is read, so the fields start from it.
        key={draft ? 'draft' : 'empty'}
        signedIn={isAuthenticated}
        onSignInToSubmit={onSignInToSubmit}
        initialDraft={draft}
        autoSubmit={canResume}
        onApplied={onApplied}
      />
    </div>
  );
}

function StatusCard({
  icon: Icon,
  title,
  body,
  link,
  testId,
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  body: string;
  link: string;
  testId: string;
}) {
  return (
    <div className="rounded-3xl p-8 text-center" style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }} data-testid={testId}>
      <Icon className="mx-auto h-8 w-8 text-[#d99a1e]" />
      <h3 className="mt-4 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</h3>
      <p className="mt-2 text-sm" style={{ color: 'var(--text-secondary)' }}>{body}</p>
      <Link href={DASHBOARD_PATH} className="mt-6 inline-flex h-9 items-center rounded-xl px-4 text-sm font-medium" style={{ background: 'var(--accent-primary)', color: 'var(--accent-foreground)' }}>
        {link}
      </Link>
    </div>
  );
}

export default PartnerApplySection;
