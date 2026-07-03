/**
 * Workflow variables of the active scope (workspace or personal), for builder
 * surfaces: draggable chips in the inspector, autocomplete suggestions.
 * React Query cached per scope; refetches on workspace switch via the key.
 */

import { useResourceQuery } from './useResourceQuery';
import { useCurrentOrg } from '@/lib/stores/current-org-store';
import {
  variablesApi,
  type WorkflowVariable,
} from '@/lib/api/services/variables-api.service';

export function useWorkflowVariables(enabled: boolean = true) {
  const { currentOrgId } = useCurrentOrg();
  const { data, isLoading, refetch } = useResourceQuery<WorkflowVariable[]>({
    queryKey: ['workflow-variables', currentOrgId ?? 'personal'],
    queryFn: () => variablesApi.list(),
    enabled,
  });
  return { variables: data ?? [], isLoading, refetch };
}
