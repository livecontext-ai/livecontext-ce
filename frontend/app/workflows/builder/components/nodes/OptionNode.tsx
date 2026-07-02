'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position } from 'reactflow';

import { getNodeVisual } from '../../data/nodeVisuals';
import type { BuilderNodeData, OptionChoice, DerivedNodeStatus, NodeStatus } from '../../types';
import { createDefaultOptionChoices } from '../../types';
import { useValidation } from '../../contexts/ValidationContext';
import { NodeActionButtons, NodeHeader, useHoverVisibility, getIconSlug, getStatusBorderColor, ReadyShimmerOverlay } from './shared';
import { findNodeClassById } from '../../nodes/nodeClasses';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { NodePlayButton } from '../NodePlayButton';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { NodeBottomBar } from './NodeBottomBar';


export function OptionNode({ data, selected, id }: NodeProps<BuilderNodeData>) {
  const visuals = getNodeVisual('option');
  const options: OptionChoice[] =
    (data.optionChoices as OptionChoice[] | undefined) ?? createDefaultOptionChoices(data.id);
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();
  const { isRunMode, viewingEpoch } = useWorkflowMode();

  // Get node class to determine family
  const nodeClass = React.useMemo(() => findNodeClassById(data.id || ''), [data.id]);
  const nodeFamily = nodeClass?.family;

  // Step-by-step execution status
  const executionStatus = useNodeExecutionStatus(id, { label: data.label, kind: data.kind });

  // Use centralized validation context for error state
  const { hasNodeErrors: checkNodeErrors } = useValidation();
  const hasError = checkNodeErrors(id);

  // Determine effective status
  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    if (viewingEpoch != null) return data.status;
    if (executionStatus.isStepByStepMode) {
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
    return data.status;
  }, [viewingEpoch, executionStatus, data.status]);

  // Get border color based on status
  const statusBorderColor = getStatusBorderColor(effectiveStatus, hasError, isRunMode || viewingEpoch != null);
  const borderColor = statusBorderColor;
  const isSkipped = !executionStatus.isStepByStepMode && effectiveStatus === 'skipped';

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
      {/* Shimmer scan effect for running state */}
      {effectiveStatus === 'running' && (
        <div
          className="absolute inset-0 pointer-events-none rounded-[26px]"
          style={{
            background: 'linear-gradient(90deg, transparent 0%, rgba(139, 92, 246, 0.15) 50%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
      )}
      {executionStatus.isStepByStepMode && effectiveStatus === 'ready' && (
        <ReadyShimmerOverlay className="absolute inset-0 pointer-events-none rounded-[26px]" />
      )}

      <NodeHeader
        visuals={{ ...visuals, iconBg: '#ede9fe' }} // Violet theme for option
        label={data.label}
        iconSlug={getIconSlug(data)}
        nodeId={id}
        nodeKind="option"
        nodeFamily={nodeFamily}
      />

      <div className="mt-4 space-y-2 text-[11px] text-slate-500" style={{ paddingBottom: effectiveStatus && effectiveStatus !== 'pending' ? '10px' : '0' }}>
        {options.map((option, index) => {
          const handleId = option.id;

          return (
            <div key={option.id} className="relative rounded-2xl border border-theme px-3 py-2">
              <div className="flex items-center gap-2">
                <span className="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300">
                  {index + 1}
                </span>
                <span className="truncate">{option.label}</span>
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

      {/* Status badge positioned at bottom right */}
      {effectiveStatus && effectiveStatus !== 'pending' && (
        <div className="absolute bottom-2 right-2 z-10">
          <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
        </div>
      )}

      {/* Step-by-step play button */}
      {executionStatus.isStepByStepMode && (
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
