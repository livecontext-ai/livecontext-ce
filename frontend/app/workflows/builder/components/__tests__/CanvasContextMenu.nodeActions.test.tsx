// @vitest-environment jsdom
/**
 * The node right-click menu mirrors the buttons that render UNDER the node
 * (NodeBottomBar), gated per node type / mode: the edit-mode trigger launcher
 * (Run / Step-by-step), the trigger pin-as-production affordance, and the
 * run-mode "View interface" action. These tests pin that node-type-aware set
 * and the events each item fires.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';

let mockExec: { canExecute: boolean; canRerun: boolean; pendingSignalCount: number; executeStep: () => void; rerunStep: () => void; resolveApproval: () => void };
let mockFlags: Record<string, unknown>;
let mockWorkflowId: string | undefined;
let mockPinDisplay: { shouldRender: boolean; isAlreadyPinned: boolean; buttonTitle: string };
const mockRequestTriggerPin = vi.fn();

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('../../contexts/StepByStepContext', () => ({ useNodeExecutionStatus: () => mockExec }));
vi.mock('../../hooks/useNodeContextualButtons', () => ({
  deriveNodeContextFlags: () => mockFlags,
  useNodeContextualButtons: () => [],
}));
vi.mock('../../nodes/nodeClasses', () => ({ findNodeClassById: () => null }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ workflowId: mockWorkflowId }) }));
vi.mock('../../hooks/useTriggerPin', () => ({
  useTriggerPinDisplay: () => mockPinDisplay,
  requestTriggerPin: (...args: unknown[]) => mockRequestTriggerPin(...args),
}));

import { NodeContextMenu, type NodeContextMenuActions } from '../CanvasContextMenu';

const makeNode = (data: Partial<BuilderNodeData> = {}): Node<BuilderNodeData> =>
  ({ id: 'n1', type: 'flowNode', position: { x: 0, y: 0 }, data: { id: 'n1', label: 'My Node', kind: 'action', ...data } } as Node<BuilderNodeData>);

const nodeActions = (): NodeContextMenuActions => ({
  openSettings: vi.fn(), duplicate: vi.fn(), copy: vi.fn(),
  selectDownstream: vi.fn(), disconnect: vi.fn(), addNote: vi.fn(), deleteNode: vi.fn(),
});

const renderMenu = (props: Partial<React.ComponentProps<typeof NodeContextMenu>> = {}) => {
  const onClose = vi.fn();
  render(
    <NodeContextMenu
      node={makeNode()} x={10} y={10}
      isRunMode={false} isPreviewOnly={false}
      hasDownstream={false} hasConnections={false}
      actions={nodeActions()} onClose={onClose}
      {...props}
    />,
  );
  return { onClose };
};

/** Captures the next CustomEvent of `type` dispatched on window. */
const captureEvent = (type: string): { detail: () => unknown } => {
  let received: unknown;
  const handler = (e: Event) => { received = (e as CustomEvent).detail; };
  window.addEventListener(type, handler);
  return { detail: () => received };
};

beforeEach(() => {
  mockExec = { canExecute: false, canRerun: false, pendingSignalCount: 0, executeStep: vi.fn(), rerunStep: vi.fn(), resolveApproval: vi.fn() };
  mockFlags = {};
  mockWorkflowId = 'wf1';
  mockPinDisplay = { shouldRender: false, isAlreadyPinned: false, buttonTitle: 'Set as production v3' };
  mockRequestTriggerPin.mockClear();
});

describe('NodeContextMenu - edit-mode trigger launcher', () => {
  it('shows Run / Step-by-step for a trigger node and fires the start events', () => {
    mockFlags = { isTriggerNode: true };
    const auto = captureEvent('workflowViewStart');
    const sbs = captureEvent('workflowStartStepByStep');
    const { onClose } = renderMenu();

    expect(screen.getByText('runAuto')).toBeTruthy();
    expect(screen.getByText('runStepByStep')).toBeTruthy();

    fireEvent.click(screen.getByText('runAuto'));
    expect(auto.detail()).toEqual({ startFromNode: 'n1' });
    expect(onClose).toHaveBeenCalled();

    fireEvent.click(screen.getByText('runStepByStep'));
    expect(sbs.detail()).toEqual({ startFromNode: 'n1' });
  });

  it('does not show the launcher for a non-trigger node', () => {
    mockFlags = {};
    renderMenu();
    expect(screen.queryByText('runAuto')).toBeNull();
    expect(screen.queryByText('runStepByStep')).toBeNull();
  });

  it('does not show the launcher in run mode (triggers run via the play button there)', () => {
    mockFlags = { isTriggerNode: true };
    renderMenu({ isRunMode: true });
    expect(screen.queryByText('runAuto')).toBeNull();
  });
});

