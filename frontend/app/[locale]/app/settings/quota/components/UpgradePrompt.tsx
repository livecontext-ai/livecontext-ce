'use client';

import React from 'react';
import { TrendingUp } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { useRouter } from 'next/navigation';

export default function UpgradePrompt() {
  const t = useTranslations('quota');
  const router = useRouter();

  return (
    <div className="bg-theme-secondary rounded-xl p-6 border border-slate-200 dark:border-slate-700/50">
      <div className="flex flex-col items-center text-center py-6">
        <div className="w-12 h-12 bg-theme-tertiary rounded-full flex items-center justify-center mb-4">
          <TrendingUp className="w-6 h-6 text-theme-primary" />
        </div>
        <h3 className="text-lg font-semibold text-theme-primary mb-2">
          {t('upgrade.title')}
        </h3>
        <p className="text-sm text-theme-secondary max-w-md mb-6">
          {t('upgrade.description')}
        </p>
        <Button
          size="sm"
          className="h-8 px-4"
          onClick={() => router.push('/app/settings/pricing')}
        >
          {t('upgrade.cta')}
        </Button>
      </div>
    </div>
  );
}
