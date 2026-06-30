'use client';

import * as React from 'react';
import clsx from 'clsx';
import { ChevronDown, ChevronUp } from 'lucide-react';

interface ValidationError {
  message: string;
  severity: 'error' | 'warning';
  source?: string;
}

interface InspectorFooterProps {
  errors: ValidationError[];
  errorCount: number;
  warningCount?: number;
  isCollapsed: boolean;
  onToggleCollapsed: () => void;
}

/**
 * Footer component for the inspector panel that displays validation errors.
 * Shows a collapsible list of errors with severity-based styling.
 */
export function InspectorFooter({
  errors,
  errorCount,
  warningCount = 0,
  isCollapsed,
  onToggleCollapsed,
}: InspectorFooterProps) {
  if (errors.length === 0) {
    return null;
  }

  return (
    <div className={clsx(
      "border-t mt-auto px-5 py-2",
      errorCount > 0
        ? "border-red-200 bg-red-50 dark:bg-red-900/10"
        : "border-yellow-200 bg-yellow-50 dark:bg-yellow-900/10"
    )}>
      <button
        type="button"
        onClick={onToggleCollapsed}
        className="w-full flex items-center justify-between text-xs font-semibold text-red-700 dark:text-red-400 mb-1 hover:opacity-80 transition-opacity"
      >
        <span>
          {errorCount > 0 && (
            <span className="text-red-700 dark:text-red-400">{errorCount} error{errorCount > 1 ? 's' : ''}</span>
          )}
          {errorCount > 0 && warningCount > 0 && <span>, </span>}
          {warningCount > 0 && (
            <span className="text-yellow-700 dark:text-yellow-400">{warningCount} warning{warningCount > 1 ? 's' : ''}</span>
          )}
        </span>
        {isCollapsed ? (
          <ChevronDown className="w-3.5 h-3.5" />
        ) : (
          <ChevronUp className="w-3.5 h-3.5" />
        )}
      </button>
      {!isCollapsed && (
        <div className={clsx(
          "overflow-y-auto",
          "max-h-20 space-y-1"
        )}>
          <ul className={clsx(
            "list-disc list-inside",
            "space-y-1"
          )}>
            {errors.map((error, idx) => (
              <li
                key={idx}
                className={clsx(
                  "text-xs leading-snug break-words",
                  error.severity === 'error'
                    ? "text-red-600 dark:text-red-400"
                    : "text-yellow-600 dark:text-yellow-400"
                )}
              >
                {error.message}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
