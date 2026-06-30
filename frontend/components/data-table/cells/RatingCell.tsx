'use client';

import React from 'react';
import { Star } from 'lucide-react';
import type { VisualCellProps } from './types';

export function RatingCell({ value, rowKey, field, displayConfig, onSaveAndExit }: VisualCellProps) {
  const max = Number(displayConfig?.max) || 5;
  const ratingValue = typeof value === 'number' ? value : Number(value) || 0;

  return (
    <div className="flex items-center justify-center gap-1 text-amber-500" onClick={(e) => e.stopPropagation()}>
      {Array.from({ length: max }).map((_, index) => {
        const filled = index < ratingValue;
        return (
          <button
            type="button"
            key={`${rowKey}-${field}-star-${index}`}
            onClick={(e) => {
              e.stopPropagation();
              onSaveAndExit(index + 1);
            }}
            className="focus-visible:outline-none transition opacity-80 hover:opacity-100 hover:scale-110"
          >
            <Star
              className={`h-4 w-4 transition ${filled ? 'fill-current' : 'fill-transparent opacity-30'} group-hover/cell:opacity-100`}
            />
          </button>
        );
      })}
    </div>
  );
}

RatingCell.editable = false;
