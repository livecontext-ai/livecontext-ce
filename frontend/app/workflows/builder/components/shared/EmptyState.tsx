'use client';

import * as React from 'react';
import { LucideIcon } from 'lucide-react';

/**
 * Reusable empty state component for displaying "no data" messages.
 * Follows the CLAUDE.md typography standards: text-sm for content.
 *
 * @param message - The message to display
 * @param icon - Optional Lucide icon component to display before the message
 * @param className - Optional className to override default styling (spacing, alignment, etc.)
 *
 * @example
 * ```tsx
 * // Default (left-aligned with padding)
 * <EmptyState message="No columns available" />
 *
 * // Centered
 * <EmptyState message="No data found" className="text-center py-4" />
 *
 * // With icon
 * <EmptyState message="No workflows" icon={Workflow} />
 *
 * // Custom styling
 * <EmptyState message="No results" className="p-4 text-center" />
 * ```
 */
interface EmptyStateProps {
  message: string;
  icon?: LucideIcon;
  className?: string;
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  message,
  icon: Icon,
  className = 'p-2',
}) => {
  return (
    <div className={`text-sm text-slate-400 dark:text-slate-500 italic ${className}`}>
      {Icon && (
        <Icon className="h-3.5 w-3.5 inline-block mr-1.5 align-text-bottom" />
      )}
      {message}
    </div>
  );
};
