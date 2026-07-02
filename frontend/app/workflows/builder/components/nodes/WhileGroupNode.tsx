'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position } from 'reactflow';
import { useTranslations } from 'next-intl';

import { getNodeVisual } from '../../data/nodeVisuals';
import type { BuilderNodeData, DerivedNodeStatus, NodeStatus } from '../../types';
import { NodeActionButtons, NodeHeader, useHoverVisibility, getIconSlug, getStatusBorderColor, ReadyShimmerOverlay } from './shared';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { NodePlayButton, deriveNodeStatus } from '../NodePlayButton';
import { NodeBottomBar } from './NodeBottomBar';


/**
 * While node - loop control flow node.
 *
 * Handles (2 left + 2 right):
 *   LEFT  top:    entry      (target, gray)
 *   LEFT  bottom: loop-back  (target, orange) - return from last body node
 *   RIGHT top:    exit       (source, gray)
 *   RIGHT bottom: body       (source, orange) - goes to first body node
 *
 * Each port row contains both a left handle and a right handle.
 */
export function WhileGroupNode({ data, selected, id }: NodeProps<BuilderNodeData>) {
  const t = useTranslations('whileGroup');
  const { isRunMode, isPreviewOnly, viewingEpoch } = useWorkflowMode();
  const visuals = getNodeVisual('whileGroup');
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();

  // Step-by-step execution status
  const stepByStepStatus = useNodeExecutionStatus(id, {
    label: data.label,
    kind: data.kind,
  });

  // Determine effective status from step-by-step context or streaming data
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
    // Auto mode: use streaming running override
    if (stepByStepStatus.isRunning) return 'running';
    return data.status;
  }, [viewingEpoch, stepByStepStatus, data.status]);

  // Don't apply skipped styling in step-by-step mode
  const isSkipped = !stepByStepStatus.isStepByStepMode && effectiveStatus === 'skipped';

  const borderColor = getStatusBorderColor(effectiveStatus);
  const isNodeRunning = effectiveStatus === 'running';

  const handleVisibility = {
    opacity: isRunMode ? 0 : 1,
    pointerEvents: (isRunMode ? 'none' : 'auto') as React.CSSProperties['pointerEvents'],
  };

  return (
    <div
      ref={nodeRef}
      className={clsx(
        'group relative rounded-[28px] bg-white/95 dark:bg-gray-800/95 px-5 py-4',
        'backdrop-blur border-2 transition-colors',
        isSkipped && !selected && 'opacity-50',
      )}
      style={{
        borderColor,
        borderStyle: 'solid',
        boxShadow: selected ? '0 0 0 2px var(--accent-primary)' : 'none',
        minWidth: 200,
      }}
      tabIndex={0}
    >
      {/* Shimmer scan effect for running state */}
      {isNodeRunning && (
        <div
          className="absolute inset-0 pointer-events-none rounded-[26px]"
          style={{
            background: 'linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
      )}
      {stepByStepStatus.isStepByStepMode && effectiveStatus === 'ready' && (
        <ReadyShimmerOverlay className="absolute inset-0 pointer-events-none rounded-[26px]" />
      )}

      <NodeHeader
        visuals={visuals}
        label={data.label || t('defaultLabel')}
        iconSlug={getIconSlug(data)}
        nodeId={id}
        nodeKind="whileGroup"
        nodeFamily="loop"
      />

      {/* Port rows - each row has a left handle (target) and a right handle (source) */}
      <div className="mt-4 space-y-2 text-[11px] text-slate-500" style={{ paddingBottom: effectiveStatus && effectiveStatus !== 'pending' ? '10px' : '0' }}>

        {/* Row 1: Entry (left) + Exit (right) - gray */}
        <div className="relative rounded-2xl border border-theme px-3 py-2">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300">
              Entry
            </span>
            <span className="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300">
              Exit
            </span>
          </div>
          {/* Entry target handle (left) */}
          <Handle
            type="target"
            id={`while-${id}-entry`}
            position={Position.Left}
            className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              left: -27,
              top: '50%',
              transform: 'translateY(-50%)',
              backgroundColor: 'var(--border-color)',
              ...handleVisibility,
            }}
          />
          {/* Exit source handle (right) */}
          <Handle
            type="source"
            id={`while-${id}-exit`}
            position={Position.Right}
            className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              right: -27,
              top: '50%',
              transform: 'translateY(-50%)',
              backgroundColor: 'var(--border-color)',
              ...handleVisibility,
            }}
          />
        </div>

        {/* Row 2: Loop-back (left, orange) + Body (right, orange) */}
        <div className="relative rounded-2xl border border-orange-200 dark:border-orange-800 px-3 py-2">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-300">
              Loop
            </span>
            {data.maxIterations && (
              <span className="text-slate-400">max {data.maxIterations}</span>
            )}
          </div>
          {/* Loop-back target handle (left, orange) */}
          <Handle
            type="target"
            id={`while-${id}-loop-back`}
            position={Position.Left}
            className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              left: -27,
              top: '50%',
              transform: 'translateY(-50%)',
              backgroundColor: '#f97316',
              ...handleVisibility,
            }}
          />
          {/* Body source handle (right, orange) */}
          <Handle
            type="source"
            id={`while-${id}-body`}
            position={Position.Right}
            className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              right: -27,
              top: '50%',
              transform: 'translateY(-50%)',
              backgroundColor: '#f97316',
              ...handleVisibility,
            }}
          />
        </div>
      </div>

      {/* Status badge */}
      {effectiveStatus && effectiveStatus !== 'pending' && (
        <div className="absolute bottom-2 right-2 z-10">
          <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
        </div>
      )}

      <NodeActionButtons
        isVisible={showActions}
        onDelete={data.onDeleteNode ? () => data.onDeleteNode?.(data.id) : undefined}
        onDuplicate={data.onDuplicateNode ? () => data.onDuplicateNode?.(data.id) : undefined}
        onHover={show}
      />

      {/* Step-by-step execution play button */}
      {!isPreviewOnly && isRunMode && stepByStepStatus.isStepByStepMode && (
        <NodeBottomBar
          hover={{ isVisible: showActions, onHover: show }}
          borderColor={borderColor}
          isRunning={isNodeRunning}
          playButton={{
            nodeId: id,
            variant: 'play',
            isAutoMode: false,
            isTriggerNode: false,
            stepByStepStatus,
          }}
        />
      )}
    </div>
  );
}
