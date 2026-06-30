'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronLeft, ChevronRight, Maximize2, Minimize2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import clsx from 'clsx';

interface InterfaceToolbarProps {
  currentPage: number;
  totalPages: number;
  onPrevious: () => void;
  onNext: () => void;
  /**
   * Semantic replacement for the bare "{page+1} / {totalPages}" counter
   * (e.g. "Item 2/3" in run mode). Falls back to the bare counter when absent.
   */
  pageLabel?: string;
  /**
   * Small contextual badge appended to the pagination group (e.g.
   * "Re-execution 2" when the displayed item is a spawn > 0 re-run).
   * Rendered even without pagination so single-page re-runs stay visible.
   */
  pageBadge?: string;
  onGoToPage?: (page: number) => void;
  onFullscreen?: () => void;
  isFullscreen?: boolean;
  /** Controls rendered before the pagination group (e.g. carousel dots) */
  leadingControls?: React.ReactNode;
  extraControls?: React.ReactNode;
  /** Close/dismiss button at the end of the toolbar */
  onClose?: () => void;
  variant?: 'light' | 'dark';
  className?: string;
}

/**
 * Shared pagination + fullscreen toolbar.
 * `variant="light"` mirrors the CanvasToolbar pill style (side panel).
 * `variant="dark"` is used in the fullscreen modal overlay.
 */
