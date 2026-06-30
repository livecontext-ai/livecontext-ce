// @vitest-environment jsdom
/**
 * The right-click menu is a DYNAMIC superset of a node's bottom-bar buttons:
 * run actions appear only when the live run state allows them, contextual
 * side-panel buttons are reused as-is, and graph-edit operations show only in
 * edit mode. These tests pin that mode-aware item set.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';

let mockExec: {
  canExecute: boolean;
  canRerun: boolean;
  pendingSignalCount: number;
  executeStep: ReturnType<typeof vi.fn>;
  rerunStep: ReturnType<typeof vi.fn>;
  resolveApproval: ReturnType<typeof vi.fn>;
};
let mockContextualButtons: Array<{ key: string; icon: React.ReactNode; title: string; onClick: (e: React.MouseEvent) => void }>;

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('../../contexts/StepByStepContext', () => ({ useNodeExecutionStatus: () => mockExec }));
vi.mock('../../hooks/useNodeContextualButtons', () => ({
  deriveNodeContextFlags: () => ({}),
  useNodeContextualButtons: () => mockContextualButtons,
}));
vi.mock('../../nodes/nodeClasses', () => ({ findNodeClassById: () => null }));

import CanvasContextMenuDefault, { NodeContextMenu, PaneContextMenu, type NodeContextMenuActions, type PaneContextMenuActions } from '../CanvasContextMenu';

const testNode = { id: 'n1', type: 'flowNode', position: { x: 0, y: 0 }, data: { id: 'n1', label: 'My Node', kind: 'action' } } as Node<BuilderNodeData>;

const nodeActions = (): NodeContextMenuActions => ({
  openSettings: vi.fn(),
  duplicate: vi.fn(),
  copy: vi.fn(),
  selectDownstream: vi.fn(),
  disconnect: vi.fn(),
  addNote: vi.fn(),
  deleteNode: vi.fn(),
});

const paneActions = (): PaneContextMenuActions => ({
  addNode: vi.fn(),
  paste: vi.fn(),
  selectAll: vi.fn(),
  autoLayout: vi.fn(),
  fitView: vi.fn(),
});

beforeEach(() => {
  mockExec = {
    canExecute: false,
    canRerun: false,
    pendingSignalCount: 0,
    executeStep: vi.fn(),
    rerunStep: vi.fn(),
    resolveApproval: vi.fn(),
  };
  mockContextualButtons = [];
});

describe('NodeContextMenu - edit mode', () => {
  const renderEdit = (overrides: Partial<React.ComponentProps<typeof NodeContextMenu>> = {}) => {
    const actions = nodeActions();
    const onClose = vi.fn();
    render(
      <NodeContextMenu
        node={testNode}
        x={10}
        y={10}
        isRunMode={false}
        isPreviewOnly={false}
        hasDownstream
        hasConnections
        actions={actions}
        onClose={onClose}
        {...overrides}
      />,
    );
    return { actions, onClose };
  };

  it('shows the full edit operation set', () => {
    renderEdit();
    expect(screen.getByText('openSettings')).toBeTruthy();
    expect(screen.getByText('duplicate')).toBeTruthy();
    expect(screen.getByText('copy')).toBeTruthy();
    expect(screen.getByText('selectDownstream')).toBeTruthy();
    expect(screen.getByText('disconnectAll')).toBeTruthy();
    expect(screen.getByText('addNote')).toBeTruthy();
    expect(screen.getByText('delete')).toBeTruthy();
    // No run actions when the run state does not permit them.
    expect(screen.queryByText('executeStep')).toBeNull();
    expect(screen.queryByText('viewDetails')).toBeNull();
  });

  it('hides selectDownstream when there is no downstream and disconnect when unconnected', () => {
    renderEdit({ hasDownstream: false, hasConnections: false });
    expect(screen.queryByText('selectDownstream')).toBeNull();
    expect(screen.queryByText('disconnectAll')).toBeNull();
  });

  it('invokes the bound action and closes when an item is clicked', () => {
    const { actions, onClose } = renderEdit();
    fireEvent.click(screen.getByText('duplicate'));
    expect(actions.duplicate).toHaveBeenCalledWith('n1');
    expect(onClose).toHaveBeenCalled();
  });
});

describe('NodeContextMenu - run mode', () => {
  const renderRun = () => {
    const actions = nodeActions();
    const onClose = vi.fn();
    render(
      <NodeContextMenu
        node={testNode}
        x={10}
        y={10}
        isRunMode
        isPreviewOnly={false}
        hasDownstream
        hasConnections
        actions={actions}
        onClose={onClose}
      />,
    );
    return { actions, onClose };
  };

  it('hides edit operations and shows "view details" instead of "open settings"', () => {
    renderRun();
    expect(screen.getByText('viewDetails')).toBeTruthy();
    expect(screen.queryByText('openSettings')).toBeNull();
    expect(screen.queryByText('duplicate')).toBeNull();
    expect(screen.queryByText('delete')).toBeNull();
  });

  it('surfaces Run step when the node is executable, and wires it', () => {
    mockExec.canExecute = true;
    renderRun();
    const item = screen.getByText('executeStep');
    expect(item).toBeTruthy();
    fireEvent.click(item);
    expect(mockExec.executeStep).toHaveBeenCalled();
  });

  it('surfaces Re-run when the node can be re-run', () => {
    mockExec.canRerun = true;
    renderRun();
    expect(screen.getByText('rerunStep')).toBeTruthy();
  });

  it('surfaces Approve/Reject when a signal is pending', () => {
    mockExec.pendingSignalCount = 1;
    renderRun();
    fireEvent.click(screen.getByText('approve'));
    expect(mockExec.resolveApproval).toHaveBeenCalledWith('APPROVED');
    fireEvent.click(screen.getByText('reject'));
    expect(mockExec.resolveApproval).toHaveBeenCalledWith('REJECTED');
  });
});

describe('NodeContextMenu - contextual side-panel buttons', () => {
  it('renders the reused contextual buttons and wires their handlers', () => {
    const onClick = vi.fn();
    mockContextualButtons = [{ key: 'agent-config', icon: <span />, title: 'Configuration', onClick }];
    const onClose = vi.fn();
    render(
      <NodeContextMenu
        node={testNode}
        x={10}
        y={10}
        isRunMode={false}
        isPreviewOnly={false}
        hasDownstream={false}
        hasConnections={false}
        actions={nodeActions()}
        onClose={onClose}
      />,
    );
    fireEvent.click(screen.getByText('Configuration'));
    expect(onClick).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});

describe('PaneContextMenu', () => {
  const renderPane = (props: Partial<React.ComponentProps<typeof PaneContextMenu>>) => {
    const actions = paneActions();
    const onClose = vi.fn();
    render(
      <PaneContextMenu
        x={10}
        y={10}
        editable
        canPaste
        hasNodes
        actions={actions}
        onClose={onClose}
        {...props}
      />,
    );
    return { actions, onClose };
  };

  it('shows every item when editable, with a non-empty clipboard and existing nodes', () => {
    renderPane({});
    ['addNode', 'paste', 'selectAll', 'autoLayout', 'fitView'].forEach((key) => {
      expect(screen.getByText(key)).toBeTruthy();
    });
  });

  it('hides mutating items in run/preview mode but keeps select all and fit view', () => {
    renderPane({ editable: false });
    expect(screen.queryByText('addNode')).toBeNull();
    expect(screen.queryByText('paste')).toBeNull();
    expect(screen.queryByText('autoLayout')).toBeNull();
    expect(screen.getByText('selectAll')).toBeTruthy();
    expect(screen.getByText('fitView')).toBeTruthy();
  });

  it('hides paste when the clipboard is empty', () => {
    renderPane({ canPaste: false });
    expect(screen.queryByText('paste')).toBeNull();
  });

  it('invokes the action and closes on click', () => {
    const { actions, onClose } = renderPane({});
    fireEvent.click(screen.getByText('addNode'));
    expect(actions.addNode).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});

describe('ContextMenuShell lifecycle (via PaneContextMenu)', () => {
  const renderShell = () => {
    const onClose = vi.fn();
    render(<PaneContextMenu x={10} y={10} editable canPaste hasNodes actions={paneActions()} onClose={onClose} />);
    return { onClose };
  };

  it('closes on an outside mousedown', () => {
    const { onClose } = renderShell();
    fireEvent.mouseDown(document.body);
    expect(onClose).toHaveBeenCalled();
  });

  it('does not close on a mousedown inside the menu', () => {
    const { onClose } = renderShell();
    fireEvent.mouseDown(screen.getByTestId('canvas-context-menu'));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes on Escape, wheel, and resize', () => {
    const escape = renderShell();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(escape.onClose).toHaveBeenCalled();

    const wheel = renderShell();
    fireEvent.wheel(document.body);
    expect(wheel.onClose).toHaveBeenCalled();

    const resize = renderShell();
    fireEvent(window, new Event('resize'));
    expect(resize.onClose).toHaveBeenCalled();
  });
});

describe('default CanvasContextMenu wrapper (lazy dispatcher)', () => {
  it('renders the node menu for variant="node" without leaking the variant prop to the DOM', () => {
    render(
      <CanvasContextMenuDefault
        variant="node"
        node={testNode}
        x={10}
        y={10}
        isRunMode={false}
        isPreviewOnly={false}
        hasDownstream={false}
        hasConnections={false}
        actions={nodeActions()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByText('openSettings')).toBeTruthy();
    expect(screen.getByTestId('canvas-context-menu').hasAttribute('variant')).toBe(false);
  });

  it('renders the pane menu for variant="pane"', () => {
    render(
      <CanvasContextMenuDefault
        variant="pane"
        x={10}
        y={10}
        editable
        canPaste
        hasNodes
        actions={paneActions()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByText('addNode')).toBeTruthy();
    expect(screen.getByTestId('canvas-context-menu').hasAttribute('variant')).toBe(false);
  });
});
