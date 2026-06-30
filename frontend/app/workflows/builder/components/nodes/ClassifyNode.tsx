'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position } from 'reactflow';

import { getNodeVisual } from '../../data/nodeVisuals';
import type { BuilderNodeData, ClassifyCategory, DerivedNodeStatus, NodeStatus } from '../../types';
import { createDefaultClassifyCategories } from '../../types';
import { useValidation } from '../../contexts/ValidationContext';
import { NodeActionButtons, NodeHeader, useHoverVisibility, getIconSlug, getStatusBorderColor } from './shared';
import { findNodeClassById } from '../../nodes/nodeClasses';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { NodePlayButton, deriveNodeStatus } from '../NodePlayButton';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { NodeBottomBar } from './NodeBottomBar';
import { getEffectiveDefaultProvider } from '@/hooks/useModels';
import { getProviderIconSlug } from '@/lib/ai-providers/providerIcons';

/**
 * Get iconSlug for Classify node based on provider
 */
function getClassifyIconSlug(data: BuilderNodeData): string | undefined {
  const provider = data.provider || getEffectiveDefaultProvider();
  return getProviderIconSlug(provider);
}

export function ClassifyNode({ data, selected, id }: NodeProps<BuilderNodeData>) {
  const visuals = getNodeVisual('classify');
  const categories: ClassifyCategory[] =
    (data.classifyCategories as ClassifyCategory[] | undefined) ?? createDefaultClassifyCategories(data.id);
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();
  const { isRunMode, viewingEpoch } = useWorkflowMode();

  // Get node class to determine family
  const nodeClass = React.useMemo(() => findNodeClassById(data.id || 'classify'), [data.id]);
  const nodeFamily = nodeClass?.family;

  // Step-by-step execution status - pass node data for accurate backend ID mapping
  const executionStatus = useNodeExecutionStatus(id, { label: data.label, kind: 'classify' });

  // Use centralized validation context for error state
  const { hasNodeErrors: checkNodeErrors } = useValidation();
  const hasError = checkNodeErrors(id);

  // Determine effective status: combine data.status (accumulated counts) with executionStatus (current execution state)
  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    if (viewingEpoch != null) return data.status;
    if (executionStatus.isStepByStepMode) {
      // In step-by-step mode, prioritize current execution state for active feedback
      if (executionStatus.isRunning) return 'running';
      if (executionStatus.isFailed) return 'failed';
      if (executionStatus.isSkipped) return 'skipped';
      if (executionStatus.isCompleted || executionStatus.isEvaluated) return 'completed';
      if (executionStatus.isReady) return 'ready';

      // CRITICAL: If no active execution state but has accumulated statusCounts from backend,
      // use data.status to show the badge with historical execution counts
      if (data.status && data.status !== 'pending') {
        return data.status;
      }

      return 'pending';
    }
    return data.status;
  }, [viewingEpoch, executionStatus, data.status]);

  // Get border color based on status
  // Always use status color for border
  const statusBorderColor = getStatusBorderColor(effectiveStatus, hasError, isRunMode || viewingEpoch != null);
  const borderColor = statusBorderColor;
  // Don't apply skipped styling in step-by-step mode
  const isSkipped = !executionStatus.isStepByStepMode && effectiveStatus === 'skipped';

  // Get the icon slug for the current provider
  const iconSlug = getClassifyIconSlug(data);

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
        // Focus/selection ring outside the node to not override status border
        boxShadow: selected ? '0 0 0 2px var(--accent-primary)' : 'none',
      }}
      tabIndex={0}
    >
      {/* Shimmer scan effect for running state - show in all modes */}
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

      <NodeHeader
        visuals={visuals}
        label={data.label}
        iconSlug={iconSlug}
        nodeId={id}
        nodeKind="reasoning"
        nodeFamily={nodeFamily}
      />

      {/* Categories section */}
      <div className="mt-4 space-y-2 text-[11px] text-slate-500 dark:text-slate-400" style={{ paddingBottom: effectiveStatus && effectiveStatus !== 'pending' ? '10px' : '0' }}>
        {categories.map((category, index) => {
          return (
            <div key={category.id} className="relative rounded-2xl border border-theme px-3 py-2">
              <div className="flex items-center gap-2">
                <span className="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300">
                  {index + 1}
                </span>
                <span className="truncate text-slate-700 dark:text-slate-300">{category.label}</span>
              </div>
              <Handle
                type="source"
                id={category.id}
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

      {/* Status badge positioned at bottom right, above the last category */}
      {effectiveStatus && effectiveStatus !== 'pending' && (
        <div className="absolute bottom-2 right-2 z-10">
          <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
        </div>
      )}

      {/* Step-by-step play button for classify nodes */}
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

      {/* Input handle on the left */}
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
