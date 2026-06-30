'use client';

import React, { useState, useMemo, useRef, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown, ChevronRight, ExternalLink, Play, Clock, Zap, Calendar, Globe, MessageCircle, Database } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  useRun,
  type WorkflowRunNodeState,
  type RunState,
  type TriggerType,
} from '@/contexts/WorkflowRunContext';
import { orchestratorApi, type StepState } from '@/lib/api/orchestrator';
import { useWorkflowStreaming } from '@/app/workflows/builder/hooks/execution/useWorkflowStreaming';
import { Button } from '@/components/ui/button';
import { NodeIcon } from '@/app/workflows/builder/components/nodes/shared';

// Local type alias for node status (extends WorkflowRunNodeState['status'] with paused/stopped/waiting_trigger)
type NodeStatus = 'pending' | 'running' | 'completed' | 'failed' | 'skipped' | 'partial_success' | 'paused' | 'stopped' | 'waiting_trigger';

// Local interface for internal node state (extends context type with optional fields)
interface WorkflowNodeState extends Omit<WorkflowRunNodeState, 'status'> {
  status: NodeStatus;
  uiStatus?: string;
}

// Normalize status string to lowercase standard format (defined outside component for hoisting)
function normalizeStatus(status: string): NodeStatus {
  const normalized = status.toLowerCase();
  switch (normalized) {
    case 'completed':
    case 'success':
      return 'completed';
    case 'failed':
    case 'error':
    case 'failure':
      return 'failed';
    case 'skipped':
    case 'skip':
      return 'skipped';
    case 'running':
    case 'in_progress':
      return 'running';
    case 'partial_success':
      return 'partial_success';
    case 'paused':
      return 'paused';
    case 'stopped':
      return 'stopped';
    case 'waiting_trigger':
      return 'waiting_trigger';
    default:
      return 'pending';
  }
}

export interface WorkflowRunBlockProps {
  /** The workflow ID */
  workflowId: string;
  /** The run ID to connect to */
  runId: string;
  /** Run index for display (e.g., "#3") */
  runIndex?: number;
  /** Workflow name for display */
  workflowName?: string;
  /** Callback when an error occurs */
  onError?: (error: string) => void;
  /** Whether this is a historical run (loaded from saved messages) - collapsed by default */
  isHistorical?: boolean;
  /** Saved status for historical runs (avoids API call) */
  savedStatus?: 'completed' | 'failed' | 'partial_success' | 'cancelled' | 'stopped' | 'paused';
  /** Saved duration in ms for historical runs */
  savedDurationMs?: number;
}

/**
 * Displays workflow execution progress in a minimal timeline style.
 * Matches the tool call style in the conversation.
 */
