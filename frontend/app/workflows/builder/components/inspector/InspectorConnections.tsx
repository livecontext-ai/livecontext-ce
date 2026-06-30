'use client';

import * as React from 'react';
import type { Connection, HandlePosition } from './useInspectorConnections';
import type { ConnectionType } from '../ConnectionTypeSelector';

interface InspectorConnectionsProps {
  connections: Connection[];
  handlePositions: Map<string, HandlePosition>;
  draggingFromHandle: string | null;
  mousePosition: { x: number; y: number } | null;
  hoveredTargetHandle: string | null;
  dragStartPosition: { x: number; y: number } | null;
  connectionType: ConnectionType;
  onDeleteConnection: (connId: string) => void;
}

function createPath(x1: number, y1: number, x2: number, y2: number, type: ConnectionType): string {
  const dx = x2 - x1;
  const dy = y2 - y1;
  const midX = (x1 + x2) / 2;
  const midY = (y1 + y2) / 2;
  
  switch (type) {
    case 'straight':
      return `M ${x1} ${y1} L ${x2} ${y2}`;
      
    case 'bezier': {
      const curvature = Math.min(Math.abs(dx) * 0.5, 200);
      const cp1x = x1 + (dx > 0 ? curvature : -curvature);
      const cp1y = y1;
      const cp2x = x2 + (dx > 0 ? -curvature : curvature);
      const cp2y = y2;
      return `M ${x1} ${y1} C ${cp1x} ${cp1y}, ${cp2x} ${cp2y}, ${x2} ${y2}`;
    }
    
    case 'smoothstep': {
      const curvature = Math.min(Math.abs(dx) * 0.6, 250);
      const cp1x = x1 + (dx > 0 ? curvature * 0.7 : -curvature * 0.7);
      const cp1y = y1;
      const cp2x = x2 + (dx > 0 ? -curvature * 0.7 : curvature * 0.7);
      const cp2y = y2;
      return `M ${x1} ${y1} C ${cp1x} ${cp1y}, ${cp2x} ${cp2y}, ${x2} ${y2}`;
    }
    
    case 'step': {
      const stepX = midX;
      return `M ${x1} ${y1} L ${stepX} ${y1} L ${stepX} ${y2} L ${x2} ${y2}`;
    }
    
    case 'wave': {
      const horizontalCurvature = Math.min(Math.abs(dx) * 0.6, 250);
      const waveAmplitude = Math.min(Math.abs(dx) * 0.25, 70);
      const isLeftToRight = dx > 0;
      
      const cp1x = x1 + (isLeftToRight ? horizontalCurvature * 0.4 : -horizontalCurvature * 0.4);
      const cp1y = midY + (isLeftToRight ? waveAmplitude : -waveAmplitude);
      const cp2x = x2 + (isLeftToRight ? -horizontalCurvature * 0.4 : horizontalCurvature * 0.4);
      const cp2y = midY + (isLeftToRight ? waveAmplitude : -waveAmplitude);
      
      return `M ${x1} ${y1} C ${cp1x} ${cp1y}, ${cp2x} ${cp2y}, ${x2} ${y2}`;
    }
    
    default:
      return `M ${x1} ${y1} L ${x2} ${y2}`;
  }
}

function getMidPoint(x1: number, y1: number, x2: number, y2: number, type: ConnectionType): { x: number; y: number } {
  const dx = x2 - x1;
  const dy = y2 - y1;
  const midX = (x1 + x2) / 2;
  const midY = (y1 + y2) / 2;
  
  switch (type) {
    case 'straight':
      return { x: midX, y: midY };
      
    case 'bezier': {
      const curvature = Math.min(Math.abs(dx) * 0.5, 200);
      const cp1x = x1 + (dx > 0 ? curvature : -curvature);
      const cp1y = y1;
      const cp2x = x2 + (dx > 0 ? -curvature : curvature);
      const cp2y = y2;
      const t = 0.5;
      const mt = 1 - t;
      const x = mt * mt * mt * x1 + 3 * mt * mt * t * cp1x + 3 * mt * t * t * cp2x + t * t * t * x2;
      const y = mt * mt * mt * y1 + 3 * mt * mt * t * cp1y + 3 * mt * t * t * cp2y + t * t * t * y2;
      return { x, y };
    }
    
    case 'smoothstep': {
      const curvature = Math.min(Math.abs(dx) * 0.6, 250);
      const cp1x = x1 + (dx > 0 ? curvature * 0.7 : -curvature * 0.7);
      const cp1y = y1;
      const cp2x = x2 + (dx > 0 ? -curvature * 0.7 : curvature * 0.7);
      const cp2y = y2;
      const t = 0.5;
      const mt = 1 - t;
      const x = mt * mt * mt * x1 + 3 * mt * mt * t * cp1x + 3 * mt * t * t * cp2x + t * t * t * x2;
      const y = mt * mt * mt * y1 + 3 * mt * mt * t * cp1y + 3 * mt * t * t * cp2y + t * t * t * y2;
      return { x, y };
    }
    
    case 'step': {
      const stepX = midX;
      return { x: stepX, y: midY };
    }
    
    case 'wave': {
      const horizontalCurvature = Math.min(Math.abs(dx) * 0.6, 250);
      const waveAmplitude = Math.min(Math.abs(dx) * 0.25, 70);
      const isLeftToRight = dx > 0;
      const cp1x = x1 + (isLeftToRight ? horizontalCurvature * 0.4 : -horizontalCurvature * 0.4);
      const cp1y = midY + (isLeftToRight ? waveAmplitude : -waveAmplitude);
      const cp2x = x2 + (isLeftToRight ? -horizontalCurvature * 0.4 : horizontalCurvature * 0.4);
      const cp2y = midY + (isLeftToRight ? waveAmplitude : -waveAmplitude);
      const t = 0.5;
      const mt = 1 - t;
      const x = mt * mt * mt * x1 + 3 * mt * mt * t * cp1x + 3 * mt * t * t * cp2x + t * t * t * x2;
      const y = mt * mt * mt * y1 + 3 * mt * mt * t * cp1y + 3 * mt * t * t * cp2y + t * t * t * y2;
      return { x, y };
    }
    
    default:
      return { x: midX, y: midY };
  }
}

