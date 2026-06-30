'use client';

import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

export interface PaginationBarProps {
  /** Zero-based page index. */
  page: number;
  /** Page size currently in use. */
  pageSize: number;
  /** Total items across all pages. */
  totalCount: number;
  /** Number of items rendered on the current page (used for the "1-25 of 88" range). */
  visibleCount: number;
  loading?: boolean;
  onPageChange: (next: number) => void;
  onPageSizeChange: (next: number) => void;
  pageSizeOptions?: number[];
  /**
   * When true the bar uses `sticky bottom-0` to dock at the bottom of the nearest
   * scrolling ancestor. Pass `false` if the parent already pins the bar via flex
   * layout (e.g. inside a flex column with the bar as the last `flex-shrink-0` child).
   */
  sticky?: boolean;
  className?: string;
}

const DEFAULT_PAGE_SIZE_OPTIONS = [10, 25, 50, 100];

/**
 * Bottom-of-page pagination strip with prev / next, current-page indicator, range info
 * and a per-page selector. Always renders so the user can change page size even with
 * a single page; the prev/next buttons disable when out of range.
 */
export function PaginationBar({
  page,
  pageSize,
  totalCount,
  visibleCount,
  loading = false,
  onPageChange,
  onPageSizeChange,
  pageSizeOptions = DEFAULT_PAGE_SIZE_OPTIONS,
  sticky = true,
  className = '',
}: PaginationBarProps) {
  const t = useTranslations('common.pagination');

  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));
  const safePage = Math.min(Math.max(page, 0), totalPages - 1);
  const from = totalCount === 0 ? 0 : safePage * pageSize + 1;
  const to = Math.min(from + Math.max(visibleCount, 0) - 1, totalCount);

  const stickyClasses = sticky
    ? 'sticky bottom-0 z-10 bg-[var(--bg-primary)]/95 backdrop-blur-sm border-t border-theme'
    : 'border-t border-theme';

  return (
    <div className={`${stickyClasses} mt-4 px-4 py-3 rounded-b-md ${className}`}>
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <span className="text-xs text-theme-muted tabular-nums">
          {t('rangeInfo', { from, to: to < from ? from : to, total: totalCount })}
        </span>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 text-xs text-theme-muted">
            <span>{t('pageSize')}</span>
            <Select
              value={String(pageSize)}
              onValueChange={(v) => onPageSizeChange(Number(v))}
            >
              <SelectTrigger className="min-h-0 h-7 w-auto min-w-[64px] rounded-md px-2 py-1 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {pageSizeOptions.map((n) => (
                  <SelectItem key={n} value={String(n)}>
                    {n}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center gap-1">
            <Button
              variant="outline"
              size="sm"
              onClick={() => onPageChange(Math.max(0, safePage - 1))}
              disabled={safePage === 0 || loading}
            >
              <ChevronLeft className="h-3.5 w-3.5 mr-1" />
              {t('prev')}
            </Button>
            <span className="text-xs text-theme-muted tabular-nums px-2">
              {t('pageInfo', { current: safePage + 1, total: totalPages })}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => onPageChange(Math.min(totalPages - 1, safePage + 1))}
              disabled={safePage >= totalPages - 1 || loading}
            >
              {t('next')}
              <ChevronRight className="h-3.5 w-3.5 ml-1" />
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
