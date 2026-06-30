'use client';

/**
 * Squat-recovery token consume page.
 *
 * Reached via the one-time HMAC link in the squat-detected email. The token
 * in the URL path IS the auth - apiClient still sends Authorization if the
 * user is logged in, but the auth-service endpoint is public and ignores it.
 * Single-use: the consume call atomically GETDELs from Redis cloud-side.
 *
 * Renders an intro that explains *why* the user is here before showing the
 * outcome - the email link drops the user cold into this page.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { AlertTriangle, CheckCircle2 } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { ceLinkService } from '@/lib/api/ce-link.service';

type Outcome = 'pending' | 'consuming' | 'ok' | 'expired' | 'error';

export default function CloudAccountRecoverPage() {
  const t = useTranslations('settings.cloudAccount.recover');
  const tErr = useTranslations('settings.cloudAccount.errors');
  const params = useParams();
  const router = useRouter();
  const token =
    typeof params?.token === 'string'
      ? params.token
      : Array.isArray(params?.token)
      ? params.token[0]
      : '';

  const [outcome, setOutcome] = useState<Outcome>('pending');
  const [error, setError] = useState<string | null>(null);

  const handleConsume = useCallback(async () => {
    if (!token) return;
    setOutcome('consuming');
    try {
      await ceLinkService.consumeRecovery(token);
      setOutcome('ok');
    } catch (err: any) {
      const status = err?.status ?? err?.response?.status;
      if (status === 404) {
        setOutcome('expired');
      } else {
        setError(err?.message || tErr('consumeRecovery'));
        setOutcome('error');
      }
    }
  }, [token, tErr]);

  useEffect(() => {
    if (token && outcome === 'pending') {
      handleConsume();
    }
  }, [token, outcome, handleConsume]);

  return (
    <div className="min-h-[400px] flex items-center justify-center">
      <div className="max-w-md w-full space-y-4 rounded-md border border-theme p-6">
        <h1 className="text-base font-semibold text-theme-primary">
          {t('title')}
        </h1>
        <p className="text-sm text-theme-secondary">{t('intro')}</p>

        {(outcome === 'pending' || outcome === 'consuming') && (
          <div className="flex items-center gap-3 text-sm text-theme-secondary">
            <LoadingSpinner size="xs" />
            <span>{t('consuming')}</span>
          </div>
        )}

        {outcome === 'ok' && (
          <div className="space-y-3">
            <div className="flex items-start gap-2 rounded-md bg-green-500/10 p-3 text-sm text-green-400">
              <CheckCircle2 className="h-4 w-4 mt-0.5 flex-shrink-0" />
              <span>{t('okBody')}</span>
            </div>
            <Button onClick={() => router.push('/app/settings/cloud-account')}>
              {t('back')}
            </Button>
          </div>
        )}

        {outcome === 'expired' && (
          <div className="space-y-3">
            <div className="flex items-start gap-2 rounded-md bg-amber-500/10 p-3 text-sm text-amber-400">
              <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
              <span>{t('expiredBody')}</span>
            </div>
            <Button
              variant="ghost"
              onClick={() => router.push('/app/settings/cloud-account')}
            >
              {t('back')}
            </Button>
          </div>
        )}

        {outcome === 'error' && (
          <div className="space-y-3">
            <div className="flex items-start gap-2 rounded-md bg-red-500/10 p-3 text-sm text-red-400">
              <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
              <span>{error || t('errorBody')}</span>
            </div>
            <Button onClick={handleConsume}>{t('retry')}</Button>
          </div>
        )}
      </div>
    </div>
  );
}
