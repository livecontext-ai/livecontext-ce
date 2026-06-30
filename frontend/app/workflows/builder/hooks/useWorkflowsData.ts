import { useQuery } from '@tanstack/react-query';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { orchestratorApi } from '@/lib/api';

export interface WorkflowItem {
  id: string;
  name: string;
  description?: string;
  tenantId: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
  plan?: any;
  runCount?: number;
}

const fetchWorkflows = async (): Promise<WorkflowItem[]> => {
  // Picker context - request the max page (100) so dropdowns aren't silently truncated.
  const data = await orchestratorApi.getWorkflows({ size: 100 });
  return (data || []) as unknown as WorkflowItem[];
};

const fetchWorkflow = async (id: string): Promise<WorkflowItem> => {
  const data = await orchestratorApi.getWorkflow(id);
  return data as unknown as WorkflowItem;
};

export const useWorkflows = (enabled: boolean = true) => {
  const { user, isLoading: authLoading } = useAuthGuard();

  // Phase 4 (2026-05-18) - org-scoped: workflows are workspace-bound.
  return useOrgScopedQuery({
    queryKey: ['workflows'] as const,
    queryFn: () => fetchWorkflows(),
    enabled: enabled && !authLoading,
    staleTime: 5 * 60 * 1000, // 5 minutes
    gcTime: 10 * 60 * 1000, // 10 minutes
  });
};

export const useWorkflow = (id: string | null, enabled: boolean = true) => {
  const { user, isLoading: authLoading } = useAuthGuard();

  return useQuery({
    queryKey: ['workflow', id],
    queryFn: () => fetchWorkflow(id!),
    enabled: enabled && !authLoading && !!id,
    staleTime: 5 * 60 * 1000, // 5 minutes
    gcTime: 10 * 60 * 1000, // 10 minutes
  });
};