export function InterfaceToolbar({
  currentPage,
  totalPages,
  onPrevious,
  onNext,
  pageLabel,
  pageBadge,
  onGoToPage,
  onFullscreen,
  isFullscreen = false,
  leadingControls,
  extraControls,
  onClose,
  variant = 'light',
  className,
}: InterfaceToolbarProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const hasPagination = totalPages > 1;
  const hasBadge = !!pageBadge;
  const hasFullscreen = !!onFullscreen;
  const hasExtra = !!extraControls;
  const hasLeading = !!leadingControls;
  const hasClose = !!onClose;
  const [isEditing, setIsEditing] = React.useState(false);
  const [editValue, setEditValue] = React.useState('');
  const inputRef = React.useRef<HTMLInputElement>(null);

  const startEditing = React.useCallback(() => {
    if (!onGoToPage) return;
    setEditValue(String(currentPage + 1));
    setIsEditing(true);
  }, [onGoToPage, currentPage]);

  const commitEdit = React.useCallback(() => {
    setIsEditing(false);
    if (!onGoToPage) return;
    const parsed = parseInt(editValue, 10);
    if (!isNaN(parsed) && parsed >= 1 && parsed <= totalPages) {
      onGoToPage(parsed - 1);
    }
  }, [editValue, totalPages, onGoToPage]);

  const handleEditKeyDown = React.useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      commitEdit();
    } else if (e.key === 'Escape') {
      setIsEditing(false);
    }
  }, [commitEdit]);

  React.useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  // Nothing to show
  if (!hasPagination && !hasBadge && !hasFullscreen && !hasExtra && !hasLeading && !hasClose) return null;

  // Small contextual badge (e.g. "Re-execution 2") shared by both variants.
  const badgeNode = hasBadge ? (
    <span
      data-testid="interface-pagination-rerun-badge"
      className="text-[10px] px-1.5 py-0.5 rounded-full bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 font-medium whitespace-nowrap"
    >
      {pageBadge}
    </span>
  ) : null;

  const isLight = variant === 'light';

  // ── Light variant - same style as CanvasToolbar ──
  if (isLight) {
    return (
      <div
        data-testid="interface-toolbar"
        className={clsx(
          'flex items-center gap-1 rounded-full bg-white/95 dark:bg-gray-800/95 px-3 py-2 backdrop-blur border-0',
          className,
        )}
      >
        {/* Leading controls (e.g. carousel dots) */}
        {hasLeading && (
          <div
            className={clsx(
              'flex items-center gap-1',
              (hasPagination || hasFullscreen || hasExtra) && 'border-r border-slate-200 dark:border-slate-700 pr-1',
            )}
          >
            {leadingControls}
          </div>
        )}

        {/* Pagination group (+ contextual re-run badge) */}
        {(hasPagination || hasBadge) && (
          <div
            className={clsx(
              'flex items-center gap-1 relative',
              (hasFullscreen || hasExtra) && 'border-r border-slate-200 dark:border-slate-700 pr-1',
            )}
          >
            {hasPagination && (
              <>
                <button
                  onClick={onPrevious}
                  disabled={currentPage === 0}
                  className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
                  aria-label={t('previous')}
                >
                  <ChevronLeft className="h-3.5 w-3.5" />
                </button>
                {isEditing ? (
                  <input
                    ref={inputRef}
                    type="text"
                    inputMode="numeric"
                    value={editValue}
                    onChange={(e) => setEditValue(e.target.value)}
                    onBlur={commitEdit}
                    onKeyDown={handleEditKeyDown}
                    className="w-8 h-5 text-xs font-medium text-center bg-transparent border-b border-blue-500 outline-none text-[var(--text-primary)]"
                  />
                ) : (
                  <span
                    className={clsx(
                      'text-xs text-[var(--text-secondary)] font-medium min-w-[50px] text-center',
                      onGoToPage && 'cursor-text',
                    )}
                    onClick={startEditing}
                    data-testid="interface-pagination-label"
                  >
                    {pageLabel ?? `${currentPage + 1} / ${totalPages}`}
                  </span>
                )}
                <button
                  onClick={onNext}
                  disabled={currentPage >= totalPages - 1}
                  className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
                  aria-label={t('next')}
                >
                  <ChevronRight className="h-3.5 w-3.5" />
                </button>
              </>
            )}
            {badgeNode}
          </div>
        )}

        {/* Fullscreen group */}
        {hasFullscreen && (
          <div
            className={clsx(
              'flex items-center gap-1',
              (hasExtra || hasClose) && 'border-r border-slate-200 dark:border-slate-700 pr-1',
            )}
          >
            <Button
              onClick={onFullscreen}
              variant="ghost"
              size="sm"
              className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
              title={isFullscreen ? t('exitFullscreen') : t('fullscreen')}
            >
              {isFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
            </Button>
          </div>
        )}

        {/* Extra controls */}
        {hasExtra && (
          <div className={clsx('flex items-center gap-1', hasClose && 'border-r border-slate-200 dark:border-slate-700 pr-1')}>
            {extraControls}
          </div>
        )}

        {/* Close button */}
        {hasClose && (
          <button
            type="button"
            onClick={onClose}
            className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    );
  }

  // ── Dark variant - fullscreen modal overlay ──
  return (
    <div
      data-testid="interface-toolbar"
      className={clsx(
        'flex items-center gap-2 bg-slate-900/80 text-white rounded-full px-3 py-2 shadow-lg',
        className,
      )}
    >
      {/* Leading controls (e.g. carousel dots) */}
      {hasLeading && (
        <>
          <div className="flex items-center gap-1">
            {leadingControls}
          </div>
          {(hasPagination || hasFullscreen || hasExtra) && <div className="w-px h-5 bg-white/30 mx-1" />}
        </>
      )}

      {/* Pagination group (+ contextual re-run badge) */}
      {(hasPagination || hasBadge) && (
        <div className="flex items-center gap-1">
          {hasPagination && (
            <>
              <button
                type="button"
                onClick={onPrevious}
                disabled={currentPage === 0}
                className={clsx(
                  'p-1.5 rounded-full hover:bg-white/20 transition-colors',
                  currentPage === 0 && 'opacity-30 cursor-not-allowed',
                )}
                title={t('previousArrow')}
              >
                <ChevronLeft className="w-5 h-5" />
              </button>
              <span className="flex items-center gap-1 text-sm font-medium min-w-[70px] justify-center select-none tabular-nums">
                {isEditing ? (
                  <input
                    ref={inputRef}
                    type="text"
                    inputMode="numeric"
                    value={editValue}
                    onChange={(e) => setEditValue(e.target.value)}
                    onBlur={commitEdit}
                    onKeyDown={handleEditKeyDown}
                    className="w-8 h-5 text-sm font-medium text-center tabular-nums bg-transparent border-b border-blue-400 outline-none text-white"
                  />
                ) : (
                  <span
                    className={clsx(
                      'tabular-nums',
                      onGoToPage && 'cursor-text hover:border-b hover:border-white/60',
                    )}
                    onClick={startEditing}
                    data-testid="interface-pagination-label"
                  >
                    {pageLabel ?? currentPage + 1}
                  </span>
                )}
                {(isEditing || pageLabel == null) && <span>/ {totalPages}</span>}
              </span>
              <button
                type="button"
                onClick={onNext}
                disabled={currentPage >= totalPages - 1}
                className={clsx(
                  'p-1.5 rounded-full hover:bg-white/20 transition-colors',
                  currentPage >= totalPages - 1 && 'opacity-30 cursor-not-allowed',
                )}
                title={t('nextArrow')}
              >
                <ChevronRight className="w-5 h-5" />
              </button>
            </>
          )}
          {badgeNode}
        </div>
      )}

      {/* Separator + Fullscreen */}
      {hasFullscreen && (
        <>
          {hasPagination && <div className="w-px h-5 bg-white/30 mx-1" />}
          <button
            type="button"
            onClick={onFullscreen}
            className="p-1.5 rounded-full hover:bg-white/20 transition-colors"
            title={isFullscreen ? t('exitFullscreen') : t('fullscreen')}
          >
            {isFullscreen ? <Minimize2 className="w-5 h-5" /> : <Maximize2 className="w-5 h-5" />}
          </button>
        </>
      )}

      {/* Separator + Extra controls */}
      {hasExtra && (
        <>
          {(hasPagination || hasFullscreen) && <div className="w-px h-5 bg-white/30 mx-1" />}
          <div className="flex items-center gap-1">
            {extraControls}
          </div>
        </>
      )}
    </div>
  );
}
