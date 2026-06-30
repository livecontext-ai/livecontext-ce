'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Settings, FileText, Play, Code } from 'lucide-react';

export type ViewMode = 'configuration' | 'result';

interface ViewModeTabsProps {
  viewMode: ViewMode;
  onViewModeChange: (mode: ViewMode) => void;
  variant?: 'header' | 'mobile' | 'basic' | 'compact';
  /** Centralized execution data toggle (run data vs config/schema) */
  showExecutionData?: boolean;
  onShowExecutionDataChange?: (show: boolean) => void;
  /** Whether to show the execution data toggle */
  canShowExecutionDataToggle?: boolean;
}

/**
 * Configuration/Logs tab selector component.
 * Used in multiple places in the InspectorPanel.
 */
export function ViewModeTabs({
  viewMode,
  onViewModeChange,
  variant = 'mobile',
  showExecutionData,
  onShowExecutionDataChange,
  canShowExecutionDataToggle = false,
}: ViewModeTabsProps) {
  const tabsRef = React.useRef<HTMLDivElement>(null);
  const [sliderStyle, setSliderStyle] = React.useState<{ left: number; width: number }>({ left: 0, width: 0 });

  // Calculate slider position for header variant (animated)
  React.useEffect(() => {
    if (variant !== 'header') return;

    const updateSlider = () => {
      if (!tabsRef.current) return;

      const activeButton = tabsRef.current.querySelector(
        `[data-tab-id="${viewMode}"]`
      ) as HTMLButtonElement;

      if (activeButton) {
        const containerRect = tabsRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();

        setSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };

    updateSlider();
    window.addEventListener('resize', updateSlider);
    return () => window.removeEventListener('resize', updateSlider);
  }, [viewMode, variant]);

  if (variant === 'header') {
    return (
      <div
        ref={tabsRef}
        className="relative inline-flex items-center gap-0.5 p-1 bg-slate-100 dark:bg-slate-700 rounded-full flex-shrink-0 mr-2"
      >
        {/* Animated slider background */}
        <div
          className="absolute top-1 bottom-1 rounded-full bg-white dark:bg-slate-600 transition-all duration-300 ease-out shadow-sm"
          style={{
            left: `${sliderStyle.left}px`,
            width: `${sliderStyle.width}px`,
            opacity: sliderStyle.width > 0 ? 1 : 0,
          }}
        />

        <TabButton
          tabId="configuration"
          isActive={viewMode === 'configuration'}
          onClick={() => onViewModeChange('configuration')}
          icon={Settings}
          label="Configuration"
        />

        <TabButton
          tabId="result"
          isActive={viewMode === 'result'}
          onClick={() => onViewModeChange('result')}
          icon={FileText}
          label="Logs"
        />

        {/* Execution data toggle (Play) - only in configuration mode */}
        {canShowExecutionDataToggle && viewMode === 'configuration' && onShowExecutionDataChange && (
          <>
            <div className="w-px h-4 bg-slate-300 dark:bg-slate-500 mx-0.5" />
            <button
              type="button"
              onClick={() => onShowExecutionDataChange(!showExecutionData)}
              className={clsx(
                "relative z-10 flex items-center gap-1 px-2 py-1 rounded-full text-sm font-medium transition-all duration-200",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-theme-tertiary outline-none",
                showExecutionData
                  ? "bg-white dark:bg-slate-600 shadow-sm"
                  : ""
              )}
              title={showExecutionData ? 'Run data' : 'Configuration'}
            >
              {showExecutionData ? (
                <Play className="w-3.5 h-3.5 text-slate-900 dark:text-slate-100" />
              ) : (
                <Code className="w-3.5 h-3.5 text-slate-500 dark:text-slate-400" />
              )}
            </button>
          </>
        )}
      </div>
    );
  }

  // Compact variant: icon-only tabs with Play toggle (for mobile/tablet)
  if (variant === 'compact') {
    return (
      <div className="relative inline-flex items-center gap-0.5 p-1 bg-slate-100 dark:bg-slate-700 rounded-full">
        <button
          type="button"
          onClick={() => onViewModeChange('configuration')}
          className={clsx(
            "relative z-10 flex items-center justify-center w-8 h-8 rounded-full transition-all duration-200",
            viewMode === 'configuration'
              ? "bg-white dark:bg-slate-600 text-slate-900 dark:text-slate-100 shadow-sm"
              : "text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
          )}
          title="Configuration"
        >
          <Settings className="w-4 h-4" />
        </button>

        <button
          type="button"
          onClick={() => onViewModeChange('result')}
          className={clsx(
            "relative z-10 flex items-center justify-center w-8 h-8 rounded-full transition-all duration-200",
            viewMode === 'result'
              ? "bg-white dark:bg-slate-600 text-slate-900 dark:text-slate-100 shadow-sm"
              : "text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
          )}
          title="Logs"
        >
          <FileText className="w-4 h-4" />
        </button>

        {canShowExecutionDataToggle && viewMode === 'configuration' && onShowExecutionDataChange && (
          <>
            <div className="w-px h-4 bg-slate-300 dark:bg-slate-500 mx-0.5" />
            <button
              type="button"
              onClick={() => onShowExecutionDataChange(!showExecutionData)}
              className={clsx(
                "relative z-10 flex items-center justify-center w-8 h-8 rounded-full transition-all duration-200",
                showExecutionData
                  ? "bg-white dark:bg-slate-600 text-slate-900 dark:text-slate-100 shadow-sm"
                  : "text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
              )}
              title={showExecutionData ? 'Run data' : 'Configuration'}
            >
              {showExecutionData ? (
                <Play className="w-3.5 h-3.5" />
              ) : (
                <Code className="w-3.5 h-3.5" />
              )}
            </button>
          </>
        )}
      </div>
    );
  }

  // Mobile and basic variants use percentage-based slider
  const tabCount = 2;
  const tabIndex = viewMode === 'configuration' ? 0 : 1;
  const tabWidth = `calc(${100 / tabCount}% - ${4 / tabCount}px)`;
  const tabLeft = tabIndex === 0 ? '4px' : `calc(${(tabIndex * 100) / tabCount}%)`;

  return (
    <div className="relative inline-flex items-center gap-0.5 p-1 bg-slate-100 dark:bg-slate-700 rounded-full">
      {/* Animated slider background */}
      <div
        className="absolute top-1 bottom-1 rounded-full bg-white dark:bg-slate-600 transition-all duration-300 ease-out shadow-sm"
        style={{
          left: tabLeft,
          width: tabWidth,
        }}
      />

      <button
        type="button"
        onClick={() => onViewModeChange('configuration')}
        className={clsx(
          "relative z-10 flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-full text-sm font-medium transition-all duration-200",
          viewMode === 'configuration'
            ? "text-slate-900 dark:text-slate-100"
            : "text-slate-500 dark:text-slate-400"
        )}
      >
        <Settings className="h-3.5 w-3.5" />
        Configuration
      </button>

      <button
        type="button"
        onClick={() => onViewModeChange('result')}
        className={clsx(
          "relative z-10 flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-full text-sm font-medium transition-all duration-200",
          viewMode === 'result'
            ? "text-slate-900 dark:text-slate-100"
            : "text-slate-500 dark:text-slate-400"
        )}
      >
        <FileText className="h-3.5 w-3.5" />
        Logs
      </button>
    </div>
  );
}

interface TabButtonProps {
  tabId: string;
  isActive: boolean;
  onClick: () => void;
  icon: React.ComponentType<{ className?: string }>;
  label: string;
}

function TabButton({ tabId, isActive, onClick, icon: Icon, label }: TabButtonProps) {
  return (
    <button
      data-tab-id={tabId}
      type="button"
      onClick={onClick}
      className={clsx(
        "relative z-10 flex items-center gap-1.5 px-2.5 py-1 rounded-full text-sm font-medium transition-all duration-200",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-theme-tertiary outline-none",
        isActive
          ? "text-slate-900 dark:text-slate-100"
          : "text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
      )}
    >
      <Icon
        className={clsx(
          "w-4 h-4 transition-colors duration-200",
          isActive ? "text-slate-900 dark:text-slate-100" : "text-current"
        )}
      />
      <span className="whitespace-nowrap">{label}</span>
    </button>
  );
}
