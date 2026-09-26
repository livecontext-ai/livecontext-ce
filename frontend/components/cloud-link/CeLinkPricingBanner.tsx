'use client';

/**
 * Cloud edition only: the pricing page reached with `?ce_link=1`, i.e. a signed-in user whose
 * self-hosted install is waiting to be linked but whose workspace is not on a paid plan yet.
 * Explains why, and offers a re-check for a user who upgraded through a path that does not end
 * on the billing success page (which continues the link by itself).
 */

import * as React from 'react';
import { useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Server } from 'lucide-react';
import { Button } from '@/components/ui/button';
import LoadingSpinner from '@/components/LoadingSpinner';
import { IS_CE } from '@/lib/edition';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { continuePendingCeLink } from '@/lib/cloud-link/pendingCeLink';

type CheckState = 'idle' | 'checking' | 'still_required' | 'no_pending' | 'error' | 'redirecting';

export function CeLinkPricingBanner() {
  const t = useTranslations('ceCloudLink.cloud');
  const searchParams = useSearchParams();
  const auth = useOptionalAuth();
  const [check, setCheck] = useState<CheckState>('idle');

  if (IS_CE || searchParams?.get('ce_link') !== '1') return null;

  const handleContinue = async () => {
    setCheck('checking');
    const outcome = await continuePendingCeLink();
    if (outcome === 'redirected') setCheck('redirecting');
    else if (outcome === 'plan_required') setCheck('still_required');
    // Nothing pending in this tab any more (expired or already used): the install has to
    // start again from its own "Connect" button.
    else if (outcome === 'none') setCheck('no_pending');
    else setCheck('error');
  };

  return (
    <div
      role="status"
      data-testid="ce-link-pricing-banner"
      className="mx-4 sm:mx-auto mt-4 max-w-3xl rounded-xl border border-[var(--accent-primary)]/30 bg-[var(--accent-primary)]/5 px-4 py-3"
    >
      <div className="flex items-start gap-3">
        <Server className="h-4 w-4 mt-0.5 flex-shrink-0 text-[var(--accent-primary)]" />
        <div className="min-w-0 flex-1 space-y-1">
          <p className="text-sm font-medium text-theme-primary">{t('pricingBannerTitle')}</p>
          <p className="text-sm text-theme-secondary">{t('pricingBannerBody')}</p>
          {check === 'still_required' && (
            <p className="text-sm text-amber-600 dark:text-amber-400">{t('pricingBannerStillRequired')}</p>
          )}
          {check === 'no_pending' && (
            <p className="text-sm text-amber-600 dark:text-amber-400">{t('pricingBannerNoPending')}</p>
          )}
          {check === 'error' && (
            <p className="text-sm text-red-600 dark:text-red-400">{t('pricingBannerError')}</p>
          )}
          {check === 'redirecting' && (
            <p className="text-sm text-theme-secondary">{t('returning')}</p>
          )}
        </div>
        {auth?.isAuthenticated && (
          <Button
            size="sm"
            variant="outline"
            onClick={handleContinue}
            disabled={check === 'checking' || check === 'redirecting'}
            className="flex-shrink-0"
          >
            {check === 'checking' && <LoadingSpinner size="xs" className="mr-2" />}
            {t('pricingBannerContinue')}
          </Button>
        )}
      </div>
    </div>
  );
}