export function InspectorConnections({
  connections,
  handlePositions,
  draggingFromHandle,
  mousePosition,
  hoveredTargetHandle,
  dragStartPosition,
  connectionType,
  onDeleteConnection,
}: InspectorConnectionsProps) {
  const paths: React.ReactElement[] = [];
  const deleteButtons: React.ReactElement[] = [];
  
  // Draw existing connections
  connections.forEach(conn => {
    const sourcePos = handlePositions.get(conn.source);
    const targetPos = handlePositions.get(conn.target);
    
    if (sourcePos && targetPos) {
      const connType = conn.type || connectionType;
      const pathData = createPath(sourcePos.x, sourcePos.y, targetPos.x, targetPos.y, connType);
      
      paths.push(
        <path
          key={`${conn.id}-${connType}`}
          d={pathData}
          stroke="#94a3b8"
          strokeWidth="2"
          fill="none"
          strokeDasharray="none"
          style={{ cursor: 'crosshair' }}
        />
      );
      
      const midPoint = getMidPoint(sourcePos.x, sourcePos.y, targetPos.x, targetPos.y, connType);
      deleteButtons.push(
        <g
          key={`delete-${conn.id}`}
          transform={`translate(${midPoint.x}, ${midPoint.y})`}
          className="cursor-pointer group"
          onClick={(e) => {
            e.stopPropagation();
            onDeleteConnection(conn.id);
          }}
        >
          {/* Zone cliquable plus grande avec padding */}
          <circle
            r="16"
            fill="white"
            strokeWidth="0"
            className="group-hover:fill-slate-100 transition-colors"
          />
          {/* Cercle principal */}
          <circle
            r="12"
            fill="white"
            strokeWidth="0"
            className="group-hover:fill-slate-50 transition-colors"
          />
          <g transform="translate(-6, -6) scale(0.5)">
            <path
              d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6h14zM10 11v6M14 11v6"
              fill="none"
              stroke="#64748b"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="group-hover:stroke-slate-700 transition-colors"
            />
          </g>
        </g>
      );
    }
  });
  
  // Draw the current connection line (that follows the mouse)
  if (draggingFromHandle && mousePosition && dragStartPosition) {
    let targetX = mousePosition.x;
    let targetY = mousePosition.y;
    let strokeColor = hoveredTargetHandle ? '#10b981' : '#3b82f6';
    let strokeWidth = hoveredTargetHandle ? '2.5' : '2';
    
    if (hoveredTargetHandle) {
      const targetPos = handlePositions.get(hoveredTargetHandle);
      if (targetPos) {
        targetX = targetPos.x;
        targetY = targetPos.y;
      }
    }
    
    const pathData = createPath(dragStartPosition.x, dragStartPosition.y, targetX, targetY, connectionType);
    paths.push(
      <path
        key="pending-connection"
        d={pathData}
        stroke={strokeColor}
        strokeWidth={strokeWidth}
        fill="none"
        strokeDasharray={hoveredTargetHandle ? "none" : "5,5"}
        opacity={hoveredTargetHandle ? "1" : "0.7"}
        style={{ transition: 'all 0.15s ease' }}
      />
    );
  }
  
  if (paths.length === 0 && deleteButtons.length === 0 && !draggingFromHandle) return null;
  
  return (
    <svg
      className="absolute inset-0"
      style={{ 
        width: '100%',
        height: '100%',
        zIndex: 1000,
        overflow: 'visible',
        pointerEvents: 'none'
      }}
    >
      <g style={{ pointerEvents: 'stroke', cursor: 'crosshair' }}>
        {paths}
      </g>
      <g style={{ pointerEvents: 'all' }}>
        {deleteButtons}
      </g>
    </svg>
  );
}

