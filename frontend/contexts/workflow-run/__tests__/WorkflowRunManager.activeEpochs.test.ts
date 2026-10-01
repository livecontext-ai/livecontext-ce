// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WorkflowRunManager } from '../WorkflowRunManager';
import { orchestratorApi } from '@/lib/api';

/**
 * The open epochs must survive the REST load, not only the WS batch.
 *
 * They answer "is another fire of this run executing right now", and that decides whether a
 * restart targeting an OLDER epoch can be offered: the backend refuses to reopen one while a
 * sibling epoch of the same DAG is still running. Fed by the socket alone, the set is empty
 * until the first batch arrives, so a canvas opened on a live run reads "nothing is executing",
 * offers the restart, and collects a 409.
 *
 * This is pinned at the MANAGER rather than at the store, because the store is not where the
 * field goes missing. `applyMetadata` / `initializeFromApi` take a hand-written object literal
 * built here from the payload; a field nobody lists is simply absent, `WorkflowRunState` marks
 * it optional so TypeScript says nothing, and the store's own test passes it in directly and
 * stays green over a wire that does not exist. That is the whole failure mode.
 */
vi.mock('../streamingDebug', () => ({
  streamDebug: { log: vi.fn(), warn: vi.fn(), error: vi.fn(), isEnabled: () => false },
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: vi.fn(),
    getStatusCounts: vi.fn(),
    rerunFromStep: vi.fn(),
    triggerSpecific: vi.fn(),
    executeSingleStep: vi.fn(),
    pauseWorkflow: vi.fn(),
    resumeWorkflow: vi.fn(),
    cancelWorkflow: vi.fn(),
    setExecutionMode: vi.fn(),
    resolveSignal: vi.fn(),
  },
}));

vi.mock('@/lib/websocket', () => ({
  wsClient: { onReconnected: () => () => {}, sendAction: vi.fn().mockResolvedValue(undefined) },
}));

vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) => label.toLowerCase().replace(/[^a-z0-9]+/g, '_'),
}));

const mockGetRunState = vi.mocked(orchestratorApi.getRunState);

function runStatePayload(overrides: Record<string, unknown> = {}) {
  return {
    runId: 'run-1',
    workflowId: 'wf-1',
    status: 'running',
    executionMode: 'automatic',
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps: [],
    edges: [],
    plan: { triggers: [{ type: 'manual', label: 'Start' }] },
    currentEpoch: 3,
    seq: 1,
    ...overrides,
  } as never;
}

describe('WorkflowRunManager - activeEpochs from the REST load', () => {
  let manager: WorkflowRunManager;

  beforeEach(() => {
    vi.clearAllMocks();
    manager = new WorkflowRunManager('run-1');
  });

  afterEach(() => {
    manager.destroy();
  });

  it('carries the open epochs from the state payload into the store', async () => {
    mockGetRunState.mockResolvedValueOnce(runStatePayload({ activeEpochs: [3] }));

    await manager.initialize();

    expect(manager.getState().activeEpochs).toEqual([3]);
  });

  it('CLEARS a known set when the run has since gone quiet', async () => {
    // The quiesced run is the case the focused-epoch restart exists for, so "nothing is
    // executing" has to be able to replace "epoch 3 is". Asserting [] on a fresh store would
    // prove nothing - that is the initial value - so this starts from a populated set.
    mockGetRunState.mockResolvedValueOnce(runStatePayload({ activeEpochs: [3] }));
    await manager.initialize();
    expect(manager.getState().activeEpochs).toEqual([3]);

    mockGetRunState.mockResolvedValueOnce(runStatePayload({ seq: 3, activeEpochs: [] }));
    await manager.refresh();

    expect(manager.getState().activeEpochs).toEqual([]);
  });

  it('leaves a known set alone when a later payload omits the field', async () => {
    // An older backend, or the public showcase payload, carries no such field. Coalescing it
    // to [] there would erase what is already established and re-open the same hole from the
    // other side - which is why the store spreads the key in only when it is present.
    mockGetRunState.mockResolvedValueOnce(runStatePayload({ activeEpochs: [2] }));
    await manager.initialize();
    expect(manager.getState().activeEpochs).toEqual([2]);

    mockGetRunState.mockResolvedValueOnce(runStatePayload({ seq: 3 }));
    await manager.refresh();

    expect(manager.getState().activeEpochs).toEqual([2]);
  });
});
