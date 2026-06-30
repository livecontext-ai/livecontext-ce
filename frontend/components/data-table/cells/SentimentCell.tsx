'use client';

import React from 'react';
import { ThumbsUp, ThumbsDown } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { VisualCellProps } from './types';

const sentiments: Array<{
  id: 'up' | 'down';
  icon: LucideIcon;
  activeClass: string;
  activeFill: string;
}> = [
  { id: 'up', icon: ThumbsUp, activeClass: 'bg-emerald-100 dark:bg-emerald-900/40 ring-2 ring-black/30 dark:ring-white/30', activeFill: 'text-black dark:text-white' },
  { id: 'down', icon: ThumbsDown, activeClass: 'bg-rose-100 dark:bg-rose-900/40 ring-2 ring-black/30 dark:ring-white/30', activeFill: 'text-black dark:text-white' },
];

export function SentimentCell({ value, rowKey, field, onSaveAndExit }: VisualCellProps) {
  const current = (typeof value === 'string' ? value : 'neutral') as 'up' | 'down' | 'neutral';

  return (
    <div className="flex items-center justify-center gap-3" onClick={(e) => e.stopPropagation()}>
      {sentiments.map(({ id, icon: Icon, activeClass, activeFill }) => {
        const active = current === id;
        return (
          <button
            key={`${rowKey}-${field}-${id}`}
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onSaveAndExit(active ? 'neutral' : id);
            }}
            className={`flex h-8 w-8 items-center justify-center rounded-full transition-all ${
              active
                ? activeClass
                : 'bg-slate-100 dark:bg-slate-800 opacity-40 hover:opacity-100 group-hover/cell:opacity-70'
            }`}
          >
            <Icon className={`h-4.5 w-4.5 ${active ? activeFill : 'text-slate-400 dark:text-slate-500'}`} />
          </button>
        );
      })}
    </div>
  );
}

SentimentCell.editable = false;
