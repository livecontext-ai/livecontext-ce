// @vitest-environment jsdom
/**
 * Which nodes the inspector pins to its 300px single-column panel.
 *
 * The gate exists for ONE screen: the MCP api/tool picker, which has no columns
 * to show until a tool is chosen. It used to also cover triggers, AI nodes and
 * core nodes, back when each rendered its own type picker - and it recognised
 * them by `data.id` PREFIX.
 *
 * Those prefixes do not survive a plan round-trip. A node is re-imported with
 * `data.id` set to its graph node id (NodeCreationService), so the id a node
 * comes back with is the id its canvas node was CREATED with, which for a
 * template or an agent-authored plan is nothing like `transform-` or
 * `form-trigger-`. The pickers were later deleted, and what was left pinned
 * configured nodes compact: no Input/Output columns, no Expand button, and no
 * Edit / Run data switcher - which is the only way to see what a step ran with.
 *
 * The fixtures below are real production shapes, not invented ones.
 */
import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Node } from 'reactflow';

import type { BuilderNodeData } from '../../../types';
import { useInspectorNodeMeta } from '../useInspectorNodeMeta';
import { shouldForceCompactPanel } from '../useInspectorLayout';

function isPinnedCompact(node: Node<BuilderNodeData>): boolean {
  const { result } = renderHook(() => useInspectorNodeMeta(node));
  const { isApiNode, isMcpGenericNode, isToolNode } = result.current;
  return shouldForceCompactPanel({ isApiNode, isMcpGenericNode, isToolNode });
}

function node(id: string, data: Partial<BuilderNodeData> & { kind: BuilderNodeData['kind'] }): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: { id, label: 'Step', ...data } as BuilderNodeData,
  };
}

describe('the node the report came in on', () => {
  it('does not pin the onboarding template transform, whose id is core-1', () => {
    // frontend/lib/templates/workflow/hello-workflow.json ships the transform as
    // `"id": "core-1"`, so every instance of "Hello workflow" carries that id.
    // `core-1` starts with `core-`, which the old gate read as "a core node that
    // has not picked its type yet" - the first workflow a new user ever opens,
    // permanently compact.
    expect(isPinnedCompact(node('core-1', { kind: 'transform', label: 'Build greeting' }))).toBe(false);
  });

  it('does not pin the same node when its id DOES carry the transform prefix', () => {
    expect(isPinnedCompact(node('transform-1758000000000', { kind: 'transform' }))).toBe(false);
  });
});

describe('triggers, which the old gate pinned almost without exception', () => {
  // 466 of the 479 triggers in production plans. A trigger is only recognised by
  // the old rule when its id happens to read `<type>-trigger…`, and an id comes
  // from whoever created the canvas node.
  it.each([
    ['a form trigger saved as trigger-new-clip', 'trigger-new-clip'],
    ['a trigger whose id is a bare uuid', '0186af67-eda6-4710-8eb4-1517e33094f1'],
    ['an agent-authored manual trigger', 'trigger:manual'],
    ['a schedule trigger with a generated id', 'trigger-34456c32-36c8-4f1b-b8a3-15fa0e1b4b83-1777543856004-yepigb55j'],
  ])('does not pin %s', (_case, id) => {
    expect(isPinnedCompact(node(id, { kind: 'entry' }))).toBe(false);
  });

  it('does not pin a trigger whose id DOES carry its type prefix either', () => {
    expect(isPinnedCompact(node('form-trigger-1788540021870', { kind: 'entry' }))).toBe(false);
  });
});

describe('the screen the gate still exists for', () => {
  it('pins a generic MCP node, which has only its api picker to show', () => {
    expect(isPinnedCompact(node('mcp-1758000000000', { kind: 'tool' }))).toBe(true);
  });

  it('pins an API node that has not chosen its tool yet', () => {
    const n = node('api-1758000000000', { kind: 'tool' });
    (n.data as any).apiData = { apiSlug: 'gmail', apiName: 'Gmail' };
    expect(isPinnedCompact(n)).toBe(true);
  });

  it('does NOT pin a tool node: it has chosen, so it has parameters and columns', () => {
    const n = node('mcp-1758000000000', { kind: 'tool' });
    (n.data as any).toolData = { toolSlug: 'send_email', toolName: 'send_email' };
    expect(isPinnedCompact(n)).toBe(false);
  });
});


/**
 * The fixtures above are only worth anything if they actually tripped the OLD
 * rule. Stated here in full, read off the same meta the panel reads, so a reader
 * can see that each case was pinned before and is not pinned now - without
 * having to check out the previous revision to believe it.
 *
 * This is the rule as it stood, verbatim in shape:
 *   hasTriggerNavigation = isTriggerNode && !dataSourceSelected && !tableSelected
 *                          && !workflowSelected && !manual && !chat && !webhook
 *                          && !schedule && !form
 *   hasNavigation        = !isToolNode && (hasTriggerNavigation || isAiGenericNode
 *                          || isCoreNode || isMcpNode)
 *   forced               = (isApiNode || isMcpGenericNode || hasNavigation) && !isToolNode
 */
function pinnedByTheOldRule(node: Node<BuilderNodeData>): boolean {
  const { result } = renderHook(() => useInspectorNodeMeta(node));
  const m = result.current;
  const dataSourceData = (node.data as any)?.dataSourceData;
  const workflowData = (node.data as any)?.workflowData;
  const dataSourceSelected = m.isTablesTrigger && dataSourceData?.dataSourceId && !dataSourceData?.tableName;
  const tableSelected = m.isTablesTrigger && dataSourceData?.dataSourceId && dataSourceData?.tableName;
  const workflowSelected = m.isWorkflowsTrigger && workflowData?.workflowId;
  const hasTriggerNavigation =
    m.isTriggerNode && !dataSourceSelected && !tableSelected && !workflowSelected &&
    !m.isManualTrigger && !m.isChatTrigger && !m.isWebhookTrigger && !m.isScheduleTrigger && !m.isFormTrigger;
  const hasNavigation =
    !m.isToolNode && (hasTriggerNavigation || m.isAiGenericNode || m.isCoreNode || m.isMcpNode);
  return (m.isApiNode || m.isMcpGenericNode || hasNavigation) && !m.isToolNode;
}

describe('what the old rule did to the same nodes', () => {
  it.each([
    ['the onboarding template transform', node('core-1', { kind: 'transform', label: 'Build greeting' })],
    ['a form trigger saved as trigger-new-clip', node('trigger-new-clip', { kind: 'entry' })],
    ['a trigger whose id is a bare uuid', node('0186af67-eda6-4710-8eb4-1517e33094f1', { kind: 'entry' })],
    ['an agent-authored manual trigger', node('trigger:manual', { kind: 'entry' })],
  ])('pinned %s, and no longer does', (_case, n) => {
    expect(pinnedByTheOldRule(n), 'the fixture must actually have tripped the old rule').toBe(true);
    expect(isPinnedCompact(n)).toBe(false);
  });

  it('agreed with the new rule on the screen the gate is for', () => {
    const mcp = node('mcp-1758000000000', { kind: 'tool' });
    expect(pinnedByTheOldRule(mcp)).toBe(true);
    expect(isPinnedCompact(mcp)).toBe(true);
  });
});
