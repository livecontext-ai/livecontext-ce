// @vitest-environment jsdom
/**
 * Who may move a node on the canvas.
 *
 * Edit mode: yes, and Save persists it. Run mode: yes, to rearrange the graph while
 * reading a run (not saved). 60589c8ec3 locked run mode to stop a move that looked
 * saved from snapping back, which took that away. A read-only preview stays fixed.
 * The toolbar lock freezes nodes in every mode, including a drag already under way.
 *
 * Mock scaffolding mirrors BuilderCanvas.saveScope.test.tsx.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';

let mockMode: { isRunMode: boolean; isPreviewOnly: boolean };
let mockPathname: string;
let mockDirection: 'horizontal' | 'vertical';

vi.mock('next/navigation', () => ({ usePathname: () => mockPathname }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({
  useWorkflowLayoutDirectionSafe: () => ({
    direction: mockDirection,
    setDirection: vi.fn(),
    setWorkflowDirection: vi.fn(),
  }),
}));
// `useOptionalTheme` is mocked alongside `useTheme`: the icons in this tree render
// through `useThemeSafely`, which reads the context OPTIONALLY so the same icons can
// render on the public marketplace outside any ThemeProvider. A mock missing it throws.
vi.mock('@/components/ThemeProvider', () => ({ useTheme: () => ({ theme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div data-testid="loading-spinner" /> }));
vi.mock('@/components/chat/SimpleToast', () => ({
  SimpleToast: () => null,
  useSimpleToast: () => ({ toast: null, showToast: vi.fn(), hideToast: vi.fn() }),
}));

let reactFlowProps: Record<string, unknown> = {};
vi.mock('reactflow', () => ({
  default: (props: { children?: React.ReactNode } & Record<string, unknown>) => {
    reactFlowProps = props;
    return <div data-testid="react-flow">{props.children}</div>;
  },
  Background: () => null,
  BackgroundVariant: { Dots: 'dots', Lines: 'lines', Cross: 'cross' },
  Panel: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  ReactFlowProvider: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  ConnectionMode: { Loose: 'loose', Strict: 'strict' },
  getBezierPath: () => ['', 0, 0, 0, 0],
  getSmoothStepPath: () => ['', 0, 0, 0, 0],
  useUpdateNodeInternals: () => () => {},
}));

vi.mock('../../constants/graphTypes', () => ({ nodeTypes: {}, edgeTypes: {} }));
vi.mock('../../contexts/ValidationContext', () => ({ useValidationOptional: () => null }));
const generateWorkflowPlan = vi.fn((..._args: unknown[]) => ({ mocked: 'plan' }));
vi.mock('../../utils/workflowPlanGenerator', () => ({
  generateWorkflowPlan: (...args: unknown[]) => generateWorkflowPlan(...args),
}));
vi.mock('../../utils/connectionValidator', () => ({ validateConnection: () => true }));
vi.mock('../../services/LayoutService', () => ({ applyDagreLayout: (n: unknown) => n }));
vi.mock('../../services/nodeMatcher', () => ({ nodeMatchesStep: () => false }));
vi.mock('../../registry/nodeRegistry', () => ({
  nodeRegistry: new Proxy({}, { get: () => () => false }),
}));
vi.mock('../../nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
vi.mock('../nodes/shared', () => ({ NodeIcon: () => null, getIconSlug: () => '' }));
vi.mock('../HoverEdgeManager', () => ({ HoverEdgeManager: () => null }));
let toolbarProps: { onToggleInteractivity: () => void } | null = null;
vi.mock('../CanvasToolbar', () => ({
  CanvasToolbar: (p: { onToggleInteractivity: () => void }) => {
    toolbarProps = p;
    return null;
  },
}));
vi.mock('../CanvasSettingsPanel', () => ({ CanvasSettingsPanel: () => null }));
vi.mock('../EmptyCanvasChat', () => ({ EmptyCanvasChat: () => <div data-testid="empty-canvas-chat" /> }));

vi.mock('../../hooks/useCanvasViewport', () => ({
  useCanvasViewport: () => ({
    isViewReady: true,
    handleInstanceInit: vi.fn(),
    handleZoomIn: vi.fn(),
    handleZoomOut: vi.fn(),
    handleFitView: vi.fn(),
  }),
}));
vi.mock('../../hooks/useInspectorDrag', () => ({
  useInspectorDrag: () => ({ position: { x: 16, y: 16 }, handleDragStart: vi.fn() }),
}));
vi.mock('../../hooks/useBoxSelection', () => ({
  useBoxSelection: () => ({
    isBoxSelectionEnabled: false,
    isSelecting: false,
    selectionStart: null,
    selectionEnd: null,
    cursorMode: 'pan' as const,
    setCursorMode: vi.fn(),
    handleSelectionChange: vi.fn(),
    containerRef: { current: null },
    selectionJustEndedRef: { current: false },
  }),
}));
vi.mock('../../hooks/useTypingSuggestion', () => ({
  useTypingSuggestion: () => ({
    typingSuggestionId: null,
    chatInput: '',
    handleSuggestionClick: vi.fn(),
    handleChatInputChange: vi.fn(),
  }),
}));
vi.mock('../../constants/workflowSuggestions', () => ({ getDisplayedSuggestions: () => [] }));

import { BuilderCanvas } from '../BuilderCanvas';

beforeEach(() => {
  (globalThis as any).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  mockMode = { isRunMode: false, isPreviewOnly: false };
  mockPathname = '/app/workflow/wf-1';
  mockDirection = 'horizontal';
  generateWorkflowPlan.mockClear();
  toolbarProps = null;
});

const props = () => ({
  nodes: [{ id: 'n1', position: { x: 0, y: 0 }, data: {} }] as any[],
  edges: [] as any[],
  onNodesChange: vi.fn(),
  onEdgesChange: vi.fn(),
  onConnect: vi.fn(),
  onCreateNode: vi.fn(),
  onSelectionChange: vi.fn(),
  hoveredEdgeId: null,
  onHoverEdge: vi.fn(),
  onDeleteEdge: vi.fn(),
  workflowId: 'wf-1',
  onSaveWorkflow: vi.fn(),
});

describe('BuilderCanvas node dragging', () => {
  it('lets nodes be dragged in edit mode, where Save persists the move', () => {
    render(<BuilderCanvas {...props()} />);
    expect(reactFlowProps.nodesDraggable).toBe(true);
  });

  it('regression: lets nodes be dragged in run mode again (locked by 60589c8ec3)', () => {
    mockMode = { isRunMode: true, isPreviewOnly: false };
    const p = props();
    render(<BuilderCanvas {...p} />);
    expect(reactFlowProps.nodesDraggable).toBe(true);

    // The move reaches the node state, so the node follows the pointer.
    const move = { type: 'position', id: 'n1', position: { x: 40, y: 80 }, dragging: true };
    act(() => (reactFlowProps.onNodesChange as (c: unknown[]) => void)([move]));
    expect(p.onNodesChange).toHaveBeenCalledWith([move]);
  });

  it('keeps a read-only preview fixed', () => {
    mockMode = { isRunMode: false, isPreviewOnly: true };
    render(<BuilderCanvas {...props()} />);
    expect(reactFlowProps.nodesDraggable).toBe(false);
  });

  it('keeps a read-only preview of a run fixed', () => {
    mockMode = { isRunMode: true, isPreviewOnly: true };
    render(<BuilderCanvas {...props()} />);
    expect(reactFlowProps.nodesDraggable).toBe(false);
  });

  it.each([
    ['edit', false],
    ['run', true],
  ])('the toolbar lock freezes nodes in %s mode, and unlocking frees them', (_label, isRunMode) => {
    mockMode = { isRunMode, isPreviewOnly: false };
    render(<BuilderCanvas {...props()} />);

    act(() => toolbarProps!.onToggleInteractivity());
    expect(reactFlowProps.nodesDraggable).toBe(false);

    act(() => toolbarProps!.onToggleInteractivity());
    expect(reactFlowProps.nodesDraggable).toBe(true);
  });

  describe('while the toolbar lock is on', () => {
    const lockedCanvas = (isRunMode: boolean) => {
      mockMode = { isRunMode, isPreviewOnly: false };
      const p = props();
      render(<BuilderCanvas {...p} />);
      act(() => toolbarProps!.onToggleInteractivity());
      const send = (changes: unknown[]) =>
        act(() => (reactFlowProps.onNodesChange as (c: unknown[]) => void)(changes));
      return { p, send };
    };

    it('drops the moves of a drag already under way', () => {
      const { p, send } = lockedCanvas(true);
      send([{ type: 'position', id: 'n1', position: { x: 40, y: 80 }, dragging: true }]);
      expect(p.onNodesChange).not.toHaveBeenCalled();
    });

    it('keeps the drag-end change, which has no position and clears the node dragging state', () => {
      const { p, send } = lockedCanvas(true);
      // The exact shape ReactFlow emits on pointer-up: updateNodePositions(items, false, false).
      const dragEnd = { type: 'position', id: 'n1', dragging: false };
      send([{ type: 'position', id: 'n1', position: { x: 40, y: 80 }, dragging: true }, dragEnd]);
      expect(p.onNodesChange).toHaveBeenCalledWith([dragEnd]);
    });

    it('keeps the measured-layout correction, a position change without the dragging flag', () => {
      const { p, send } = lockedCanvas(false);
      const correction = { type: 'position', id: 'n1', position: { x: 0, y: 320 } };
      send([correction]);
      expect(p.onNodesChange).toHaveBeenCalledWith([correction]);
    });

    it('keeps measurements, which ReactFlow needs to paint the node', () => {
      const { p, send } = lockedCanvas(true);
      const measure = { type: 'dimensions', id: 'n1', dimensions: { width: 200, height: 80 } };
      send([measure]);
      expect(p.onNodesChange).toHaveBeenCalledWith([measure]);
    });

    it('still strips removals in run mode', () => {
      const { p, send } = lockedCanvas(true);
      const measure = { type: 'dimensions', id: 'n1', dimensions: { width: 200, height: 80 } };
      send([{ type: 'remove', id: 'n1' }, measure]);
      expect(p.onNodesChange).toHaveBeenCalledWith([measure]);
    });

    it('lets drag moves through again once unlocked', () => {
      const { p, send } = lockedCanvas(true);
      act(() => toolbarProps!.onToggleInteractivity());
      const move = { type: 'position', id: 'n1', position: { x: 40, y: 80 }, dragging: true };
      send([move]);
      expect(p.onNodesChange).toHaveBeenCalledWith([move]);
    });
  });
});
