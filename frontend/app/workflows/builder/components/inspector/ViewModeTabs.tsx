'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Settings, FileText, Play } from 'lucide-react';

export type ViewMode = 'configuration' | 'result';

interface ViewModeTabsProps {
  viewMode: ViewMode;
  onViewModeChange: (mode: ViewMode) => void;
  /** 'compact' = icon-only (narrow panels / mobile); anything else = icon + label. */
  variant?: 'header' | 'mobile' | 'basic' | 'compact';
  /** Whether the node has run data to show ("Run data" vs the config form). */
  showExecutionData?: boolean;
  onShowExecutionDataChange?: (show: boolean) => void;
  /** Whether a "Run data" segment is available (node produced output). */
  canShowExecutionDataToggle?: boolean;
}

type SegmentId = 'edit' | 'data' | 'logs';

/**
 * The inspector view switcher: one clean, labeled segmented control that makes
 * the three node views explicit instead of a 2-tab pill plus a cryptic icon.
 *
 *  - Edit     -> the node's configuration form (viewMode=configuration, data off)
 *  - Run data -> the node's execution output (viewMode=configuration, data on) -
 *                only shown when the node actually produced run data
 *  - Logs     -> the run/result view (viewMode=result)
 *
 * Square-rounded, theme-tokened, and responsive: `compact` renders icon-only for
 * narrow panels, otherwise each segment shows its icon and label.
 */
export function ViewModeTabs({
  viewMode,
  onViewModeChange,
  variant = 'mobile',
  showExecutionData,
  onShowExecutionDataChange,
  canShowExecutionDataToggle = false,
}: ViewModeTabsProps) {
  const iconOnly = variant === 'compact';

  const active: SegmentId =
    viewMode === 'result' ? 'logs' : showExecutionData ? 'data' : 'edit';

  const select = (id: SegmentId) => {
    if (id === 'logs') {
      onViewModeChange('result');
      return;
    }
    // Edit / Run data both live in the configuration view; the execution-data
    // toggle picks which one is shown.
    onViewModeChange('configuration');
    onShowExecutionDataChange?.(id === 'data');
  };

  const segments: Array<{ id: SegmentId; label: string; icon: React.ComponentType<{ className?: string }> }> = [
    { id: 'edit', label: 'Edit', icon: Settings },
    ...(canShowExecutionDataToggle
      ? [{ id: 'data' as const, label: 'Run data', icon: Play }]
      : []),
    { id: 'logs', label: 'Logs', icon: FileText },
  ];

  return (
    <div className="inline-flex flex-shrink-0 items-center gap-0.5 rounded-lg bg-theme-tertiary p-1">
      {segments.map(({ id, label, icon: Icon }) => {
        const isActive = active === id;
        return (
          <button
            key={id}
            type="button"
            data-tab-id={id}
            onClick={() => select(id)}
            title={label}
            aria-pressed={isActive}
            className={clsx(
              'relative flex items-center justify-center gap-1.5 rounded-md text-sm font-medium transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60',
              iconOnly ? 'h-8 w-8' : 'px-2.5 py-1',
              isActive
                ? 'bg-[var(--bg-primary)] text-theme-primary shadow-sm'
                : 'text-theme-secondary hover:text-theme-primary',
            )}
          >
            <Icon className="h-4 w-4" />
            {!iconOnly && <span className="whitespace-nowrap">{label}</span>}
          </button>
        );
      })}
    </div>
  );
}
