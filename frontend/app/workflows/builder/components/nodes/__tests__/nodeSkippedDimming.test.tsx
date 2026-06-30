// @vitest-environment jsdom
/**
 * Regression: skipped-node dimming parity across run-visualization node types.
 *
 * In a finished run, a SKIPPED node must render at reduced opacity (opacity-50)
 * so the executed path stands out. Most node components apply this, but
 * AggregateNode / ExitNode / ResponseNode / SplitNode derived a 'skipped'
 * status (gray border) yet never wired the `opacity-50` dimming class - so an
 * aggregate fed only by skipped branches stayed fully visible. These specs pin
 * the parity: the four formerly-broken nodes must dim exactly like MergeNode
 * (the always-correct control), and only in the same conditions:
 *   - dim only outside step-by-step mode (live stepping keeps nodes vivid),
 *   - dim only for the 'skipped' status (not completed/pending).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

// --- Configurable context state, driven per-test ---
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
// Keep getStatusBorderColor / getIconSlug / useHoverVisibility real (pure / the
// status logic under test); only stub the heavy presentational children.
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => null };
});
// reactflow Handle needs a provider; stub it and the graph hooks MergeNode reads.
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
  ['MergeNode (control)', MergeNode],
];

const renderNode = (Comp: React.ComponentType<any>, status?: string, selected = false) =>
  render(
    <Comp data={{ id: 'n1', label: 'N', status }} selected={selected} id="n1" />,
  );

const root = (c: ReturnType<typeof render>) => c.container.firstChild as HTMLElement;

beforeEach(() => {
  mockMode = { isRunMode: true, viewingEpoch: null };
  mockExec = execStatus();
});

describe.each(components)('%s skipped dimming', (_name, Comp) => {
  it('dims (opacity-50) when skipped in a finished run', () => {
    mockExec = execStatus({ isStepByStepMode: false });
    const c = renderNode(Comp, 'skipped');
    expect(root(c).classList.contains('opacity-50')).toBe(true);
  });

  it('stays at full opacity when skipped during live step-by-step', () => {
    // isStepByStepMode true → effectiveStatus derives 'skipped' but the node
    // must NOT dim mid-run (the user is actively stepping through it).
    mockExec = execStatus({ isStepByStepMode: true, isSkipped: true });
    const c = renderNode(Comp, undefined);
    expect(root(c).classList.contains('opacity-50')).toBe(false);
  });

  it('does not dim a completed node', () => {
    mockExec = execStatus({ isStepByStepMode: false });
    const c = renderNode(Comp, 'completed');
    expect(root(c).classList.contains('opacity-50')).toBe(false);
  });

  it('keeps a selected skipped node fully visible (opacity-100)', () => {
    mockExec = execStatus({ isStepByStepMode: false });
    const c = renderNode(Comp, 'skipped', true);
    expect(root(c).classList.contains('opacity-50')).toBe(false);
  });
});
