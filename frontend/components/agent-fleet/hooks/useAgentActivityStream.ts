'use client';

import { useCallback } from 'react';
import { useChannel } from '@/lib/websocket/use-channel';
import { create } from 'zustand';
import { useStoreWithEqualityFn } from 'zustand/traditional';

// ─── Event types from backend AgentActivityPublisher ───

export interface AgentActivityEvent {
  event: 'execution_started' | 'tool_call_started' | 'tool_call_completed' | 'execution_completed';
  executionId: string;
  agentEntityId: string;
  timestamp: string;
  /**
   * When set, this execution was started to work on a specific task - consumers (e.g. the task
   * board shimmer) should scope visual indicators to (agentId, taskId) rather than per-agent.
   * Null when the agent is running outside any task (direct chat, workflow, schedule with no work).
   */
  taskId?: string;
  // execution_started
  model?: string;
  source?: string;
  // tool_call_started / tool_call_completed
  toolName?: string;
  toolCallId?: string;
  // tool_call_completed
  success?: boolean;
  durationMs?: number;
  // execution_completed
  status?: string;
  totalTokens?: number;
  totalToolCalls?: number;
}

export interface AgentActivityState {
  isRunning: boolean;
  currentExecutionId: string | null;
  currentToolName: string | null;
  toolCallCount: number;
  /** The task this agent is currently working on, if any. Null for non-task executions. */
  currentTaskId: string | null;
  lastEvent: AgentActivityEvent | null;
}

const EMPTY_STATE: AgentActivityState = {
  isRunning: false,
  currentExecutionId: null,
  currentToolName: null,
  toolCallCount: 0,
  currentTaskId: null,
  lastEvent: null,
};

// ─── Zustand store for fleet-wide agent activity ───

interface AgentActivityStore {
  agents: Record<string, AgentActivityState>;
  /** Monotonic counter incremented on every execution_completed - used by consumers to trigger refetch. */
  completionSeq: number;
  handleEvent: (agentId: string, event: AgentActivityEvent) => void;
  clearAgent: (agentId: string) => void;
  /** Flush any buffered events immediately (test/SSR hook - see the coalescing note below). */
  flushNow: () => void;
}

type AgentTimers = Record<string, ReturnType<typeof setTimeout>>;

/**
 * Pure per-event reducer. Applies ONE activity event to an {@code agents} map and returns
 * the (possibly new) map + a completionSeq bump. The semantics are identical to the former
 * inline switch - only the dispatch is now batched (see {@link useAgentActivityStore}).
 * Returns the SAME {@code agents} reference (seqBump 0) when the event is a no-op (the
 * stale-snapshot guard), so the flush can detect "nothing changed".
 */
