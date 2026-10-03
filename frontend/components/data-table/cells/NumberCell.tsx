'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { getClientLocale } from '@/lib/utils/locale';
import type { VisualCellProps } from './types';

/**
 * `Intl` THROWS on a fraction-digit count that is negative, above 100 or not a number, and a throw
 * while rendering one cell takes the whole table down. The count comes from the column config,
 * which an agent or a typed value can set to anything.
 *
 * Two bounds on purpose. RENDERING honours anything up to 20 digits, so a column deliberately
 * configured with 8 decimals keeps showing 8. The CONFIG INPUTS offer 0 to 6, which is what their
 * own `max` hint always claimed without enforcing.
 */
export const NUMBER_RENDER_MAX_DECIMALS = 20;
export const NUMBER_CONFIG_MAX_DECIMALS = 6;
export function numberDecimals(configured: unknown, cap: number = NUMBER_RENDER_MAX_DECIMALS): number {
  const n = Math.trunc(Number(configured ?? 0));
  if (!Number.isFinite(n) || n < 0) return 0;
  return Math.min(n, cap);
}

export function NumberCell({ value, displayConfig, isEditing, onSaveAndExit }: VisualCellProps) {
  const t = useTranslations('dataTable');
  const format = (displayConfig?.format as string) || 'plain';
  const decimals = numberDecimals(displayConfig?.decimals);
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
