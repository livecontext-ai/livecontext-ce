'use client';

import LoadingSpinner from '@/components/LoadingSpinner';

/**
 * Loading state for workflow view
 */
export function WorkflowLoadingState() {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-theme-primary transition-colors duration-300">
      <LoadingSpinner size="lg" />
    </div>
  );
}
