'use client';

import * as React from 'react';
import clsx from 'clsx';
import { useTranslations } from 'next-intl';
import { Handle, NodeProps, Position } from 'reactflow';
import { CheckCircle, XCircle, Loader2, ThumbsUp, ThumbsDown, Clock, ChevronDown, ChevronUp, Maximize2, MessageSquareQuote } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';

import { getNodeVisual } from '../../data/nodeVisuals';
import type { BuilderNodeData, ApprovalOutput, DerivedNodeStatus, NodeStatus } from '../../types';
import { createDefaultApprovalOutputs } from '../../types';
import { useValidation } from '../../contexts/ValidationContext';
import { NodeActionButtons, NodeHeader, useHoverVisibility, getIconSlug, getStatusBorderColor, ReadyShimmerOverlay } from './shared';
import { findNodeClassById } from '../../nodes/nodeClasses';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { NodePlayButton } from '../NodePlayButton';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { NodeBottomBar } from './NodeBottomBar';
import { requestApprovalReview } from '../../services/approvalReviewStore';
import { ApprovalContextDialog } from '../ApprovalContextDialog';

/**
 * One-line preview of a pending signal's split-item context (e.g. the
 * `current_item` the approval refers to). Prefers the first non-empty string
 * value; falls back to compact JSON. `preview` is capped at 80 chars for the
 * row; `full` carries the untruncated text for the `title` tooltip.
 */
export function formatItemContextPreview(
  itemContext: unknown,
): { preview: string; full: string } | null {
  if (itemContext == null || typeof itemContext !== 'object' || Array.isArray(itemContext)) return null;
  const values = Object.values(itemContext as Record<string, unknown>);
  if (values.length === 0) return null;
  const firstString = values.find(
    (v): v is string => typeof v === 'string' && v.trim() !== '',
  );
  let full: string;
  if (firstString != null) {
    full = firstString;
  } else {
    try {
      full = JSON.stringify(itemContext) ?? '';
    } catch {
      return null;
    }
  }
  if (!full) return null;
  const preview = full.length > 80 ? `${full.slice(0, 79)}…` : full;
  return { preview, full };
}

