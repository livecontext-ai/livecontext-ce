// @vitest-environment jsdom
/**
 * The loader SEEDS the canvas reading direction from the loaded plan's
 * `layoutDirection` (the workflow's DB identity), beating the user's account
 * default so a refresh renders the workflow the way it was authored.
 *
 * Precedence, verified here:
 *  - plan.layoutDirection = 'vertical'|'horizontal'  -> seed exactly that.
 *  - absent (a legacy plan authored before this feature, OR a new/empty one) -> DO
 *    NOT seed; keep the user's account default. Forcing horizontal on un-stamped
 *    workflows would override a vertical-preference user's account choice, breaking
 *    the Settings preference. The direction only becomes the plan's identity on save.
 *
 * The seed uses setWorkflowDirection (in-memory only), never setDirection, so it
 * never pollutes the user's global preference.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import * as React from 'react';

const getWorkflow = vi.fn();
const getRun = vi.fn();
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: (id: string) => getWorkflow(id),
    getRun: (id: string) => getRun(id),
  },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));

const setWorkflowDirection = vi.fn();
const setDirection = vi.fn();
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({
  useWorkflowLayoutDirectionSafe: () => ({
    direction: 'horizontal',
    setDirection,
    setWorkflowDirection,
  }),
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

import { useWorkflowLoader } from '../useWorkflowLoader';
import { WorkflowPlanImporter } from '../../services/workflowPlanImporter/WorkflowPlanImporter';

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
  });
}

beforeEach(() => {
  getWorkflow.mockReset();
  getRun.mockReset();
  setWorkflowDirection.mockReset();
  setDirection.mockReset();
  vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
    success: true, nodes: [], edges: [], validation: { isValid: true },
  } as any);
});

describe('useWorkflowLoader - seeds direction from plan.layoutDirection', () => {
  it('seeds vertical when the plan stores layoutDirection="vertical"', async () => {
    getWorkflow.mockResolvedValue({
      plan: { layoutDirection: 'vertical', triggers: [], mcps: [{ id: 's1' }], edges: [] },
    });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(setWorkflowDirection).toHaveBeenCalledWith('vertical');
    // Never touches the GLOBAL preference.
    expect(setDirection).not.toHaveBeenCalled();
  });

  it('seeds horizontal when the plan stores layoutDirection="horizontal"', async () => {
    getWorkflow.mockResolvedValue({
      plan: { layoutDirection: 'horizontal', triggers: [], mcps: [{ id: 's1' }], edges: [] },
    });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(setWorkflowDirection).toHaveBeenCalledWith('horizontal');
  });

  it('does NOT seed a legacy plan with nodes but no layoutDirection (keeps account default)', async () => {
    // Regression (e2e CE-WFDIR-002): forcing horizontal here overrode a vertical-
    // preference user's account choice on every un-stamped workflow. An absent
    // direction must leave the canvas on the account default, not force horizontal.
    getWorkflow.mockResolvedValue({
      plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] },
    });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(setWorkflowDirection).not.toHaveBeenCalled();
    expect(setDirection).not.toHaveBeenCalled();
  });

  it('does NOT seed for an empty new plan (keeps the user account default)', async () => {
    getWorkflow.mockResolvedValue({
      plan: { triggers: [], mcps: [], edges: [] },
    });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(setWorkflowDirection).not.toHaveBeenCalled();
    expect(setDirection).not.toHaveBeenCalled();
  });

  it('seeds direction WITHOUT re-flowing: the imported node positions are set as-is', async () => {
    // The seed must never trigger an auto-layout (that would trash saved positions).
    // importPlan returns a node at a precise position; the loader must set exactly
    // that position, proving no dagre re-flow ran between seed and setNodes.
    vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
      success: true,
      nodes: [{ id: 'n1', position: { x: 777, y: 333 }, data: {} }],
      edges: [],
      validation: { isValid: true },
    } as any);
    getWorkflow.mockResolvedValue({
      plan: { layoutDirection: 'vertical', triggers: [], mcps: [{ id: 's1' }], edges: [] },
    });
    const setNodes = vi.fn();
    const { result } = renderLoader(setNodes);

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(setWorkflowDirection).toHaveBeenCalledWith('vertical');
    const setNode = setNodes.mock.calls.at(-1)?.[0]?.[0];
    expect(setNode.position).toEqual({ x: 777, y: 333 });
  });

  it('re-seeds direction when a version with a different layoutDirection is restored', async () => {
    // A restore lands onto a canvas that already has an active direction; a restored
    // version carrying its own layoutDirection must re-seed so handles/edges attach on
    // the correct node edges without a full reload.
    getWorkflow.mockResolvedValue({
      plan: { layoutDirection: 'horizontal', triggers: [], mcps: [{ id: 's1' }], edges: [] },
    });
    const { result } = renderLoader();
    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    setWorkflowDirection.mockClear();

    await act(async () => {
      window.dispatchEvent(new CustomEvent('workflowPlanRestore', {
        detail: { plan: { layoutDirection: 'vertical', triggers: [], mcps: [{ id: 's1' }], edges: [] } },
      }));
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(setWorkflowDirection).toHaveBeenCalledWith('vertical');
    expect(setDirection).not.toHaveBeenCalled();
  });

  it('leaves the current direction untouched when a restored version has no layoutDirection', async () => {
    getWorkflow.mockResolvedValue({
      plan: { layoutDirection: 'vertical', triggers: [], mcps: [{ id: 's1' }], edges: [] },
    });
    const { result } = renderLoader();
    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    setWorkflowDirection.mockClear();

    await act(async () => {
      window.dispatchEvent(new CustomEvent('workflowPlanRestore', {
        detail: { plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } },
      }));
      await Promise.resolve();
      await Promise.resolve();
    });

    // No layoutDirection on the restored plan -> no re-seed (current direction stays).
    expect(setWorkflowDirection).not.toHaveBeenCalled();
  });
});
