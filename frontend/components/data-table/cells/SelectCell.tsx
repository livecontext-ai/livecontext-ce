'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { getDisplayOptions } from '@/components/data-table/visualHelpers';
import {
  Select,
  SelectTrigger,
  SelectContent,
  SelectItem,
  SelectValue,
} from '@/components/ui/select';
import type { VisualCellProps } from './types';

/**
 * Unified select cell - uses Radix UI Select dropdown.
 * Handles both old 'select' and migrated 'badge' columns.
 * Supports per-option colors via options[].color or legacy palette.
 */
export function SelectCell({ value, rowKey, field, displayConfig, onSaveAndExit }: VisualCellProps) {
  const t = useTranslations('dataTable');
  const options = getDisplayOptions(displayConfig);
  const palette = (displayConfig?.palette as Record<string, string>) || {};

  // Legacy badge with palette but no structured options
  if (options.length === 0 && Object.keys(palette).length > 0) {
    const color = palette[value as string] || '#0ea5e9';
    return (
      <span className="rounded-full px-3 py-1 text-xs font-semibold text-white" style={{ backgroundColor: color }}>
        {value || '\u2014'}
      </span>
    );
  }

  if (options.length === 0) {
    return <span className="text-xs text-theme-secondary">{t('noOptions')}</span>;
  }

  const currentOption = options.find(o => o.value === value);
  const currentColor = currentOption?.color || palette[value as string];

  return (
    <div onClick={(e) => e.stopPropagation()}>
      <Select value={value || ''} onValueChange={(v) => onSaveAndExit(v)}>
        <SelectTrigger className="h-7 min-h-0 w-full rounded-lg border-0 bg-transparent px-2 py-0.5 text-xs shadow-none focus:ring-0 focus:ring-offset-0 hover:bg-[var(--bg-secondary)] [&>svg]:opacity-0 [&:hover>svg]:opacity-50">
          {currentColor ? (
            <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold text-white" style={{ backgroundColor: currentColor }}>
              {currentOption?.label || value || '\u2014'}
            </span>
          ) : (
            <SelectValue placeholder={t('selectPlaceholder')} />
          )}
        </SelectTrigger>
        <SelectContent>
          {options.map(option => {
            const optColor = option.color || palette[option.value];
            return (
              <SelectItem key={`${rowKey}-${field}-${option.value}`} value={option.value}>
                <div className="flex items-center gap-2">
                  {optColor && (
                    <span className="h-3 w-3 rounded-full flex-shrink-0" style={{ backgroundColor: optColor }} />
                  )}
                  <span>{option.label}</span>
                </div>
              </SelectItem>
            );
          })}
        </SelectContent>
      </Select>
    </div>
  );
}

SelectCell.editable = false;
