'use client';

import React from 'react';
import {
  getBezierPath,
  getStraightPath,
  getSmoothStepPath,
  EdgeLabelRenderer,
  type EdgeProps,
} from 'reactflow';
import { Trash2, Pencil } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { EdgeStatusLabel } from '@/app/workflows/builder/components/EdgeStatusLabel';
import { useEdgeActions } from '@/app/workflows/builder/components/EdgeActionsContext';
import { deriveStatusFromCounts } from '@/app/workflows/builder/utils/statusCounts';
import type { DerivedNodeStatus } from '@/app/workflows/builder/types';

const PATH_FUNCTIONS: Record<string, typeof getBezierPath> = {
  default: getBezierPath,
  bezier: getBezierPath,
  straight: getStraightPath,
  step: getSmoothStepPath,
  smoothstep: getSmoothStepPath,
};

/**
 * Custom edge for fleet canvas that renders status count labels
 * at the midpoint, like BuilderEdge does in the workflow canvas.
 * Supports path type switching via data.pathType.
 */
export const FleetEdge = React.memo(function FleetEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  style,
  data,
  markerEnd,
}: EdgeProps) {
  const pathType = (data as any)?.pathType || 'default';
  const pathFn = PATH_FUNCTIONS[pathType] || getBezierPath;
  const [edgePath, labelX, labelY] = pathFn({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  const statusCounts = (data as any)?.statusCounts as Record<string, number> | undefined;
  const totalCalls = (data as any)?.totalCalls as number | undefined;
  const status = statusCounts ? deriveStatusFromCounts(statusCounts) : undefined;
  const stroke = (style?.stroke as string) || 'var(--border-color)';
  const hasLabel = (statusCounts && status && status !== 'pending') || (totalCalls && totalCalls > 0);

  // ── Edit-mode hover action (delete / edit) ──
  // The canvas tracks the hovered edge via EdgeActionsContext; the per-edge action
  // ('delete' | 'edit') and the handlers are injected on edge.data by AgentFleetCanvas.
  const t = useTranslations('common');
  const { hoveredEdgeId } = useEdgeActions();
  const editMode = !!(data as any)?.fleetEditMode;
  const edgeAction = (data as any)?.fleetEdgeAction as 'delete' | 'edit' | undefined;
  const onEdgeDelete = (data as any)?.onFleetEdgeDelete as ((id: string) => void) | undefined;
  const onEdgeEdit = (data as any)?.onFleetEdgeEdit as ((id: string) => void) | undefined;
  const showAction = editMode && !!edgeAction && hoveredEdgeId === id;

  return (
    <>
      <path
        id={id}
        d={edgePath}
        fill="none"
        style={style}
        className="react-flow__edge-path"
        markerEnd={markerEnd as string}
      />

      {showAction && (
        <EdgeLabelRenderer>
          <div
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY - (hasLabel ? 24 : 0)}px)`,
              pointerEvents: 'all',
              zIndex: 20,
            }}
            className="nodrag nopan"
          >
            <button
              onClick={(e) => {
                e.stopPropagation();
                if (edgeAction === 'delete') onEdgeDelete?.(id);
                else onEdgeEdit?.(id);
              }}
              title={edgeAction === 'delete' ? t('remove') : t('edit')}
              className="flex h-6 w-6 items-center justify-center rounded-full bg-[var(--bg-primary)] text-[var(--text-primary)] shadow-md hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] transition-colors"
            >
              {edgeAction === 'delete' ? <Trash2 className="h-3 w-3" /> : <Pencil className="h-3 w-3" />}
            </button>
          </div>
        </EdgeLabelRenderer>
      )}
      {hasLabel && (
        <EdgeLabelRenderer>
          <div
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              pointerEvents: 'all',
              zIndex: 10,
            }}
            className="flex flex-col items-center gap-0.5"
          >
            {statusCounts && status && status !== 'pending' && (
              <EdgeStatusLabel
                status={status as DerivedNodeStatus}
                statusCounts={statusCounts}
                strokeColor={stroke}
              />
            )}
            {totalCalls !== undefined && totalCalls > 0 && (
              <span className="text-[10px] text-slate-500 dark:text-slate-400 bg-white/90 dark:bg-gray-800/90 rounded-full px-1.5 py-0.5 font-medium backdrop-blur">
                {totalCalls} call{totalCalls !== 1 ? 's' : ''}
              </span>
            )}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
});
