import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/websocket/use-channel', () => ({
  useChannel: vi.fn(),
}));

import type { AgentActivityEvent } from '../useAgentActivityStream';
import { useAgentActivityStore } from '../useAgentActivityStream';

const AGENT_ID = 'agent-1';

function event(overrides: Partial<AgentActivityEvent>): AgentActivityEvent {
  return {
    event: 'execution_started',
    executionId: 'exec-1',
    agentEntityId: AGENT_ID,
    timestamp: '2026-05-22T10:00:00Z',
    ...overrides,
  };
}

describe('useAgentActivityStore realtime task scoping', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    useAgentActivityStore.setState({ agents: {}, completionSeq: 0, _timers: {} } as any);
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    useAgentActivityStore.setState({ agents: {}, completionSeq: 0, _timers: {} } as any);
  });

  it('preserves taskId when execution_started arrives after the task-claim tool event', () => {
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'tool_call_started',
      toolName: 'task_claim',
      toolCallId: 'call-1',
      taskId: 'task-1',
    }));

    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_started',
      executionId: 'exec-2',
      taskId: undefined,
      model: 'deepseek-chat',
      source: 'schedule',
    }));

    useAgentActivityStore.getState().flushNow(); // events are buffered + flushed per frame; drain for the assertion
    const state = useAgentActivityStore.getState().agents[AGENT_ID];
    expect(state.isRunning).toBe(true);
    expect(state.currentExecutionId).toBe('exec-2');
    expect(state.currentTaskId).toBe('task-1');
    expect(state.currentToolName).toBeNull();
  });

  it('does not resurrect a completed agent from a stale post-completion tool event', () => {
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_started',
      executionId: 'exec-1',
      taskId: 'task-1',
    }));
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_completed',
      executionId: 'exec-1',
      taskId: 'task-1',
      status: 'success',
      totalTokens: 10,
      totalToolCalls: 1,
    }));

    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'tool_call_started',
      executionId: 'exec-1',
      toolName: 'late_tool',
      toolCallId: 'call-late',
      taskId: undefined,
    }));

    useAgentActivityStore.getState().flushNow(); // events are buffered + flushed per frame; drain for the assertion
    const state = useAgentActivityStore.getState().agents[AGENT_ID];
    expect(state.isRunning).toBe(false);
    expect(state.currentTaskId).toBeNull();
    expect(state.currentToolName).toBe('late_tool');
    expect(useAgentActivityStore.getState().completionSeq).toBe(1);
  });

  it('re-announced execution_started for the SAME running execution preserves live tool counters (snapshot replay)', () => {
    // Live run progresses to its 2nd tool.
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_started', executionId: 'exec-1', taskId: 'task-1',
    }));
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'tool_call_started', executionId: 'exec-1', toolName: 'web_search', toolCallId: 'c1', taskId: 'task-1',
    }));
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'tool_call_completed', executionId: 'exec-1', toolName: 'web_search', toolCallId: 'c1', success: true, taskId: 'task-1',
    }));
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'tool_call_started', executionId: 'exec-1', toolName: 'read_file', toolCallId: 'c2', taskId: 'task-1',
    }));

    // A NEW client subscribes → gateway re-publishes execution_started (snapshot) on the
    // shared channel. This must NOT blank out the in-flight tool state for existing viewers.
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_started', executionId: 'exec-1', taskId: 'task-1', model: 'claude-opus-4-8', source: 'CONVERSATION',
    }));

    useAgentActivityStore.getState().flushNow(); // events are buffered + flushed per frame; drain for the assertion
    const state = useAgentActivityStore.getState().agents[AGENT_ID];
    expect(state.isRunning).toBe(true);
    expect(state.currentExecutionId).toBe('exec-1');
    expect(state.currentToolName).toBe('read_file'); // preserved, not reset to null
    expect(state.toolCallCount).toBe(1);             // preserved, not reset to 0
    expect(state.lastEvent?.event).toBe('tool_call_started'); // not regressed to execution_started
  });

  it('execution_started for a DIFFERENT execution still resets counters (a genuine new run is not a replay)', () => {
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_started', executionId: 'exec-1',
    }));
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'tool_call_started', executionId: 'exec-1', toolName: 'web_search', toolCallId: 'c1',
    }));

    // A new execution starts on the same agent → fresh counters.
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_started', executionId: 'exec-2',
    }));

    useAgentActivityStore.getState().flushNow(); // events are buffered + flushed per frame; drain for the assertion
    const state = useAgentActivityStore.getState().agents[AGENT_ID];
    expect(state.currentExecutionId).toBe('exec-2');
    expect(state.currentToolName).toBeNull();
    expect(state.toolCallCount).toBe(0);
  });

  it('ignores a stale snapshot execution_started for an execution that already completed (no resurrection)', () => {
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_started', executionId: 'exec-1', taskId: 'task-1',
    }));
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_completed', executionId: 'exec-1', taskId: 'task-1',
      status: 'success', totalTokens: 5, totalToolCalls: 1,
    }));

    // A snapshot (the DB still reported RUNNING at query time) re-announces the
    // just-finished execution AFTER its completion landed. Must NOT resurrect it.
    useAgentActivityStore.getState().handleEvent(AGENT_ID, event({
      event: 'execution_started', executionId: 'exec-1', taskId: 'task-1',
    }));

    useAgentActivityStore.getState().flushNow(); // events are buffered + flushed per frame; drain for the assertion
    const state = useAgentActivityStore.getState().agents[AGENT_ID];
    expect(state.isRunning).toBe(false);
    expect(state.lastEvent?.event).toBe('execution_completed');
  });
});