describe('NodeContextMenu - trigger pin/unpin', () => {
  it('shows the pin title and routes the flow to the mounted button by node id', () => {
    mockFlags = { isTriggerNode: true };
    mockPinDisplay = { shouldRender: true, isAlreadyPinned: false, buttonTitle: 'Set as production v3' };
    const { onClose } = renderMenu();

    const item = screen.getByText('Set as production v3');
    expect(item).toBeTruthy();
    fireEvent.click(item);
    expect(mockRequestTriggerPin).toHaveBeenCalledWith('wf1', 'n1');
    expect(onClose).toHaveBeenCalled();
  });

  it('renders the unpin label when the version is already pinned', () => {
    mockFlags = { isTriggerNode: true };
    mockPinDisplay = { shouldRender: true, isAlreadyPinned: true, buttonTitle: 'Remove from production v3' };
    renderMenu({ isRunMode: true });
    expect(screen.getByText('Remove from production v3')).toBeTruthy();
  });

  it('hides the pin item when the affordance does not apply', () => {
    mockFlags = { isTriggerNode: true };
    mockPinDisplay = { shouldRender: false, isAlreadyPinned: false, buttonTitle: 'Set as production v3' };
    renderMenu();
    expect(screen.queryByText('Set as production v3')).toBeNull();
  });

  it('hides the pin item when there is no workflow id', () => {
    mockFlags = { isTriggerNode: true };
    mockWorkflowId = undefined;
    mockPinDisplay = { shouldRender: true, isAlreadyPinned: false, buttonTitle: 'Set as production v3' };
    renderMenu();
    expect(screen.queryByText('Set as production v3')).toBeNull();
  });
});

describe('NodeContextMenu - view interface', () => {
  it('shows View interface in run mode and opens the resolved interface', () => {
    mockFlags = { isInterfaceNode: true };
    const open = captureEvent('workflowOpenApplicationTab');
    const onClose = vi.fn();
    render(
      <NodeContextMenu
        node={makeNode({ id: 'interface-x', interfaceData: { interfaceId: 'iface1' } } as Partial<BuilderNodeData>)}
        x={10} y={10} isRunMode isPreviewOnly={false}
        hasDownstream={false} hasConnections={false}
        actions={nodeActions()} onClose={onClose}
      />,
    );
    const item = screen.getByText('viewInterface');
    expect(item).toBeTruthy();
    fireEvent.click(item);
    expect(open.detail()).toEqual({ interfaceId: 'iface1' });
    expect(onClose).toHaveBeenCalled();
  });

  it('does not show View interface in edit mode', () => {
    mockFlags = { isInterfaceNode: true };
    render(
      <NodeContextMenu
        node={makeNode({ interfaceData: { interfaceId: 'iface1' } } as Partial<BuilderNodeData>)}
        x={10} y={10} isRunMode={false} isPreviewOnly={false}
        hasDownstream={false} hasConnections={false}
        actions={nodeActions()} onClose={vi.fn()}
      />,
    );
    expect(screen.queryByText('viewInterface')).toBeNull();
  });

  it('resolves the interface id by parsing the node-class id when no explicit id is set', () => {
    mockFlags = { isInterfaceNode: true };
    const open = captureEvent('workflowOpenApplicationTab');
    render(
      <NodeContextMenu
        node={makeNode({ id: 'interface-abc--3' } as Partial<BuilderNodeData>)}
        x={10} y={10} isRunMode isPreviewOnly={false}
        hasDownstream={false} hasConnections={false}
        actions={nodeActions()} onClose={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByText('viewInterface'));
    expect(open.detail()).toEqual({ interfaceId: 'abc' });
  });

  it('hides View interface when no interface id can be resolved', () => {
    mockFlags = { isInterfaceNode: true };
    render(
      <NodeContextMenu
        node={makeNode({ id: 'interface' } as Partial<BuilderNodeData>)}
        x={10} y={10} isRunMode isPreviewOnly={false}
        hasDownstream={false} hasConnections={false}
        actions={nodeActions()} onClose={vi.fn()}
      />,
    );
    expect(screen.queryByText('viewInterface')).toBeNull();
  });
});