function reduceAgentEvent(
  agents: Record<string, AgentActivityState>,
  timers: AgentTimers,
  agentId: string,
  event: AgentActivityEvent,
  get: () => AgentActivityStore,
): { agents: Record<string, AgentActivityState>; seqBump: number } {
  const current = agents[agentId];
  let next: AgentActivityState;
  let seqBump = 0;

  switch (event.event) {
    case 'execution_started': {
      // Stale-snapshot guard. The activity snapshot re-publishes execution_started for
      // executions the DB still reports RUNNING; if one lands just AFTER we already
      // processed execution_completed for the SAME execution (a snapshot/completion
      // race), do NOT resurrect a finished agent - drop it. A genuine new run always
      // carries a DIFFERENT executionId (dispatcher-minted UUID) and is unaffected.
      if (current
          && current.lastEvent?.event === 'execution_completed'
          && current.lastEvent?.executionId === event.executionId) {
        return { agents, seqBump: 0 };
      }
      // Clear any previous stale timer, start a new one
      if (timers[agentId]) clearTimeout(timers[agentId]);
      timers[agentId] = setTimeout(() => {
        const s = get();
        if (s.agents[agentId]?.isRunning) {
          s.clearAgent(agentId);
        }
      }, 10 * 60 * 1000); // 10 minutes
      // Idempotent re-announcement. The agent:activity snapshot (replayed on a
      // LATE WS subscribe - mirrors workflow:run's requestSnapshot) re-publishes
      // execution_started for the still-running execution so a client that arrived
      // mid-run learns the agent is busy. When this re-announces the SAME execution
      // we're already tracking, preserve the live counters instead of resetting
      // them - a snapshot fires on the shared channel for EVERY newly subscribing
      // client, so a naive reset would blank out every other viewer's in-flight
      // tool state on each new subscriber.
      const isReannounce = current?.isRunning === true
        && current.currentExecutionId === event.executionId;
      next = {
        isRunning: true,
        currentExecutionId: event.executionId,
        currentToolName: isReannounce ? current?.currentToolName ?? null : null,
        toolCallCount: isReannounce ? current?.toolCallCount ?? 0 : 0,
        // Preserve a taskId we already learned about. The bridge sync schedule
        // path emits execution_started with taskId=null because the agent picks
        // its task via MCP after dispatch - if claimTask's synthetic
        // tool_call_started lands FIRST (Redis ordering / clock skew), naively
        // overwriting to null would erase the (agent, task) link and break the
        // task board shimmer for the entire run.
        currentTaskId: event.taskId ?? current?.currentTaskId ?? null,
        // On re-announce keep the existing lastEvent so a snapshot replay doesn't
        // regress the displayed activity from a live tool event back to the start.
        lastEvent: isReannounce ? current?.lastEvent ?? event : event,
      };
      break;
    }

    case 'tool_call_started':
      next = {
        ...(current || EMPTY_STATE),
        // Bootstrap isRunning when we hear a tool event for an agent we haven't
        // seen yet. AgentTaskService.claimTask publishes a synthetic
        // tool_call_started(task_claim) that can race ahead of the bridge sync
        // execution_started - without this default the shimmer would never
        // activate. Stay at false when current already ran to completion, so a
        // stale post-completion tool event doesn't resurrect a dead agent state.
        isRunning: current?.isRunning ?? true,
        currentToolName: event.toolName || null,
        // Preserve taskId from the running execution; tool events may omit it on
        // legacy payloads - fall back to what we already had rather than overwriting.
        currentTaskId: event.taskId ?? current?.currentTaskId ?? null,
        lastEvent: event,
      };
      break;

    case 'tool_call_completed':
      next = {
        ...(current || EMPTY_STATE),
        currentToolName: null,
        toolCallCount: (current?.toolCallCount ?? 0) + 1,
        currentTaskId: event.taskId ?? current?.currentTaskId ?? null,
        lastEvent: event,
      };
      break;

    case 'execution_completed': {
      if (timers[agentId]) {
        clearTimeout(timers[agentId]);
        delete timers[agentId];
      }
      next = {
        isRunning: false,
        currentExecutionId: null,
        currentToolName: null,
        toolCallCount: 0,
        currentTaskId: null,
        lastEvent: event,
      };
      seqBump = 1;
      break;
    }

    default:
      return { agents, seqBump: 0 };
  }

  return { agents: { ...agents, [agentId]: next }, seqBump };
}

// ── Coalescing buffer ──
// The fleet subscribes ~20 agent:activity channels with requestSnapshot=true, so on
// connect the gateway replays ~20 execution_started events back-to-back. Each WS frame is
// its OWN message task, so a synchronous set() per event was up to ~20 separate React
// commits in one frame (the "[Violation] 'message' handler took 170ms"). We instead buffer
// events and flush ONE set() per animation frame - requestAnimationFrame (NOT a microtask,
// which would fire per-task and not coalesce across the burst) batches the whole burst into
// a single commit. Ordering is preserved (FIFO) and completionSeq bumps are summed, so
// refetch-on-completion consumers still fire exactly once per completion.
let pendingEvents: Array<{ agentId: string; event: AgentActivityEvent }> = [];
let flushScheduled = false;

function clearPendingEvents() {
  pendingEvents = [];
}

