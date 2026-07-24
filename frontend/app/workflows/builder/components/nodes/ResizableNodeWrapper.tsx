'use client';

import { NodeResizer } from 'reactflow';

interface ResizableNodeWrapperProps {
  /** Whether resize is enabled (e.g., disabled in run mode) */
  enabled?: boolean;
  minWidth?: number;
  maxWidth?: number;
  minHeight?: number;
  maxHeight?: number;
  /** Called when resize ends - use to persist dimensions to node data */
  onResizeEnd?: (width: number, height: number) => void;
  /** Called during resize - use for live feedback (e.g., disable transitions) */
  onResize?: () => void;
  /** Handle line color */
  color?: string;
  /** Lock the node's current aspect ratio while resizing (interface nodes: the declared format's ratio) */
  keepAspectRatio?: boolean;
}

/**
 * Wrapper around React Flow's NodeResizer.
 * Provides 4 sides + 4 corners resize with hover visibility via CSS.
 *
 * NodeResizer writes directly to node.style.width/height in the React Flow store.
 * Use onResizeEnd to sync back to node data for persistence.
 */
export function ResizableNodeWrapper({
  enabled = true,
  minWidth = 100,
  maxWidth = 800,
  minHeight = 80,
  maxHeight = 600,
  onResizeEnd,
  onResize,
  color = '#94a3b8',
  keepAspectRatio = false,
}: ResizableNodeWrapperProps) {
  return (
    <NodeResizer
      isVisible={enabled}
      minWidth={minWidth}
      maxWidth={maxWidth}
      minHeight={minHeight}
      maxHeight={maxHeight}
      keepAspectRatio={keepAspectRatio}
      handleClassName="node-resize-handle"
      lineClassName="node-resize-line"
      color={color}
      onResize={onResize ? () => onResize() : undefined}
      onResizeEnd={(_event, params) => {
        if (onResizeEnd) {
          onResizeEnd(
            Math.round(params.width),
            Math.round(params.height),
          );
        }
      }}
    />
  );
}
