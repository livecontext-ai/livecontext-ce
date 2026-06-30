'use client';

import React from 'react';
import { Mail } from 'lucide-react';
import type { VisualCellProps } from './types';

export function EmailCell({ value, isEditing, onSaveAndExit }: VisualCellProps) {
  if (isEditing) {
    return (
      <input
        type="email"
        defaultValue={typeof value === 'string' ? value : ''}
        autoFocus
        className="w-full rounded-md border border-theme bg-theme-primary px-2 py-1 text-sm text-theme-primary"
        placeholder="user@example.com"
        onBlur={(event) => onSaveAndExit(event.currentTarget.value)}
        onClick={(e) => e.stopPropagation()}
      />
    );
  }

  const email = typeof value === 'string' ? value : '';
  if (!email) {
    return <span className="text-xs text-theme-secondary">No email</span>;
  }

  return (
    <div className="flex items-center justify-center gap-1.5 text-sm">
      <Mail className="h-3.5 w-3.5 text-theme-secondary" />
      <a href={`mailto:${email}`} className="text-theme-primary underline-offset-2 hover:underline" onClick={(e) => e.stopPropagation()}>
        {email}
      </a>
    </div>
  );
}
