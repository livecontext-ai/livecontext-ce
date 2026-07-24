'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Trash2 } from 'lucide-react';
import {
  BaseEdge,
  EdgeLabelRenderer,
  EdgeProps,
  useStore,
  type ReactFlowState,
  getBezierPath,
  getStraightPath,
  getSmoothStepPath,
} from 'reactflow';

import { useEdgeActions } from './EdgeActionsContext';
import type { ConnectionType } from './ConnectionTypeSelector';
import type { DerivedNodeStatus } from '../types';
import { EdgeStatusLabel } from './EdgeStatusLabel';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { isFlowBackward } from './nodes/handleGeometry';
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
  source,
  target,
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
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  // Half-width of the widest endpoint node, so a vertical loop-back rail can clear
  // the node BODY instead of only its centre-pinned handle. Selector returns a
  // number (not an object), so it cannot loop on referential inequality.
  const widestEndpointHalfWidth = useStore(
    React.useCallback(
      (s: ReactFlowState) => {
        const w = (id2: string) => s.nodeInternals.get(id2)?.width ?? 0;
        return Math.max(w(source), w(target)) / 2;
      },
      [source, target],
    ),
  );
  const t = useTranslations('workflowBuilder.nodes');
  const isBackEdge = data?.isBackEdge === true;
  const isWhileBodyEdge = data?.isWhileBodyEdge === true;
  const isLoopBackEdge = data?.isLoopBackEdge === true;
  const isWhileEdge = isWhileBodyEdge || isLoopBackEdge;
  const baseConnectionType: ConnectionType = data?.connectionType || 'bezier';

  // Use smoothstep when a connection goes "backwards" (against the flow) for better
  // visual display; back-edges always use smoothstep.
  //
  // "Backwards" is relative to the CANVAS READING DIRECTION, not to the handle
  // position: with the flow running left-to-right an edge is backward when it points
  // left, and with it running top-to-bottom when it points up. Deriving this from
  // `sourcePosition` alone (as this did) inverts the test the moment the canvas turns
  // vertical: every ordinary top-to-bottom edge has sourceY < targetY and would be
  // flagged backward, while a genuine loop back UP would not be.
  const isVerticalFlow = layoutDirection === 'vertical';
  // 'auto' picks the edge shape from the reading direction: the gentle `wave` bezier
  // reads well left-to-right, while `smoothstep`'s right angles read cleaner running
  // top-to-bottom. Resolved here, per edge, so no direction has to be threaded into
  // edge data.
  const resolvedBase: ConnectionType =
    baseConnectionType === 'auto' ? (isVerticalFlow ? 'smoothstep' : 'wave') : baseConnectionType;
  const isBackwardConnection = isFlowBackward(layoutDirection, sourceX, sourceY, targetX, targetY);
  const connectionType: ConnectionType = isBackEdge ? 'smoothstep' : (isBackwardConnection ? 'smoothstep' : resolvedBase);

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
    const pad = 28;
    const clearance = 50;
    // Horizontal routes the rail under the nodes, where a fixed 50px clears any node
    // (they are ~80-140px tall and the handles sit on their left/right borders).
    // Vertical routes it beside them, where the handles sit at the horizontal CENTRE
    // of a 200-900px-wide node, so the rail has to clear a real half-width. Measure
    // the widest endpoint rather than guessing a constant.
    const railClearance = clearance + widestEndpointHalfWidth;

    if (isVerticalFlow) {
      // Mirror of the horizontal U, rotated a quarter turn: the flow runs down, so
      // the loop-back exits below the source, routes up a rail CLEAR of the column,
      // and drops into the target's top handle.
      //
      // The rail must clear the node body, not just the handle: in vertical, handles
      // sit at the horizontal CENTRE of their row, and nodes are 200-900px wide, so a
      // fixed "+50 from the handle" (the mirror of the horizontal version, where a
      // node is only ~80px tall) would run the rail straight through the node.
      // `railClearance` is the measured half-width of the widest endpoint node.
      const rightX = Math.max(sourceX, targetX) + railClearance;
      const downY = sourceY + pad;
      const upY = targetY - pad;

      edgePath = [
        `M ${sourceX},${sourceY}`,
        `V ${downY - r}`,
        `A ${r},${r} 0 0 0 ${sourceX + r},${downY}`,
        `H ${rightX - r}`,
        `A ${r},${r} 0 0 0 ${rightX},${downY - r}`,
        `V ${upY + r}`,
        `A ${r},${r} 0 0 0 ${rightX - r},${upY}`,
        `H ${targetX + r}`,
        // Arrives heading WEST and leaves heading SOUTH into the top handle, so the
        // fillet ends BELOW the corner (upY + r). Ending at `upY - r` sent the path
        // 16px above the corner and then retraced back down over itself.
        // Sweep 0 like the other three corners: west -> south is (-1,0)x(0,1) = -1,
        // i.e. counter-clockwise in screen coordinates (y grows downward).
        `A ${r},${r} 0 0 0 ${targetX},${upY + r}`,
        `V ${targetY}`,
      ].join(' ');

      labelX = rightX;
      labelY = (downY + upY) / 2;
    } else {
      const bottomY = Math.max(sourceY, targetY) + clearance;
      const rightX = sourceX + pad;
      const leftX = targetX - pad;

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
    }
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
