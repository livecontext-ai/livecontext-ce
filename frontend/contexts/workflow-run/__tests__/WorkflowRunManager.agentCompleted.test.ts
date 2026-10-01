// @vitest-environment jsdom
/**
 * Which finished nodes make the conversation panel reload its messages.
 *
 * <p>The manager cannot see node types: the WS payload carries a step id and a
 * status and nothing else, which is why the sibling `core:` case dispatches for
 * the whole prefix and lets its listener filter. The agent case has to work the
 * same way, and this file exists because it briefly did not.
 *
 * <p>When generate joined the AI family it started sharing the `agent:` prefix
 * while producing no messages at all, and the reload was suppressed for it by
 * testing the step id against `agent:generate`. That guard was wrong in both
 * directions, and neither direction failed loudly:
 *
 * <ul>
 *   <li>a generate node is keyed from its LABEL, so "Make Clip" gives
 *       `agent:make_clip` and was never matched, leaving the case it targeted
 *       exactly as it was;</li>
 *   <li>a real LLM agent called "Generate Report" gives `agent:generate_report`,
 *       which the guard DID match, so its answers stopped arriving in the panel
 *       for no reason other than its name.</li>
 * </ul>
 *
 * <p>What the guard saved was one reload of a panel that already reloads itself
 * on a timer, so the fix is to dispatch for the family and let the listener
 * decide.
 *
 * <p>One thing to be honest about: the guard was written and removed inside the
 * same branch, so against the branch point this file asserts behaviour that was
 * never broken there. It is not a regression test for a shipped bug; it is a
 * standing one against the shortcut, which looks cheap enough to reach for
 * again the next time somebody wants to spare the panel a reload. Reintroduce
 * the prefix test and two of these cases fail.
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
vi.mock('@/lib/websocket', () => ({ wsClient: { onReconnected: () => () => {}, sendAction: vi.fn().mockResolvedValue(undefined) } }));
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
  };
}

/** The step ids a batch-update reported as completed, in order. */
function completedStepIdsFrom(spy: ReturnType<typeof vi.fn>): string[] {
  return spy.mock.calls.map((call) => (call[0] as CustomEvent).detail.stepId);
}

describe('WorkflowRunManager - which completed nodes reload the conversation', () => {
  let manager: WorkflowRunManager;
  let spy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    vi.clearAllMocks();
    manager = new WorkflowRunManager('run-agent-completed');
    manager.setCurrentPlanGetter(() => ({ triggers: [], mcps: [], cores: [], edges: [] }) as any);
    mockGetRunState.mockResolvedValue(baseState());
    await manager.initialize();
    spy = vi.fn();
    window.addEventListener('workflowAgentCompleted', spy as EventListener);
  });

  afterEach(() => {
    window.removeEventListener('workflowAgentCompleted', spy as EventListener);
    manager.destroy();
  });

  function batch(steps: Array<{ id: string; status: string }>) {
    manager.handleBatchUpdate({ runId: 'run-agent-completed', seq: 1, steps });
  }

  it('reloads for an LLM agent whose label merely begins with "generate"', () => {
    // The regression: "Generate Report" normalizes to agent:generate_report, and
    // a prefix test on `agent:generate` silenced a node that really does write
    // the messages the panel is there to show.
    batch([{ id: 'agent:generate_report', status: 'completed' }]);

    expect(
      completedStepIdsFrom(spy),
      'an agent stopped being reloaded because of what it was called',
    ).toEqual(['agent:generate_report']);
  });

  it('reloads for an ordinary agent, which is the case that must keep working', () => {
    batch([{ id: 'agent:writer', status: 'completed' }]);

    expect(completedStepIdsFrom(spy)).toEqual(['agent:writer']);
  });

  it('dispatches for a generate node too, since nothing here can tell it apart', () => {
    // Not an oversight: the payload carries no node type, and a generate node's
    // key comes from its label, so "Make Clip" is indistinguishable from an
    // agent's key at this point. Filtering belongs to the listener, exactly as
    // it does for the core: case in the same loop.
    batch([{ id: 'agent:make_clip', status: 'completed' }]);

    expect(completedStepIdsFrom(spy)).toEqual(['agent:make_clip']);
  });

  it('says nothing for a node that has not finished', () => {
    batch([{ id: 'agent:writer', status: 'running' }]);

    expect(completedStepIdsFrom(spy)).toEqual([]);
  });

  it('says nothing for a node outside the AI family', () => {
    batch([{ id: 'core:wait', status: 'completed' }]);

    expect(completedStepIdsFrom(spy)).toEqual([]);
  });

  it('fires once per node, however many batches repeat it', () => {
    batch([{ id: 'agent:writer', status: 'completed' }]);
    batch([{ id: 'agent:writer', status: 'completed' }]);

    expect(
      completedStepIdsFrom(spy),
      'every batch carries the whole picture, so an unguarded dispatch would reload on each one',
    ).toEqual(['agent:writer']);
  });
});
