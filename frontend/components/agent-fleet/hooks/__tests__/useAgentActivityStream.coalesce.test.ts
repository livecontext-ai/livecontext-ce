import { describe, it, expect, beforeEach } from 'vitest';
import { useAgentActivityStore, type AgentActivityEvent } from '../useAgentActivityStream';

/**
 * Coalescing contract for the agent-activity store: the fleet subscribes ~20 agent:activity
 * channels with requestSnapshot=true, so on connect ~20 execution_started events arrive in a
 * burst. handleEvent must BUFFER them and apply ONE store update per frame (flushed here via
 * the exposed flushNow()), not one synchronous set()/commit per event - that was the 170ms
 * main-thread block. The reducer semantics (ordering, completionSeq, stale-snapshot guard,
 * re-announce) must be preserved across the batch.
 */
const evt = (over: Partial<AgentActivityEvent> & Pick<AgentActivityEvent, 'event' | 'agentEntityId' | 'executionId'>): AgentActivityEvent => ({
  timestamp: '2026-06-16T00:00:00Z',
  ...over,
});

function dispatch(agentId: string, event: AgentActivityEvent) {
  useAgentActivityStore.getState().handleEvent(agentId, event);
}

describe('useAgentActivityStore - event coalescing', () => {
  beforeEach(() => {
    // Drain any buffered events from a prior test, then reset the store clean.
    useAgentActivityStore.getState().flushNow();
    useAgentActivityStore.setState({ agents: {}, completionSeq: 0 });
  });

  it('buffers events - handleEvent does NOT mutate the store synchronously', () => {
    dispatch('a1', evt({ event: 'execution_started', agentEntityId: 'a1', executionId: 'e1' }));
    dispatch('a2', evt({ event: 'execution_started', agentEntityId: 'a2', executionId: 'e2' }));

    // Still empty: the burst is buffered for the next frame, not committed per-event.
    expect(useAgentActivityStore.getState().agents).toEqual({});

    useAgentActivityStore.getState().flushNow();

    const { agents } = useAgentActivityStore.getState();
    expect(agents.a1?.isRunning).toBe(true);
    expect(agents.a1?.currentExecutionId).toBe('e1');
    expect(agents.a2?.isRunning).toBe(true);
  });

  it('applies a burst of N agents in ONE flush, preserving per-agent reduction', () => {
    for (let i = 0; i < 20; i++) {
      dispatch(`agent-${i}`, evt({ event: 'execution_started', agentEntityId: `agent-${i}`, executionId: `exec-${i}` }));
    }
    useAgentActivityStore.getState().flushNow();

    const { agents } = useAgentActivityStore.getState();
    expect(Object.keys(agents)).toHaveLength(20);
    for (let i = 0; i < 20; i++) {
      expect(agents[`agent-${i}`]?.isRunning).toBe(true);
      expect(agents[`agent-${i}`]?.currentExecutionId).toBe(`exec-${i}`);
    }
  });

  it('sums completionSeq across all execution_completed events in a batch', () => {
    dispatch('a1', evt({ event: 'execution_started', agentEntityId: 'a1', executionId: 'e1' }));
    dispatch('a2', evt({ event: 'execution_started', agentEntityId: 'a2', executionId: 'e2' }));
    dispatch('a1', evt({ event: 'execution_completed', agentEntityId: 'a1', executionId: 'e1', status: 'COMPLETED' }));
    dispatch('a2', evt({ event: 'execution_completed', agentEntityId: 'a2', executionId: 'e2', status: 'FAILED' }));
    useAgentActivityStore.getState().flushNow();

    // Two completions in the batch → completionSeq bumped by exactly 2 (refetch-on-completion fires once per completion).
    expect(useAgentActivityStore.getState().completionSeq).toBe(2);
    expect(useAgentActivityStore.getState().agents.a1?.isRunning).toBe(false);
    expect(useAgentActivityStore.getState().agents.a2?.isRunning).toBe(false);
  });

  it('applies ordered events for one agent within a batch (started → tool → completed)', () => {
    dispatch('a1', evt({ event: 'execution_started', agentEntityId: 'a1', executionId: 'e1' }));
    dispatch('a1', evt({ event: 'tool_call_started', agentEntityId: 'a1', executionId: 'e1', toolName: 'web_search' }));
    dispatch('a1', evt({ event: 'tool_call_completed', agentEntityId: 'a1', executionId: 'e1', toolName: 'web_search', success: true }));
    useAgentActivityStore.getState().flushNow();

    const a1 = useAgentActivityStore.getState().agents.a1;
    expect(a1?.isRunning).toBe(true);
    expect(a1?.currentToolName).toBeNull();      // tool completed
    expect(a1?.toolCallCount).toBe(1);
    expect(a1?.lastEvent?.event).toBe('tool_call_completed');
  });

  it('preserves the stale-snapshot guard within a batch (a post-completion started for the SAME exec does not resurrect)', () => {
    dispatch('a1', evt({ event: 'execution_started', agentEntityId: 'a1', executionId: 'e1' }));
    dispatch('a1', evt({ event: 'execution_completed', agentEntityId: 'a1', executionId: 'e1', status: 'COMPLETED' }));
    // A snapshot replay of the SAME (now-finished) execution arrives after completion.
    dispatch('a1', evt({ event: 'execution_started', agentEntityId: 'a1', executionId: 'e1' }));
    useAgentActivityStore.getState().flushNow();

    // The agent must stay finished - the stale started is dropped, exactly as in the un-batched path.
    expect(useAgentActivityStore.getState().agents.a1?.isRunning).toBe(false);
    expect(useAgentActivityStore.getState().completionSeq).toBe(1);
  });

  it('a fully no-op batch (only a dropped stale event) leaves the store reference unchanged', () => {
    // Seed a completed agent.
    dispatch('a1', evt({ event: 'execution_started', agentEntityId: 'a1', executionId: 'e1' }));
    dispatch('a1', evt({ event: 'execution_completed', agentEntityId: 'a1', executionId: 'e1', status: 'COMPLETED' }));
    useAgentActivityStore.getState().flushNow();
    const before = useAgentActivityStore.getState().agents;

    // Only a stale (dropped) started in this batch.
    dispatch('a1', evt({ event: 'execution_started', agentEntityId: 'a1', executionId: 'e1' }));
    useAgentActivityStore.getState().flushNow();

    expect(useAgentActivityStore.getState().agents).toBe(before); // same reference → no re-render
  });
});
