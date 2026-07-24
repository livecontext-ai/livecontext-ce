/**
 * Reconciliation for the schedule-trigger inspector's auto-create.
 *
 * A saved schedule trigger can already be backed by a schedule row that the
 * builder node's `data.standaloneScheduleId` doesn't reference. Two independent
 * keying schemes produce such a row:
 *  - an ATTACHED row keyed by `(workflowId, triggerId)`, created by the backend
 *    pin-sync when the saved plan carried no `scheduleId`. It has NO
 *    `sourceNodeId`, so it can only be matched by `(workflowId, triggerId)`.
 *  - a STANDALONE row keyed by `(tenant, sourceNodeId)`, minted by a prior
 *    auto-create of THIS builder node.
 *
 * Blindly auto-creating whenever `data.standaloneScheduleId` was absent spawned
 * a SECOND standalone schedule every time such a trigger was reopened. That
 * orphan never fires (its `workflow_id` stays null), clutters the Triggers bell,
 * and burns the per-user schedule quota. Adopting the existing row instead keeps
 * one schedule per trigger node and lets the plan reference the row that fires.
 *
 * The attached row is preferred: when both an attached (firing) row and a stale
 * standalone (phantom) row exist for the same node, adopting the attached one
 * makes the plan point at the schedule that actually runs.
 */

import type { ScheduleOverview } from '@/lib/api/orchestrator';
import { buildStandaloneSourceNodeId } from './standaloneSourceNodeId';

export function findAdoptableSchedule(
  schedules: ScheduleOverview[],
  opts: { nodeId: string; workflowId?: string | null; triggerId?: string | null },
): ScheduleOverview | undefined {
  const { nodeId, workflowId, triggerId } = opts;

  // Prefer the attached row that actually fires for this workflow+trigger.
  if (workflowId && triggerId) {
    const attached = schedules.find(
      s => s.workflowId === workflowId && s.triggerId === triggerId,
    );
    if (attached) return attached;
  }

  // Else a standalone row previously minted for this exact builder node.
  const sourceNodeId = buildStandaloneSourceNodeId('schedule', nodeId);
  return schedules.find(s => s.sourceNodeId === sourceNodeId);
}
