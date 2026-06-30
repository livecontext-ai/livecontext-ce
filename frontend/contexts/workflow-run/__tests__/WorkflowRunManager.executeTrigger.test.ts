/**
 * Tests for WorkflowRunManager.executeTrigger workflow-trigger simulate auto-fill.
 *
 * When a builder simulate fires a workflow trigger, the manager fetches the parent
 * workflow's latest run and uses its metadata.lastCycleResult to mirror the
 * production payload built by WorkflowTriggerDispatchService.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WorkflowRunManager } from '../WorkflowRunManager';

vi.mock('../streamingDebug', () => ({
  streamDebug: { log: vi.fn(), warn: vi.fn(), error: vi.fn(), isEnabled: () => false },
}));

const mockGetRunState = vi.fn();
const mockTriggerSpecific = vi.fn().mockResolvedValue({ status: 'triggered', readySteps: [] });
const mockGetLatestWorkflowRun = vi.fn();
const mockGetAllRunSteps = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: (...args: any[]) => mockGetRunState(...args),
    triggerSpecific: (...args: any[]) => mockTriggerSpecific(...args),
    getLatestWorkflowRun: (...args: any[]) => mockGetLatestWorkflowRun(...args),
    getAllRunSteps: (...args: any[]) => mockGetAllRunSteps(...args),
    rerunFromStep: vi.fn(),
    getStatusCounts: vi.fn(),
    executeSingleStep: vi.fn().mockResolvedValue({}),
    pauseWorkflow: vi.fn().mockResolvedValue({}),
    resumeWorkflow: vi.fn().mockResolvedValue({}),
    cancelWorkflow: vi.fn().mockResolvedValue({}),
    setExecutionMode: vi.fn().mockResolvedValue({ readySteps: [] }),
    resolveSignal: vi.fn().mockResolvedValue({ status: 'resolved' }),
  },
}));

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

vi.mock('@/lib/websocket', () => ({
  wsClient: { sendAction: vi.fn().mockResolvedValue(undefined) },
}));

vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) =>
    label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''),
}));

const PARENT_ID = 'parent-wf-uuid';
const PARENT_RUN_ID = 'run-parent-public-123';

function basePlan() {
  return {
    triggers: [
      { type: 'workflow', label: 'On Parent', id: PARENT_ID },
    ],
    mcps: [],
    cores: [],
    edges: [],
  };
}

function baseState(overrides: Record<string, any> = {}) {
  return {
    workflowId: 'wf-child',
    status: 'waiting_trigger',
    executionMode: 'step_by_step',
    triggerType: 'workflow',
    readySteps: ['trigger:on_parent'],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps: [],
    edges: [],
    plan: basePlan(),
    seq: 0,
    ...overrides,
  };
}

describe('WorkflowRunManager.executeTrigger - workflow simulate auto-fill', () => {
  let manager: WorkflowRunManager;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    manager = new WorkflowRunManager('run-child');
    manager.setCurrentPlanGetter(() => basePlan() as any);
    mockGetRunState.mockResolvedValue(baseState());
  });

  afterEach(() => {
    manager.destroy();
    vi.useRealTimers();
  });

  it('auto-fills payload from parent metadata.lastCycleResult', async () => {
    const lastCycleResult = { 'mcp:get_user': { user_id: 42 }, 'mcp:format': { greeting: 'hi' } };
    mockGetLatestWorkflowRun.mockResolvedValueOnce({
      id: 'parent-uuid-internal',
      runId: PARENT_RUN_ID,
      workflowId: PARENT_ID,
      status: 'COMPLETED',
      metadata: { lastCycleResult },
    });

    await manager.initialize();
    await manager.executeStep('trigger:on_parent', undefined, 'workflow');

    expect(mockGetLatestWorkflowRun).toHaveBeenCalledWith(PARENT_ID);
    expect(mockTriggerSpecific).toHaveBeenCalledTimes(1);
    const [, , triggerType, payload] = mockTriggerSpecific.mock.calls[0];
    expect(triggerType).toBe('workflow');
    expect(payload).toMatchObject({
      result: lastCycleResult,
      status: 'COMPLETED',
      parentWorkflowId: PARENT_ID,
      parentRunId: PARENT_RUN_ID,
      statistics: { completedSteps: 0, failedSteps: 0, totalSteps: 0 },
    });
    expect(typeof payload.triggeredAt).toBe('string');
  });

  it('falls back to empty result when parent has no metadata', async () => {
    mockGetLatestWorkflowRun.mockResolvedValueOnce({
      id: 'parent-uuid-internal',
      runId: PARENT_RUN_ID,
      workflowId: PARENT_ID,
      status: 'FAILED',
      metadata: undefined,
    });

    await manager.initialize();
    await manager.executeStep('trigger:on_parent', undefined, 'workflow');

    const [, , , payload] = mockTriggerSpecific.mock.calls[0];
    expect(payload.result).toEqual({});
    expect(payload.status).toBe('FAILED');
  });

  it('does not call getLatestWorkflowRun when payload already provided', async () => {
    await manager.initialize();
    await manager.executeStep('trigger:on_parent', { custom: 'data' }, 'workflow');

    expect(mockGetLatestWorkflowRun).not.toHaveBeenCalled();
    const [, , , payload] = mockTriggerSpecific.mock.calls[0];
    expect(payload).toEqual({ custom: 'data' });
  });

  it('proceeds with empty payload when parent run is not found', async () => {
    mockGetLatestWorkflowRun.mockResolvedValueOnce(null);

    await manager.initialize();
    await manager.executeStep('trigger:on_parent', undefined, 'workflow');

    expect(mockTriggerSpecific).toHaveBeenCalledTimes(1);
    const [, , , payload] = mockTriggerSpecific.mock.calls[0];
    // Empty/no auto-fill - payload should be undefined or {} (falsy result branch)
    expect(payload).toBeUndefined();
  });

  it('does not auto-fill for non-workflow triggers (e.g. schedule)', async () => {
    mockGetRunState.mockReset();
    mockGetRunState.mockResolvedValue(
      baseState({
        triggerType: 'schedule',
        plan: { triggers: [{ type: 'schedule', label: 'Daily' }], mcps: [], cores: [], edges: [] },
      }),
    );
    manager.setCurrentPlanGetter(
      () => ({ triggers: [{ type: 'schedule', label: 'Daily' }] } as any),
    );

    await manager.initialize();
    await manager.executeStep('trigger:daily', undefined, 'schedule');

    expect(mockGetLatestWorkflowRun).not.toHaveBeenCalled();
    expect(mockTriggerSpecific).toHaveBeenCalledTimes(1);
  });
});
