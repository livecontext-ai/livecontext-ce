// @vitest-environment jsdom
/**
 * Regression (audit 2026-07-17): the header "Save" button / pin-button path
 * (the `workflowViewSave` event, handled ONLY by BuilderCanvas.handleSaveEvent)
 * generated the plan WITHOUT the active reading direction, so a workflow that was
 * toggled to vertical then Save'd (never run, never renamed) persisted with NO
 * `layoutDirection` and reloaded horizontal. The three other persist paths
 * (save-before-run, rename) were threaded; this most-used one was missed.
 *
 * The Save handler must now stamp the active direction into `generateWorkflowPlan`
 * so the header Save persists the workflow's rendering identity.
 *
 * Mock scaffolding mirrors BuilderCanvas.loadError.test.tsx.
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
  mockDirection = 'horizontal';
  generateWorkflowPlan.mockClear();
});

const baseProps = (onSaveWorkflow: any) => ({
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
  onSaveWorkflow,
});

async function fireSave() {
  await act(async () => {
    window.dispatchEvent(new CustomEvent('workflowViewSave'));
    // let the async handler settle
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('BuilderCanvas - header Save persists layoutDirection', () => {
  it('stamps the active vertical direction into the generated plan on workflowViewSave', async () => {
    mockDirection = 'vertical';
    const onSaveWorkflow = vi.fn().mockResolvedValue(undefined);
    render(<BuilderCanvas {...baseProps(onSaveWorkflow)} />);

    await fireSave();

    expect(generateWorkflowPlan).toHaveBeenCalledTimes(1);
    // 3rd arg is the direction - pre-fix it was omitted (undefined).
    expect((generateWorkflowPlan.mock.calls[0] as unknown[])[2]).toBe('vertical');
    expect(onSaveWorkflow).toHaveBeenCalledWith('wf-1', { mocked: 'plan' });
  });

  it('stamps horizontal when that is the active direction', async () => {
    mockDirection = 'horizontal';
    const onSaveWorkflow = vi.fn().mockResolvedValue(undefined);
    render(<BuilderCanvas {...baseProps(onSaveWorkflow)} />);

    await fireSave();

    expect((generateWorkflowPlan.mock.calls[0] as unknown[])[2]).toBe('horizontal');
  });
});
