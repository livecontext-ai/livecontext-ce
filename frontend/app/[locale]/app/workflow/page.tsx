'use client';

import { WorkflowView } from '@/components/views/WorkflowView';

/**
 * Workflow page component
 * Uses native Next.js routing - view is determined by URL via useCurrentView() hook
 */
export default function AppWorkflowPage() {
  return <WorkflowView />;
}
