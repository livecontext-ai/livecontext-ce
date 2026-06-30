// @vitest-environment jsdom
/**
 * Tests for the run-info popover step actions ({@link StepRowActions}).
 *
 * StepRowActions reuses the canvas button builders but reimplements the
 * trigger-fire path because it renders OUTSIDE the StepByStepProvider. These
 * tests pin the two behaviors that path adds on top of the shared builders:
 *
 *   1. The trigger play is gated on an active (non-terminal) run - mirroring the
 *      canvas, which hides the play once nothing is ready, and the dispatcher,
 *      which rejects firing into a terminal run.
 *   2. Firing returns to all-epochs (setViewingEpoch(null)) then dispatches a
 *      workflowId-scoped `workflowExecuteStep` event (so a concurrently mounted
 *      sub-workflow builder does not also fire).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';

const { setViewingEpoch } = vi.hoisted(() => ({ setViewingEpoch: vi.fn() }));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, setViewingEpoch }),
}));
vi.mock('@/app/workflows/builder/nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
// Leaf deps of useNodeContextualButtons - kept real, but its imports stubbed so
// importing it in jsdom is cheap and side-panel calls no-op.
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONVERSATION_TAB: 'conversation',
  AGENT_CONFIGURATION_TAB: 'configuration',
}));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({ openFilesPanel: vi.fn() }));
// Presentational leaf components stubbed so we can assert StepRowActions wiring
// without their context deps (TriggerNodePinButton needs WorkflowRunContext;
// NodePlayButton needs i18n).
vi.mock('@/app/workflows/builder/components/nodes/TriggerNodePinButton', () => ({
  TriggerNodePinButton: () => <div data-testid="pin" />,
}));
vi.mock('@/app/workflows/builder/components/NodePlayButton', () => ({
  NodePlayButton: (props: any) => (
    <button data-testid="play" onClick={() => props.onExecute(props.nodeId)}>play</button>
  ),
}));

import { StepRowActions } from '../StepRowActions';

const mkNode = (id: string, data: Record<string, any> = {}): Node<BuilderNodeData> =>
  ({ id, position: { x: 0, y: 0 }, data: { id, label: id, ...data } as BuilderNodeData } as Node<BuilderNodeData>);

describe('StepRowActions', () => {
  beforeEach(() => {
    setViewingEpoch.mockClear();
  });

  it('shows the trigger play on an active run', () => {
    const { getByTestId } = render(
      <StepRowActions step={{ alias: 'trigger:manual' }} matchedNode={mkNode('manual-trigger-1', { kind: 'entry' })} workflowId="wf-1" isStepByStep={false} isRunActive />,
    );
    expect(getByTestId('play')).toBeTruthy();
    expect(getByTestId('pin')).toBeTruthy();
  });

  it('hides the trigger play on a terminal run but keeps pin', () => {
    const { queryByTestId } = render(
      <StepRowActions step={{ alias: 'trigger:manual' }} matchedNode={mkNode('manual-trigger-1', { kind: 'entry' })} workflowId="wf-1" isStepByStep={false} isRunActive={false} />,
    );
    expect(queryByTestId('play')).toBeNull();
    expect(queryByTestId('pin')).toBeTruthy();
  });

  it('fires via a workflowId-scoped event and returns to all-epochs', () => {
    const dispatch = vi.spyOn(window, 'dispatchEvent');
    const { getByTestId } = render(
      <StepRowActions step={{ alias: 'trigger:manual' }} matchedNode={mkNode('manual-trigger-1', { kind: 'entry' })} workflowId="wf-1" isStepByStep={false} isRunActive />,
    );
    fireEvent.click(getByTestId('play'));
    expect(setViewingEpoch).toHaveBeenCalledWith(null);
    const evt = dispatch.mock.calls.map((c) => c[0] as Event).find((e) => e.type === 'workflowExecuteStep') as CustomEvent;
    expect(evt).toBeTruthy();
    expect(evt.detail).toEqual({ stepId: 'trigger:manual', workflowId: 'wf-1' });
    dispatch.mockRestore();
  });

  it('renders nothing for a plain non-trigger node with no side panel', () => {
    const { container } = render(
      <StepRowActions step={{ alias: 'mcp:fetch' }} matchedNode={mkNode('http-request', { kind: 'http_request' })} workflowId="wf-1" isStepByStep={false} isRunActive />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('does not show a play for a non-trigger node even on an active run', () => {
    const { queryByTestId } = render(
      <StepRowActions step={{ alias: 'agent:helper' }} matchedNode={mkNode('ai-agent', { agentConfigId: 'cfg-1' })} workflowId="wf-1" isStepByStep={false} isRunActive />,
    );
    expect(queryByTestId('play')).toBeNull();
    expect(queryByTestId('pin')).toBeNull();
  });
});
