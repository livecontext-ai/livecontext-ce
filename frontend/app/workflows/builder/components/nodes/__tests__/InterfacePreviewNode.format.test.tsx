// @vitest-environment jsdom
/**
 * InterfacePreviewNode: format-exact display.
 *
 * Regressions pinned here:
 *  - the node no longer draws an always-visible border ring: idle = no ring, a status
 *    color / selection ring is painted on the InterfaceThumbnail FRAME (the box that is
 *    exactly the interface's declared format), so the ring hugs the real shape;
 *  - the node box snaps itself to the format's aspect ratio (previewWidth/Height persisted
 *    via onNodeUpdate), so the canvas node IS the format - no internal letterbox;
 *  - resizing keeps that ratio (keepAspectRatio forwarded to the NodeResizer wrapper);
 *  - placeholder states (no template yet) keep the classic bordered card.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';

let mockMode: any;
let mockInterfaceDetails: any;
let mockRenderState: any;

const execStatus = () => ({
  isStepByStepMode: false,
  isReady: false,
  canExecute: false,
  isExecuting: false,
  isRerunning: false,
  isRunning: false,
  isFailed: false,
  isSkipped: false,
  isCompleted: false,
  canRerun: false,
  executeStep: vi.fn(),
  rerunStep: vi.fn(),
  fireFromAnyEpoch: vi.fn(),
});

// Capture the props each collaborator receives.
const thumbnailProps: any[] = [];
vi.mock('../../interface/InterfaceThumbnail', () => ({
  InterfaceThumbnail: (props: any) => {
    thumbnailProps.push(props);
    return <div data-testid="thumbnail" />;
  },
}));
const resizerProps: any[] = [];
vi.mock('../ResizableNodeWrapper', () => ({
  ResizableNodeWrapper: (props: any) => {
    resizerProps.push(props);
    return null;
  },
}));

// setNodes captured per test: the run-mode snap writes the node box through it.
let setNodesMock: ReturnType<typeof vi.fn>;
// The box the ReactFlow store reports for this node - what the snap reads to
// decide whether anything needs writing. Defaults to the plan importer's
// historical 400x250, i.e. the box every never-edited interface node carries.
let storeNodeStyle: { width?: number; height?: number } | undefined;
vi.mock('reactflow', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useReactFlow: () => ({ setNodes: setNodesMock }),
  useStore: (selector: (s: any) => unknown) =>
    selector({ nodeInternals: new Map([['interface-iface-1', { id: 'interface-iface-1', style: storeNodeStyle }]]) }),
  // The verified-retry reads the CURRENT store imperatively through the store api.
  useStoreApi: () => ({
    getState: () => ({
      nodeInternals: new Map([['interface-iface-1', { id: 'interface-iface-1', style: storeNodeStyle }]]),
    }),
  }),
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));
vi.mock('../../inspector/outputs/ItemNavigator', () => ({ ItemNavigator: () => null }));
vi.mock('../../NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => null };
});
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [undefined],
}));
vi.mock('../../../hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: mockInterfaceDetails, isLoading: false }),
  useInterfaceRender: () => ({ refetch: vi.fn(), ...mockRenderState }),
}));
vi.mock('../../../utils/interfaceHtmlUtils', () => ({
  translateWithMapping: (d: any) => d,
  mergeTriggerDataIntoResolved: (d: any) => d,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('../../../contexts/StepByStepContext', () => ({
  useNodeExecutionStatus: () => execStatus(),
}));
vi.mock('../../NodePlayButton', () => ({
  NodePlayButton: () => null,
  deriveNodeStatus: () => undefined,
}));
vi.mock('../NodeBottomBar', () => ({ NodeBottomBar: () => null }));

import { InterfacePreviewNode } from '../InterfacePreviewNode';

const baseData = (over: Record<string, any> = {}) => ({
  id: 'interface-iface-1',
  label: 'My UI',
  kind: 'interface',
  onNodeUpdate: vi.fn(),
  interfaceData: {
    interfaceId: 'iface-1',
    editorExpression: '<h1>hi</h1>',
    showPreview: true,
  } as Record<string, any>,
  ...over,
});

function renderNode(data: any, selected = false) {
  return render(
    <InterfacePreviewNode data={data} selected={selected} id="interface-iface-1" {...({} as any)} />
  );
}

beforeEach(() => {
  thumbnailProps.length = 0;
  resizerProps.length = 0;
  setNodesMock = vi.fn();
  storeNodeStyle = { width: 400, height: 250 };
  mockMode = {
    isRunMode: false,
    isPreviewOnly: false,
    isApplicationMode: false,
    runId: undefined,
    viewingEpoch: null,
  };
  mockInterfaceDetails = { id: 'iface-1', htmlTemplate: '<h1>hi</h1>', dataSourceId: null };
  mockRenderState = { data: undefined, isLoading: false, error: null };
});

const lastThumb = () => thumbnailProps[thumbnailProps.length - 1];

describe('InterfacePreviewNode ring policy (no default ring, ring hugs the format frame)', () => {
  it('idle: NO border ring on the node and NO ring on the frame', () => {
    const { container } = renderNode(baseData());
    const root = container.firstChild as HTMLElement;
    expect(root.className).not.toContain('border-2');
    expect(root.style.borderColor).toBe('');
    expect(lastThumb().frameStyle).toBeUndefined();
    // The frame still rounds + clips the content to the real format.
    expect(lastThumb().frameClassName).toContain('rounded-xl');
    expect(lastThumb().frameClassName).toContain('overflow-hidden');
  });

  it('running: the status ring is painted on the FORMAT FRAME, not on the node rectangle', () => {
    renderNode(baseData({ status: 'running' }));
    expect(lastThumb().frameStyle?.boxShadow).toContain('#3b82f6');
  });

  it('rings are INSET - an outward shadow would be clipped by the overflow-hidden ancestors that are frame-sized once the box is snapped', () => {
    renderNode(baseData({ status: 'running' }), true);
    const ring = lastThumb().frameStyle?.boxShadow as string;
    for (const part of ring.split(',')) {
      expect(part.trim().startsWith('inset')).toBe(true);
    }
  });

  it('selected idle: the selection ring hugs the frame too', () => {
    renderNode(baseData(), true);
    expect(lastThumb().frameStyle?.boxShadow).toContain('var(--accent-primary)');
  });

  it('selected + status: both rings stack on the frame (status innermost band)', () => {
    renderNode(baseData({ status: 'failed' }), true);
    const ring = lastThumb().frameStyle?.boxShadow as string;
    expect(ring).toContain('#ef4444');
    expect(ring).toContain('var(--accent-primary)');
    expect(ring.indexOf('#ef4444')).toBeLessThan(ring.indexOf('var(--accent-primary)'));
  });

  it('placeholder (no template): keeps the classic bordered card so the node stays visible', () => {
    mockInterfaceDetails = null;
    const data = baseData();
    data.interfaceData = { ...data.interfaceData, editorExpression: '' };
    const { container } = renderNode(data);
    const root = container.firstChild as HTMLElement;
    expect(root.className).toContain('border-2');
  });

  it('run-mode LOADING keeps the bordered card - the spinner must not float chrome-less on the canvas', () => {
    mockMode.isRunMode = true;
    mockRenderState = { data: undefined, isLoading: true, error: null };
    const data = baseData();
    data.interfaceData = { ...data.interfaceData, editorExpression: '' };
    const { container } = renderNode(data);
    const root = container.firstChild as HTMLElement;
    expect(root.className).toContain('border-2');
  });
});

describe('InterfacePreviewNode format snap (the node box IS the format)', () => {
  it('snaps a fresh node to the declared format ratio (vertical -> 225x400)', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    const data = baseData();
    renderNode(data);
    expect(data.onNodeUpdate).toHaveBeenCalledWith(
      expect.objectContaining({
        interfaceData: expect.objectContaining({ previewWidth: 225, previewHeight: 400 }),
      })
    );
  });

  it('no declared format snaps to the classic 1280x800 ratio (400x250)', () => {
    const data = baseData();
    renderNode(data);
    expect(data.onNodeUpdate).toHaveBeenCalledWith(
      expect.objectContaining({
        interfaceData: expect.objectContaining({ previewWidth: 400, previewHeight: 250 }),
      })
    );
  });

  it('does NOT re-snap when the box already matches the format (no dirty-loop)', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    const data = baseData();
    data.interfaceData = { ...data.interfaceData, previewWidth: 225, previewHeight: 400 };
    renderNode(data);
    expect(data.onNodeUpdate).not.toHaveBeenCalled();
  });

  it('yields to the template load: no snap while the DB template has not landed locally', () => {
    // Fresh node: empty local template + DB template pending. The snap effect must not
    // fire an onNodeUpdate in the same commit as the template-load update - both spread
    // the same stale data, so the later call would wholesale-drop editorExpression.
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    const data = baseData();
    data.interfaceData = { ...data.interfaceData, editorExpression: '' };
    renderNode(data);
    const snapCalls = (data.onNodeUpdate as ReturnType<typeof vi.fn>).mock.calls
      .filter((c: any[]) => c[0]?.interfaceData?.previewWidth != null);
    expect(snapCalls).toHaveLength(0);
  });

  it('never PERSISTS a snap in run mode - a workflow the user is only watching must not be dirtied', () => {
    mockMode.isRunMode = true;
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    const data = baseData();
    renderNode(data);
    expect(data.onNodeUpdate).not.toHaveBeenCalled();
  });

  it('locks the resize handles to the format ratio (keepAspectRatio)', () => {
    renderNode(baseData());
    expect(resizerProps.length).toBeGreaterThan(0);
    expect(resizerProps[resizerProps.length - 1].keepAspectRatio).toBe(true);
  });
});

/**
 * Run-mode box snap.
 *
 * The persisted box (interfaces[].previewWidth/Height in the plan) is only ever
 * written by the EDIT-mode effect above, and the plan importer defaults it to the
 * historical 400x250 when absent. So a run opened straight in run mode - triggered
 * by an agent through the MCP execute action, reopened from the runs list, a shared
 * link - rendered a vertical interface letterboxed in the middle of a landscape
 * card, while the same workflow looked right after a pass through the builder.
 * Run mode now snaps the LIVE box (style only, never persisted).
 */
