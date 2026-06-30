'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Trash2 } from 'lucide-react';
import {
  BaseEdge,
  EdgeLabelRenderer,
  EdgeProps,
  getBezierPath,
  getStraightPath,
  getSmoothStepPath,
} from 'reactflow';

import { useEdgeActions } from './EdgeActionsContext';
import type { ConnectionType } from './ConnectionTypeSelector';
import type { DerivedNodeStatus } from '../types';
import { EdgeStatusLabel } from './EdgeStatusLabel';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

// Get stroke color based on status
function getStatusStrokeColor(status?: DerivedNodeStatus): string {
  if (!status || status === 'pending') return 'var(--border-color)';
  switch (status) {
    case 'running':
      return '#3b82f6'; // blue-500
    case 'completed':
      return '#10b981'; // emerald-500
    case 'failed':
      return '#ef4444'; // red-500
    case 'skipped':
      return '#94a3b8'; // slate-400
    case 'partial_success':
      return '#f59e0b'; // amber-500
    default:
      return 'var(--border-color)';
  }
}

export function BuilderEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  selected,
  style,
  data,
}: EdgeProps) {
  const t = useTranslations('workflowBuilder.nodes');
  const isBackEdge = data?.isBackEdge === true;
  const isWhileBodyEdge = data?.isWhileBodyEdge === true;
  const isLoopBackEdge = data?.isLoopBackEdge === true;
  const isWhileEdge = isWhileBodyEdge || isLoopBackEdge;
  const baseConnectionType: ConnectionType = data?.connectionType || 'bezier';

  // Use smoothstep when connection goes "backwards" for better visual display
  // Back-edges always use smoothstep
  // Detect connection direction based on source handle position
  const isHorizontalConnection = sourcePosition === 'right' || sourcePosition === 'left';
  const isVerticalConnection = sourcePosition === 'top' || sourcePosition === 'bottom';

  // Horizontal (left/right handles): backward when source is to the right of target
  // Vertical (top/bottom handles): backward when source is below target (top handle outputs upward)
  const isHorizontalBackward = isHorizontalConnection && sourceX > targetX;
  const isVerticalBackward = isVerticalConnection && sourceY < targetY;
  const isBackwardConnection = isHorizontalBackward || isVerticalBackward;
  const connectionType: ConnectionType = isBackEdge ? 'smoothstep' : (isBackwardConnection ? 'smoothstep' : baseConnectionType);

  // Select the path function according to the connection type
  let edgePath: string;
  let labelX: number;
  let labelY: number;

  // Special U-shaped path for while-body backward edges (loop-back):
  // Routes below the main flow to avoid crossing forward edges.
  //   source ──→ ╮
  //              │  (below the nodes)
  //   target ←── ╯
  if (isLoopBackEdge) {
    const r = 16;
    const hPad = 28;
    const vPad = 50;
    const bottomY = Math.max(sourceY, targetY) + vPad;
    const rightX = sourceX + hPad;
    const leftX = targetX - hPad;

    edgePath = [
      `M ${sourceX},${sourceY}`,
      `H ${rightX - r}`,
      `A ${r},${r} 0 0 1 ${rightX},${sourceY + r}`,
      `V ${bottomY - r}`,
      `A ${r},${r} 0 0 1 ${rightX - r},${bottomY}`,
      `H ${leftX + r}`,
      `A ${r},${r} 0 0 1 ${leftX},${bottomY - r}`,
      `V ${targetY + r}`,
      `A ${r},${r} 0 0 1 ${leftX + r},${targetY}`,
      `H ${targetX}`,
    ].join(' ');

    labelX = (rightX + leftX) / 2;
    labelY = bottomY;
  } else {
    switch (connectionType) {
      case 'straight':
        [edgePath, labelX, labelY] = getStraightPath({
          sourceX,
          sourceY,
          targetX,
          targetY,
        });
        break;
      case 'smoothstep':
        [edgePath, labelX, labelY] = getSmoothStepPath({
          sourceX,
          sourceY,
          sourcePosition,
          targetX,
          targetY,
          targetPosition,
          borderRadius: 20,
        });
        break;
      case 'step':
        [edgePath, labelX, labelY] = getSmoothStepPath({
          sourceX,
          sourceY,
          sourcePosition,
          targetX,
          targetY,
          targetPosition,
          borderRadius: 0,
        });
        break;
      case 'wave':
        [edgePath, labelX, labelY] = getBezierPath({
          sourceX,
          sourceY,
          sourcePosition,
          targetX,
          targetY,
          targetPosition,
        });
        break;
      case 'bezier':
      default:
        [edgePath, labelX, labelY] = getBezierPath({
          sourceX,
          sourceY,
          sourcePosition,
          targetX,
          targetY,
          targetPosition,
        });
        break;
    }
  }

  const { hoveredEdgeId, onDeleteEdge } = useEdgeActions();
  const { isRunMode, isPreviewOnly } = useWorkflowMode();
  const isHovered = hoveredEdgeId === id;
  // z-index: 5 par défaut (devant les notes), 20 si sélectionné (devant tout)
  const edgeZIndex = selected ? 20 : 5;

  // Animation de pointillés pour les edges sélectionnés ou running
  const isRunning = data?.status === 'running';
  const shouldAnimate = selected || isRunning;

  // Get stroke color based on status or selection
  const statusStrokeColor = getStatusStrokeColor(data?.status as DerivedNodeStatus | undefined);
  const backEdgeColor = '#f59e0b'; // amber-500
  const whileBodyColor = '#f97316'; // orange-500
  const stroke = selected ? 'var(--accent-primary)'
    : isWhileEdge && (!data?.status || data?.status === 'pending') ? whileBodyColor
    : isBackEdge && (!data?.status || data?.status === 'pending') ? backEdgeColor
    : statusStrokeColor;
  const isSkipped = data?.status === 'skipped';


  // Select the appropriate arrow marker based on status/selection
  const getMarkerEnd = () => {
    if (selected) return 'url(#arrow-selected)';
    if (isWhileEdge && (!data?.status || data?.status === 'pending')) return 'url(#arrow-while-body)';
    const status = data?.status as DerivedNodeStatus | undefined;
    if (!status || status === 'pending') return 'url(#arrow-default)';
    return `url(#arrow-${status})`;
  };
  const markerEndUrl = getMarkerEnd();

  // Gérer l'opacité avec un délai avant de disparaître
  const [showButton, setShowButton] = React.useState(false);
  const timeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  React.useEffect(() => {
    if (isHovered) {
      // Afficher immédiatement au survol
      setShowButton(true);
      // Nettoyer le timeout précédent s'il existe
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
        timeoutRef.current = null;
      }
    } else {
      // Attendre 500ms avant de cacher
      timeoutRef.current = setTimeout(() => {
        setShowButton(false);
      }, 500);
    }

    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, [isHovered]);

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEndUrl}
        style={{
          ...style,
          stroke,
          strokeWidth: isRunning ? 2 : 1.6,
          strokeDasharray: shouldAnimate || isSkipped || isBackEdge || isWhileEdge ? '8 4' : 'none',
          strokeDashoffset: shouldAnimate ? 0 : 0,
          transition: 'stroke 0.15s ease, opacity 0.15s ease, stroke-width 0.15s ease',
          zIndex: edgeZIndex,
          opacity: isSkipped && !selected ? 0.5 : 1,
          animation: shouldAnimate ? (isRunning ? 'dash-flow 0.8s linear infinite' : 'dash-flow 1.5s linear infinite') : 'none',
        }}
      />
      <BaseEdge
        id={`${id}-hit`}
        path={edgePath}
        style={{
          stroke: 'transparent',
          strokeWidth: 18,
          pointerEvents: 'stroke',
          zIndex: edgeZIndex,
        }}
      />

      <EdgeLabelRenderer>
        <div
          style={{
            position: 'absolute',
            left: `${labelX}px`,
            top: `${labelY}px`,
            transform: 'translate(-50%, -50%)',
            pointerEvents: 'none',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '4px',
          }}
        >
          {/* Edge Status Label */}
          {data?.status && data.status !== 'pending' && (
            <div style={{ display: 'flex', justifyContent: 'center', width: '100%' }}>
              <EdgeStatusLabel
                status={data.status as DerivedNodeStatus}
                statusCounts={data.statusCounts}
                isSkipped={isSkipped}
                strokeColor={stroke}
              />
            </div>
          )}


          {/* Delete Button - Hidden in run mode and readonly */}
          {!isRunMode && !isPreviewOnly && (
            <button
              type="button"
              title={t('delete')}
              aria-label={t('delete')}
              data-testid="workflow-edge-delete"
              onClick={(event) => {
                event.stopPropagation();
                onDeleteEdge(id);
              }}
              onMouseEnter={() => {
                // Garder visible si on survole le bouton
                if (timeoutRef.current) {
                  clearTimeout(timeoutRef.current);
                  timeoutRef.current = null;
                }
                setShowButton(true);
              }}
              style={{
                opacity: showButton ? 1 : 0,
                pointerEvents: showButton ? 'all' : 'none',
                transition: 'opacity 0.2s ease',
              }}
              className="inline-flex items-center justify-center gap-2 whitespace-nowrap border border-transparent font-medium tracking-wide transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-primary)] disabled:pointer-events-none disabled:opacity-60 disabled:cursor-not-allowed hover:shadow-[0_12px_32px_var(--shadow-color)] text-sm h-6 w-6 p-0 rounded-full bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] shadow-none"
            >
              <Trash2 className="h-3 w-3" strokeWidth={2} />
            </button>
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
