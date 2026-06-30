/**
 * Error and skip state banners for output display.
 */

'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { AlertCircleIcon, MinusCircleIcon } from 'lucide-react';

interface OutputErrorProps {
  error: string;
}

export function OutputError({ error }: OutputErrorProps) {
  const t = useTranslations('workflowBuilder.inspector');

  return (
    <div className="flex flex-col items-start p-4 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-800 rounded-lg">
      <div className="flex items-center gap-2 text-red-600 dark:text-red-400 mb-2">
        <AlertCircleIcon className="h-4 w-4" />
        <span className="text-sm font-medium">
          {t('output.error', { defaultValue: 'Execution Error' })}
        </span>
      </div>
      <p className="text-sm text-red-700 dark:text-red-300 break-all">
        {error}
      </p>
    </div>
  );
}

interface SkipBannerProps {
  reason: string;
  sourceNode?: string | null;
}

export function SkipBanner({ reason, sourceNode }: SkipBannerProps) {
  const t = useTranslations('workflowBuilder.inspector');

  return (
    <div className="flex flex-col items-start p-4 bg-amber-50 dark:bg-amber-950/30 border border-amber-200 dark:border-amber-800 rounded-lg">
      <div className="flex items-center gap-2 text-amber-600 dark:text-amber-400 mb-2">
        <MinusCircleIcon className="h-4 w-4" />
        <span className="text-sm font-medium">
          {t('output.skipped', { defaultValue: 'Skipped' })}
        </span>
      </div>
      <p className="text-sm text-amber-700 dark:text-amber-300 break-all">
        {reason}
      </p>
    </div>
  );
}

export default OutputError;
