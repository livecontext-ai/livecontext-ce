// @vitest-environment jsdom
/**
 * A node cannot be moved on a canvas that cannot save the move.
 *
 * In run mode (and in a read-only preview) the canvas has no Save, yet it let nodes
 * be dragged: the move looked done, and switching back to edit mode silently put the
 * node back where it was saved (reproduced live 2026-09-23: y=257 back to 153), with
 * Save disabled because nothing had changed. Dragging is locked wherever the canvas is.
 *
 * Mock scaffolding mirrors BuilderCanvas.saveScope.test.tsx.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

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
vi.mock('../CanvasToolbar', () => ({ CanvasToolbar: () => null }));
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

  it('regression: locks dragging in run mode, where no Save exists and the move was silently lost', () => {
    mockMode = { isRunMode: true, isPreviewOnly: false };
    render(<BuilderCanvas {...props()} />);
    expect(reactFlowProps.nodesDraggable).toBe(false);
  });

  it('locks dragging in a read-only preview too', () => {
    mockMode = { isRunMode: false, isPreviewOnly: true };
    render(<BuilderCanvas {...props()} />);
    expect(reactFlowProps.nodesDraggable).toBe(false);
  });
});
