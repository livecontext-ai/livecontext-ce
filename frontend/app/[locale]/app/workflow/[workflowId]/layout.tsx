'use client';

import { use } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { WorkflowDetailView } from '@/components/views/workflow';
import { WorkflowModeProvider } from '@/contexts/WorkflowModeContext';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';

interface WorkflowLayoutProps {
  children: React.ReactNode;
  params: Promise<{ workflowId: string }>;
}

/**
 * Layout for all workflow routes.
 * Stays mounted during navigation between edit and run mode,
 * avoiding the unmount/remount cycle of WorkflowBuilder.
 *
 * Providers are here to avoid dismounting/remounting when runId changes.
 *
 * Routes covered:
 * - /app/workflow/[workflowId] (edit mode)
 * - /app/workflow/[workflowId]/run/[runId] (run mode)
 */
export default function WorkflowLayout({ children, params }: WorkflowLayoutProps) {
  const { workflowId } = use(params);
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const autoOpenApp = searchParams.get('app') === 'true';

  // Extract runId from URL if in run mode
  const runIdMatch = pathname.match(/\/run\/([^\/]+)/);
  const runId = runIdMatch ? runIdMatch[1] : undefined;

  return (
    <WorkflowModeProvider workflowId={workflowId}>
      <WorkflowRunProvider>
        <WorkflowDetailView workflowId={workflowId} runId={runId} autoOpenApp={autoOpenApp} />
        {children}
      </WorkflowRunProvider>
    </WorkflowModeProvider>
  );
}
