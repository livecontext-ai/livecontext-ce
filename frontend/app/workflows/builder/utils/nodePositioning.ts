/**
 * Utilities for calculating node positions in the workflow builder
 */

import type { WorkflowLayoutDirection } from '@/contexts/WorkflowLayoutDirectionContext';
import { DEFAULT_WORKFLOW_LAYOUT_DIRECTION } from '@/contexts/WorkflowLayoutDirectionContext';

interface PendingHoverConnection {
  nodeId: string;
  handleId: string;
  handleType: 'source' | 'target';
  handlePosition: 'left' | 'right' | 'top' | 'bottom';
  position: { x: number; y: number };
}

const NODE_WIDTH = 220; // approximate
const NODE_HEIGHT = 100; // approximate
const SPACING = 150; // gap between the "+" and the new node

/**
 * Where to drop a node created from a hover "+".
 *
 * Placed by the handle's ROLE and the canvas direction, not the geometric side, so a
 * source "+" always continues the flow (right in horizontal, below in vertical) and a
 * target "+" always precedes the hovered node. `pending.position` is the "+"'s own
 * location, already offset out along the correct axis by the overlay, so the new node
 * just extends past it. The old switch keyed off `handlePosition` and had no vertical
 * case, so a vertical "+" placed the node sideways.
 */
export function calculateNodePosition(
  pendingConnection: PendingHoverConnection | null,
  nodeCreationCounter: number,
  direction: WorkflowLayoutDirection = DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
): { x: number; y: number } {
  if (pendingConnection) {
    const { position, handleType } = pendingConnection;
    const isSource = handleType === 'source';

    if (direction === 'vertical') {
      // Flow runs down: a source drops the new node below, a target above, centred
      // horizontally on the "+".
      return {
        x: position.x - NODE_WIDTH / 2,
        y: isSource ? position.y + SPACING : position.y - NODE_HEIGHT - SPACING,
      };
    }
    // Flow runs right: a source drops the new node to the right, a target to the left,
    // centred vertically on the "+".
    return {
      x: isSource ? position.x + SPACING : position.x - NODE_WIDTH - SPACING,
      y: position.y - 40,
    };
  }

  // Default positioning (grid pattern) when no pending connection
  const basePosition = { x: 400, y: 300 };
  const gridSize = 180; // Grid spacing
  const colsPerRow = 3;
  const col = nodeCreationCounter % colsPerRow;
  const row = Math.floor(nodeCreationCounter / colsPerRow);

  return {
    x: basePosition.x + (col - 1) * gridSize,
    y: basePosition.y + row * gridSize,
  };
}
