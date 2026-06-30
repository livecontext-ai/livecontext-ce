'use client';

import React, { useCallback, useRef, useState } from 'react';
import { Check, ChevronDown } from 'lucide-react';
import { getDisplayOptions } from '@/components/data-table/visualHelpers';
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover';
import type { VisualCellProps } from './types';

function parseTags(value: any): string[] {
  if (Array.isArray(value)) return value;
  if (typeof value === 'string' && value.length > 0) {
    if (value.startsWith('[')) {
      try { return JSON.parse(value); } catch { /* fallback */ }
    }
    return value.split(',').map((t: string) => t.trim()).filter(Boolean);
  }
  return [];
}

/**
 * Multi-select cell - same visual as SelectCell but with checkboxes for multiple picks.
 * Popover dropdown styled identically to Radix SelectContent.
 */
export function MultiSelectCell({ value, rowKey, field, displayConfig, onSaveAndExit }: VisualCellProps) {
  const options = getDisplayOptions(displayConfig);
  const tags = parseTags(value);
  const palette = (displayConfig?.palette as Record<string, string>) || {};

  const [selected, setSelected] = useState<Set<string>>(new Set(tags));
  const [open, setOpen] = useState(false);
  const lastSavedRef = useRef<string>(JSON.stringify(tags));

  // Sync from props only when value genuinely changed (not our own save echo)
  const propsKey = JSON.stringify(tags);
  if (!open && propsKey !== lastSavedRef.current) {
    lastSavedRef.current = propsKey;
    const newSet = new Set(tags);
    if (tags.length !== selected.size || tags.some(t => !selected.has(t))) {
      setSelected(newSet);
    }
  }

  const toggle = useCallback((val: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(val)) next.delete(val);
      else next.add(val);
      return next;
    });
  }, []);

  const handleOpenChange = useCallback((isOpen: boolean) => {
    if (!isOpen) {
      setSelected(current => {
        const arr = Array.from(current);
        const key = JSON.stringify(arr);
        lastSavedRef.current = key;
        onSaveAndExit(key);
        return current;
      });
    }
    setOpen(isOpen);
  }, [onSaveAndExit]);

  // Build color map
  const colorMap: Record<string, string> = {};
  options.forEach(opt => {
    if (opt.color) colorMap[opt.value] = opt.color;
  });

  // No options configured - show tags as static pills
  if (options.length === 0) {
    if (tags.length === 0) return <span className="text-xs text-theme-secondary">No tags</span>;
    return (
      <div className="flex flex-wrap items-center justify-center gap-1 text-xs">
        {tags.map(tag => (
          <span key={`${rowKey}-${field}-${tag}`} className="rounded-md bg-fuchsia-100 px-2 py-0.5 text-fuchsia-700 dark:bg-fuchsia-900/30 dark:text-fuchsia-300">
            {tag}
          </span>
        ))}
      </div>
    );
  }

  // Build trigger label - same style as SelectCell
  const selectedOptions = options.filter(o => selected.has(o.value));
  const firstSelected = selectedOptions[0];
  const firstColor = firstSelected?.color || palette[firstSelected?.value];

  return (
    <div onClick={(e) => e.stopPropagation()}>
      <Popover open={open} onOpenChange={handleOpenChange}>
        <PopoverTrigger asChild>
          <button
            type="button"
            className="flex min-h-7 w-full items-center justify-between rounded-lg bg-transparent px-2 py-1 text-xs shadow-none hover:bg-[var(--bg-secondary)] transition-colors"
          >
            <span className="flex flex-wrap items-center gap-1 min-w-0">
              {selected.size === 0 ? (
                <span className="text-theme-secondary">Select...</span>
              ) : (
                selectedOptions.map(opt => {
                  const optColor = opt.color || palette[opt.value];
                  return optColor ? (
                    <span key={opt.value} className="rounded-full px-2 py-0.5 text-[11px] font-semibold text-white" style={{ backgroundColor: optColor }}>
                      {opt.label}
                    </span>
                  ) : (
                    <span key={opt.value} className="rounded-full px-2 py-0.5 text-[11px] font-medium bg-slate-200 dark:bg-slate-700 text-[var(--text-primary)]">
                      {opt.label}
                    </span>
                  );
                })
              )}
            </span>
            <ChevronDown className="h-3.5 w-3.5 opacity-0 group-hover/cell:opacity-50 flex-shrink-0" />
          </button>
        </PopoverTrigger>

        {/* Dropdown - same visual as SelectContent */}
        <PopoverContent
          className="z-[10001] min-w-[8rem] overflow-hidden rounded-2xl border border-theme bg-[var(--bg-primary)] p-1 shadow-md"
          align="start"
          sideOffset={4}
        >
          <div className="max-h-60 overflow-y-auto">
            {options.map(option => {
              const isSelected = selected.has(option.value);
              const optColor = option.color || palette[option.value];
              return (
                <button
                  key={option.value}
                  type="button"
                  onClick={() => toggle(option.value)}
                  className="relative flex w-full cursor-pointer select-none items-center rounded-xl py-2 pl-8 pr-2 text-sm text-[var(--text-primary)] transition-colors duration-150 hover:bg-[var(--bg-tertiary)] hover:shadow-sm outline-none"
                >
                  {/* Checkbox indicator - same position as SelectItem check */}
                  <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
                    <div className={`flex h-4 w-4 items-center justify-center rounded border-2 transition-all ${
                      isSelected
                        ? 'bg-black border-black dark:bg-white dark:border-white'
                        : 'border-slate-300 dark:border-slate-600'
                    }`}>
                      {isSelected && <Check className="h-3 w-3 text-white dark:text-black" />}
                    </div>
                  </span>

                  <div className="flex items-center gap-2">
                    {optColor && (
                      <span className="h-3 w-3 rounded-full flex-shrink-0" style={{ backgroundColor: optColor }} />
                    )}
                    <span>{option.label}</span>
                  </div>
                </button>
              );
            })}
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
}

MultiSelectCell.editable = false;
