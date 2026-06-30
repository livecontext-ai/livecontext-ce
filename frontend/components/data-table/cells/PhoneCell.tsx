'use client';

import React from 'react';
import { Phone } from 'lucide-react';
import type { VisualCellProps } from './types';

export function PhoneCell({ value, isEditing, onSaveAndExit }: VisualCellProps) {
  if (isEditing) {
    return (
      <input
        type="tel"
        defaultValue={typeof value === 'string' ? value : ''}
        autoFocus
        className="w-full rounded-md border border-theme bg-theme-primary px-2 py-1 text-sm text-theme-primary"
        placeholder="+1 (555) 123-4567"
        onBlur={(event) => onSaveAndExit(event.currentTarget.value)}
        onClick={(e) => e.stopPropagation()}
      />
    );
  }

  const phone = typeof value === 'string' ? value : '';
  if (!phone) {
    return <span className="text-xs text-theme-secondary">No phone</span>;
  }

  return (
    <div className="flex items-center justify-center gap-1.5 text-sm">
      <Phone className="h-3.5 w-3.5 text-theme-secondary" />
      <a href={`tel:${phone}`} className="text-theme-primary underline-offset-2 hover:underline" onClick={(e) => e.stopPropagation()}>
        {phone}
      </a>
    </div>
  );
}