export function WorkflowRunBlock({
  workflowId,
  runId,
  runIndex,
  workflowName,
  onError,
  isHistorical = false,
  savedStatus,
  savedDurationMs,
}: WorkflowRunBlockProps) {
  const t = useTranslations('chat.workflowRun');

  // Historical runs (after refresh) start collapsed, live runs start expanded
  const [isExpanded, setIsExpanded] = useState(!isHistorical);

  // Track run duration - only start when we actually receive data
  const startTimeRef = useRef<number | null>(null);
  const [runDuration, setRunDuration] = useState<number | null>(null);

  // State for fetched run data (used for historical runs when expanded)
  const [fetchedNodes, setFetchedNodes] = useState<WorkflowNodeState[]>([]);
  const [fetchedStatus, setFetchedStatus] = useState<string | null>(null);
  const [fetchedDurationMs, setFetchedDurationMs] = useState<number | null>(null);
  const [isFetching, setIsFetching] = useState(false);
  const hasFetchedRef = useRef(false);

  // State for starting execution (user confirmation)
  const [isStarting, setIsStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);

  // State for triggering workflow (for waiting_trigger state)
  const [isTriggering, setIsTriggering] = useState(false);
  const [triggerError, setTriggerError] = useState<string | null>(null);

  // Track if user has clicked "Run" to switch to run mode (before showing trigger button)
  const [hasEnteredRunMode, setHasEnteredRunMode] = useState(false);

  // Use the unified run context - WorkflowRunBlock never opens its own streaming connection
  // The Run button (in WorkflowBuilder) is the only thing that opens streaming connections
  // useRun() does NOT auto-connect streaming - it only fetches initial state via REST
  const [runState, runContext] = useRun(isHistorical ? undefined : runId);

  // Fetch run state when expanding a historical run (one-time REST call, not streaming)
  useEffect(() => {
    if (isHistorical && isExpanded && !hasFetchedRef.current && fetchedNodes.length === 0) {
      hasFetchedRef.current = true;
      setIsFetching(true);

      // Fetch both run state (for nodes) and run info (for duration) in parallel
      Promise.all([
        orchestratorApi.getRunState(runId),
        orchestratorApi.getRun(runId),
      ])
        .then(([state, run]) => {
          // Map StepState to WorkflowNodeState, filtering out gateway nodes
          const nodes: WorkflowNodeState[] = state.steps
            .filter((step: StepState) => !step.stepId.startsWith('gateway_'))
            .map((step: StepState) => ({
              nodeId: step.stepId,
              label: step.stepAlias || step.stepId,
              status: normalizeStatus(step.status),
              durationMs: step.executionTimeMs || 0,
              statusCounts: step.statusCounts,
              error: step.errorMessage,
            }));
          setFetchedNodes(nodes);
          setFetchedStatus(state.status);

          // Use run duration from the run object
          if (run.durationMs) {
            setFetchedDurationMs(run.durationMs);
          }
        })
        .catch((err) => {
          console.error('[WorkflowRunBlock] Failed to fetch run state:', err);
          onError?.('Failed to load run details');
        })
        .finally(() => {
          setIsFetching(false);
        });
    }
  }, [isHistorical, isExpanded, runId, fetchedNodes.length, onError]);

  // Use run state for live runs, or fetched nodes for historical runs
  // Always normalize statuses to lowercase for consistent comparison
  const nodes: WorkflowNodeState[] = useMemo(() => {
    // Priority: run state (live) > fetched nodes (historical)
    if (runState && runState.nodes.length > 0) {
      // Convert from context format to component format with normalized status
      return runState.nodes.map(n => ({
        nodeId: n.nodeId,
        label: n.label,
        status: normalizeStatus(n.status),
        durationMs: n.durationMs,
        statusCounts: n.statusCounts,
        error: n.error,
      }));
    }
    // Use fetched nodes for historical runs
    if (fetchedNodes.length > 0) {
      return fetchedNodes;
    }
    return [];
  }, [runState, fetchedNodes]);

  const workflowStatus = useMemo(() => {
    // Priority: run state (live) > fetched status (historical)
    if (runState?.workflowStatus) {
      // Normalize the status to lowercase
      return {
        ...runState.workflowStatus,
        status: normalizeStatus(runState.workflowStatus.status) as RunState['runStatus'],
      };
    }
    // Use fetched status for historical runs
    if (fetchedStatus) {
      return {
        status: normalizeStatus(fetchedStatus) as RunState['runStatus'],
        durationMs: fetchedDurationMs || undefined,
      };
    }
    return null;
  }, [runState, fetchedStatus, fetchedDurationMs]);

  // No error from streaming since we don't open connections
  const error = null;

  // Derive overall status for header display
  // Priority: savedStatus (historical) > fetchedStatus > workflowStatus > nodes
  const displayStatus = useMemo(() => {
    // For historical runs with saved status, use it directly (no API call needed)
    if (isHistorical && savedStatus) {
      return savedStatus;
    }

    // For historical runs with fetched status (from API), use it
    if (isHistorical && fetchedStatus) {
      return normalizeStatus(fetchedStatus);
    }

    // For historical runs without any status, show as completed (not shimmer)
    // Historical runs are from past executions - they can't be "pending" in real-time
    if (isHistorical && !savedStatus && !fetchedStatus && !isFetching) {
      return 'completed'; // Default to completed for historical runs without status
    }

    // While fetching, show pending (but not shimmer since it's historical)
    if (isHistorical && isFetching) {
      return 'completed'; // Still show completed while loading
    }

    // IMPORTANT: Check for waiting_trigger FIRST - this takes precedence over node statuses
    // When waiting for trigger, nodes may exist with 'pending' status but we should show waiting_trigger
    if (workflowStatus?.status === 'waiting_trigger') {
      return 'waiting_trigger';
    }

    // No nodes yet means pending (starting) - only for live runs
    if (nodes.length === 0) {
      // But if workflowStatus says it's done, use that
      if (workflowStatus?.status && ['completed', 'failed', 'cancelled', 'stopped', 'paused', 'partial_success'].includes(workflowStatus.status)) {
        return workflowStatus.status;
      }
      return 'pending';
    }

    // If workflowStatus indicates completion, trust it over node statuses
    // (some nodes like skipped branches may stay pending)
    if (workflowStatus?.status && ['completed', 'failed', 'cancelled', 'stopped', 'paused', 'partial_success'].includes(workflowStatus.status)) {
      return workflowStatus.status;
    }

    // Check node statuses - if any is running or pending, workflow is running
    const hasRunning = nodes.some(n => n.status === 'running');
    const hasPending = nodes.some(n => n.status === 'pending');

    if (hasRunning || hasPending) {
      return 'running';
    }

    // All nodes are resolved (not running/pending) - determine final status from nodes
    const hasCompleted = nodes.some(n => n.status === 'completed');
    const hasFailed = nodes.some(n => n.status === 'failed');
    if (hasFailed && hasCompleted) return 'partial_success';
    if (hasFailed) return 'failed';
    return 'completed';
  }, [workflowStatus, nodes, isHistorical, savedStatus, fetchedStatus, isFetching]);

  const isRunning = displayStatus === 'running' || displayStatus === 'pending';
  const isWaitingTrigger = displayStatus === 'waiting_trigger';
  const isFinished = displayStatus === 'completed' || displayStatus === 'failed' || displayStatus === 'partial_success' || displayStatus === 'paused' || displayStatus === 'cancelled' || displayStatus === 'stopped';

  // Start tracking duration when we receive first node data
  useEffect(() => {
    if (nodes.length > 0 && startTimeRef.current === null) {
      startTimeRef.current = Date.now();
    }
  }, [nodes.length]);

  // Update duration while running
  useEffect(() => {
    if (isRunning && startTimeRef.current !== null) {
      const interval = setInterval(() => {
        setRunDuration(Date.now() - startTimeRef.current!);
      }, 100);
      return () => clearInterval(interval);
    } else if (isFinished && startTimeRef.current !== null && runDuration === null) {
      // Set final duration when finished
      setRunDuration(Date.now() - startTimeRef.current);
    }
  }, [isRunning, isFinished, runDuration]);

  // Effective duration: savedDurationMs (historical) > fetchedDurationMs > local tracking > API durationMs
  const effectiveDuration = savedDurationMs ?? fetchedDurationMs ?? runDuration ?? workflowStatus?.durationMs ?? null;

  const handleToggle = () => {
    setIsExpanded(!isExpanded);
  };

  // Extract short run ID for display (last part after last underscore)
  const shortRunId = runId.split('_').slice(-1)[0] || runId;

  const handleOpenBuilder = (e: React.MouseEvent) => {
    e.stopPropagation();
    window.open(`/app/workflow/${workflowId}/run/${runId}`, '_blank');
  };

  // Start execution (user confirmation)
  const handleStartExecution = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsStarting(true);
    setStartError(null);

    try {
      await orchestratorApi.startWorkflowRun(workflowId, runId);

      // Dispatch event to notify React Flow to switch to run mode
      window.dispatchEvent(new CustomEvent('workflowExecutionStarted', {
        detail: {
          workflowId,
          runId,
          runIndex,
        },
      }));

      // Streaming will automatically update and show execution progress
    } catch (error) {
      console.error('Failed to start workflow execution:', error);
      setStartError('Failed to start execution');
      onError?.('Failed to start execution');
    } finally {
      setIsStarting(false);
    }
  };

  // Enter run mode (first step before triggering)
  const handleEnterRunMode = (e: React.MouseEvent) => {
    e.stopPropagation();

    // Dispatch event to switch ReactFlow to run mode
    window.dispatchEvent(new CustomEvent('workflowExecutionStarted', {
      detail: {
        workflowId,
        runId,
        runIndex,
      },
    }));

    // Mark that we've entered run mode - now show trigger button
    setHasEnteredRunMode(true);
  };

  // Find the trigger step ID from readySteps (e.g., "trigger:manual", "trigger:my_trigger")
  const readySteps = runState?.readySteps || new Set<string>();
  const triggerStepIdFromState = Array.from(readySteps).find(stepId => stepId.startsWith('trigger:'));

  // Get trigger type from run state
  const triggerType: TriggerType = runState?.triggerType || null;

  // Determine trigger behavior based on type
  // - Manual/Datasource: User clicks button to fire
  // - Chat: User sends message (handled separately)
  // - Webhook/Schedule: External trigger, show waiting indicator
  // - null/unknown: Default to manual behavior (clickable)
  const isClickableTrigger = triggerType === 'manual' || triggerType === 'datasource' || triggerType === null;
  const isExternalTrigger = triggerType === 'webhook' || triggerType === 'schedule';
  const isChatTrigger = triggerType === 'chat';

  // Keep track of trigger step ID even when it's removed from readySteps during execution
  const triggerStepIdRef = useRef<string | null>(null);
  if (triggerStepIdFromState) {
    triggerStepIdRef.current = triggerStepIdFromState;
  }
  // Reset ref when no longer waiting for trigger (workflow completed/running)
  useEffect(() => {
    if (!isWaitingTrigger) {
      triggerStepIdRef.current = null;
      setHasEnteredRunMode(false);
    }
  }, [isWaitingTrigger]);

  const triggerStepId = triggerStepIdFromState || triggerStepIdRef.current;

  // Show trigger button only for clickable triggers (manual, datasource)
  const showTriggerButton = !!triggerStepId && isClickableTrigger;
  // Can only click if not already triggering
  const canTrigger = showTriggerButton && !isTriggering;

  // Trigger execution for waiting_trigger state (second step after entering run mode)
  // Uses the same flow as ReactFlow canvas: context.executeStep(runId, triggerId)
  const handleTriggerExecution = async (e: React.MouseEvent) => {
    e.stopPropagation();

    // Same check as NodePlayButton: only execute if trigger is ready
    if (!canTrigger || !triggerStepId) {
      return;
    }

    setIsTriggering(true);
    setTriggerError(null);

    try {
      if (!runContext) {
        throw new Error('Run context not available');
      }

      // Use the same executeStep method as ReactFlow canvas
      // This handles trigger type detection and proper API calls internally
      await runContext.executeStep(runId, triggerStepId);

    } catch (error) {
      console.error('Failed to trigger workflow:', error);
      setTriggerError('Failed to trigger workflow');
      onError?.('Failed to trigger workflow');
    } finally {
      setIsTriggering(false);
    }
  };

  // Get status text for finished runs
  const getStatusText = () => {
    switch (displayStatus) {
      case 'completed':
        return t('status.completed');
      case 'failed':
        return t('status.failed');
      case 'partial_success':
        return t('status.partialSuccess');
      case 'paused':
        return t('status.paused');
      case 'stopped':
        return t('status.stopped');
      default:
        return t('status.finished');
    }
  };

  return (
    <div className="group/run overflow-hidden">
      {/* Header - clickable to expand/collapse */}
      <button
        onClick={handleToggle}
        className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300 transition-colors mb-3 max-w-full"
      >
        {isRunning ? (
          <span className="font-medium shimmer-text">Run {shortRunId}...</span>
        ) : (
          <>
            <Play className="h-3.5 w-3.5 shrink-0" />
            <span className="font-medium text-slate-600 dark:text-slate-300">
              Run {shortRunId} {getStatusText()}
              {effectiveDuration !== null && effectiveDuration > 0 && ` in ${formatDuration(effectiveDuration)}`}
            </span>
          </>
        )}

        {/* Actions on hover */}
        <div className="opacity-0 group-hover/run:opacity-100 transition-opacity flex items-center gap-1">
          {/* Open in Builder button */}
          <span
            role="button"
            onClick={handleOpenBuilder}
            className="p-0.5 hover:bg-slate-200 dark:hover:bg-slate-700 rounded"
          >
            <ExternalLink className="h-3.5 w-3.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300" />
          </span>

          {/* Chevron */}
          {isExpanded ? (
            <ChevronDown className="h-3.5 w-3.5" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5" />
          )}
        </div>
      </button>

      {/* Start Execution Button - shown when workflow is PENDING (awaiting user confirmation) */}
      {displayStatus === 'pending' && nodes.length === 0 && !isHistorical && (
        <div className="mb-3 ml-3">
          {startError && (
            <div className="text-xs text-red-500 mb-2">
              {startError}
            </div>
          )}
          <Button
            variant="default"
            size="sm"
            onClick={handleStartExecution}
            disabled={isStarting}
            className="h-8 px-3"
          >
            {isStarting ? (
              <LoadingSpinner size="xs" className="mr-1" />
            ) : (
              <Play className="w-4 h-4 mr-1" />
            )}
            {isStarting ? t('starting') : t('startExecution')}
          </Button>
        </div>
      )}

      {/* Waiting for Trigger - Step 1: Show "Run" button to enter run mode */}
      {isWaitingTrigger && !isHistorical && !hasEnteredRunMode && (
        <div className="mb-3 ml-3">
          <Button
            variant="default"
            size="sm"
            onClick={handleEnterRunMode}
            className="h-8 px-3"
          >
            <Play className="w-4 h-4 mr-1" />
            {t('run')}
          </Button>
        </div>
      )}

      {/* Waiting for Trigger - Step 2a: Show trigger button for manual/datasource triggers */}
      {isWaitingTrigger && !isHistorical && hasEnteredRunMode && showTriggerButton && (
        <div className="mb-3 ml-3">
          {triggerError && (
            <div className="text-xs text-red-500 mb-2">
              {triggerError}
            </div>
          )}
          <Button
            variant="default"
            size="sm"
            onClick={handleTriggerExecution}
            disabled={!canTrigger}
            className="h-8 px-3 bg-amber-500 hover:bg-amber-600 disabled:bg-amber-300"
          >
            {isTriggering ? (
              <LoadingSpinner size="xs" className="mr-1" />
            ) : triggerType === 'datasource' ? (
              <Database className="w-4 h-4 mr-1" />
            ) : (
              <Zap className="w-4 h-4 mr-1" />
            )}
            {isTriggering ? t('triggering') : triggerType === 'datasource' ? t('loadData') : t('fireTrigger')}
          </Button>
        </div>
      )}

      {/* Waiting for Trigger - Step 2b: Show shimmer button for webhook/schedule triggers (external) */}
      {isWaitingTrigger && !isHistorical && hasEnteredRunMode && isExternalTrigger && (
        <div className="mb-3 ml-3">
          <div
            className="relative inline-flex items-center h-8 px-4 rounded-full bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 cursor-default overflow-hidden"
            title={triggerType === 'schedule' ? t('waitingSchedule') : t('listeningWebhook')}
          >
            {/* Shimmer scan effect - same as NodePlayButton */}
            <div
              className="absolute inset-0 pointer-events-none"
              style={{
                background: 'linear-gradient(90deg, transparent 0%, rgba(245, 158, 11, 0.3) 50%, transparent 100%)',
                backgroundSize: '200% 100%',
                animation: 'shimmer-scan 4s ease-in-out infinite',
              }}
            />
            {triggerType === 'schedule' ? (
              <Calendar className="w-4 h-4 mr-1.5 text-slate-600 dark:text-slate-400 relative z-10" />
            ) : (
              <Globe className="w-4 h-4 mr-1.5 text-slate-600 dark:text-slate-400 relative z-10" />
            )}
            <span className="text-sm font-medium text-slate-600 dark:text-slate-400 relative z-10">
              {triggerType === 'schedule' ? t('waitingScheduleShort') : t('waitingWebhookShort')}
            </span>
          </div>
        </div>
      )}

      {/* Waiting for Trigger - Step 2c: Show shimmer button for chat triggers */}
      {isWaitingTrigger && !isHistorical && hasEnteredRunMode && isChatTrigger && (
        <div className="mb-3 ml-3">
          <div
            className="relative inline-flex items-center h-8 px-4 rounded-full bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 cursor-default overflow-hidden"
            title={t('sendMessageTrigger')}
          >
            {/* Shimmer scan effect - green tint for chat */}
            <div
              className="absolute inset-0 pointer-events-none"
              style={{
                background: 'linear-gradient(90deg, transparent 0%, rgba(34, 197, 94, 0.3) 50%, transparent 100%)',
                backgroundSize: '200% 100%',
                animation: 'shimmer-scan 4s ease-in-out infinite',
              }}
            />
            <MessageCircle className="w-4 h-4 mr-1.5 text-slate-600 dark:text-slate-400 relative z-10" />
            <span className="text-sm font-medium text-slate-600 dark:text-slate-400 relative z-10">
              {t('waitingMessage')}
            </span>
          </div>
        </div>
      )}

      {/* Expanded content */}
      {isExpanded && (
        <div className="relative flex flex-col overflow-hidden">
          {/* Error state */}
          {error && (
            <div className="text-xs text-red-500 mb-3">
              {error}
            </div>
          )}

          {/* Loading state */}
          {isFetching && nodes.length === 0 && (
            <div className="text-xs text-slate-400 mb-3">
              {t('loadingDetails')}
            </div>
          )}

          {/* Node timeline */}
          {!error && nodes.length > 0 && (
            <>
              {nodes.map((node) => (
                <NodeTimelineItem
                  key={node.nodeId}
                  node={node}
                />
              ))}
            </>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Individual node in the timeline (matching ActivityFeed style).
 */
function NodeTimelineItem({ node }: { node: WorkflowNodeState }) {
  // Get status counts (handle both uppercase and lowercase keys)
  const counts = node.statusCounts;
  const runningCount = counts?.RUNNING ?? counts?.running ?? 0;
  const successCount = counts?.SUCCESS ?? counts?.success ?? counts?.completed ?? 0;
  const failedCount = counts?.FAILED ?? counts?.failed ?? counts?.error ?? 0;
  const skippedCount = counts?.SKIPPED ?? counts?.skipped ?? 0;
  const totalCount = runningCount + successCount + failedCount + skippedCount;

  // Only show counts if there are multiple (loops/batches) and at least one non-zero
  const hasMultipleCounts = totalCount > 1 || runningCount > 0;

  // Determine effective status - use statusCounts if available to fix "grey" nodes with success
  let effectiveStatus = node.status;
  if (effectiveStatus === 'pending' || effectiveStatus === 'skipped') {
    // If node shows pending/skipped but has success counts, it's actually completed
    if (successCount > 0 && failedCount === 0 && runningCount === 0) {
      effectiveStatus = 'completed';
    } else if (failedCount > 0 && successCount > 0) {
      effectiveStatus = 'partial_success';
    } else if (failedCount > 0) {
      effectiveStatus = 'failed';
    } else if (runningCount > 0) {
      effectiveStatus = 'running';
    }
  }

  const dotColor = getStatusDotColor(effectiveStatus);

  return (
    <div className="relative flex gap-2 pl-[7px] mb-3">
      {/* Status dot */}
      <div className={`absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full ${dotColor}`} />

      <div className="flex-1 ml-3">
        <div className="flex items-center gap-2">
          {/* Node icon */}
          <NodeIcon
            nodeId={node.nodeId}
            alt={node.label || node.nodeId}
            size="xs"
          />

          {/* Node label */}
          <span className="text-sm text-slate-700 dark:text-slate-200 leading-5">
            {node.label || node.nodeId}
          </span>

          {/* Status counts for loops - only show non-zero counts */}
          {hasMultipleCounts && (
            <div className="flex items-center gap-1 text-xs">
              {runningCount > 0 && (
                <span className="text-blue-500">{runningCount}</span>
              )}
              {successCount > 0 && (
                <span className="text-green-500">{successCount}</span>
              )}
              {failedCount > 0 && (
                <span className="text-red-500">{failedCount}</span>
              )}
              {skippedCount > 0 && (
                <span className="text-slate-400">{skippedCount}</span>
              )}
            </div>
          )}

          {/* Duration - show for completed nodes, "<1s" for very fast ones */}
          {effectiveStatus !== 'pending' && effectiveStatus !== 'running' && (
            <span className="flex items-center gap-1 text-xs text-slate-400">
              <Clock className="h-3 w-3" />
              {(node.durationMs ?? 0) < 1000 ? '<1s' : formatDuration(node.durationMs!)}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * Get dot color based on status.
 */
function getStatusDotColor(status: string): string {
  switch (status) {
    case 'completed':
      return 'bg-green-500';
    case 'failed':
      return 'bg-red-500';
    case 'running':
      return 'bg-blue-500 animate-pulse';
    case 'paused':
      return 'bg-yellow-500';
    case 'partial_success':
      return 'bg-orange-500';
    case 'skipped':
    default:
      return 'bg-slate-400';
  }
}


/**
 * Format duration in milliseconds to human-readable string.
 */
function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;
  return `${minutes}m ${remainingSeconds}s`;
}

export default WorkflowRunBlock;
