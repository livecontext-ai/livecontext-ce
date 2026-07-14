'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { getClientLocale } from '@/lib/utils/locale';
import type { VisualCellProps } from './types';

export function NumberCell({ value, displayConfig, isEditing, onSaveAndExit }: VisualCellProps) {
  const t = useTranslations('dataTable');
  const format = (displayConfig?.format as string) || 'plain';
  const decimals = Number(displayConfig?.decimals ?? 0);
  const currencySymbol = (displayConfig?.currencySymbol as string) || '$';

  if (isEditing) {
    return (
      <input
        type="number"
        defaultValue={typeof value === 'number' ? value : Number(value) || 0}
        autoFocus
        step={decimals > 0 ? Math.pow(10, -decimals) : 1}
        className="w-full rounded-md border border-theme bg-theme-primary px-2 py-1 text-sm text-theme-primary text-center"
        onBlur={(event) => onSaveAndExit(event.currentTarget.value)}
        onClick={(e) => e.stopPropagation()}
      />
    );
  }

  const num = typeof value === 'number' ? value : Number(value);
  if (value === null || value === undefined || value === '' || isNaN(num)) {
    return <span className="text-xs text-theme-secondary">{t('noData')}</span>;
  }

  let formatted: string;
  const locale = getClientLocale();

  switch (format) {
    case 'currency':
      formatted = `${currencySymbol}${num.toLocaleString(locale, { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}`;
      break;
    case 'percentage':
      formatted = `${num.toLocaleString(locale, { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}%`;
      break;
    default:
      formatted = num.toLocaleString(locale, { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
  }

  return (
    <span className="text-sm font-medium text-theme-primary tabular-nums">{formatted}</span>
  );
}
