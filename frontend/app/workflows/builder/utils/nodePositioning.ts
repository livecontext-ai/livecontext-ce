/**
 * Utilities for calculating node positions in the workflow builder
 */

interface PendingHoverConnection {
  nodeId: string;
  handleId: string;
  handleType: 'source' | 'target';
  handlePosition: 'left' | 'right' | 'top';
  position: { x: number; y: number };
}

/**
 * Calculate the target position for a new node based on pending connection or grid pattern
 */
export function calculateNodePosition(
  pendingConnection: PendingHoverConnection | null,
  nodeCreationCounter: number
): { x: number; y: number } {
  if (pendingConnection) {
    const nodeWidth = 220; // Approximate node width
    const nodeHeight = 100; // Approximate node height
    const spacing = 150; // Space between nodes

    if (pendingConnection.handlePosition === 'right') {
      // Clicked on right handle - position new node to the right
      return {
        x: pendingConnection.position.x + spacing,
        y: pendingConnection.position.y - 40, // Center vertically
      };
    } else if (pendingConnection.handlePosition === 'top') {
      // Clicked on top handle - position new node above
      return {
        x: pendingConnection.position.x - nodeWidth / 2, // Center horizontally
        y: pendingConnection.position.y - nodeHeight - spacing,
      };
    } else {
      // Clicked on left handle - position new node to the left
      return {
        x: pendingConnection.position.x - nodeWidth - spacing,
        y: pendingConnection.position.y - 40, // Center vertically
      };
    }
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
