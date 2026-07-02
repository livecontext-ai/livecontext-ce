// @vitest-environment jsdom
/**
 * Step-by-step "focused/playable" shimmer: a node whose status is READY in
 * step-by-step mode renders a green ReadyShimmerOverlay (data-testid
 * "ready-shimmer"), the same visual language as the blue running shimmer, so
 * the user can see at a glance which node they are on and can execute next.
 * The overlay must appear ONLY for that state:
 *   - not outside step-by-step mode (auto runs never show it),
 *   - not for running/completed nodes (running keeps its blue shimmer),
 *   - gone once the node is no longer ready.
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
// Keep ReadyShimmerOverlay / getStatusBorderColor real (they are under test);
// only stub the heavy presentational children.
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

describe.each(components)('%s ready shimmer', (_name, Comp) => {
  it('shows the green shimmer when the node is READY in step-by-step mode', () => {
    mockExec = execStatus({ isStepByStepMode: true, isReady: true });
    const c = renderNode(Comp);
    expect(shimmer(c)).not.toBeNull();
  });

  it('does not show the shimmer outside step-by-step mode even for a ready status', () => {
    mockExec = execStatus({ isStepByStepMode: false });
    const c = renderNode(Comp, 'ready');
    expect(shimmer(c)).toBeNull();
  });

  it('does not show the ready shimmer on a RUNNING node (running keeps its own shimmer)', () => {
    mockExec = execStatus({ isStepByStepMode: true, isRunning: true });
    const c = renderNode(Comp);
    expect(shimmer(c)).toBeNull();
  });

  it('drops the shimmer once the node completes', () => {
    mockExec = execStatus({ isStepByStepMode: true, isCompleted: true });
    const c = renderNode(Comp);
    expect(shimmer(c)).toBeNull();
  });
});
