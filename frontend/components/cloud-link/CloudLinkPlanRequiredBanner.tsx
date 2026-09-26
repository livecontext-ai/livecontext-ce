'use client';

/**
 * CE only: the linked LiveContext Cloud account is not on a paid plan, so the cloud refuses
 * every link-gated call (status.planRequired, set by the backend when register or heartbeat
 * answered 403 CLOUD_LINK_PLAN_REQUIRED). The link is KEPT: paying on the cloud restores it
 * automatically, so the only action offered is the cloud pricing page, in a new tab.
 *
 * Hosts: the settings cloud-account page and the ce-setup wizard (step 1, once linked).
 * The sidebar shows a compact badge with the same destination instead.
 */

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { AlertTriangle, ExternalLink } from 'lucide-react';
import { CLOUD_PRICING_URL } from '@/lib/edition/cloudWebUrl';
import { cn } from '@/lib/utils';

export interface CloudLinkPlanRequiredBannerProps {
  className?: string;
}

export function CloudLinkPlanRequiredBanner({ className }: CloudLinkPlanRequiredBannerProps) {
  const t = useTranslations('ceCloudLink.planRequired');
  return (
    <div
      role="alert"
      data-testid="cloud-link-plan-required-banner"
      className={cn(
        'flex items-start gap-2.5 rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-3',
        className,
      )}
    >
      <AlertTriangle className="h-4 w-4 flex-shrink-0 mt-0.5 text-amber-600 dark:text-amber-400" />
      <div className="min-w-0 space-y-1">
        <p className="text-sm font-medium text-amber-800 dark:text-amber-300">{t('title')}</p>
        <p className="text-sm text-amber-700/90 dark:text-amber-300/80">{t('body')}</p>
        <a
          href={CLOUD_PRICING_URL}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1.5 text-sm font-medium text-amber-800 dark:text-amber-300 underline underline-offset-2 hover:text-amber-900 dark:hover:text-amber-200"
        >
          {t('cta')}
          <ExternalLink className="h-3.5 w-3.5" />
        </a>
      </div>
    </div>
  );
}
