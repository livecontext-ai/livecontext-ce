// @vitest-environment jsdom
/**
 * The consumer half of camera follow, rendered for real.
 *
 * This exists because the producer being unit-tested proves nothing about the feature:
 * an audit deleted the whole listener from BuilderCanvas and 4,597 tests across 272
 * files stayed green, with the toggle still reading as pressed and the camera never
 * moving. What is pinned here is the wiring itself: that the canvas listens for the
 * shared event name, that it frames the nodes it was given, that it uses the declared
 * travel duration, and that it ignores an event belonging to another canvas.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';
import {
  WORKFLOW_FOLLOW_NODES_EVENT,
  FOLLOW_TRANSITION_MS,
} from '../../services/runFollowEvent';

let mockMode: { isRunMode: boolean; isPreviewOnly: boolean };
const fitBounds = vi.fn();

vi.mock('next/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('@/components/chat/SimpleToast', () => ({
  SimpleToast: () => null,
  useSimpleToast: () => ({ toast: null, showToast: vi.fn(), hideToast: vi.fn() }),
}));

// The canvas takes its ReactFlow instance from onInit, so the mock hands one over as
// soon as it mounts. `fitBounds` is the call under test.
vi.mock('reactflow', () => ({
  default: ({ children, onInit }: { children?: React.ReactNode; onInit?: (i: unknown) => void }) => {
    // ONCE. onInit sets state in the canvas, which re-renders and hands back a new
    // callback identity; re-firing on that would spin forever and kill the worker.
    const fired = React.useRef(false);
    React.useEffect(() => {
      if (fired.current) return;
      fired.current = true;
      onInit?.({ fitBounds, setCenter: vi.fn(), getZoom: () => 1, fitView: vi.fn() });
    });
    return <div data-testid="react-flow">{children}</div>;
  },
  Background: () => <div data-testid="rf-background" />,
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
vi.mock('../../services/LayoutService', () => ({
  applyDagreLayout: (n: unknown) => n,
  layoutConfigForDirection: () => ({}),
}));
vi.mock('../../registry/nodeRegistry', () => ({
  nodeRegistry: new Proxy({}, { get: () => () => false }),
}));
vi.mock('../../nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
vi.mock('../nodes/shared', () => ({ NodeIcon: () => null, getIconSlug: () => '' }));
vi.mock('../HoverEdgeManager', () => ({ HoverEdgeManager: () => null }));
vi.mock('../CanvasToolbar', () => ({ CanvasToolbar: () => null }));
vi.mock('../CanvasSettingsPanel', () => ({ CanvasSettingsPanel: () => null }));
vi.mock('../EmptyCanvasChat', () => ({ EmptyCanvasChat: () => null }));
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

const node = (id: string, x: number, y: number) => ({
  id,
  position: { x, y },
  width: 200,
  height: 60,
  data: { label: id },
});

beforeEach(() => {
  (globalThis as any).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  mockMode = { isRunMode: true, isPreviewOnly: false };
  fitBounds.mockClear();
});

const baseProps = () => ({
  nodes: [node('n-a', 0, 0), node('n-b', 600, 0)] as any[],
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

function follow(detail: { workflowId?: string; nodeIds: string[] }) {
  act(() => {
    window.dispatchEvent(new CustomEvent(WORKFLOW_FOLLOW_NODES_EVENT, { detail }));
  });
}

describe('BuilderCanvas camera follow', () => {
  it('moves the camera to the nodes the event names', () => {
    render(<BuilderCanvas {...baseProps()} />);
    follow({ workflowId: 'wf-1', nodeIds: ['n-a'] });

    expect(fitBounds).toHaveBeenCalledTimes(1);
    const [rect, options] = fitBounds.mock.calls[0];
    // Centred on n-a (0,0 to 200,60), padded on every side.
    expect(rect.x).toBeLessThan(0);
    expect(rect.y).toBeLessThan(0);
    expect(rect.x + rect.width).toBeGreaterThan(200);
    expect(rect.y + rect.height).toBeGreaterThan(60);
    // The declared travel time is the "smooth transition" requirement.
    expect(options).toMatchObject({ duration: FOLLOW_TRANSITION_MS });
  });

  it('spans every named node, so a fan-out is framed whole', () => {
    render(<BuilderCanvas {...baseProps()} />);
    follow({ workflowId: 'wf-1', nodeIds: ['n-a', 'n-b'] });

    const [rect] = fitBounds.mock.calls[0];
    expect(rect.x + rect.width).toBeGreaterThan(800); // reaches past n-b
  });


  it('frames nodes that arrived AFTER it mounted', () => {
    // The headline scenario: opening a run link mounts the canvas with no nodes and the
    // plan lands afterwards. The listener is registered once, so it has to read the
    // CURRENT nodes rather than the ones captured when it was registered, or it frames
    // an empty list for the rest of the run, silently.
    const { rerender } = render(<BuilderCanvas {...baseProps()} nodes={[]} />);
    rerender(<BuilderCanvas {...baseProps()} />);
    follow({ workflowId: 'wf-1', nodeIds: ['n-a'] });
    expect(fitBounds, 'the listener was still looking at the nodes it mounted with').toHaveBeenCalledTimes(1);
  });

  it('frames a move that arrived BEFORE its nodes rendered, once they do', () => {
    // An agent's plan sync is imported inside a transition, so the move for the nodes it
    // just added can land before those nodes commit. Dropping it would leave the build
    // unfollowed with the toggle reading as on.
    const { rerender } = render(<BuilderCanvas {...baseProps()} nodes={[]} />);
    follow({ workflowId: 'wf-1', nodeIds: ['n-a'] });
    expect(fitBounds).not.toHaveBeenCalled();

    rerender(<BuilderCanvas {...baseProps()} />);
    expect(fitBounds).toHaveBeenCalledTimes(1);
    // Applied once, not on every later render.
    rerender(<BuilderCanvas {...baseProps()} nodes={[node('n-a', 50, 0), node('n-b', 600, 0)] as any[]} />);
    expect(fitBounds).toHaveBeenCalledTimes(1);
  });

  it('drops a move whose nodes never came in time, rather than yanking the camera later', () => {
    const now = vi.spyOn(performance, 'now').mockReturnValue(1_000);
    try {
      const { rerender } = render(<BuilderCanvas {...baseProps()} nodes={[]} />);
      follow({ workflowId: 'wf-1', nodeIds: ['n-a'] });
      now.mockReturnValue(1_000 + 5_000);
      rerender(<BuilderCanvas {...baseProps()} />);
      expect(fitBounds).not.toHaveBeenCalled();
    } finally {
      now.mockRestore();
    }
  });

  it('never keeps a move meant for another canvas waiting for nodes', () => {
    const { rerender } = render(<BuilderCanvas {...baseProps()} nodes={[]} />);
    follow({ workflowId: 'another-wf', nodeIds: ['n-a'] });
    rerender(<BuilderCanvas {...baseProps()} />);
    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('follows a node to its new position after a re-layout', () => {
    const { rerender } = render(<BuilderCanvas {...baseProps()} />);
    follow({ workflowId: 'wf-1', nodeIds: ['n-a'] });
    const [first] = fitBounds.mock.calls[0];

    rerender(<BuilderCanvas {...baseProps()} nodes={[node('n-a', 4000, 0), node('n-b', 4600, 0)] as any[]} />);
    follow({ workflowId: 'wf-1', nodeIds: ['n-a'] });
    const [second] = fitBounds.mock.calls[1];

    expect(second.x, 'the camera framed the old position').toBeGreaterThan(first.x + 3000);
  });

  it('never selects a node, which would swap the panel to the inspector', () => {
    const props = baseProps();
    render(<BuilderCanvas {...props} />);
    follow({ workflowId: 'wf-1', nodeIds: ['n-a'] });
    expect(props.onSelectionChange).not.toHaveBeenCalled();
  });

  it('ignores a move meant for another canvas', () => {
    render(<BuilderCanvas {...baseProps()} />);
    follow({ workflowId: 'another-wf', nodeIds: ['n-a'] });
    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('ignores a move naming nodes it does not have', () => {
    render(<BuilderCanvas {...baseProps()} />);
    follow({ workflowId: 'wf-1', nodeIds: ['not-here'] });
    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('stops listening once unmounted', () => {
    const { unmount } = render(<BuilderCanvas {...baseProps()} />);
    unmount();
    follow({ workflowId: 'wf-1', nodeIds: ['n-a'] });
    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('survives a camera move that throws, rather than taking the canvas down', () => {
    // jsdom hands an exception thrown inside a listener to the GLOBAL error channel
    // instead of propagating it out of dispatchEvent, so `expect(...).not.toThrow()`
    // passes against completely unprotected code. Watch the channel the error would
    // actually reach.
    const onError = vi.fn();
    window.addEventListener('error', onError);
    fitBounds.mockImplementationOnce(() => {
      throw new Error('viewport gone');
    });
    render(<BuilderCanvas {...baseProps()} />);
    follow({ workflowId: 'wf-1', nodeIds: ['n-a'] });
    window.removeEventListener('error', onError);

    expect(onError, 'the camera move took the canvas down with it').not.toHaveBeenCalled();
    // And the canvas keeps working afterwards.
    fitBounds.mockClear();
    follow({ workflowId: 'wf-1', nodeIds: ['n-b'] });
    expect(fitBounds).toHaveBeenCalledTimes(1);
  });
});
