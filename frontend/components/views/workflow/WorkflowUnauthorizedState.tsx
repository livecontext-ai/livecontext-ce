'use client';

/**
 * Unauthorized state for workflow view
 */
export function WorkflowUnauthorizedState() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-theme-primary transition-colors duration-300">
      <div className="text-center">
        <h2 className="text-xl font-semibold text-theme-primary mb-2">Unauthorized</h2>
        <p className="text-theme-secondary">Sign in to view this workflow.</p>
      </div>
    </div>
  );
}
