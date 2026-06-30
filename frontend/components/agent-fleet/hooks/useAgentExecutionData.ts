'use client';

import { useQuery } from '@tanstack/react-query';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { queryKeys } from '@/lib/api/query-keys';
import type {
  AgentExecutionRecord,
  PagedResponse,
} from '@/lib/api/orchestrator/agent-metrics.types';

/**
 * Fetch paginated agent executions.
 */
export function useAgentExecutions(agentId: string | undefined, page = 0, size = 15) {
  return useQuery<PagedResponse<AgentExecutionRecord>>({
    queryKey: [...queryKeys.agent.executions(agentId || ''), page, size],
    queryFn: () => agentService.getAgentExecutions(agentId!, page, size),
    enabled: !!agentId,
    staleTime: 30_000,
  });
}

/**
 * Fetch a single agent execution record.
 */
export function useAgentExecution(executionId: string | null | undefined) {
  return useQuery<AgentExecutionRecord>({
    queryKey: queryKeys.agent.execution(executionId || ''),
    queryFn: () => agentService.getExecution(executionId!),
    enabled: !!executionId,
    staleTime: 60_000,
  });
}

// Conversation / tool-calls / iterations hooks intentionally removed - these used to be
// single-shot React Query wrappers that silently truncated execution detail to page 0 after
// the backend was paginated. Consumers must use {@code useExecutionPagedResource} which
// surfaces page metadata + a {@code loadOlder} callback so lazy-load can be wired.
