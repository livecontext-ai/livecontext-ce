// @vitest-environment jsdom
/**
 * The loader lets the IMPORTER decide which way a plan reads and puts the canvas in that
 * direction: the plan's stamp, else horizontal for positions saved before the stamp
 * existed, else (no position at all) the user's default. The rule itself is pinned by
 * planLayoutDirection.test and WorkflowPlanImporter.layoutDirection.test; this suite pins the
 * WIRING, which is where it failed before:
 *  - what the loader tells the importer (the user's default, or the pin of a pinned surface),
 *  - that the canvas adopts the direction the nodes were placed in, in the same batch,
 *  - that loading never writes the account default (setDirection).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import * as React from 'react';

const getWorkflow = vi.fn();
const getRun = vi.fn();
const getAgents = vi.fn();
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: (id: string) => getWorkflow(id),
    getRun: (id: string) => getRun(id),
    getAgents: () => getAgents(),
  },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));

const setWorkflowDirection = vi.fn();
const setDirection = vi.fn();
/** The canvas scope the loader runs under; each test states the one it needs. */
const canvas = { direction: 'horizontal', defaultDirection: 'horizontal', isPinned: false };
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({
  useWorkflowLayoutDirectionSafe: () => ({ ...canvas, setDirection, setWorkflowDirection }),
}));

vi.mock('../../services/workflowPlanImporter/WorkflowPlanImporter', () => ({
  WorkflowPlanImporter: {
    importPlan: vi.fn().mockResolvedValue({ success: true, nodes: [], edges: [], validation: { isValid: true } }),
  },
}));
vi.mock('../../registry/nodeRegistry', () => ({ nodeRegistry: { isLoopNode: () => false } }));
vi.mock('../../services/statusUpdater', () => ({
  updateNodesFromBatchSteps: (n: unknown) => n,
  updateDecisionNodesFromPredecessors: (n: unknown) => n,
}));
vi.mock('../../services/edgeStatusService', () => ({
  updateEdgesFromBatch: (e: unknown) => e,
  updateLoopInternalEdges: (e: unknown) => e,
}));

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useWorkflowLoader } from '../useWorkflowLoader';
import { WorkflowPlanImporter } from '../../services/workflowPlanImporter/WorkflowPlanImporter';

/**
 * The loader reads the canvas's query client to hand it to the importer (the interface
 * format lookup shares the node's own cache entry), so it needs a provider - as the rest
 * of the builder already did through `useWorkflowEventListeners`.
 */
function withQueryClient({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

function renderLoader(setNodes: (...a: unknown[]) => void = vi.fn()) {
  return renderHook(() => {
    const nodesRef = React.useRef([]);
    const edgesRef = React.useRef([]);
    return useWorkflowLoader({
      workflowId: 'wf-1',
      runId: undefined,
      planOverride: undefined,
      setNodes,
      setEdges: vi.fn(),
      nodesRef,
      edgesRef,
    } as any);
  }, { wrapper: withQueryClient });
}

beforeEach(() => {
  getWorkflow.mockReset();
  getRun.mockReset();
  getAgents.mockReset();
  getAgents.mockResolvedValue([]);
  setWorkflowDirection.mockReset();
  setDirection.mockReset();
  Object.assign(canvas, { direction: 'horizontal', defaultDirection: 'horizontal', isPinned: false });
  vi.mocked(WorkflowPlanImporter.importPlan).mockReset();
  vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
    success: true, nodes: [], edges: [], validation: { isValid: true }, layoutDirection: 'horizontal',
  } as any);
});

/** The layout options (third argument) of an importPlan call; negative counts from the end. */
function layoutOptionsOf(call = 0) {
  return vi.mocked(WorkflowPlanImporter.importPlan).mock.calls.at(call)?.[2];
}

