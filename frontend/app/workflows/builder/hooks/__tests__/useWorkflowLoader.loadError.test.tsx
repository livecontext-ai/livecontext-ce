// @vitest-environment jsdom
/**
 * Regression (2026-06-12 prod): when the plan fetch failed (e.g. the post-create
 * redirect pointed at a 404 workflow), useWorkflowLoader silently returned -
 * workflowLoaded never turned true, dirty-tracking never armed, and the builder
 * showed an empty canvas with a permanently disabled Save button and no error.
 * The hook now surfaces `loadError` and offers `retryLoad`.
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

function renderLoader(runId?: string) {
  return renderHook(() => {
    const nodesRef = React.useRef([]);
    const edgesRef = React.useRef([]);
    return useWorkflowLoader({
      workflowId: 'wf-404',
      runId,
      planOverride: undefined,
      setNodes: vi.fn(),
      setEdges: vi.fn(),
      nodesRef,
      edgesRef,
    } as any);
  });
}

beforeEach(() => {
  getWorkflow.mockReset();
  getRun.mockReset();
  vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
    success: true, nodes: [], edges: [], validation: { isValid: true },
  } as any);
});

describe('useWorkflowLoader - load failure surfaces loadError + retryLoad', () => {
  it('sets loadError when the edit-mode plan fetch fails (pre-fix: silent dead builder)', async () => {
    getWorkflow.mockRejectedValue(new Error('404'));
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.loadError).toBe(true));
    // The old symptom: never "loaded", so dirty-tracking would never arm.
    expect(result.current.workflowLoaded).toBe(false);
    expect(result.current.isLoadingWorkflow).toBe(false);
  });

  it('sets loadError when the run-mode plan fetch (getRun) fails', async () => {
    getRun.mockRejectedValue(new Error('run 404'));
    const { result } = renderLoader('run-123');

    await waitFor(() => expect(result.current.loadError).toBe(true));
    expect(result.current.workflowLoaded).toBe(false);
    expect(getWorkflow).not.toHaveBeenCalled();
  });

  it('sets loadError when the plan import fails (same dead-builder class as a failed fetch)', async () => {
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
      success: false, error: 'boom', nodes: [], edges: [], validation: { isValid: false },
    } as any);
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.loadError).toBe(true));
    expect(result.current.workflowLoaded).toBe(false);
  });

  it('sets loadError on an unexpected mid-load throw (outer catch)', async () => {
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    vi.mocked(WorkflowPlanImporter.importPlan).mockRejectedValue(new Error('unexpected'));
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.loadError).toBe(true));
    expect(result.current.workflowLoaded).toBe(false);
    expect(result.current.isLoadingWorkflow).toBe(false);
  });

  it('retryLoad clears the error and a successful re-fetch marks the workflow loaded', async () => {
    getWorkflow.mockRejectedValueOnce(new Error('transient'));
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [], edges: [] } });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.loadError).toBe(true));

    act(() => result.current.retryLoad());

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(result.current.loadError).toBe(false);
    expect(getWorkflow).toHaveBeenCalledTimes(2);
  });
});
