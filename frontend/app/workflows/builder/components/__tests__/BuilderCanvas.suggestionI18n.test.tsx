// @vitest-environment jsdom
/**
 * Pins the i18n WIRING in BuilderCanvas: getDisplayedSuggestions returns metadata
 * only (id/icon/triggerType); BuilderCanvas must resolve the user-facing label AND
 * prompt from i18n (keyed by id) before handing them to EmptyCanvasChat. Here the
 * EmptyCanvasChat mock surfaces the resolved label/prompt so we can assert they
 * came from t(`suggestions.<id>.label|prompt`), not from a hardcoded constant.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockMode: { isRunMode: boolean; isPreviewOnly: boolean };
let mockPathname: string;

vi.mock('next/navigation', () => ({ usePathname: () => mockPathname }));
// translations resolve to the key path, so the assertion proves "went through i18n"
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
  Panel: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
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
// surface the resolved label/prompt that BuilderCanvas passes down
vi.mock('../EmptyCanvasChat', () => ({
  EmptyCanvasChat: ({ displayedSuggestions }: any) => (
    <div data-testid="empty-canvas-chat">
      {displayedSuggestions.map((s: any) => (
        <div key={s.id}>
          <span data-testid="sug-label">{s.label}</span>
          <span data-testid="sug-prompt">{s.prompt}</span>
        </div>
      ))}
    </div>
  ),
}));
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
// metadata only - exactly what the real getDisplayedSuggestions now returns
vi.mock('../../constants/workflowSuggestions', () => ({
  getDisplayedSuggestions: () => [{ id: 'flight-deals', icon: () => null, triggerType: 'schedule' }],
}));

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
});

describe('BuilderCanvas - suggestion label/prompt resolved from i18n', () => {
  it('resolves chip label and prompt via t(`suggestions.<id>.{label,prompt}`)', () => {
    // BuilderCanvas renders EmptyCanvasChat for both the desktop (top) and mobile
    // (bottom) panels, so each testid appears twice - both must carry the i18n value.
    const { getAllByTestId } = render(<BuilderCanvas {...baseProps()} />);
    const labels = getAllByTestId('sug-label');
    const prompts = getAllByTestId('sug-prompt');
    expect(labels.length).toBeGreaterThan(0);
    labels.forEach((el) => expect(el.textContent).toBe('suggestions.flight-deals.label'));
    prompts.forEach((el) => expect(el.textContent).toBe('suggestions.flight-deals.prompt'));
  });
});
