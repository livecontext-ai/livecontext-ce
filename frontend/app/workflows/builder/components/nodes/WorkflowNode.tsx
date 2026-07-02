'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position } from 'reactflow';

import { getNodeVisual } from '../../data/nodeVisuals';
import type { BuilderNodeData, DerivedNodeStatus, NodeStatus } from '../../types';
import { useValidation } from '../../contexts/ValidationContext';
import { NodeHeader, useHoverVisibility, getIconSlug, getStatusBorderColor, ReadyShimmerOverlay } from './shared';
import { findNodeClassById } from '../../nodes/nodeClasses';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { Workflow } from 'lucide-react';
import { NodePlayButton, deriveNodeStatus } from '../NodePlayButton';
import { NodeBottomBar } from './NodeBottomBar';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';


/**
 * WorkflowNode - A specialized node for workflow triggers
 *
 * This node is used for workflow triggers that reference another workflow.
 */
export function WorkflowNode({ data, selected, id }: NodeProps<BuilderNodeData>) {
  const visuals = getNodeVisual('entry');
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();
  const { isRunMode, viewingEpoch } = useWorkflowMode();

  // Get node class to determine family
  const nodeClass = React.useMemo(() => findNodeClassById(data.id || ''), [data.id]);
  const nodeFamily = nodeClass?.family;

  // Step-by-step execution status
  const stepByStepStatus = useNodeExecutionStatus(id, { label: data.label, kind: data.kind });

  // Use centralized validation context for error state
  const { hasNodeErrors: checkNodeErrors } = useValidation();
  const hasError = checkNodeErrors(id);
  const sidePanel = useSidePanelSafe();
  const referencedWorkflowId: string | undefined = (data as any)?.workflowData?.workflowId || undefined;
  const referencedWorkflowName: string = (data as any)?.workflowData?.workflowName || data.label || 'Workflow';

  // Determine effective status
  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    if (viewingEpoch != null) return data.status;
    if (stepByStepStatus.isStepByStepMode) {
      if (stepByStepStatus.isRunning) return 'running';
      if (stepByStepStatus.isFailed) return 'failed';
      if (stepByStepStatus.isSkipped) return 'skipped';
      if (stepByStepStatus.isCompleted) return 'completed';
      if (stepByStepStatus.isReady) return 'ready';
      return 'pending';
    }
    return data.status;
  }, [viewingEpoch, stepByStepStatus, data.status]);

  // Get border color based on status
  // Always use status color for border
  const statusBorderColor = getStatusBorderColor(effectiveStatus, hasError, isRunMode || viewingEpoch != null);
  const borderColor = statusBorderColor;

  // Don't apply skipped styling in step-by-step mode
  const isSkipped = !stepByStepStatus.isStepByStepMode && effectiveStatus === 'skipped';

  // Check if node is running (for animation) - show shimmer in all modes
  const isNodeRunning = effectiveStatus === 'running';

  // Trigger detection for play button
  const nodeId = data.id || '';
  const isManualTrigger = nodeId === 'manual-trigger' || nodeId.startsWith('manual-trigger-');
  const isChatTrigger = nodeId === 'chat-trigger' || nodeId.startsWith('chat-trigger-');
  const isWebhookTrigger = nodeId === 'webhook-trigger' || nodeId.startsWith('webhook-trigger-');
  const isScheduleTrigger = nodeId === 'schedule-trigger' || nodeId.startsWith('schedule-trigger-');
  const isWorkflowsTrigger = nodeId === 'workflows-trigger' || nodeId.startsWith('workflows-trigger-') || (data.kind === 'entry' && !!(data as any)?.workflowData?.workflowId);
  const isTriggerNode = data.kind === 'entry';

  return (
    <div
      ref={nodeRef}
      className={clsx(
        'group relative rounded-2xl bg-white dark:bg-gray-800',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
        'border-2 transition-colors',
        isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
        isSkipped && selected && 'opacity-100',
      )}
      style={{
        borderColor,
        width: 180,
        borderStyle: 'solid',
      }}
      tabIndex={0}
    >
      {/* Shimmer scan effect for running state */}
      {isNodeRunning && (
        <div
          className="absolute inset-0 pointer-events-none rounded-2xl z-[5]"
          style={{
            background: 'linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
      )}
      {stepByStepStatus.isStepByStepMode && effectiveStatus === 'ready' && (
        <ReadyShimmerOverlay className="absolute inset-0 pointer-events-none rounded-2xl z-[5]" />
      )}

      {/* Node content */}
      <div className="p-3 space-y-2">
        <NodeHeader
          visuals={visuals}
          label={data.label}
          iconSlug={getIconSlug(data)}
          nodeId={nodeId}
          nodeKind={data.kind}
          nodeFamily={nodeFamily}
        />

        {data.description && (
          <p className="text-sm text-slate-400 dark:text-slate-500 line-clamp-2">
            {data.description}
          </p>
        )}
      </div>

      {/* Status badge positioned at bottom right */}
      <div className="absolute bottom-2 right-2 z-10">
        <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
      </div>

      {/* Centralized bottom bar: sub-workflow button + play/rerun + hover delete/duplicate */}
      {(() => {
        const btns: { key: string; icon: React.ReactNode; title: string; onClick: (e: React.MouseEvent) => void }[] = [];
        if (isWorkflowsTrigger && referencedWorkflowId) {
          btns.push({
            key: 'subworkflow',
            icon: <Workflow className="h-3 w-3" strokeWidth={2} />,
            title: referencedWorkflowName,
            onClick: () => {
              if (isRunMode) {
                window.dispatchEvent(new CustomEvent('workflowOpenSubWorkflow', { detail: { workflowId: referencedWorkflowId, workflowName: referencedWorkflowName, nodeId: id } }));
              } else {
                import('@/components/app/WorkflowBuilderPanelContent').then(({ WorkflowBuilderPanelContent }) => {
                  sidePanel?.openTab({ id: `workflow-builder-${referencedWorkflowId}`, label: referencedWorkflowName, icon: React.createElement(Workflow, { className: 'w-4 h-4' }), content: React.createElement(WorkflowBuilderPanelContent, { workflowId: referencedWorkflowId }), preferredWidth: 0.5, keepMounted: true });
                });
              }
            },
          });
        }
        const showPlay = isRunMode && (stepByStepStatus.isStepByStepMode || (isWorkflowsTrigger && stepByStepStatus.isReady));
        // Hover delete/duplicate - same row + style as the persistent buttons
        // (NodeBottomBar hides them in run / preview-only mode).
        const hasHoverActions = !isRunMode && !!(data.onDeleteNode || data.onDuplicateNode);
        if (btns.length === 0 && !showPlay && !hasHoverActions) return null;
        return (
          <NodeBottomBar
            hover={{ isVisible: showActions, onHover: show }}
            borderColor={borderColor}
            isRunning={isNodeRunning}
            buttons={btns.length > 0 ? btns : undefined}
            hoverActions={hasHoverActions ? {
              onDelete: data.onDeleteNode ? () => data.onDeleteNode?.(data.id) : undefined,
              onDuplicate: data.onDuplicateNode ? () => data.onDuplicateNode?.(data.id) : undefined,
            } : undefined}
            playButton={showPlay ? {
              nodeId: id,
              variant: (isManualTrigger ? 'lightning' : isChatTrigger ? 'message' : isWebhookTrigger ? 'webhook' : isScheduleTrigger ? 'schedule' : isWorkflowsTrigger ? 'workflow' : 'play') as any,
              isAutoMode: !stepByStepStatus.isStepByStepMode,
              isTriggerNode,
              stepByStepStatus,
            } : undefined}
          />
        );
      })()}

      {/* Target handle on left (receives connections) */}
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

      {/* Source handle on right (sends connections) */}
      <Handle
        type="source"
        position={Position.Right}
        id="source-right"
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          right: -6,
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