export const useAgentActivityStore = create<AgentActivityStore>((set, get) => {
  const flush = () => {
    flushScheduled = false;
    if (pendingEvents.length === 0) return;
    const batch = pendingEvents;
    pendingEvents = [];
    set((state) => {
      const timers = (state as any)._timers as AgentTimers;
      let agents = state.agents;
      let seqBump = 0;
      for (const { agentId, event } of batch) {
        const result = reduceAgentEvent(agents, timers, agentId, event, get);
        agents = result.agents;
        seqBump += result.seqBump;
      }
      if (agents === state.agents && seqBump === 0) {
        return state; // every event was a no-op (stale-snapshot guard)
      }
      return { agents, completionSeq: state.completionSeq + seqBump };
    });
  };

  const scheduleFlush = () => {
    if (flushScheduled) return;
    flushScheduled = true;
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(flush);
    } else {
      setTimeout(flush, 0);
    }
  };

  return {
    agents: {},
    completionSeq: 0,
    // Auto-clear stale "running" states after 10 minutes (safety net if execution_completed is lost)
    _timers: {} as AgentTimers,

    handleEvent: (agentId: string, event: AgentActivityEvent) => {
      pendingEvents.push({ agentId, event });
      scheduleFlush();
    },

    flushNow: flush,

    clearAgent: (agentId: string) => {
      set((state) => {
        const { [agentId]: _, ...rest } = state.agents;
        return { agents: rest };
      });
    },
  };
});

// Phase 3 (2026-05-18) - HMR-safe module-singleton subscriber. The agent
// activity map accumulates per-agentId state from the WS channel
// (`agent:activity:{agentId}`); on workspace switch we drop the map so
// activity from agents only visible in the previous workspace doesn't
// linger. The Symbol.for() key on globalThis survives Next.js fast-refresh
// module reloads; dispose-then-rebind keeps exactly one live subscriber.
if (typeof window !== 'undefined') {
  const HMR_KEY = Symbol.for('__lc_orgReset:useAgentActivityStore');
  const g = globalThis as unknown as Record<symbol, (() => void) | undefined>;
  if (typeof g[HMR_KEY] === 'function') g[HMR_KEY]!();
  import('@/lib/stores/current-org-store').then(({ useCurrentOrgStore }) => {
    g[HMR_KEY] = useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => {
        clearPendingEvents(); // drop any buffered events from the previous workspace
        useAgentActivityStore.setState({ agents: {}, completionSeq: 0 });
      },
    );
  }).catch(() => {});
}

// ─── Shared equality function for visual activity selectors ───

const activityEq = (
  a: AgentActivityState | undefined,
  b: AgentActivityState | undefined,
): boolean =>
  a?.isRunning === b?.isRunning
  && a?.currentToolName === b?.currentToolName
  && a?.toolCallCount === b?.toolCallCount
  && a?.currentTaskId === b?.currentTaskId
  && a?.lastEvent?.event === b?.lastEvent?.event
  && a?.lastEvent?.executionId === b?.lastEvent?.executionId;

/**
 * Optimized selector for a single agent's activity state.
 * Uses a custom equality function to prevent re-renders when non-visual fields change
 * (e.g. lastEvent timestamp updates without changing event type or execution ID).
 */
export function useAgentActivity(agentId: string | null) {
  return useStoreWithEqualityFn(
    useAgentActivityStore,
    useCallback((s: AgentActivityStore) => (agentId ? s.agents[agentId] : undefined), [agentId]),
    activityEq,
  );
}

/**
 * Individual channel subscriber component - rendered per agent in fleet view.
 * Keeps each useChannel call in its own hook invocation to satisfy Rules of Hooks.
 */
export function useAgentActivitySubscriber(agentId: string) {
  const handleEvent = useAgentActivityStore((s) => s.handleEvent);

  const handler = useCallback(
    (data: AgentActivityEvent) => {
      if (data?.event) {
        handleEvent(agentId, data);
      }
    },
    [agentId, handleEvent],
  );

  // requestSnapshot mirrors workflow:run - on a LATE subscribe the gateway asks
  // agent-service to re-publish execution_started for any execution still RUNNING,
  // so opening the page mid-run (esp. for bridge/CLI agents, whose only activity
  // events are execution_started/completed) still surfaces the "working" state
  // instead of staying idle until completion.
  useChannel<AgentActivityEvent>(
    `agent:activity:${agentId}`,
    handler,
    { requestSnapshot: true },
  );
}
