'use client';

import React from 'react';
import type { VisualCellProps } from './types';

// `disabled:cursor-default!` with the `!`: globals.css sets `button:disabled { cursor: not-allowed }`
// outside any layer, which beats a plain utility. A read-only table is not a forbidden one.
export function CheckboxCell({ value, onSaveAndExit, readOnly }: VisualCellProps) {
  const checked = value === true || value === 'true' || value === '1' || value === 'yes';

  return (
    <div className="flex items-center justify-center" onClick={(e) => e.stopPropagation()}>
      <button
        type="button"
        disabled={readOnly}
        onClick={(e) => {
          e.stopPropagation();
          onSaveAndExit(!checked);
        }}
        className={`h-5 w-5 rounded-md border-2 transition-all flex items-center justify-center disabled:cursor-default! ${
          checked
            ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)] text-[var(--accent-foreground)]'
            : 'border-slate-300 dark:border-slate-600 bg-transparent enabled:hover:border-slate-500 dark:enabled:hover:border-slate-400'
        }`}
      >
        {checked && (
          <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
          </svg>
        )}
      </button>
    </div>
  );
}

CheckboxCell.editable = false;
