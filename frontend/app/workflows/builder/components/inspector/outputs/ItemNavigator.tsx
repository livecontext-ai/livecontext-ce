'use client';

/**
 * ItemNavigator - Navigation between multiple items (datasource rows, Split iterations)
 * Reusable component for both InputColumn and OutputColumn.
 * Supports direct index editing: click the number to type a specific item to jump to.
 *
 * Optional status filter: when {@code statusOptions} is non-empty the component
 * renders a small {@code ListFilter} icon button next to the chevrons that
 * opens a popover with the available statuses. Style mirrors the conversation
 * sidebar filter (rounded-xl popover, ghostGray icon button) so the inspector
 * filters feel consistent with the chat-list filters.
 */

import * as React from 'react';
import clsx from 'clsx';
import { ChevronLeft, ChevronRight, ListFilter } from 'lucide-react';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { useTranslations } from 'next-intl';
import { StatusBadge, type StatusType } from '@/components/ui/StatusBadge';

export const ALL_STATUSES_VALUE = '__all__';

interface ItemNavigatorProps {
  currentIndex: number;
  totalItems: number;
  onIndexChange: (index: number) => void;
  className?: string;
  /** Optional label for context (e.g., "Row", "Item", "Iteration") */
  itemLabel?: string;
  /**
   * Status filter options to render in a compact popover. Pass the canonical
   * StatusType values that exist in the current data set (so users only see
   * filters that would actually return rows).
   */
  statusOptions?: StatusType[];
  /** Currently selected status filter, or {@link ALL_STATUSES_VALUE} for no filter. */
  statusFilter?: string;
  onStatusFilterChange?: (next: string) => void;
}

export function ItemNavigator({
  currentIndex,
  totalItems,
  onIndexChange,
  className,
  itemLabel,
  statusOptions,
  statusFilter,
  onStatusFilterChange,
}: ItemNavigatorProps) {
  const t = useTranslations('dataTable');
  const [isEditing, setIsEditing] = React.useState(false);
  const [editValue, setEditValue] = React.useState('');
  const [isFilterOpen, setIsFilterOpen] = React.useState(false);
  const inputRef = React.useRef<HTMLInputElement>(null);

  // Only surface the filter when there are at least two distinct statuses to
  // pick from - a filter that can only ever match the single value present in
  // the data set would be pure noise.
  const showFilter = !!onStatusFilterChange && (statusOptions?.length ?? 0) >= 2;
  const hasMultipleItems = totalItems > 1;
  const isFilterActive = !!statusFilter && statusFilter !== ALL_STATUSES_VALUE;

  // Hide entirely when there is nothing to do - no items, no filter to surface.
  if (!hasMultipleItems && !showFilter) return null;

  const hasNext = currentIndex < totalItems - 1;
  const hasPrev = currentIndex > 0;

  const handleStartEdit = () => {
    setEditValue(String(currentIndex + 1));
    setIsEditing(true);
    setTimeout(() => inputRef.current?.select(), 0);
  };

  const handleConfirmEdit = () => {
    const parsed = parseInt(editValue, 10);
    if (!isNaN(parsed) && parsed >= 1 && parsed <= totalItems) {
      onIndexChange(parsed - 1);
    }
    setIsEditing(false);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleConfirmEdit();
    } else if (e.key === 'Escape') {
      setIsEditing(false);
    }
  };

  const handleSelectStatus = (next: string) => {
    onStatusFilterChange?.(next);
    setIsFilterOpen(false);
  };

  return (
    <div
      className={clsx(
        'flex items-center justify-center gap-1',
        className,
      )}
    >
      {hasMultipleItems && (
        <>
          <button
            onClick={() => hasPrev && onIndexChange(currentIndex - 1)}
            disabled={!hasPrev}
            className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
            aria-label="Previous"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
          </button>

          {isEditing ? (
            <input
              ref={inputRef}
              type="number"
              min={1}
              max={totalItems}
              value={editValue}
              onChange={(e) => setEditValue(e.target.value)}
              onBlur={handleConfirmEdit}
              onKeyDown={handleKeyDown}
              className="w-8 h-5 text-xs font-medium text-center bg-transparent border-b border-blue-500 outline-none text-[var(--text-primary)]"
            />
          ) : (
            <span
              onClick={handleStartEdit}
              className="text-xs text-[var(--text-secondary)] font-medium min-w-[50px] text-center cursor-text"
            >
              {itemLabel ? `${itemLabel} ` : ''}
              {currentIndex + 1} / {totalItems}
            </span>
          )}

          <button
            onClick={() => hasNext && onIndexChange(currentIndex + 1)}
            disabled={!hasNext}
            className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
            aria-label="Next"
          >
            <ChevronRight className="h-3.5 w-3.5" />
          </button>
        </>
      )}

      {showFilter && (
        <Popover open={isFilterOpen} onOpenChange={setIsFilterOpen}>
          <PopoverTrigger asChild>
            <button
              type="button"
              aria-label={t('allStatuses')}
              title={t('allStatuses')}
              className={clsx(
                'relative w-7 h-7 p-0 rounded-full inline-flex items-center justify-center transition-colors',
                isFilterActive
                  ? 'text-theme-primary bg-gray-100 dark:bg-gray-700'
                  : 'text-theme-muted hover:bg-gray-100 dark:hover:bg-gray-700 hover:text-theme-primary',
              )}
            >
              <ListFilter className="w-3.5 h-3.5 flex-shrink-0" />
              {isFilterActive && (
                <span
                  className="absolute top-0.5 right-0.5 h-1.5 w-1.5 rounded-full bg-blue-500"
                  aria-hidden
                />
              )}
            </button>
          </PopoverTrigger>
          <PopoverContent
            align="end"
            sideOffset={5}
            className="w-auto min-w-[140px] p-1.5 bg-theme-primary rounded-xl border border-gray-300/70 dark:border-gray-600/70 z-[99999]"
          >
            <div className="space-y-0.5">
              <button
                type="button"
                onClick={() => handleSelectStatus(ALL_STATUSES_VALUE)}
                className={clsx(
                  'w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs transition-colors',
                  !isFilterActive
                    ? 'bg-gray-100 dark:bg-gray-700 text-theme-primary'
                    : 'text-theme-muted hover:bg-gray-100 dark:hover:bg-gray-700 hover:text-theme-primary',
                )}
              >
                {t('allStatuses')}
              </button>
              {statusOptions!.map((s) => {
                const isSelected = statusFilter === s;
                return (
                  <button
                    key={s}
                    type="button"
                    onClick={() => handleSelectStatus(s)}
                    className={clsx(
                      'w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs transition-colors',
                      isSelected
                        ? 'bg-gray-100 dark:bg-gray-700 text-theme-primary'
                        : 'text-theme-muted hover:bg-gray-100 dark:hover:bg-gray-700 hover:text-theme-primary',
                    )}
                  >
                    <StatusBadge status={s} variant="noBackground" />
                  </button>
                );
              })}
            </div>
          </PopoverContent>
        </Popover>
      )}
    </div>
  );
}
