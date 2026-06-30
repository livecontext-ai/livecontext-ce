import { useCallback, useRef } from 'react';
import { useWorkflowRunContext } from '@/contexts/WorkflowRunContext';
import { useChannel } from '@/lib/websocket';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';

/**
 * Event types that should be routed to handleEvent() instead of handleBatchUpdate().
 *
 * The set MUST stay aligned with the backend's wire-type table at
 * `WorkflowEventPublisher.EVENT_TYPE_WIRE_NAMES` (Java) plus the named
 * events from `WorkflowStreamingService` / `StepByStepEventService` /
 * `WorkflowEventEmitter` (workflowConfiguration, readySteps, …).
 *
 * The 9 camelCase types from `EVENT_TYPE_WIRE_NAMES` (`stepStatus`, `edgeStatus`,
 * `workflowStatistics`, `loopEvent`, `retryEvent`, `debugLog`, `mergeEvent`,
 * `agentToolCall`, plus `workflowStatus`) used to fall through to
 * `handleBatchUpdate`; there `hasSnapshotData=false` (these payloads carry
 * `normalizedStepId/edgeId/...` but not the `.steps/.edges` snapshot keys),
 * so `processBatchUpdate` was skipped - yet `lastKnownSeq` had already been
 * bumped. Result: the next legitimate REST `/state` snapshot was strict-`<`
 * dropped, and the UI froze on the partial state. (Manifested as "no
 * shimmer / no status updates" in prod after multiple-trigger fires.)
 */
const INDIVIDUAL_EVENT_TYPES = new Set([
  'readySteps',
  'decisionEvaluated',
  'workflowStatus',
  'workflowPaused',
  'workflowResuming',
  'stepRerun',
  'agentBrowseStep',
  // 2026-05-05 wire-types alignment with backend EVENT_TYPE_WIRE_NAMES.
  'stepStatus',
  'edgeStatus',
  'workflowStatistics',
  'loopEvent',
  'retryEvent',
  'debugLog',
  'mergeEvent',
  'agentToolCall',
  'workflowConfiguration',
]);

/**
 * Custom hook to manage real-time updates for a workflow run via WebSocket.
 *
 * Subscribes to the WebSocket channel `workflow:run:{runId}` and forwards
 * events to the WorkflowRunManager pipeline. Routes events by type:
 * - batch-update → handleBatchUpdate() (debounced visualization updates)
 * - readySteps, decisionEvaluated, workflowStatus → handleEvent() (immediate state updates)
 *
 * @param runId - The workflow run ID to connect to
 * @param enabled - Whether real-time updates should be active (default: true)
 */
export function useWorkflowStreaming(
  runId: string | undefined | null,
  enabled: boolean = true
) {
  const runContext = useWorkflowRunContext();
  const lastProcessedRef = useRef<number>(0);

  // WebSocket channel handler - routes events by type
  const handleWsMessage = useCallback(
    (data: any) => {
      if (!runContext || !runId) return;

      // The inner payload from Redis has a "type" field identifying the event kind
      const eventType = data?.type as string | undefined;

      // Route individual events (readySteps, decisionEvaluated, etc.) to handleEvent()
      if (eventType && INDIVIDUAL_EVENT_TYPES.has(eventType)) {
        runContext.handleEvent?.(eventType, data);
        return;
      }

      // Dedup batch-updates by timestamp to avoid processing duplicate snapshots
      const ts = data?.timestamp || 0;
      if (ts > 0 && ts <= lastProcessedRef.current) return;
      if (ts > 0) lastProcessedRef.current = ts;

      // Forward batch-update to the existing WorkflowRunManager pipeline
      runContext.handleBatchUpdate?.(data);
    },
    [runContext, runId]
  );

  // Subscribe to WebSocket channel for this run.
  // In publication preview the showcase clone is inert (no live execution),
  // so subscribing wastes a connection and would auth-fail for anonymous
  // visitors. Skip the channel entirely.
  //
  // 2026-05-04 hot-fix: the Phase A1 debounce 250ms (intended to absorb a
  // theoretical React 18 strict-mode double-invoke storm) caused real-world
  // unsubscribe/re-subscribe cycles in production - visible in gateway logs
  // as 3s/8s/30s subscription churn for active runs. Revert to the original
  // direct read of `getActivePublicPreview()`. The `useChannel` hook already
  // dedups when the resolved channel string is unchanged across renders.
  const wsChannel = enabled && runId && !getActivePublicPreview() ? `workflow:run:${runId}` : null;
  useChannel(wsChannel, handleWsMessage, { requestSnapshot: true });

  // Get current connection state
  const state = runContext?.getState(runId || '');

  return {
    isConnected: state?.isConnected ?? false,
    isLoading: state?.isLoading ?? false,
    error: state?.error ?? null,
  };
}