export function UserApprovalNode({ data, selected, id }: NodeProps<BuilderNodeData>) {
  const tRun = useTranslations('runMode');
  const visuals = getNodeVisual('approval');
  const outputs: ApprovalOutput[] =
    (data.approvalOutputs as ApprovalOutput[] | undefined) ?? createDefaultApprovalOutputs(data.id);
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();
  const { isRunMode, viewingEpoch } = useWorkflowMode();

  const nodeClass = React.useMemo(() => findNodeClassById(data.id || ''), [data.id]);
  const nodeFamily = nodeClass?.family;

  const executionStatus = useNodeExecutionStatus(id, { label: data.label, kind: 'approval' });

  const { hasNodeErrors: checkNodeErrors } = useValidation();
  const hasError = checkNodeErrors(id);

  // --- Approval-specific heuristic ---
  // When the backend auto-executes this node (e.g., after trigger in auto mode),
  // the frontend never calls executeStepByStep(), so awaitingSignalSteps is never
  // populated. Detect awaiting state: node is "running" but frontend isn't actively
  // executing it → it must be waiting for user approval.
  const [hasResolved, setHasResolved] = React.useState(false);

  // Reset hasResolved when node leaves running state (allows re-runs)
  React.useEffect(() => {
    if (!executionStatus.isRunning && !executionStatus.isAwaitingSignal) {
      setHasResolved(false);
    }
  }, [executionStatus.isRunning, executionStatus.isAwaitingSignal]);

  const isAwaitingSignalDetected = !hasResolved && (
    executionStatus.isAwaitingSignal ||
    (executionStatus.isRunning && !executionStatus.isExecuting)
  );

  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    // Historical epoch viewing: use data.status set by useEpochStateViewing
    if (viewingEpoch != null) return data.status;
    if (executionStatus.isStepByStepMode) {
      if (isAwaitingSignalDetected) return 'awaiting_signal';
      if (executionStatus.isRunning) return 'running';
      if (executionStatus.isFailed) return 'failed';
      if (executionStatus.isSkipped) return 'skipped';
      if (executionStatus.isCompleted || executionStatus.isEvaluated) return 'completed';
      if (executionStatus.isReady) return 'ready';

      if (data.status && data.status !== 'pending') {
        return data.status;
      }

      return 'pending';
    }
    // Auto mode: still detect awaiting signal for approval nodes
    if (isAwaitingSignalDetected) return 'awaiting_signal';
    return data.status;
  }, [viewingEpoch, executionStatus, data.status, isAwaitingSignalDetected]);

  const statusBorderColor = getStatusBorderColor(effectiveStatus, hasError, isRunMode || viewingEpoch != null);
  const borderColor = statusBorderColor;
  const isSkipped = !executionStatus.isStepByStepMode && effectiveStatus === 'skipped';
  const isAwaitingSignal = effectiveStatus === 'awaiting_signal';

  const [isResolving, setIsResolving] = React.useState(false);
  const [resolvingItemId, setResolvingItemId] = React.useState<string | null>(null);
  const [showPerItem, setShowPerItem] = React.useState(false);

  // Pending signals with per-item detail (from split context)
  const pendingSignals = executionStatus.pendingSignals ?? [];
  const pendingCount = executionStatus.pendingSignalCount;
  const isSplitContext = pendingSignals.length > 1 && pendingSignals.some(s => s.itemId != null && s.itemId !== '0');

  // In all-epoch view with multiple pending signals, show bulk labels
  const isBulk = viewingEpoch == null && pendingCount > 1;

  const handleResolve = React.useCallback(async (resolution: 'APPROVED' | 'REJECTED', itemId?: string, epoch?: number) => {
    if (itemId != null) {
      setResolvingItemId(itemId);
    } else {
      setIsResolving(true);
      setHasResolved(true);
    }
    try {
      await executionStatus.resolveApproval(resolution, itemId, epoch);
    } finally {
      setIsResolving(false);
      setResolvingItemId(null);
    }
  }, [executionStatus]);

  // Clicking an item row (not its approve/reject buttons) opens the inspector
  // in review mode: node selected, input column widened, output collapsed, and
  // every data navigator jumped to that item's (epoch, itemIndex).
  const handleReviewItem = React.useCallback((signal: { epoch?: number; itemId?: string }) => {
    const itemIndex = signal.itemId != null && Number.isFinite(Number(signal.itemId))
      ? Number(signal.itemId)
      : null;
    requestApprovalReview(id, signal.epoch ?? null, itemIndex);
  }, [id]);

  const APPROVAL_PORTS = ['approved', 'rejected', 'timeout'];

  return (
    <div
      ref={nodeRef}
      className={clsx(
        'group relative rounded-[28px] bg-white/95 dark:bg-gray-800/95 px-5 py-4',
        'backdrop-blur focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
        'border-2 transition-colors',
        isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
        isSkipped && selected && 'opacity-100',
      )}
      style={{
        borderColor,
        borderStyle: 'solid',
        position: 'relative',
        boxShadow: selected ? '0 0 0 2px var(--accent-primary)' : 'none',
      }}
      tabIndex={0}
    >
      {/* Shimmer scan effect for running state (blue, same as other nodes) */}
      {effectiveStatus === 'running' && (
        <div
          className="absolute inset-0 pointer-events-none rounded-[26px]"
          style={{
            background: 'linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
      )}
      {executionStatus.isStepByStepMode && effectiveStatus === 'ready' && (
        <ReadyShimmerOverlay className="absolute inset-0 pointer-events-none rounded-[26px]" />
      )}
      {/* Shimmer scan effect for awaiting approval (amber) */}
      {effectiveStatus === 'awaiting_signal' && (
        <div
          className="absolute inset-0 pointer-events-none rounded-[26px]"
          style={{
            background: 'linear-gradient(90deg, transparent 0%, rgba(245, 158, 11, 0.15) 50%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
      )}

      <NodeHeader
        visuals={{ ...visuals, iconBg: '#fef3c7' }} // Amber theme for approval
        label={data.label}
        iconSlug={getIconSlug(data)}
        nodeId={id}
        nodeKind="approval"
        nodeFamily={nodeFamily}
      />

      <div className="mt-4 space-y-2 text-[11px] text-slate-500" style={{ paddingBottom: effectiveStatus && effectiveStatus !== 'pending' ? '10px' : '0' }}>
        {outputs.map((output, index) => {
          const handleId = output.id;
          const port = APPROVAL_PORTS[index] ?? '';

          return (
            <div
              key={output.id}
              className="relative rounded-2xl border px-3 py-2 transition-all border-theme"
            >
              <div className="flex items-center gap-2">
                <span className="flex items-center justify-center h-3.5 w-3.5 text-slate-500">
                  {port === 'approved' && <ThumbsUp className="h-3 w-3" />}
                  {port === 'rejected' && <ThumbsDown className="h-3 w-3" />}
                  {port === 'timeout' && <Clock className="h-3 w-3" />}
                </span>
                <span className="truncate">
                  {output.label}
                </span>
              </div>
              <Handle
                type="source"
                id={handleId}
                position={Position.Right}
                className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
                style={{
                  right: -27,
                  top: '50%',
                  transform: 'translateY(-50%)',
                  backgroundColor: 'var(--border-color)',
                  opacity: isRunMode ? 0 : 1,
                  pointerEvents: isRunMode ? 'none' : 'auto'
                }}
              />
            </div>
          );
        })}
      </div>

      {/* Approve / Reject buttons - positioned OUTSIDE the node to avoid triggering node selection */}
      {isAwaitingSignal && (
        <div
          // z-50: the expanded per-item list extends below the node and must
          // float above SIBLING nodes (which would otherwise intercept clicks
          // on the lower item rows). ReactFlow Panels (zoom toolbar) live in a
          // separate layer above all nodes and can still overlap - panning the
          // canvas remains the user's escape there.
          className="absolute left-1/2 -translate-x-1/2 flex flex-col items-center gap-1.5 nodrag nopan z-50"
          style={{ top: 'calc(100% + 8px)', minWidth: '220px' }}
          onMouseDown={(e) => e.stopPropagation()}
          onClick={(e) => e.stopPropagation()}
        >
          {/* Configured approval context (single approval). Labelled + clickable
              so it reads clearly ("this is what you're approving") and opens the
              full text in a modal. Per-item context shows in the list below. */}
          {!isSplitContext && pendingSignals[0]?.approvalContext && (
            <ApprovalContextDialog
              signals={pendingSignals}
              resolve={executionStatus.resolveApproval}
              initialSignalId={pendingSignals[0].id}
              data-testid="approval-node-context"
              className="nodrag nopan max-w-[260px] w-full flex flex-col gap-0.5 text-left bg-white dark:bg-gray-800 rounded-lg border border-amber-200 dark:border-amber-500/30 px-2.5 py-1.5 shadow-sm hover:border-amber-300 dark:hover:border-amber-500/50 transition-colors"
            >
              <span className="flex items-center justify-between gap-1 text-[9px] font-semibold uppercase tracking-wide text-amber-600 dark:text-amber-400">
                {tRun('approvalContextLabel')}
                <Maximize2 className="h-2.5 w-2.5 opacity-60" />
              </span>
              <span className="text-[11px] text-slate-600 dark:text-slate-300 line-clamp-2">
                {pendingSignals[0].approvalContext}
              </span>
            </ApprovalContextDialog>
          )}

          {/* Bulk approve/reject row */}
          <div className="flex items-center gap-2">
            <button
              onClick={() => handleResolve('APPROVED')}
              disabled={isResolving}
              className={clsx(
                'flex items-center gap-1 px-3 py-1.5 rounded-full text-xs font-medium transition-colors shadow-sm',
                'bg-emerald-100 text-emerald-700 hover:bg-emerald-200',
                isResolving && 'opacity-50 cursor-not-allowed'
              )}
            >
              {isResolving && resolvingItemId == null ? (
                <LoadingSpinner size="xs" />
              ) : (
                <CheckCircle className="h-3.5 w-3.5" />
              )}
              {isBulk || isSplitContext ? `Approve All (${pendingCount})` : 'Approve'}
            </button>
            <button
              onClick={() => handleResolve('REJECTED')}
              disabled={isResolving}
              className={clsx(
                'flex items-center gap-1 px-3 py-1.5 rounded-full text-xs font-medium transition-colors shadow-sm',
                'bg-red-100 text-red-700 hover:bg-red-200',
                isResolving && 'opacity-50 cursor-not-allowed'
              )}
            >
              {isResolving && resolvingItemId == null ? (
                <LoadingSpinner size="xs" />
              ) : (
                <XCircle className="h-3.5 w-3.5" />
              )}
              {isBulk || isSplitContext ? `Reject All (${pendingCount})` : 'Reject'}
            </button>
          </div>

          {/* Per-item toggle + list (split context only) */}
          {isSplitContext && (
            <>
              {/* Open the review modal: walk every pending item (Prev/Next),
                  approve/reject each, auto-advancing to the next. */}
              <ApprovalContextDialog
                signals={pendingSignals}
                resolve={executionStatus.resolveApproval}
                data-testid="approval-node-review-all"
                className="nodrag nopan flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium bg-amber-100 text-amber-700 hover:bg-amber-200 dark:bg-amber-500/20 dark:text-amber-300 dark:hover:bg-amber-500/30 transition-colors shadow-sm"
              >
                <MessageSquareQuote className="h-3.5 w-3.5" />
                {tRun('approvalBar.reviewAll', { count: pendingSignals.length })}
              </ApprovalContextDialog>
              <button
                onClick={() => setShowPerItem(prev => !prev)}
                className="flex items-center gap-1 text-[10px] text-slate-500 hover:text-slate-700 transition-colors"
              >
                {showPerItem ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
                {showPerItem ? 'Hide items' : `Show ${pendingSignals.length} items`}
              </button>
              {showPerItem && (
                <div className="flex flex-col gap-1 bg-white dark:bg-gray-800 rounded-lg border border-slate-200 dark:border-slate-700 p-2 shadow-md max-h-40 overflow-y-auto w-full">
                  {pendingSignals.map((signal) => {
                    // itemId is the 0-based split index - show it 1-based for
                    // humans (same convention as the Files browser labels).
                    const itemNumber = signal.itemId != null && Number.isFinite(Number(signal.itemId))
                      ? Number(signal.itemId) + 1
                      : null;
                    return (
                    <div key={signal.id} className="flex items-center justify-between gap-2 px-2 py-1 rounded hover:bg-slate-50 dark:hover:bg-slate-700/50">
                      <button
                        type="button"
                        data-testid={`signal-item-review-${signal.id}`}
                        onClick={() => handleReviewItem(signal)}
                        title={tRun('reviewItemTooltip')}
                        className="flex flex-col min-w-0 flex-1 cursor-pointer text-left"
                      >
                        <div className="flex items-center gap-1.5">
                          <span className="text-[10px] text-slate-600 dark:text-slate-400 font-mono whitespace-nowrap">
                            {tRun('itemBadge', { number: itemNumber ?? (signal.itemId ?? '?') })}
                          </span>
                          {signal.epoch != null && (
                            <span
                              data-testid={`signal-epoch-badge-${signal.id}`}
                              className="text-[10px] px-1.5 rounded-full bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400 whitespace-nowrap"
                            >
                              {/* RAW epoch - TriggerEpochManager numbers fires from 1; the epoch
                                  selector and Files browser display the raw value too. */}
                              {tRun('epochBadge', { number: signal.epoch })}
                            </span>
                          )}
                        </div>
                        {signal.approvalContext && (
                          <span
                            data-testid={`signal-approval-context-${signal.id}`}
                            className="text-[10px] text-slate-600 dark:text-slate-300 font-medium truncate text-left"
                            title={signal.approvalContext}
                          >
                            {signal.approvalContext}
                          </span>
                        )}
                      </button>
                      <div className="flex items-center gap-1">
                        <button
                          onClick={() => handleResolve('APPROVED', signal.itemId, signal.epoch)}
                          disabled={resolvingItemId === signal.itemId}
                          className={clsx(
                            'flex items-center gap-0.5 px-2 py-0.5 rounded-full text-[10px] font-medium transition-colors',
                            'bg-emerald-50 text-emerald-600 hover:bg-emerald-100',
                            resolvingItemId === signal.itemId && 'opacity-50 cursor-not-allowed'
                          )}
                        >
                          {resolvingItemId === signal.itemId ? (
                            <Loader2 className="h-2.5 w-2.5 animate-spin" />
                          ) : (
                            <CheckCircle className="h-2.5 w-2.5" />
                          )}
                        </button>
                        <button
                          onClick={() => handleResolve('REJECTED', signal.itemId, signal.epoch)}
                          disabled={resolvingItemId === signal.itemId}
                          className={clsx(
                            'flex items-center gap-0.5 px-2 py-0.5 rounded-full text-[10px] font-medium transition-colors',
                            'bg-red-50 text-red-600 hover:bg-red-100',
                            resolvingItemId === signal.itemId && 'opacity-50 cursor-not-allowed'
                          )}
                        >
                          {resolvingItemId === signal.itemId ? (
                            <Loader2 className="h-2.5 w-2.5 animate-spin" />
                          ) : (
                            <XCircle className="h-2.5 w-2.5" />
                          )}
                        </button>
                      </div>
                    </div>
                    );
                  })}
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Status badge */}
      {effectiveStatus && effectiveStatus !== 'pending' && (
        <div className="absolute bottom-2 right-2 z-10">
          <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
        </div>
      )}

      {/* Step-by-step play button - User Approval executes as a regular step (not core/decision) */}
      {executionStatus.isStepByStepMode && !isAwaitingSignal && (
        <NodeBottomBar
          hover={{ isVisible: showActions, onHover: show }}
          borderColor={borderColor}
          isRunning={effectiveStatus === 'running'}
          playButton={{
            nodeId: id,
            variant: 'play',
            isAutoMode: false,
            isTriggerNode: false,
            stepByStepStatus: executionStatus,
          }}
        />
      )}

      <NodeActionButtons
        isVisible={showActions}
        onDelete={data.onDeleteNode ? () => data.onDeleteNode?.(data.id) : undefined}
        onDuplicate={data.onDuplicateNode ? () => data.onDuplicateNode?.(data.id) : undefined}
        onHover={show}
      />

      <Handle
        type="target"
        position={Position.Left}
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          left: -6,
          top: '50%',
          transform: 'translateY(-50%)',
          backgroundColor: 'var(--border-color)',
          opacity: isRunMode ? 0 : 1,
          pointerEvents: isRunMode ? 'none' : 'auto'
        }}
      />
    </div>
  );
}
