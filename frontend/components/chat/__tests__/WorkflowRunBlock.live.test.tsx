/**
 * @vitest-environment jsdom
 *
 * Regression: the chat run card never listened to its run.
 *
 * It imported the streaming hook and never called it, so after its first REST read it showed
 * "running" forever unless a builder canvas for the same run happened to be mounted (and fed the
 * shared manager). It now streams its run while the run can still move, and a reconnect re-reads
 * it through the manager.
 *
 * Wired with the REAL provider, manager, store and streaming hook; only the socket and the HTTP
 * layer are faked. The fake socket delivers one payload object to every handler of a channel,
 * exactly like wsClient does, which is what the double-surface test relies on.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, act, waitFor } from '@testing-library/react';
import * as React from 'react';

const ws = vi.hoisted(() => ({
  channels: new Map<string, Set<(data: unknown) => void>>(),
  requestSnapshot: new Map<string, boolean>(),
  reconnectListeners: new Set<() => void>(),
}));

vi.mock('@/lib/websocket', async () => {
  const { useEffect, useRef } = await import('react');
  return {
    wsClient: {
      sendAction: vi.fn().mockResolvedValue(undefined),
      onReconnected: (listener: () => void) => {
        ws.reconnectListeners.add(listener);
        return () => { ws.reconnectListeners.delete(listener); };
      },
    },
    useChannel: (channel: string | null, handler: (data: unknown) => void, options?: { requestSnapshot?: boolean }) => {
      const handlerRef = useRef(handler);
      handlerRef.current = handler;
      useEffect(() => {
        if (!channel) return;
        const wrapped = (data: unknown) => handlerRef.current(data);
        if (!ws.channels.has(channel)) ws.channels.set(channel, new Set());
        ws.channels.get(channel)!.add(wrapped);
        ws.requestSnapshot.set(channel, !!options?.requestSnapshot);
        return () => {
          ws.channels.get(channel)?.delete(wrapped);
          if (ws.channels.get(channel)?.size === 0) ws.channels.delete(channel);
        };
      }, [channel]);
    },
  };
});

const mockGetRunState = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: (...args: unknown[]) => mockGetRunState(...args),
    execution: { getRunSignals: vi.fn().mockResolvedValue([]) },
  },
}));
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: { getRunState: vi.fn(), getRun: vi.fn(), startWorkflowRun: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: {} }));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({ NodeIcon: () => null }));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    (values ? [key, ...Object.values(values)].join(' ') : key),
}));

import { WorkflowRunBlock } from '../WorkflowRunBlock';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';
import { useWorkflowStreaming } from '@/app/workflows/builder/hooks/execution/useWorkflowStreaming';
import { getWorkflowRunManager, deleteWorkflowRunManager } from '@/contexts/workflow-run';

function emit(channel: string, payload: Record<string, unknown>): void {
  // One object for every handler, like wsClient.
  for (const handler of Array.from(ws.channels.get(channel) ?? [])) handler(payload);
}

function state(runId: string, overrides: Record<string, unknown> = {}) {
  return {
    runId,
    workflowId: 'wf-1',
    status: 'running',
    executionMode: 'automatic',
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: ['mcp:fetch'],
    steps: [{ stepId: 'mcp:fetch', stepAlias: 'Fetch', status: 'RUNNING' }],
    edges: [],
    plan: { triggers: [{ type: 'manual', label: 'Start' }], mcps: [], cores: [], edges: [] },
    seq: 10,
    ...overrides,
  };
}

const waitingState = (runId: string) => state(runId, {
  status: 'waiting_trigger',
  runningStepIds: [],
  completedStepIds: ['mcp:fetch'],
  steps: [{ stepId: 'mcp:fetch', stepAlias: 'Fetch', status: 'COMPLETED' }],
  readySteps: ['trigger:start'],
  seq: 14,
});

const completedState = (runId: string) => state(runId, {
  status: 'completed',
  runningStepIds: [],
  completedStepIds: ['mcp:fetch'],
  steps: [{ stepId: 'mcp:fetch', stepAlias: 'Fetch', status: 'COMPLETED' }],
  seq: 14,
});

function renderCard(runId: string, extra?: React.ReactNode) {
  return render(
    <WorkflowRunProvider>
      <WorkflowRunBlock workflowId="wf-1" runId={runId} />
      {extra}
    </WorkflowRunProvider>,
  );
}

const usedRunIds: string[] = [];
function newRunId(tag: string): string {
  const runId = `run_1_${tag}`;
  usedRunIds.push(runId);
  return runId;
}

describe('WorkflowRunBlock - follows its run live', () => {
  beforeEach(() => {
    mockGetRunState.mockReset();
    ws.channels.clear();
    ws.requestSnapshot.clear();
    ws.reconnectListeners.clear();
  });

  afterEach(() => {
    cleanup();
    for (const runId of usedRunIds.splice(0)) deleteWorkflowRunManager(runId);
  });

  it('subscribes to its run channel (with a snapshot) and shows the run finishing', async () => {
    const runId = newRunId('live');
    const channel = `workflow:run:${runId}`;
    mockGetRunState.mockResolvedValue(state(runId));

    renderCard(runId);

    await waitFor(() => expect(ws.channels.has(channel)).toBe(true));
    expect(ws.requestSnapshot.get(channel)).toBe(true);
    expect(screen.getByText('headerRunning live')).toBeTruthy();

    // The backend now reports the run finished; the event arrives on the channel.
    mockGetRunState.mockResolvedValue(completedState(runId));
    act(() => emit(channel, { type: 'workflowStatus', runId, status: 'COMPLETED' }));

    await waitFor(() => expect(screen.getByText(/status\.completed/)).toBeTruthy());
    // A finished run has nothing left to follow: the channel slot is released.
    await waitFor(() => expect(ws.channels.has(channel)).toBe(false));
  });

  it('recovers an epoch that closed while the socket was down, on reconnect', async () => {
    const runId = newRunId('drop');
    mockGetRunState.mockResolvedValue(state(runId));

    renderCard(runId);
    await waitFor(() => expect(screen.getByText('headerRunning drop')).toBeTruthy());

    // The epoch closed while the tab had no socket: the closing snapshot reached nobody.
    mockGetRunState.mockResolvedValue(waitingState(runId));
    await act(async () => {
      for (const listener of Array.from(ws.reconnectListeners)) listener();
    });

    await waitFor(() => expect(screen.queryByText('headerRunning drop')).toBeNull());
    expect(screen.getByText('run')).toBeTruthy(); // the waiting_trigger "Run" button
  });

  it('shows the run finished when the card is remounted after it finished server-side, with no WS event', async () => {
    const runId = newRunId('remount');
    mockGetRunState.mockResolvedValue(state(runId));

    const first = renderCard(runId);
    await waitFor(() => expect(screen.getByText('headerRunning remount')).toBeTruthy());
    first.unmount(); // the chat scrolls away or the conversation is left; the manager survives

    // The run finishes while no card is mounted. Nothing is published to this tab afterwards.
    mockGetRunState.mockResolvedValue(completedState(runId));
    renderCard(runId);

    await waitFor(() => expect(screen.getByText(/status\.completed/)).toBeTruthy());
    expect(screen.queryByText('headerRunning remount')).toBeNull();
  });

  it('drops the channel once the first read says the run is already finished', async () => {
    const runId = newRunId('done');
    mockGetRunState.mockResolvedValue(completedState(runId));

    renderCard(runId);

    await waitFor(() => expect(screen.getByText(/status\.completed/)).toBeTruthy());
    await waitFor(() => expect(ws.channels.has(`workflow:run:${runId}`)).toBe(false));
  });

  it('catches a run that finishes while its first read is in flight (the read-then-finish window)', async () => {
    const runId = newRunId('window');
    const channel = `workflow:run:${runId}`;
    let answerRead!: (value: unknown) => void;
    mockGetRunState.mockImplementationOnce(() => new Promise(resolve => { answerRead = resolve; }));
    // Every later read is stale too: the only source of "completed" is the live event, so the
    // card cannot pass through a follow-up refresh.
    mockGetRunState.mockResolvedValue(state(runId));

    renderCard(runId);

    // Listening from mount, before the read answers: the snapshot requested on subscribe is
    // skipped for an unchanged state, so a subscription opened after the read would have a gap.
    await waitFor(() => expect(ws.channels.has(channel)).toBe(true));

    // The run finishes now, BEFORE the read answers: the event reaches the run while its read
    // is still in flight...
    act(() => emit(channel, { type: 'workflowStatus', runId, status: 'COMPLETED' }));
    expect(getWorkflowRunManager(runId).getState().runStatus).toBe('completed');

    // ...and the read, taken just before the finish, then answers "running".
    await act(async () => { answerRead(state(runId)); });

    await waitFor(() => expect(screen.getByText(/status\.completed/)).toBeTruthy());
    expect(screen.queryByText('headerRunning window')).toBeNull();
    expect(getWorkflowRunManager(runId).getState().runStatus).toBe('completed');
  });

  it('feeds the run manager each event once when a builder canvas streams the same run', async () => {
    const runId = newRunId('twice');
    const channel = `workflow:run:${runId}`;
    mockGetRunState.mockResolvedValue(state(runId));

    function CanvasStreaming() {
      useWorkflowStreaming(runId);
      return null;
    }
    renderCard(runId, <CanvasStreaming />);

    await waitFor(() => expect(ws.channels.get(channel)?.size).toBe(2));
    const handleEvent = vi.spyOn(getWorkflowRunManager(runId), 'handleEvent');

    act(() => emit(channel, { type: 'decisionEvaluated', runId, coreId: 'core:check', selectedBranch: 'if' }));

    expect(handleEvent).toHaveBeenCalledTimes(1);
  });

  it('renders without a run provider (markdown on a page that mounts none) instead of crashing', () => {
    const runId = newRunId('bare');

    expect(() => render(<WorkflowRunBlock workflowId="wf-1" runId={runId} />)).not.toThrow();
    expect(ws.channels.size).toBe(0);
  });
});