describe('InterfacePreviewNode run-mode format snap (agent-triggered run showed the default box)', () => {
  const runNode = (over: Record<string, any> = {}) => {
    mockMode.isRunMode = true;
    mockMode.runId = 'run_1';
    return renderNode(baseData(over));
  };
  // Run the captured updater over the store's nodes, like ReactFlow would.
  const snappedBox = () => {
    const nodes = [{ id: 'interface-iface-1', style: { ...storeNodeStyle } }];
    const updater = setNodesMock.mock.calls[setNodesMock.mock.calls.length - 1][0];
    return updater(nodes)[0].style;
  };

  it('snaps the default 400x250 box to the interface vertical format (the reported bug: 9:16 video centered in a landscape card)', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    runNode();
    // 1080x1920 fitted in the 400 snap box.
    expect(snappedBox()).toMatchObject({ width: 225, height: 400 });
  });

  it('reads the format from the RUN RESULT first: a run replays its frozen snapshot, which may differ from the live interface', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'widescreen' };
    mockRenderState = { data: { format: 'vertical' }, isLoading: false, error: null };
    runNode();
    expect(snappedBox()).toMatchObject({ width: 225, height: 400 });
  });

  it('does not dispatch at all once the box already matches - the box comes back through the store, so an unconditional write would loop (and each write resets every node)', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    storeNodeStyle = { width: 225, height: 400 };
    runNode();
    expect(setNodesMock).not.toHaveBeenCalled();
  });

  it('re-snaps when the box is rebuilt back to the stored default mid-run (workflow refetch) instead of leaving the letterbox for good', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    storeNodeStyle = { width: 225, height: 400 };
    const c = runNode();
    expect(setNodesMock).not.toHaveBeenCalled();

    // The plan is re-imported while still in run mode: 400x250 is back.
    storeNodeStyle = { width: 400, height: 250 };
    c.rerender(<InterfacePreviewNode data={baseData()} selected={false} id="interface-iface-1" {...({} as any)} />);
    expect(setNodesMock).toHaveBeenCalledTimes(1);
    expect(snappedBox()).toMatchObject({ width: 225, height: 400 });
  });

  it('touches no other node', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    runNode();
    const other = { id: 'some-other-node', style: { width: 400, height: 250 } };
    const updater = setNodesMock.mock.calls[setNodesMock.mock.calls.length - 1][0];
    expect(updater([other])[0]).toBe(other);
  });

  it('snaps a node with no dimensions at all in its style (never sized, not just wrongly sized)', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    storeNodeStyle = undefined;
    runNode();
    expect(snappedBox()).toMatchObject({ width: 225, height: 400 });
  });

  it('does NOT reshape an interface with no declared format: a legacy free-form box stays as the user left it', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: null };
    runNode();
    expect(setNodesMock).not.toHaveBeenCalled();
  });

  it('does not run in compact (preview off) mode - there is no format frame to match', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    mockMode.isRunMode = true;
    const data = baseData();
    data.interfaceData = { ...data.interfaceData, showPreview: false };
    renderNode({ ...data, isPreviewMode: false });
    expect(setNodesMock).not.toHaveBeenCalled();
  });

  it('does not touch the box in edit mode - the persisting effect owns it there', () => {
    mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
    renderNode(baseData());
    expect(setNodesMock).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------------------
  // Verified retry: the dispatch can be silently LOST while storeBox never
  // changes (fired during a mount commit before ReactFlow registers
  // onNodesChange, or wiped by a concurrent stale parent write in the same
  // batch). Pre-fix, the effect was one-shot: a lost dispatch left the node
  // letterboxed in the importer's 400x250 for good (WF-AUTH-026 under load).
  // ---------------------------------------------------------------------------

  it('REGRESSION lost-dispatch: retries when the store still holds the old box after the verify delay', async () => {
    vi.useFakeTimers();
    try {
      mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
      // storeNodeStyle stays 400x250: the dispatch never lands anywhere.
      runNode();
      expect(setNodesMock).toHaveBeenCalledTimes(1);

      await act(async () => { vi.advanceTimersByTime(300); });
      expect(setNodesMock, 'a lost write must be re-dispatched').toHaveBeenCalledTimes(2);
      expect(snappedBox()).toMatchObject({ width: 225, height: 400 });
    } finally {
      vi.useRealTimers();
    }
  });

  it('does NOT retry once the write is observed to have landed', async () => {
    vi.useFakeTimers();
    try {
      mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
      runNode();
      expect(setNodesMock).toHaveBeenCalledTimes(1);

      // The write lands (parent -> prepared -> store) before the verify tick.
      storeNodeStyle = { width: 225, height: 400 };
      await act(async () => { vi.advanceTimersByTime(300); });
      expect(setNodesMock, 'a landed write needs no retry').toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('stops re-arming after the bounded attempt count (never an endless retry loop)', async () => {
    vi.useFakeTimers();
    try {
      mockInterfaceDetails = { ...mockInterfaceDetails, format: 'vertical' };
      runNode();

      for (let i = 0; i < 12; i++) {
        await act(async () => { vi.advanceTimersByTime(300); });
      }
      const settled = setNodesMock.mock.calls.length;
      // 5 armed attempts + the final unarmed dispatch = 6; the exact number matters
      // less than the fact it STOPS.
      expect(settled).toBeLessThanOrEqual(6);

      await act(async () => { vi.advanceTimersByTime(3000); });
      expect(setNodesMock.mock.calls.length, 'retry must stop growing').toBe(settled);
    } finally {
      vi.useRealTimers();
    }
  });
});
