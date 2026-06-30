import type { Task } from '@/lib/api/orchestrator/task.types';
import type { Agent } from '@/lib/api/orchestrator/types';

export const MAX_TASK_ACTIVITY_SUBS = 19;

export function selectTaskActivityAgentIds(tasks: Task[], agents: Agent[]): string[] {
  const ids = new Set<string>();
  for (const task of tasks) {
    if (task.assignedToAgentId) ids.add(task.assignedToAgentId);
    if (task.reviewerAgentId) ids.add(task.reviewerAgentId);
  }
  for (const agent of agents) {
    if (agent.id) ids.add(agent.id);
  }
  return Array.from(ids).slice(0, MAX_TASK_ACTIVITY_SUBS);
}
