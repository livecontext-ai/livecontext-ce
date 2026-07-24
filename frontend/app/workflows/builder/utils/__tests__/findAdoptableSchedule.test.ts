import { describe, it, expect } from 'vitest';
import { findAdoptableSchedule } from '../findAdoptableSchedule';
import type { ScheduleOverview } from '@/lib/api/orchestrator';

/**
 * Regression coverage for the "two schedule triggers for one workflow" bug.
 *
 * A schedule trigger built by an agent (or by any flow that left the plan's
 * `scheduleId` param null) is backed by an ATTACHED schedule row keyed by
 * (workflowId, triggerId) with NO sourceNodeId. When the builder reopened that
 * trigger it saw `data.standaloneScheduleId` empty and blindly auto-created a
 * SECOND, standalone schedule (workflow_id null) that never fires but clutters
 * the Triggers bell and burns the schedule quota. findAdoptableSchedule makes
 * the auto-create adopt the existing row instead of minting a duplicate.
 */
describe('findAdoptableSchedule', () => {
  const NODE_ID = 'trigger-2f70816b-aef3-4c94-1784043671372-3w53a89on';
  const SOURCE_NODE_ID = `schedule-${NODE_ID}`;
  const WORKFLOW_ID = '1bffde93-accd-48a3-a11a-ee053cdd8e3d';
  const TRIGGER_ID = 'trigger:poll_inbox';

  const sched = (partial: Partial<ScheduleOverview>): ScheduleOverview => ({
    id: 'id',
    name: 'Schedule',
    workflowId: null,
    workflowName: null,
    triggerId: null,
    cronExpression: '0 * * * *',
    timezone: 'UTC',
    enabled: true,
    executionCount: 0,
    createdAt: '2026-07-14T00:00:00Z',
    isActive: true,
    sourceNodeId: null,
    ...partial,
  });

  const attached = sched({
    id: 'attached-id',
    workflowId: WORKFLOW_ID,
    triggerId: TRIGGER_ID,
    sourceNodeId: null, // backend-created attached row has no sourceNodeId
  });
  const standalone = sched({
    id: 'standalone-id',
    workflowId: null,
    triggerId: null,
    sourceNodeId: SOURCE_NODE_ID,
  });

  it('adopts the attached row by (workflowId, triggerId) - the reported bug', () => {
    const found = findAdoptableSchedule([attached], {
      nodeId: NODE_ID,
      workflowId: WORKFLOW_ID,
      triggerId: TRIGGER_ID,
    });
    expect(found?.id).toBe('attached-id');
  });

  it('prefers the attached (firing) row over a stale standalone phantom for the same node', () => {
    // Both an attached firing row and a leftover standalone phantom exist.
    // Adopting the attached one makes the plan reference the schedule that fires.
    const found = findAdoptableSchedule([standalone, attached], {
      nodeId: NODE_ID,
      workflowId: WORKFLOW_ID,
      triggerId: TRIGGER_ID,
    });
    expect(found?.id).toBe('attached-id');
  });

  it('adopts a prior standalone row by sourceNodeId when no attached row exists', () => {
    const found = findAdoptableSchedule([standalone], {
      nodeId: NODE_ID,
      workflowId: WORKFLOW_ID,
      triggerId: TRIGGER_ID,
    });
    expect(found?.id).toBe('standalone-id');
  });

  it('returns undefined when nothing matches so the caller creates a fresh schedule', () => {
    const unrelated = sched({ id: 'other', workflowId: 'other-wf', triggerId: 'trigger:x' });
    const found = findAdoptableSchedule([unrelated], {
      nodeId: NODE_ID,
      workflowId: WORKFLOW_ID,
      triggerId: TRIGGER_ID,
    });
    expect(found).toBeUndefined();
  });

  it('does not adopt a row of the SAME workflow but a DIFFERENT trigger (both halves of the key matter)', () => {
    const sameWfOtherTrigger = sched({
      id: 'same-wf-other-trigger',
      workflowId: WORKFLOW_ID,
      triggerId: 'trigger:some_other_schedule',
    });
    const found = findAdoptableSchedule([sameWfOtherTrigger], {
      nodeId: NODE_ID,
      workflowId: WORKFLOW_ID,
      triggerId: TRIGGER_ID,
    });
    expect(found).toBeUndefined();
  });

  it('ignores an attached row of a DIFFERENT workflow (no cross-workflow adoption)', () => {
    const otherWorkflow = sched({
      id: 'other-wf-row',
      workflowId: 'a-different-workflow',
      triggerId: TRIGGER_ID, // same trigger label, different workflow
    });
    const found = findAdoptableSchedule([otherWorkflow], {
      nodeId: NODE_ID,
      workflowId: WORKFLOW_ID,
      triggerId: TRIGGER_ID,
    });
    expect(found).toBeUndefined();
  });

  it('on a brand-new unsaved workflow (no workflowId) only a sourceNodeId match is adoptable', () => {
    // No workflowId yet: the (workflowId, triggerId) branch is skipped, so an
    // attached-looking row must NOT be adopted; only the node's own standalone is.
    const found = findAdoptableSchedule([attached, standalone], {
      nodeId: NODE_ID,
      workflowId: null,
      triggerId: TRIGGER_ID,
    });
    expect(found?.id).toBe('standalone-id');
  });

  it('does not match a standalone row of a different node by sourceNodeId', () => {
    const otherNodeStandalone = sched({
      id: 'other-node',
      sourceNodeId: 'schedule-some-other-node',
    });
    const found = findAdoptableSchedule([otherNodeStandalone], {
      nodeId: NODE_ID,
      workflowId: null,
      triggerId: null,
    });
    expect(found).toBeUndefined();
  });
});
