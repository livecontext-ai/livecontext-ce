'use client';

import WorkflowTable from '@/components/WorkflowTable';
import { AuthenticatedView } from './AuthenticatedView';

/**
 * WorkflowView - Workflow list.
 *
 * The kanban board view now lives only in the aggregated Board menu
 * (/app/board → Workflows tab), so this page shows just the list (no board toggle).
 *
 * tenantId is NOT passed - the Gateway injects X-User-ID from JWT automatically.
 */
export function WorkflowView() {
  return (
    <AuthenticatedView>
      <WorkflowTable />
    </AuthenticatedView>
  );
}