async function restore(plan: Record<string, unknown>) {
  await act(async () => {
    window.dispatchEvent(new CustomEvent('workflowPlanRestore', { detail: { plan } }));
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('useWorkflowLoader - the canvas reads a plan the way the importer placed it', () => {
  it('hands the importer the user default as the fallback, and pins nothing', async () => {
    canvas.defaultDirection = 'vertical';
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(layoutOptionsOf()).toEqual({ fallbackDirection: 'vertical' });
  });

  it('adopts the direction the importer placed the nodes in', async () => {
    // A legacy plan opened by a vertical-default user: the importer keeps its horizontal
    // positions and says so; the canvas must render horizontal, or the positions are drawn
    // with the wrong handles (the "every workflow looks broken" defect).
    canvas.defaultDirection = 'vertical';
    vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
      success: true, nodes: [], edges: [], validation: { isValid: true }, layoutDirection: 'horizontal',
    } as any);
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(setWorkflowDirection).toHaveBeenCalledWith('horizontal');
    expect(setDirection, 'loading a workflow wrote the account default').not.toHaveBeenCalled();
  });

  it('sets the direction before the nodes, so no frame draws them with the wrong handles', async () => {
    vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
      success: true,
      nodes: [{ id: 'n1', position: { x: 777, y: 333 }, data: {} }],
      edges: [],
      validation: { isValid: true },
      layoutDirection: 'vertical',
    } as any);
    getWorkflow.mockResolvedValue({ plan: { layoutDirection: 'vertical', triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    const setNodes = vi.fn();
    const { result } = renderLoader(setNodes);

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    const directionAt = setWorkflowDirection.mock.invocationCallOrder[0];
    const nodesAt = setNodes.mock.invocationCallOrder.at(-1)!;
    expect(directionAt).toBeLessThan(nodesAt);
    // The importer's positions are set as they are: no layout pass between import and paint.
    expect(setNodes.mock.calls.at(-1)?.[0]?.[0].position).toEqual({ x: 777, y: 333 });
  });

  it('regression: waits for the avatar lookup, so no frame draws the old nodes with the new handles', async () => {
    // An agent node without an avatar makes the loader await getAgents() between the import
    // and setNodes. The direction used to be set BEFORE that await, so it rendered on its
    // own with whatever nodes were on the canvas.
    let resolveAgents: (agents: unknown[]) => void = () => {};
    getAgents.mockReturnValue(new Promise((resolve) => { resolveAgents = resolve; }));
    vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
      success: true,
      nodes: [{ id: 'agent-1', position: { x: 0, y: 0 }, data: { agentConfigId: 'cfg-1' } }],
      edges: [],
      validation: { isValid: true },
      layoutDirection: 'vertical',
    } as any);
    getWorkflow.mockResolvedValue({ plan: { layoutDirection: 'vertical', triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    const setNodes = vi.fn();
    const { result } = renderLoader(setNodes);

    await waitFor(() => expect(getAgents).toHaveBeenCalled());
    expect(setWorkflowDirection, 'direction set while the nodes were still pending').not.toHaveBeenCalled();

    await act(async () => { resolveAgents([]); });
    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(setWorkflowDirection).toHaveBeenCalledWith('vertical');
    expect(setWorkflowDirection.mock.invocationCallOrder.at(-1)!)
      .toBeLessThan(setNodes.mock.invocationCallOrder.at(-1)!);
  });

  it('regression: a version restore also waits for the avatar lookup before switching direction', async () => {
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    const setNodes = vi.fn();
    const { result } = renderLoader(setNodes);
    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    setWorkflowDirection.mockClear();

    let resolveAgents: (agents: unknown[]) => void = () => {};
    getAgents.mockReturnValue(new Promise((resolve) => { resolveAgents = resolve; }));
    vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
      success: true,
      nodes: [{ id: 'agent-1', position: { x: 0, y: 0 }, data: { agentConfigId: 'cfg-1' } }],
      edges: [],
      validation: { isValid: true },
      layoutDirection: 'vertical',
    } as any);

    await restore({ layoutDirection: 'vertical', triggers: [], mcps: [{ id: 's1' }], edges: [] });
    expect(getAgents).toHaveBeenCalled();
    expect(setWorkflowDirection, 'direction set while the restored nodes were still pending').not.toHaveBeenCalled();

    await act(async () => { resolveAgents([]); });
    await waitFor(() => expect(setWorkflowDirection).toHaveBeenCalledWith('vertical'));
    expect(setWorkflowDirection.mock.invocationCallOrder.at(-1)!)
      .toBeLessThan(setNodes.mock.invocationCallOrder.at(-1)!);
  });

  it('pins the importer to the surface direction on a pinned canvas (marketplace preview)', async () => {
    Object.assign(canvas, { direction: 'vertical', defaultDirection: 'vertical', isPinned: true });
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(layoutOptionsOf()).toEqual({ fallbackDirection: 'vertical', forcedDirection: 'vertical' });
  });

  it('keeps the canvas direction when the import fails', async () => {
    vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
      success: false, nodes: [], edges: [], validation: { isValid: false }, error: 'bad', layoutDirection: 'vertical',
    } as any);
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.loadError).toBe(true));
    expect(setWorkflowDirection).not.toHaveBeenCalled();
  });

  it('reads a restored version by the same rule, falling back on the CURRENT canvas direction', async () => {
    // A version without a single position keeps the direction the canvas is already in,
    // not the account default: the user is looking at this workflow, not at a new one.
    Object.assign(canvas, { direction: 'vertical', defaultDirection: 'horizontal' });
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    const { result } = renderLoader();
    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    setWorkflowDirection.mockClear();
    vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
      success: true, nodes: [], edges: [], validation: { isValid: true }, layoutDirection: 'horizontal',
    } as any);

    await restore({ layoutDirection: 'horizontal', triggers: [], mcps: [{ id: 's1' }], edges: [] });

    expect(layoutOptionsOf(-1)).toEqual({ fallbackDirection: 'vertical' });
    expect(setWorkflowDirection).toHaveBeenCalledWith('horizontal');
    expect(setDirection).not.toHaveBeenCalled();
  });
});
