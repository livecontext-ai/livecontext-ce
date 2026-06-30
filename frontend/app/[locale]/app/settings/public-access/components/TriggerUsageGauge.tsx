'use client';

import React from 'react';
import { useTranslations } from 'next-intl';

interface TriggerUsageGaugeProps {
  currentCount: number;
  maxPerUser: number;
}

export function TriggerUsageGauge({ currentCount, maxPerUser }: TriggerUsageGaugeProps) {
  const t = useTranslations('triggerSettings');
  const percentage = maxPerUser > 0 ? Math.min(100, (currentCount / maxPerUser) * 100) : 0;

  return (
    <div className="space-y-1.5">
      <p className="text-sm text-theme-secondary">
        {t('usage', { count: currentCount, max: maxPerUser })}
      </p>
      <div className="w-full h-2 bg-slate-200 dark:bg-slate-700 rounded-full">
        <div
          className="h-full bg-black dark:bg-white rounded-full transition-all"
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  );
}
