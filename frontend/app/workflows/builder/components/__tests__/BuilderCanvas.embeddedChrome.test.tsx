// @vitest-environment jsdom
/**
 * Regression specs for two side-panel canvas bugs (2026-06-11):
 *
 * 1. Empty-canvas AI composer ("How can I help you…?" + textarea/trigger)
 *    leaked into EMBEDDED canvases (SidePanel workflow tabs on chat pages)
 *    while an agent was initializing a plan. It must render ONLY on the
 *    workflow's own /app/workflow/<id> page.
 *
 * 2. ReactFlow <Background> pattern-id collision: every canvas mounted
 *    without an explicit id shares `pattern-1`, so with several canvases in
 *    the DOM (keepMounted SidePanel tabs) the dots grid of the active canvas
 *    was painted with ANOTHER instance's viewport transform and visually
 *    disappeared ("edit mode with the run-mode background"). Each
 *    BuilderCanvas must hand <Background> a unique, SVG-safe id.
 */
import React from 'react';
import { act } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, within, waitFor } from '@testing-library/react';

// --- Configurable state, driven per-test ---
let mockMode: { isRunMode: boolean; isPreviewOnly: boolean };
let mockPathname: string;

vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('@/components/chat/SimpleToast', () => ({
  SimpleToast: () => null,
  useSimpleToast: () => ({ toast: null, showToast: vi.fn(), hideToast: vi.fn() }),
}));

// reactflow: capture Background props; render Panel children so the
// empty-canvas chat (mounted inside <Panel>) is reachable.
vi.mock('reactflow', () => ({
  default: ({ children }: { children?: React.ReactNode }) => <div data-testid="react-flow">{children}</div>,
  Background: ({ id }: { id?: string }) => <div data-testid="rf-background" data-bg-id={id ?? ''} />,
  BackgroundVariant: { Dots: 'dots', Lines: 'lines', Cross: 'cross' },
  Panel: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  ReactFlowProvider: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  ConnectionMode: { Loose: 'loose', Strict: 'strict' },
  getBezierPath: () => ['', 0, 0, 0, 0],
  getSmoothStepPath: () => ['', 0, 0, 0, 0],
  useUpdateNodeInternals: () => () => {},
}));

