// @vitest-environment jsdom
/**
 * A run that stops because the workspace is out of credits must SAY so.
 *
 * The orchestrator no longer refuses an out-of-credit fire before the epoch opens
 * (that left no run, no node and no error - a scheduled workflow just appeared to
 * stop). The trigger node itself now fails with `error_code=CREDIT_EXHAUSTED` and
 * the rest of the workflow is skipped. An AUTOMATIC fire is acked 202 before that
 * happens, so the HTTP 402 that used to raise the "Insufficient credits" modal is
 * gone on that path: the failed NODE is what has to raise it.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WorkflowRunManager } from '../WorkflowRunManager';
import { showInsufficientCreditsModal } from '@/components/billing/InsufficientCreditsModal';

vi.mock('../streamingDebug', () => ({
  streamDebug: { log: vi.fn(), warn: vi.fn(), error: vi.fn(), isEnabled: () => false },
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: vi.fn().mockResolvedValue(null),
    triggerSpecific: vi.fn(),
    getLatestWorkflowRun: vi.fn(),
    getAllRunSteps: vi.fn(),
    rerunFromStep: vi.fn(),
    getStatusCounts: vi.fn(),
    executeSingleStep: vi.fn(),
    pauseWorkflow: vi.fn(),
    resumeWorkflow: vi.fn(),
    cancelWorkflow: vi.fn(),
    setExecutionMode: vi.fn(),
    resolveSignal: vi.fn(),
  },
}));

// The real predicate is under test here - only the modal itself is mocked.
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({
  showInsufficientCreditsModal: vi.fn(),
}));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({
  showInsufficientStorageModal: vi.fn(),
}));
vi.mock('@/lib/billing/ceRelayErrorModals', () => ({ handleCeRelayError: () => false }));
vi.mock('@/lib/websocket', () => ({ wsClient: { onReconnected: () => () => {}, sendAction: vi.fn() } }));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) =>
    label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''),
}));

describe('WorkflowRunManager - out-of-credit node failure', () => {
  let manager: WorkflowRunManager;

  beforeEach(() => {
    vi.clearAllMocks();
    manager = new WorkflowRunManager('run-broke');
  });

  afterEach(() => {
    manager?.destroy();
    vi.restoreAllMocks();
  });

  it('raises the Insufficient credits modal when the trigger node reports CREDIT_EXHAUSTED', () => {
    (manager as any).handleStepFailed('trigger:daily', {
      id: 'trigger:daily',
      label: 'Daily',
      status: 'failed',
      errorMessage: 'Out of credits: this workflow cannot run. Add credits to run it again.',
      output: { error_code: 'CREDIT_EXHAUSTED' },
    });

    expect(showInsufficientCreditsModal).toHaveBeenCalledTimes(1);
  });

  it('recognises the failure from the message alone (output trimmed off the payload)', () => {
    (manager as any).handleStepFailed('agent:writer', {
      id: 'agent:writer',
      label: 'Writer',
      status: 'failed',
      errorMessage: 'Out of credits: this workflow cannot run. Add credits to run it again.',
    });

    expect(showInsufficientCreditsModal).toHaveBeenCalledTimes(1);
  });

  it('stays silent for an unrelated node failure', () => {
    (manager as any).handleStepFailed('mcp:fetch', {
      id: 'mcp:fetch',
      label: 'Fetch',
      status: 'failed',
      errorMessage: 'Connection refused',
    });

    expect(showInsufficientCreditsModal).not.toHaveBeenCalled();
  });
});
