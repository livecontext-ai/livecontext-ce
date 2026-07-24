// @vitest-environment jsdom
/**
 * Run cost + budget WS events.
 *
 * `runCost` carries the fresh accumulated cost of a run (an agent execution
 * settled its credits). It must update the store's costCredits/budgetCredits and
 * the per-epoch bucket so the RunInfo panel live-updates "Cost of this run".
 *
 * `runBudgetBlocked` means a new epoch was refused because the budget was
 * reached. The manager is not a React component, so it surfaces the toast by
 * dispatching a window CustomEvent that the run-mode panel listens for.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WorkflowRunManager } from '../WorkflowRunManager';

vi.mock('../streamingDebug', () => ({
  streamDebug: { log: vi.fn(), warn: vi.fn(), error: vi.fn(), isEnabled: () => false },
}));

const mockGetRunState = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: (...args: any[]) => mockGetRunState(...args),
    triggerSpecific: vi.fn().mockResolvedValue({ status: 'triggered', readySteps: [] }),
    getLatestWorkflowRun: vi.fn(),
    getAllRunSteps: vi.fn(),
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

vi.mock('@/lib/api/error-utils', () => ({ is402Error: () => false, is413StorageError: () => false }));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));
vi.mock('@/lib/websocket', () => ({ wsClient: { sendAction: vi.fn().mockResolvedValue(undefined) } }));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) => label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''),
}));

function baseState() {
  return {
    workflowId: 'wf-1',
    status: 'running',
    executionMode: 'automatic',
    triggerType: 'manual',
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps: [],
    edges: [],
    plan: { triggers: [], mcps: [], cores: [], edges: [] },
    seq: 0,
    costCredits: 0.1,
    budgetCredits: 10,
  };
}

describe('WorkflowRunManager - runCost / runBudgetBlocked', () => {
  let manager: WorkflowRunManager;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    manager = new WorkflowRunManager('run-cost');
    manager.setCurrentPlanGetter(() => ({ triggers: [], mcps: [], cores: [], edges: [] }) as any);
    mockGetRunState.mockResolvedValue(baseState());
  });

  afterEach(() => {
    manager.destroy();
    vi.useRealTimers();
  });

  it('runCost updates total cost, budget and the per-epoch bucket', async () => {
    await manager.initialize();
    const store = (manager as any).store;

    manager.handleEvent('runCost', {
      runId: 'run-cost',
      epoch: 2,
      epochCostCredits: 0.4,
      totalCostCredits: 1.25,
      budgetCredits: 10,
    });

    const s = store.getState();
    expect(s.costCredits).toBe(1.25);
    expect(s.budgetCredits).toBe(10);
    expect(s.costByEpoch['2']).toBe(0.4);
  });

  it('runCost honors a null budget (budget cleared)', async () => {
    await manager.initialize();
    const store = (manager as any).store;

    manager.handleEvent('runCost', {
      runId: 'run-cost',
      epoch: 1,
      epochCostCredits: 0.2,
      totalCostCredits: 0.2,
      budgetCredits: null,
    });

    expect(store.getState().budgetCredits).toBeNull();
  });

  it('runBudgetBlocked dispatches a window CustomEvent with the figures', async () => {
    await manager.initialize();
    const spy = vi.fn();
    window.addEventListener('workflow:runBudgetBlocked', spy as EventListener);

    manager.handleEvent('runBudgetBlocked', {
      runId: 'run-cost',
      spentCredits: 12,
      budgetCredits: 10,
    });

    expect(spy).toHaveBeenCalledTimes(1);
    const detail = (spy.mock.calls[0][0] as CustomEvent).detail;
    expect(detail).toMatchObject({ runId: 'run-cost', spentCredits: 12, budgetCredits: 10 });
    window.removeEventListener('workflow:runBudgetBlocked', spy as EventListener);
  });
});
