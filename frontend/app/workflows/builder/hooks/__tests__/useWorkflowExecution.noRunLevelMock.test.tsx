// @vitest-environment jsdom
/**
 * Run-level mock removal (2026-07-13): mocking is decided per node (the
 * node's mock block), never at run launch. Both start handlers used to read
 * `event.detail.mockMode` and forward it to the execute API; these tests pin
 * the ABSENCE of that forwarding: even a dispatcher that still passes
 * mockMode must not reach orchestratorApi.executeWorkflow with it.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  executeWorkflow: vi.fn(),
  getAllCredentials: vi.fn(),
  executeSingleStepInStepByStepMode: vi.fn(),
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: api }));
vi.mock('@/lib/api/error-utils', () => ({
  is402Error: () => false,
  is413StorageError: () => false,
}));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({
  showInsufficientCreditsModal: vi.fn(),
}));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({
  showInsufficientStorageModal: vi.fn(),
}));
vi.mock('@/lib/billing/ceRelayErrorModals', () => ({ handleCeRelayError: () => false }));
vi.mock('../../utils/workflowPlanGenerator', () => ({
  generateWorkflowPlan: () => ({ id: 'generated' }),
}));
vi.mock('@/lib/credentials/reconcilePlanCredentials', () => ({
  reconcilePlanCredentials: (plan: unknown) => plan,
}));
vi.mock('../useWorkflowLoader', () => ({ markRunAsJustExecuted: vi.fn() }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isPreviewOnly: false }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
}));

import { useWorkflowExecution } from '../useWorkflowExecution';

function renderExecutionHook() {
  return renderHook(() =>
    useWorkflowExecution({
      workflowId: 'wf-1',
      nodes: [],
      edges: [],
      router: { push: vi.fn(), replace: vi.fn(), back: vi.fn() } as never,
      setWorkflowStatus: vi.fn(),
      pauseResumeActions: { setMode: vi.fn(), updateReadySteps: vi.fn() },
      onSaveBeforeExecute: async () => JSON.stringify({ id: 'p1' }),
    })
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.executeWorkflow.mockResolvedValue({ runId: 'run-1', status: 'running' });
  api.getAllCredentials.mockResolvedValue([]);
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('useWorkflowExecution - no run-level mock forwarding', () => {
  it('workflowViewStart: a stray detail.mockMode is NOT forwarded to the execute API', async () => {
    renderExecutionHook();

    window.dispatchEvent(
      new CustomEvent('workflowViewStart', {
        detail: { workflowId: 'wf-1', mockMode: 'all_mcp' },
      })
    );

    await waitFor(() => expect(api.executeWorkflow).toHaveBeenCalledTimes(1));
    const payload = api.executeWorkflow.mock.calls[0][0];
    expect(payload).not.toHaveProperty('mockMode');
    expect(payload).toMatchObject({ workflowId: 'wf-1' });
  });

  it('workflowStartStepByStep: a stray detail.mockMode is NOT forwarded either', async () => {
    renderExecutionHook();

    window.dispatchEvent(
      new CustomEvent('workflowStartStepByStep', {
        detail: { workflowId: 'wf-1', mockMode: 'off' },
      })
    );

    await waitFor(() => expect(api.executeWorkflow).toHaveBeenCalledTimes(1));
    const payload = api.executeWorkflow.mock.calls[0][0];
    expect(payload).not.toHaveProperty('mockMode');
    expect(payload).toMatchObject({ workflowId: 'wf-1', executionMode: 'step_by_step' });
  });
});
