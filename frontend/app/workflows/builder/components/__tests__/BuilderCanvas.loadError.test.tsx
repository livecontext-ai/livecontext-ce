// @vitest-environment jsdom
/**
 * Regression (2026-06-12 prod): a failed plan fetch left the builder as a
 * SILENT empty canvas with a permanently disabled Save button. BuilderCanvas
 * now renders an explicit error overlay (title + message + Retry) when
 * `loadError` is set, and the Retry button re-triggers the load.
 *
 * Mock scaffolding mirrors BuilderCanvas.embeddedChrome.test.tsx.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent } from '@testing-library/react';

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

describe('BuilderCanvas - load-error overlay', () => {
  it('shows the error overlay with a Retry button when loadError is set (pre-fix: silent empty canvas)', () => {
    const onRetryLoad = vi.fn();
    const { getByText, getByRole } = render(
      <BuilderCanvas {...baseProps()} loadError onRetryLoad={onRetryLoad} />
    );
    expect(getByText('loadErrorTitle')).toBeTruthy();
    expect(getByText('loadErrorMessage')).toBeTruthy();

    fireEvent.click(getByRole('button', { name: 'loadErrorRetry' }));
    expect(onRetryLoad).toHaveBeenCalledTimes(1);
  });

  it('does not show the overlay while a (re)load is in flight - the spinner wins', () => {
    const { queryByText, queryAllByTestId } = render(
      <BuilderCanvas {...baseProps()} loadError isLoadingWorkflow />
    );
    expect(queryByText('loadErrorTitle')).toBeNull();
    expect(queryAllByTestId('loading-spinner').length).toBeGreaterThan(0);
  });

  it('shows no overlay on a healthy canvas', () => {
    const { queryByText } = render(<BuilderCanvas {...baseProps()} />);
    expect(queryByText('loadErrorTitle')).toBeNull();
  });
});
