// @vitest-environment jsdom
/**
 * Regression: in a brand-new (empty) workflow, the full-width "How can I help
 * you?" composer Panel (z-50) overlaid the top-right "Add node" toolbox button
 * (z-10) and swallowed its clicks - the toolbox was unreachable. Fix: the
 * empty-chat Panels are pointer-events:none (only the composer column re-enables
 * them, see EmptyCanvasChat.test) and the Add-node button is lifted to z-[60].
 *
 * Mock scaffolding mirrors BuilderCanvas.loadError.test.tsx, except the reactflow
 * Panel mock PRESERVES style/className so the pointer-events fix is observable.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockMode: { isRunMode: boolean; isPreviewOnly: boolean };
let mockPathname: string;

vi.mock('next/navigation', () => ({ usePathname: () => mockPathname }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('@/components/ThemeProvider', () => ({ useTheme: () => ({ theme: 'light' }) }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div data-testid="loading-spinner" /> }));
vi.mock('@/components/chat/SimpleToast', () => ({
  SimpleToast: () => null,
  useSimpleToast: () => ({ toast: null, showToast: vi.fn(), hideToast: vi.fn() }),
}));

vi.mock('reactflow', () => ({
  default: ({ children }: { children?: React.ReactNode }) => <div data-testid="react-flow">{children}</div>,
  Background: () => null,
  BackgroundVariant: { Dots: 'dots', Lines: 'lines', Cross: 'cross' },
  // PRESERVE style + className so we can assert pointer-events:none on the panel
  Panel: ({ children, style, className }: { children?: React.ReactNode; style?: React.CSSProperties; className?: string }) =>
    <div data-testid="rf-panel" style={style} className={className}>{children}</div>,
  ReactFlowProvider: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  ConnectionMode: { Loose: 'loose', Strict: 'strict' },
  getBezierPath: () => ['', 0, 0, 0, 0],
  getSmoothStepPath: () => ['', 0, 0, 0, 0],
  useUpdateNodeInternals: () => () => {},
}));

vi.mock('../../constants/graphTypes', () => ({ nodeTypes: {}, edgeTypes: {} }));
vi.mock('../../contexts/ValidationContext', () => ({ useValidationOptional: () => null }));
vi.mock('../../utils/workflowPlanGenerator', () => ({ generateWorkflowPlan: vi.fn() }));
vi.mock('../../utils/connectionValidator', () => ({ validateConnection: () => true }));
vi.mock('../../services/LayoutService', () => ({ applyDagreLayout: (n: unknown) => n }));
vi.mock('../../services/nodeMatcher', () => ({ nodeMatchesStep: () => false }));
vi.mock('../../registry/nodeRegistry', () => ({ nodeRegistry: new Proxy({}, { get: () => () => false }) }));
vi.mock('../../nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
vi.mock('../nodes/shared', () => ({ NodeIcon: () => null, getIconSlug: () => '' }));
vi.mock('../HoverEdgeManager', () => ({ HoverEdgeManager: () => null }));
vi.mock('../CanvasToolbar', () => ({ CanvasToolbar: () => null }));
vi.mock('../CanvasSettingsPanel', () => ({ CanvasSettingsPanel: () => null }));
vi.mock('../EmptyCanvasChat', () => ({ EmptyCanvasChat: () => <div data-testid="empty-canvas-chat" /> }));

vi.mock('../../hooks/useCanvasViewport', () => ({
  useCanvasViewport: () => ({
    isViewReady: true, handleInstanceInit: vi.fn(), handleZoomIn: vi.fn(), handleZoomOut: vi.fn(), handleFitView: vi.fn(),
  }),
}));
vi.mock('../../hooks/useInspectorDrag', () => ({
  useInspectorDrag: () => ({ position: { x: 16, y: 16 }, handleDragStart: vi.fn() }),
}));
vi.mock('../../hooks/useBoxSelection', () => ({
  useBoxSelection: () => ({
    isBoxSelectionEnabled: false, isSelecting: false, selectionStart: null, selectionEnd: null,
    handleToggleBoxSelection: vi.fn(), handleSelectionChange: vi.fn(),
    containerRef: { current: null }, selectionJustEndedRef: { current: false },
  }),
}));
vi.mock('../../hooks/useTypingSuggestion', () => ({
  useTypingSuggestion: () => ({ typingSuggestionId: null, chatInput: '', handleSuggestionClick: vi.fn(), handleChatInputChange: vi.fn() }),
}));
vi.mock('../../constants/workflowSuggestions', () => ({ getDisplayedSuggestions: () => [] }));

import { BuilderCanvas } from '../BuilderCanvas';

beforeEach(() => {
  (globalThis as any).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
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
  // edit mode: providing this renders the floating "Add node" toolbox button
  onOpenNodeCreator: vi.fn(),
});

describe('BuilderCanvas - empty-canvas toolbox reachability', () => {
  it('renders the empty-chat composer Panels with pointer-events:none', () => {
    const { getAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    const composerPanels = getAllByTestId('rf-panel').filter(
      (p) => p.querySelector('[data-testid="empty-canvas-chat"]') !== null
    );
    expect(composerPanels.length).toBeGreaterThan(0);
    for (const panel of composerPanels) {
      expect(panel.style.pointerEvents).toBe('none');
    }
  });

  it('lifts the Add-node toolbox button container above the composer (z-[60])', () => {
    const { container } = render(<BuilderCanvas {...baseProps()} />);
    const lifted = container.querySelector('.z-\\[60\\]');
    expect(lifted, 'add-node button container should use z-[60], not z-10').toBeTruthy();
    // it must actually wrap the clickable Add-node button
    expect(lifted!.querySelector('button')).toBeTruthy();
  });
});
