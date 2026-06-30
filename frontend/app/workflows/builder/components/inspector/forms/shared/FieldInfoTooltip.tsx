'use client';

import * as React from 'react';
import * as ReactDOM from 'react-dom';
import { Info, X } from 'lucide-react';
import { usePopoverPosition } from '../../../../hooks/ui/usePopoverPosition';

/**
 * Reusable click-to-open info tooltip component.
 * Displays a description in a popover when the info icon is clicked.
 *
 * @param description - The description text to display in the tooltip
 *
 * @example
 * ```tsx
 * <FieldInfoTooltip description="This field controls the workflow execution mode" />
 * ```
 */
export const FieldInfoTooltip = ({ description }: { description: string }) => {
  const [isOpen, setIsOpen] = React.useState(false);
  const { buttonRef, popoverPosition } = usePopoverPosition(isOpen, 200);

  return (
    <div className="relative inline-flex">
      <button
        ref={buttonRef}
        onClick={(e) => {
          e.stopPropagation();
          setIsOpen(!isOpen);
        }}
        className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
        title="Click for more info"
      >
        <Info className="h-3 w-3 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300" />
      </button>
      {isOpen && typeof document !== 'undefined' && ReactDOM.createPortal(
        <>
          <div
            className="fixed inset-0 z-[9998]"
            onClick={() => setIsOpen(false)}
          />
          <div
            className="fixed z-[9999] w-52 p-2.5 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 shadow-lg"
            style={{ top: popoverPosition.top, left: popoverPosition.left }}
          >
            <div className="flex items-start justify-between gap-2">
              <p className="text-xs text-slate-600 dark:text-slate-300 leading-relaxed flex-1">
                {description}
              </p>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  setIsOpen(false);
                }}
                className="p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700 flex-shrink-0"
              >
                <X className="h-3 w-3 text-slate-400" />
              </button>
            </div>
          </div>
        </>,
        document.body
      )}
    </div>
  );
};
