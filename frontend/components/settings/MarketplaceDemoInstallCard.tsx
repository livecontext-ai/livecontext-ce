'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { MonitorPlay } from 'lucide-react';
import { Switch } from '@/components/ui/switch';
import {
  setMarketplaceDemoInstall,
  useMarketplaceDemoInstallFlag,
} from '@/lib/marketplace/demoInstallMode';

/**
 * Admin control for marketplace demo-install mode.
 *
 * Lives on an already admin-gated settings page, so it uses the RAW flag hook:
 * the toggle must reflect what this browser has stored even for the split second
 * before roles resolve. Every consumer that changes marketplace behaviour uses
 * the admin-checked hook instead.
 */
export function MarketplaceDemoInstallCard() {
  const t = useTranslations('settings.marketplaceHighlights.demoInstall');
  const enabled = useMarketplaceDemoInstallFlag();

  return (
    <div className="rounded-lg border border-theme bg-theme-secondary p-4">
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-start gap-3 min-w-0">
          <MonitorPlay className="h-3.5 w-3.5 mt-0.5 shrink-0 text-theme-secondary" />
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-theme-primary">{t('title')}</h2>
            <p className="mt-1 text-sm text-theme-secondary">{t('description')}</p>
            <p className="mt-1 text-sm text-theme-tertiary">{t('scope')}</p>
          </div>
        </div>
        <Switch
          checked={enabled}
          onCheckedChange={setMarketplaceDemoInstall}
          aria-label={t('title')}
          // Switch exposes `testId`, not `data-testid`: a hyphenated JSX
          // attribute is not type-checked, so the latter is dropped in silence.
          testId="marketplace-demo-install-toggle"
        />
      </div>
      {enabled && (
        <p
          role="status"
          className="mt-3 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/20 dark:text-amber-300"
        >
          {t('activeNotice')}
        </p>
      )}
    </div>
  );
}

export default MarketplaceDemoInstallCard;
