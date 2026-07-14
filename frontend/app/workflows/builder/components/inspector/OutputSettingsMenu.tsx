'use client';

import * as React from 'react';
import clsx from 'clsx';
import { useTranslations } from 'next-intl';
import { Settings, FlaskConical } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';

interface OutputSettingsMenuProps {
  /**
   * "Use as mock output" handler - present only on mock-capable nodes.
   * The whole menu hides when absent (no other entries yet).
   */
  onUseAsMock?: (output: unknown) => void;
  /**
   * Output object currently loaded by the column's RunDataPreview
   * (null = loading / nothing to display / torn down - see the publisher
   * contract in RunDataPreview).
   */
  loadedOutput: unknown | null;
}

/**
 * Gear menu rendered next to the OUTPUT column title - actions on the run
 * output currently displayed by the column. Same visual pattern as the other
 * builder dropdowns (version history, header run menu): rounded-2xl panel,
 * rounded-xl items.
 */
export function OutputSettingsMenu({ onUseAsMock, loadedOutput }: OutputSettingsMenuProps) {
  const tc = useTranslations('workflowBuilder.canvas');
  const tMock = useTranslations('workflowBuilder.mock');
  const [isOpen, setIsOpen] = React.useState(false);

  if (!onUseAsMock) return null;

  const canUseAsMock =
    loadedOutput !== null && typeof loadedOutput === 'object' && !Array.isArray(loadedOutput);

  return (
    <Popover open={isOpen} onOpenChange={setIsOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="p-1 hover:bg-slate-100 dark:hover:bg-slate-700 rounded text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
          title={tc('settings')}
          aria-haspopup="menu"
          aria-expanded={isOpen}
          data-testid="output-settings-trigger"
        >
          <Settings className="h-3.5 w-3.5" />
        </button>
      </PopoverTrigger>
      {/* z-[10000]: the popover portals to <body> with a default z-50, but the
          inspector panel hosting the trigger sits at z-[9999] (mobile) /
          z-[150] (desktop) - without the override the menu opens BEHIND it. */}
      <PopoverContent
        align="end"
        className="w-60 p-2 rounded-2xl bg-theme-primary border border-theme shadow-lg z-[10000]"
      >
        <div role="menu" className="space-y-1">
          <button
            type="button"
            role="menuitem"
            disabled={!canUseAsMock}
            data-testid="output-use-as-mock"
            onClick={() => {
              setIsOpen(false);
              if (canUseAsMock) onUseAsMock(loadedOutput);
            }}
            className={clsx(
              'w-full flex items-center gap-3 px-3 py-2.5 rounded-xl transition-colors text-theme-primary',
              canUseAsMock
                ? 'cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-800'
                : 'opacity-50 cursor-not-allowed'
            )}
          >
            <FlaskConical className="h-4 w-4 flex-shrink-0" strokeWidth={2} />
            <span className="text-sm flex-1 text-left truncate">{tMock('useAsMock')}</span>
          </button>
        </div>
      </PopoverContent>
    </Popover>
  );
}
