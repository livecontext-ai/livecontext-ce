'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { AlertTriangle, ArrowRight, CheckCircle, Loader2, MessageSquare, PackageCheck } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { dmApi } from '@/lib/api/dm-api';
import {
  partnerProgramApi,
  type OfferAppDelivery,
  type PartnerOfferWelcome,
} from '@/lib/api/services/partner-program-api.service';
import { proxiedAvatarUrl } from '@/lib/partners/avatarUrl';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';

/** How long the modal keeps asking while apps are on their way; the server keeps delivering after. */
export const WELCOME_POLL_MS = 3_000;
export const WELCOME_POLL_FOR_MS = 3 * 60_000;

const inFlight = (status: OfferAppDelivery) => status === 'WAITING' || status === 'PENDING';

/**
 * What a client sees on coming back from paying through a partner's offer, instead of the plain
 * "you are now on Pro": their plan being switched on, then each app the offer gives arriving in
 * their workspace (installed by the server whatever happens to this tab), and the partner to
 * write to. {@code planState} is the pricing page's own confirmation of the new plan.
 */
export function PartnerOfferWelcomeModal({
  token,
  planState,
  planCode,
  onClose,
}: {
  token: string;
  planState: 'processing' | 'success' | 'error';
  /** The new plan's code (STARTER, PRO, TEAM...), once known. */
  planCode: string;
  onClose: () => void;
}) {
  const t = useTranslations('partnerOffer.welcome');
  const tPlans = useTranslations('partnersLanding.calculator.plans');
  const planKey = planCode.toLowerCase();
  const planName = tPlans.has(planKey) ? tPlans(planKey) : planCode.charAt(0) + planCode.slice(1).toLowerCase();
  const router = useRouter();
  const startedAt = useRef(Date.now());
  const [writing, setWriting] = useState(false);
  const [writeFailed, setWriteFailed] = useState(false);
  const [timedOut, setTimedOut] = useState(false);
  useEffect(() => {
    const id = window.setTimeout(() => setTimedOut(true), WELCOME_POLL_FOR_MS);
    return () => window.clearTimeout(id);
  }, []);

  const welcome = useQuery({
    queryKey: ['partner-program', 'offer-welcome', token],
    queryFn: () => partnerProgramApi.offerWelcome(token),
    retry: 1,
    // Asked again while an app is on its way, for a few minutes: the server delivers on its own.
    refetchInterval: (query) => {
      // An offer that could not be read (gone, refused) is not asked again: the modal says so.
      if (query.state.status === 'error') return false;
      const data = query.state.data as PartnerOfferWelcome | undefined;
      const waiting = !data || data.apps.some((a) => inFlight(a.status));
      return waiting && Date.now() - startedAt.current < WELCOME_POLL_FOR_MS ? WELCOME_POLL_MS : false;
    },
  });
  const data = welcome.data;
  const partner = data?.partner ?? null;
  const partnerName = partner?.name ?? null;
  const apps = data?.apps ?? [];
  const stillComing = apps.some((a) => inFlight(a.status));
  const gaveUpWaiting = stillComing && timedOut;

  const write = async () => {
    if (!partner?.user_id || writing) return;
    setWriting(true);
    setWriteFailed(false);
    try {
      const thread = await dmApi.openThread(partner.user_id);
      onClose();
      router.push(`/app/messages/${thread.id}`);
    } catch {
      setWriteFailed(true);
    } finally {
      setWriting(false);
    }
  };

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-lg" data-testid="offer-welcome">
        <div className="text-center">
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-theme-tertiary">
            {planState === 'processing' && <Loader2 className="h-6 w-6 animate-spin text-theme-secondary" aria-hidden />}
            {planState === 'success' && <CheckCircle className="h-6 w-6 text-green-500" aria-hidden />}
            {planState === 'error' && <AlertTriangle className="h-6 w-6 text-amber-500" aria-hidden />}
          </div>
          <DialogTitle className="text-xl leading-tight text-theme-primary" data-testid="offer-welcome-title">
            {planState === 'success' ? t('title', { plan: planName }) : t('activating')}
          </DialogTitle>
          <DialogDescription className="mt-1 text-sm text-theme-secondary">
            {planState === 'error' ? t('slowPayment') : planState === 'success' ? t('planActive') : t('activatingHint')}
          </DialogDescription>
        </div>

        {apps.length > 0 && (
          <div className="mt-5" data-testid="offer-welcome-apps">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-theme-primary">
              <PackageCheck className="h-3.5 w-3.5 text-theme-secondary" aria-hidden />
              {partnerName ? t('appsTitle', { partner: partnerName }) : t('appsTitleGeneric')}
            </h3>
            <ul className="mt-2 space-y-2">
              {apps.map((app) => (
                <li
                  key={app.id}
                  className="flex items-center gap-3 rounded-xl border border-theme bg-theme-primary px-3 py-2"
                  data-testid={`offer-welcome-app-${app.id}`}
                  data-status={app.status}
                >
                  <span className="min-w-0 flex-1 truncate text-sm font-medium text-theme-primary">{app.title || t('appFallback')}</span>
                  {inFlight(app.status) && (
                    <span className="flex shrink-0 items-center gap-1.5 text-sm text-theme-secondary">
                      <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
                      {t('installing')}
                    </span>
                  )}
                  {app.status === 'INSTALLED' && (
                    <Link
                      href={`/app/applications/${app.id}`}
                      onClick={onClose}
                      className="inline-flex h-8 shrink-0 items-center gap-1 rounded-lg border border-theme px-2.5 text-sm text-theme-primary hover:bg-theme-tertiary"
                      data-testid="offer-welcome-open"
                    >
                      {t('open')}
                      <ArrowRight className="h-3.5 w-3.5" aria-hidden />
                    </Link>
                  )}
                  {app.status === 'FAILED' && (
                    <span className="shrink-0 text-sm text-amber-600 dark:text-amber-400">{t('failed')}</span>
                  )}
                </li>
              ))}
            </ul>
            {gaveUpWaiting && <p className="mt-2 text-sm text-theme-secondary" data-testid="offer-welcome-later">{t('arrivingLater')}</p>}
          </div>
        )}
        {welcome.isError && (
          <p className="mt-4 text-sm text-theme-secondary" data-testid="offer-welcome-unreadable">{t('unreadable')}</p>
        )}

        {partner?.user_id && (
          <div className="mt-5 rounded-xl bg-theme-tertiary p-3" data-testid="offer-welcome-partner">
            <div className="flex items-center gap-3">
              <PublisherAvatar userId={partner.user_id} name={partnerName ?? undefined} src={proxiedAvatarUrl(partner.avatar_url) ?? undefined} size={36} variant="neutral" />
              <div className="min-w-0 flex-1 text-sm text-theme-secondary">
                {partnerName ? t('partnerLine', { partner: partnerName }) : t('partnerLineGeneric')}
              </div>
            </div>
            <Button type="button" variant="outline" className="mt-3 w-full" onClick={write} disabled={writing} data-testid="offer-welcome-write">
              <MessageSquare className="h-3.5 w-3.5" aria-hidden />
              {partnerName ? t('write', { partner: partnerName }) : t('writeGeneric')}
            </Button>
          </div>
        )}
        {writeFailed && <p role="alert" className="mt-2 text-sm text-red-500">{t('writeError')}</p>}

        <Button type="button" className="mt-5 w-full" onClick={onClose} data-testid="offer-welcome-close">
          {t('start')}
        </Button>
      </DialogContent>
    </Dialog>
  );
}

export default PartnerOfferWelcomeModal;
