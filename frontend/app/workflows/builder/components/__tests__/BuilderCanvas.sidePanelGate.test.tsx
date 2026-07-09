// @vitest-environment jsdom
/**
 * Empty-canvas hero is HIDDEN while the right side panel is open.
 *
 * On the workflow's own /app/workflow/<id> page the empty-canvas hero (the
 * "How can I help you?" composer overlay) renders when the canvas is empty. When
 * the user opens the right side panel, its AI Chat tab shows the (now centered)
 * composer, so the canvas hero would be a duplicate - BuilderCanvas hides it via
 * the `!isSidePanelOpen` gate. Panel closed -> hero visible; panel open -> hidden.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockMode: { isRunMode: boolean; isPreviewOnly: boolean };
let mockPathname: string;
// null => outside a SidePanelProvider (useSidePanelSafe returns null).
let mockSidePanelValue: { isOpen: boolean } | null;

vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => mockSidePanelValue,
}));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('@/components/chat/SimpleToast', () => ({
  SimpleToast: () => null,
  useSimpleToast: () => ({ toast: null, showToast: vi.fn(), hideToast: vi.fn() }),
}));

vi.mock('reactflow', () => ({
  default: ({ children }: { children?: React.ReactNode }) => <div data-testid="react-flow">{children}</div>,
  Background: ({ id }: { id?: string }) => <div data-testid="rf-background" data-bg-id={id ?? ''} />,
  BackgroundVariant: { Dots: 'dots', Lines: 'lines', Cross: 'cross' },
  Panel: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  ReactFlowProvider: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  ConnectionMode: { Loose: 'loose', Strict: 'strict' },
  getBezierPath: () => ['', 0, 0, 0, 0],
  getSmoothStepPath: () => ['', 0, 0, 0, 0],
}));

vi.mock('../../constants/graphTypes', () => ({ nodeTypes: {}, edgeTypes: {} }));
vi.mock('../../contexts/ValidationContext', () => ({ useValidationOptional: () => null }));
vi.mock('../../utils/workflowPlanGenerator', () => ({ generateWorkflowPlan: vi.fn() }));
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
vi.mock('../EmptyCanvasChat', () => ({
  EmptyCanvasChat: () => <div data-testid="empty-canvas-chat" />,
}));

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

beforeEach(() => {
  (globalThis as any).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  mockMode = { isRunMode: false, isPreviewOnly: false };
  mockPathname = '/app/workflow/wf-1';
  mockSidePanelValue = { isOpen: false };
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

describe('BuilderCanvas - empty-canvas hero hidden while the right side panel is open', () => {
  it('shows the hero on the workflow page when the side panel is CLOSED', () => {
    mockSidePanelValue = { isOpen: false };
    const { queryAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    expect(queryAllByTestId('empty-canvas-chat').length).toBeGreaterThan(0);
  });

  it('hides the hero on the workflow page when the side panel is OPEN', () => {
    mockSidePanelValue = { isOpen: true };
    const { queryAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    expect(queryAllByTestId('empty-canvas-chat')).toHaveLength(0);
  });

  it('shows the hero when rendered outside a SidePanelProvider (useSidePanelSafe returns null)', () => {
    mockSidePanelValue = null; // `?? false` defensive path -> treated as closed
    const { queryAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    expect(queryAllByTestId('empty-canvas-chat').length).toBeGreaterThan(0);
  });
});
