'use client';

import React from 'react';
import type { VisualCellProps } from './types';

export function CheckboxCell({ value, onSaveAndExit }: VisualCellProps) {
  const checked = value === true || value === 'true' || value === '1' || value === 'yes';

  return (
    <div className="flex items-center justify-center" onClick={(e) => e.stopPropagation()}>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onSaveAndExit(!checked);
        }}
        className={`h-5 w-5 rounded-md border-2 transition-all flex items-center justify-center ${
          checked
            ? 'bg-black border-black text-white dark:bg-white dark:border-white dark:text-black'
            : 'border-slate-300 dark:border-slate-600 bg-transparent hover:border-slate-500 dark:hover:border-slate-400'
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
