/**
 * Task node `taskContext` wiring (org-scope audit follow-up, 2026-06-10).
 *
 * The backend (Core.TaskConfig + TaskNode.executeCreate) has always accepted a
 * `taskContext` object on create_task, but the builder neither emitted it in
 * the plan nor re-imported it - a ghost field. These tests pin the new 3-layer
 * wiring: builder data (`taskContextJson` string) → plan (`task.taskContext`
 * object) → builder data again, plus the validation rule that flags invalid
 * JSON instead of silently dropping it.
 */
import { describe, it, expect, vi } from 'vitest';
import type { Node } from 'reactflow';
import { processTransformAndWaitNodes } from '../stepProcessor';
import { NodeConfigurationRule } from '../../services/validation/rules-v2/NodeConfigurationRule';
import { NodeCreationService } from '../../services/workflowPlanImporter/NodeCreationService';
import type { BuilderNodeData } from '../../types';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), getTokenProvider: () => null },
}));
vi.mock('../../services/workflowPlanImporter/ToolDataService', () => ({
  ToolDataService: { fetchToolsBatch: vi.fn().mockResolvedValue(new Map()), getToolData: vi.fn() },
}));

function taskNode(extraData: Record<string, unknown>): Node<BuilderNodeData> {
  return {
    id: 'task-1',
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'task-1',
      label: 'Track Work',
      kind: 'task',
      taskOperation: 'create_task',
      taskTitle: 'Review doc',
      ...extraData,
    } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function runStepProcessor(node: Node<BuilderNodeData>) {
  const ctx: any = {
    nodes: [node],
    edges: [],
    plan: { cores: [] },
    stepLabelMap: new Map<string, string>(),
    stepPlanByNodeId: new Map<string, any>(),
  };
  processTransformAndWaitNodes(ctx);
  const core = ctx.plan.cores.find((c: any) => c.type === 'task');
  expect(core).toBeDefined();
  return core;
}

describe('stepProcessor - task node taskContext serialization', () => {
  it('emits taskContext as a plan object when taskContextJson is a valid JSON object', () => {
    const core = runStepProcessor(taskNode({
      taskContextJson: '{ "orderId": "12345", "url": "{{trigger:start.output.url}}" }',
    }));
    expect(core.task.taskContext).toEqual({
      orderId: '12345',
      url: '{{trigger:start.output.url}}',
    });
  });

  it('omits taskContext when taskContextJson is absent or blank', () => {
    expect(runStepProcessor(taskNode({})).task).not.toHaveProperty('taskContext');
    expect(runStepProcessor(taskNode({ taskContextJson: '   ' })).task).not.toHaveProperty('taskContext');
  });

  it('drops invalid JSON instead of corrupting the plan', () => {
    const core = runStepProcessor(taskNode({ taskContextJson: '{ not json' }));
    expect(core.task).not.toHaveProperty('taskContext');
  });

  it('drops non-object JSON (array, scalar, empty object) - backend expects a map', () => {
    expect(runStepProcessor(taskNode({ taskContextJson: '[1,2]' })).task).not.toHaveProperty('taskContext');
    expect(runStepProcessor(taskNode({ taskContextJson: '"text"' })).task).not.toHaveProperty('taskContext');
    expect(runStepProcessor(taskNode({ taskContextJson: '{}' })).task).not.toHaveProperty('taskContext');
  });

  it('still emits the other task fields alongside taskContext', () => {
    const core = runStepProcessor(taskNode({
      taskPriority: 'high',
      taskContextJson: '{"k":"v"}',
    }));
    expect(core.task.operation).toBe('create_task');
    expect(core.task.title).toBe('Review doc');
    expect(core.task.priority).toBe('high');
    expect(core.task.taskContext).toEqual({ k: 'v' });
  });
});

describe('NodeCreationService - task core import (plan → builder data)', () => {
  function taskPlan(taskConfig: Record<string, unknown>) {
    return {
      name: 'p',
      triggers: [],
      mcps: [],
      edges: [],
      cores: [{
        id: 'core:track_work',
        label: 'Track Work',
        type: 'task',
        task: { operation: 'create_task', title: 'Review doc', ...taskConfig },
      }],
    } as any;
  }

  async function importedTaskData(taskConfig: Record<string, unknown>) {
    const result = await NodeCreationService.createNodes(taskPlan(taskConfig));
    const node = result.nodes.find((n) => (n.data as any).kind === 'task');
    expect(node).toBeDefined();
    return node!.data as any;
  }

  it('re-imports plan taskContext as pretty-printed taskContextJson (round-trip with stepProcessor)', async () => {
    const data = await importedTaskData({ taskContext: { orderId: '12345' } });
    expect(data.taskContextJson).toBe(JSON.stringify({ orderId: '12345' }, null, 2));
    // full round-trip: re-serializing the imported node restores the plan object
    const core = runStepProcessor(taskNode({ taskContextJson: data.taskContextJson }));
    expect(core.task.taskContext).toEqual({ orderId: '12345' });
  });

  it('imports absent or empty taskContext as an empty taskContextJson', async () => {
    expect((await importedTaskData({})).taskContextJson).toBe('');
    expect((await importedTaskData({ taskContext: {} })).taskContextJson).toBe('');
  });
});

describe('NodeConfigurationRule - task_invalid_task_context', () => {
  function validate(node: Node<BuilderNodeData>) {
    const rule = new NodeConfigurationRule();
    const result = rule.validate({ nodes: [node], edges: [] } as any);
    return (result as any).issues ?? [];
  }

  function issueRules(issues: any[]): string[] {
    return issues.map((i: any) => i?.context?.rule).filter(Boolean);
  }

  it('flags invalid JSON in taskContextJson on create_task', () => {
    const issues = validate(taskNode({ taskContextJson: '{ broken' }));
    expect(issueRules(issues)).toContain('task_invalid_task_context');
  });

  it('flags JSON that is not an object (array)', () => {
    const issues = validate(taskNode({ taskContextJson: '[1, 2, 3]' }));
    expect(issueRules(issues)).toContain('task_invalid_task_context');
  });

  it('accepts a valid JSON object and an empty field', () => {
    expect(issueRules(validate(taskNode({ taskContextJson: '{"a": 1}' }))))
      .not.toContain('task_invalid_task_context');
    expect(issueRules(validate(taskNode({}))))
      .not.toContain('task_invalid_task_context');
  });
});
