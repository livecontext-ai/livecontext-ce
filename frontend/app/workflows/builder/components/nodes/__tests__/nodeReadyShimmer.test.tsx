// @vitest-environment jsdom
/**
 * The green node-body "ready" shimmer was REMOVED. A node that is runnable now
 * no longer paints a green scan across its whole body; instead its run button in
 * the bottom bar is revealed on its own with a blue shimmer (see
 * NodeBottomBar force-reveal + NodePlayButton RUNNABLE_SHIMMER). This test is a
 * regression guard that the old green overlay (data-testid "ready-shimmer")
 * never comes back for any node state, in or out of step-by-step mode.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockExec: any;
let mockMode: any;

const execStatus = (over: Record<string, boolean> = {}) => ({
  isStepByStepMode: false,
  isRunning: false,
  isFailed: false,
  isSkipped: false,
  isCompleted: false,
  isReady: false,
  ...over,
});

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
vi.mock('../../../contexts/StepByStepContext', () => ({
  useNodeExecutionStatus: () => mockExec,
}));
vi.mock('../../../contexts/ValidationContext', () => ({
  useValidation: () => ({ hasNodeErrors: () => false }),
}));
vi.mock('../../../nodes/nodeClasses', () => ({
  findNodeClassById: () => undefined,
}));
vi.mock('../../NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('../NodeBottomBar', () => ({ NodeBottomBar: () => null }));
vi.mock('../../NodePlayButton', () => ({
  NodePlayButton: () => null,
  deriveNodeStatus: () => undefined,
}));
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => null };
});
vi.mock('reactflow', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useNodes: () => [],
  useEdges: () => [],
}));

import { AggregateNode } from '../AggregateNode';
import { ExitNode } from '../ExitNode';
import { ResponseNode } from '../ResponseNode';
import { SplitNode } from '../SplitNode';
import { MergeNode } from '../MergeNode';

const components: ReadonlyArray<readonly [string, React.ComponentType<any>]> = [
  ['AggregateNode', AggregateNode],
  ['ExitNode', ExitNode],
  ['ResponseNode', ResponseNode],
  ['SplitNode', SplitNode],
  ['MergeNode', MergeNode],
];

const renderNode = (Comp: React.ComponentType<any>, status?: string) =>
  render(<Comp data={{ id: 'n1', label: 'N', status }} selected={false} id="n1" />);

const shimmer = (c: ReturnType<typeof render>) =>
  c.container.querySelector('[data-testid="ready-shimmer"]');

beforeEach(() => {
  mockMode = { isRunMode: true, viewingEpoch: null };
  mockExec = execStatus();
});

describe.each(components)('%s has no green ready shimmer', (_name, Comp) => {
  it('does NOT render the green node-body shimmer when READY in step-by-step mode', () => {
    mockExec = execStatus({ isStepByStepMode: true, isReady: true });
    const c = renderNode(Comp);
    expect(shimmer(c)).toBeNull();
  });

  it('does NOT render it for a ready status outside step-by-step mode either', () => {
    mockExec = execStatus({ isStepByStepMode: false });
    const c = renderNode(Comp, 'ready');
    expect(shimmer(c)).toBeNull();
  });

  it('does NOT render it for a running node', () => {
    mockExec = execStatus({ isStepByStepMode: true, isRunning: true });
    const c = renderNode(Comp);
    expect(shimmer(c)).toBeNull();
  });
});
