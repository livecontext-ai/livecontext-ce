'use client';

import React from 'react';
import { useTranslations } from 'next-intl';

export default function ShareLoading() {
  const t = useTranslations('publicShare');

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 dark:bg-slate-950">
      <div className="text-center space-y-4">
        <div className="w-8 h-8 border-2 border-slate-300 border-t-blue-500 rounded-full animate-spin mx-auto" />
        <p className="text-sm text-slate-500 dark:text-slate-400">{t('share.loading')}</p>
      </div>
    </div>
  );
}
