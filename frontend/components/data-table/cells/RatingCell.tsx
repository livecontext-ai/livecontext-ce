'use client';

import React from 'react';
import { Star } from 'lucide-react';
import type { VisualCellProps } from './types';

/**
 * Stars are drawn one by one, so the count has to be a small whole number whatever the config
 * says. The config slider stops at 10 and the server sets no ceiling of its own, so this cap only
 * bites on a max written from outside the UI: such a column draws 20 stars, all filled past 20.
 */
export const RATING_MAX_STARS = 20;
export function ratingStarCount(configured: unknown): number {
  const n = Math.trunc(Number(configured));
  if (!Number.isFinite(n) || n < 1) return 5;
  return Math.min(n, RATING_MAX_STARS);
}

export function RatingCell({ value, rowKey, field, displayConfig, onSaveAndExit, readOnly }: VisualCellProps) {
  const max = ratingStarCount(displayConfig?.max);
  const ratingValue = typeof value === 'number' ? value : Number(value) || 0;

  return (
    <div className="flex items-center justify-center gap-1 text-amber-500" onClick={(e) => e.stopPropagation()}>
      {Array.from({ length: max }).map((_, index) => {
        const filled = index < ratingValue;
        return (
          <button
            type="button"
            disabled={readOnly}
            key={`${rowKey}-${field}-star-${index}`}
            onClick={(e) => {
              e.stopPropagation();
              onSaveAndExit(index + 1);
            }}
            className="focus-visible:outline-none transition opacity-80 enabled:hover:opacity-100 enabled:hover:scale-110 disabled:cursor-default!"
          >
            <Star
              className={`h-4 w-4 transition ${filled ? 'fill-current' : 'fill-transparent opacity-30'} ${readOnly ? '' : 'group-hover/cell:opacity-100'}`}
            />
          </button>
        );
      })}
    </div>
  );
}

RatingCell.editable = false;
