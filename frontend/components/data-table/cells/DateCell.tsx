'use client';

import React from 'react';
import { Calendar } from 'lucide-react';
import type { VisualCellProps } from './types';
import { formatUtcDate, formatUtcDateTime, formatUtcTime, parseUtcAware } from '@/lib/utils/dateFormatters';

export function DateCell({ value, displayConfig, isEditing, onSaveAndExit }: VisualCellProps) {
  const dateFormat = (displayConfig?.dateFormat as string) || 'date';

  if (isEditing) {
    const inputType = dateFormat === 'time' ? 'time' : dateFormat === 'datetime' ? 'datetime-local' : 'date';
    // Convert ISO string to input-friendly format
    let inputValue = '';
    if (value) {
      try {
        const d = parseUtcAware(value as string);
        if (!isNaN(d.getTime())) {
          if (inputType === 'date') inputValue = d.toISOString().split('T')[0];
          else if (inputType === 'time') inputValue = d.toISOString().split('T')[1]?.substring(0, 5) || '';
          else inputValue = d.toISOString().substring(0, 16);
        }
      } catch {
        inputValue = typeof value === 'string' ? value : '';
      }
    }

    return (
      <input
        type={inputType}
        defaultValue={inputValue}
        autoFocus
        className="w-full rounded-md border border-theme bg-theme-primary px-2 py-1 text-sm text-theme-primary"
        onChange={(event) => onSaveAndExit(event.currentTarget.value)}
        onClick={(e) => e.stopPropagation()}
      />
    );
  }

  if (!value) {
    return <span className="text-xs text-theme-secondary">No date</span>;
  }

  let formatted = String(value);
  try {
    const d = parseUtcAware(value as string);
    if (!isNaN(d.getTime())) {
      // All table cells display in UTC to mirror server-side storage.
      // Edit widgets (input type=date / time / datetime-local) still use
      // browser-local TZ - accepted limitation of the HTML controls.
      if (dateFormat === 'time') {
        formatted = formatUtcTime(d);
      } else if (dateFormat === 'datetime') {
        formatted = formatUtcDateTime(d);
      } else {
        formatted = formatUtcDate(d);
      }
    }
  } catch {
    // keep raw string
  }

  return (
    <div className="flex items-center justify-center gap-1.5 text-sm text-theme-primary">
      <Calendar className="h-3.5 w-3.5 text-theme-secondary" />
      <span>{formatted}</span>
    </div>
  );
}
