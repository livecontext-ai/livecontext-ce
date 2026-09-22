'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Settings, Play } from 'lucide-react';
import { useTranslations } from 'next-intl';

interface ViewModeTabsProps {
  /** 'compact' = icon-only (narrow panels / mobile); anything else = icon + label. */
  variant?: 'header' | 'mobile' | 'basic' | 'compact';
  /** Whether the node has run data to show ("Run data" vs the config form). */
  showExecutionData?: boolean;
  onShowExecutionDataChange?: (show: boolean) => void;
  /** Whether a "Run data" segment is available (node produced output). */
  canShowExecutionDataToggle?: boolean;
}

type SegmentId = 'edit' | 'data';

/**
 * The inspector view switcher: one clean, labeled segmented control that makes
 * the configuration and run-data views explicit.
 *
 *  - Edit     -> the node's configuration form
 *  - Run data -> the node's execution output
 *                only shown when the node actually produced run data
 * Square-rounded, theme-tokened, and responsive: `compact` renders icon-only for
 * narrow panels, otherwise each segment shows its icon and label.
 */
export function ViewModeTabs({
  variant = 'mobile',
  showExecutionData,
  onShowExecutionDataChange,
  canShowExecutionDataToggle = false,
}: ViewModeTabsProps) {
  const t = useTranslations('workflowBuilder.inspector');
  const iconOnly = variant === 'compact';

  const active: SegmentId = showExecutionData ? 'data' : 'edit';

  const select = (id: SegmentId) => {
    onShowExecutionDataChange?.(id === 'data');
  };

  const segments: Array<{ id: SegmentId; label: string; icon: React.ComponentType<{ className?: string }> }> = [
    { id: 'edit', label: t('editMode'), icon: Settings },
    ...(canShowExecutionDataToggle
      ? [{ id: 'data' as const, label: t('runDataMode'), icon: Play }]
      : []),
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