// Heavy graph/builder internals - not under test.
vi.mock('../../constants/graphTypes', () => ({ nodeTypes: {}, edgeTypes: {} }));
vi.mock('../../contexts/ValidationContext', () => ({ useValidationOptional: () => null }));
vi.mock('../../utils/workflowPlanGenerator', () => ({ generateWorkflowPlan: vi.fn() }));
vi.mock('../../utils/connectionValidator', () => ({ validateConnection: () => true }));
vi.mock('../../services/LayoutService', () => ({
  applyDagreLayout: (n: unknown) => n,
  layoutConfigForDirection: () => ({}),
}));
vi.mock('../../services/nodeMatcher', () => ({ nodeMatchesStep: () => false }));
vi.mock('../../registry/nodeRegistry', () => ({
  nodeRegistry: new Proxy({}, { get: () => () => false }),
}));
vi.mock('../../nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
vi.mock('../nodes/shared', () => ({ NodeIcon: () => null, getIconSlug: () => '' }));
vi.mock('../HoverEdgeManager', () => ({ HoverEdgeManager: () => null }));
vi.mock('../CanvasToolbar', () => ({ CanvasToolbar: () => null }));
vi.mock('../CanvasSettingsPanel', () => ({ CanvasSettingsPanel: () => null }));
vi.mock('../EmptyCanvasChat', () => ({
  EmptyCanvasChat: () => <div data-testid="empty-canvas-chat" />,
}));

// Builder hooks - stubbed to their idle shapes.
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
    handleToggleBoxSelection: vi.fn(),
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

// jsdom lacks ResizeObserver (used for inspector bounds tracking)
beforeEach(() => {
  (globalThis as any).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  mockMode = { isRunMode: false, isPreviewOnly: false };
  mockPathname = '/app/workflow/wf-1';
});

const baseProps = () => ({
  nodes: [] as any[],
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
});

describe('BuilderCanvas - empty-canvas chat is page-only (not embedded)', () => {
  it('shows the empty-canvas chat on the workflow own page', () => {
    mockPathname = '/app/workflow/wf-1';
    const { queryAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    // Rendered twice (desktop top panel + mobile bottom panel)
    expect(queryAllByTestId('empty-canvas-chat').length).toBeGreaterThan(0);
  });

  it('shows the empty-canvas chat on a locale-prefixed workflow page', () => {
    mockPathname = '/fr/app/workflow/wf-1';
    const { queryAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    expect(queryAllByTestId('empty-canvas-chat').length).toBeGreaterThan(0);
  });

  it('hides the empty-canvas chat when embedded in a chat-page side panel', () => {
    mockPathname = '/app/c/conv-42';
    const { queryAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    expect(queryAllByTestId('empty-canvas-chat')).toHaveLength(0);
  });

  it('hides the empty-canvas chat for a sub-workflow embedded on another workflow page', () => {
    mockPathname = '/app/workflow/parent-wf';
    const { queryAllByTestId } = render(<BuilderCanvas {...baseProps()} workflowId="child-wf" />);
    expect(queryAllByTestId('empty-canvas-chat')).toHaveLength(0);
  });

  it('still hides the empty-canvas chat in run mode on the workflow own page', () => {
    mockMode = { isRunMode: true, isPreviewOnly: false };
    mockPathname = '/app/workflow/wf-1';
    const { queryAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    expect(queryAllByTestId('empty-canvas-chat')).toHaveLength(0);
  });
});

describe('BuilderCanvas - unique Background pattern id per instance', () => {
  it('passes a non-empty, SVG-safe id to <Background>', () => {
    const { getByTestId } = render(<BuilderCanvas {...baseProps()} />);
    const bgId = getByTestId('rf-background').getAttribute('data-bg-id');
    expect(bgId).toBeTruthy();
    // useId raw output contains ":" which breaks SVG IRI fragment refs
    expect(bgId).toMatch(/^[a-zA-Z0-9_-]+$/);
  });

  it('two simultaneously mounted canvases get DISTINCT pattern ids', () => {
    const first = render(<BuilderCanvas {...baseProps()} />);
    const second = render(<BuilderCanvas {...baseProps()} workflowId="wf-2" />);
    const id1 = within(first.container).getByTestId('rf-background').getAttribute('data-bg-id');
    const id2 = within(second.container).getByTestId('rf-background').getAttribute('data-bg-id');
    expect(id1).toBeTruthy();
    expect(id2).toBeTruthy();
    expect(id1).not.toBe(id2);
  });

  it('renders no Background at all in run mode (vignette canvas)', () => {
    mockMode = { isRunMode: true, isPreviewOnly: false };
    const { queryAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    expect(queryAllByTestId('rf-background')).toHaveLength(0);
  });
});

describe('BuilderCanvas - auto-layout after a hover-"+" insert', () => {
  beforeEach(() => {
    mockMode = { isRunMode: false, isPreviewOnly: false };
  });

  it('re-flows the graph when a hoverPlusNodeInserted event fires', async () => {
    // The "+" add path dispatches `hoverPlusNodeInserted`; the canvas listens and
    // runs auto-layout (onForceNodesUpdate). applyDagreLayout is mocked to identity,
    // so a call to onForceNodesUpdate with the nodes proves the wiring.
    const onForceNodesUpdate = vi.fn();
    const nodes = [{ id: 'a', position: { x: 0, y: 0 }, data: {} }] as any[];
    render(
      <BuilderCanvas
        {...baseProps()}
        nodes={nodes}
        onForceNodesUpdate={onForceNodesUpdate}
      />,
    );
    expect(onForceNodesUpdate).not.toHaveBeenCalled();

    await act(async () => {
      window.dispatchEvent(new CustomEvent('hoverPlusNodeInserted'));
    });
    // The listener defers by 130ms so the new node/edge settle first.
    await waitFor(() => expect(onForceNodesUpdate).toHaveBeenCalledWith(nodes), { timeout: 1000 });
  });

  it('does NOT re-flow on an unrelated event (only the "+" insert triggers it)', async () => {
    const onForceNodesUpdate = vi.fn();
    const nodes = [{ id: 'a', position: { x: 0, y: 0 }, data: {} }] as any[];
    render(<BuilderCanvas {...baseProps()} nodes={nodes} onForceNodesUpdate={onForceNodesUpdate} />);

    await act(async () => {
      // A plain node-created event (fires on cancel and toolbox adds too) must NOT
      // auto-layout - only the dedicated hover-"+" signal does.
      window.dispatchEvent(new CustomEvent('workflowNodeCreated'));
    });
    await new Promise((r) => setTimeout(r, 250));
    expect(onForceNodesUpdate).not.toHaveBeenCalled();
  });
});
