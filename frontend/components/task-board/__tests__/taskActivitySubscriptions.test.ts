import { describe, expect, it } from 'vitest';
import {
  MAX_TASK_ACTIVITY_SUBS,
  selectTaskActivityAgentIds,
} from '../taskActivitySubscriptions';
import type { Task } from '@/lib/api/orchestrator/task.types';
import type { Agent } from '@/lib/api/orchestrator/types';

function task(id: string, assignedToAgentId?: string | null, reviewerAgentId?: string | null): Task {
  return {
    id,
    title: id,
    priority: 'normal',
    status: 'pending',
    assignedToAgentId: assignedToAgentId ?? null,
    reviewerAgentId: reviewerAgentId ?? null,
  } as Task;
}

function agent(id: string): Agent {
  return { id, name: id } as Agent;
}

describe('selectTaskActivityAgentIds', () => {
  it('keeps one WebSocket slot for the task board channel', () => {
    expect(MAX_TASK_ACTIVITY_SUBS).toBe(19);
  });

  it('prioritizes task-linked agents before visible board agents near the cap', () => {
    const visibleAgents = Array.from({ length: 25 }, (_, index) => agent(`visible-${index}`));

    const selected = selectTaskActivityAgentIds([
      task('task-1', 'assigned-agent'),
      task('task-2', null, 'reviewer-agent'),
    ], visibleAgents);

    expect(selected).toHaveLength(19);
    expect(selected.slice(0, 2)).toEqual(['assigned-agent', 'reviewer-agent']);
    expect(selected).toContain('visible-0');
    expect(selected).not.toContain('visible-24');
  });

  it('deduplicates agents that are both task-linked and visible', () => {
    const selected = selectTaskActivityAgentIds([
      task('task-1', 'agent-1', 'agent-2'),
      task('task-2', 'agent-1', null),
    ], [
      agent('agent-1'),
      agent('agent-3'),
    ]);

    expect(selected).toEqual(['agent-1', 'agent-2', 'agent-3']);
  });
});
