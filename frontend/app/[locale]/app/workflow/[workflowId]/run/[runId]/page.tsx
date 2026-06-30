'use client';

/**
 * Workflow run mode page.
 * Content is now rendered by the parent layout.tsx (2 levels up) to avoid unmount/remount
 * when navigating between edit and run modes.
 *
 * The layout extracts the runId from the URL pathname and passes it to WorkflowDetailView,
 * which remains mounted during navigation.
 */
export default function WorkflowRunPage() {
  // Layout handles all content - this page is just a placeholder
  return null;
}

