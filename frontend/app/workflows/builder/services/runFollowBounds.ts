/**
 * The rectangle the camera should frame so that every running node is visible.
 *
 * Pure and separate from the canvas so the framing rule can be tested without a
 * ReactFlow instance, which is the part worth testing: the fan-out case (a fork, or two
 * independent branches, put several nodes in RUNNING at once) is exactly what a manual check in front
 * of a canvas never reproduces reliably.
 */

export interface FollowNodeLike {
  position?: { x: number; y: number } | null;
  width?: number | null;
  height?: number | null;
}

export interface FollowRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface FollowBoundsOptions {
  /** Breathing room added on every side, in flow units. */
  pad: number;
  /** Used only for a node ReactFlow has not measured yet. */
  fallbackWidth: number;
  fallbackHeight: number;
}

/**
 * @returns the padded bounding box of `nodes`, or null when there is nothing to frame.
 *
 * A node with no position is skipped rather than treated as sitting at the origin: an
 * unplaced node would otherwise drag the box to (0, 0) and point the camera at empty
 * canvas, which looks exactly like a bug in the follow itself.
 */
export function computeFollowBounds(
  nodes: FollowNodeLike[],
  options: FollowBoundsOptions,
): FollowRect | null {
  const placed = nodes.filter(
    (node): node is FollowNodeLike & { position: { x: number; y: number } } =>
      !!node && !!node.position && Number.isFinite(node.position.x) && Number.isFinite(node.position.y),
  );
  if (placed.length === 0) return null;

  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;

  for (const node of placed) {
    // A measured width of 0 is not a measurement, it is a node mid-mount; fall back
    // rather than collapse the box on it.
    const width = node.width && node.width > 0 ? node.width : options.fallbackWidth;
    const height = node.height && node.height > 0 ? node.height : options.fallbackHeight;
    minX = Math.min(minX, node.position.x);
    minY = Math.min(minY, node.position.y);
    maxX = Math.max(maxX, node.position.x + width);
    maxY = Math.max(maxY, node.position.y + height);
  }

  return {
    x: minX - options.pad,
    y: minY - options.pad,
    width: maxX - minX + options.pad * 2,
    height: maxY - minY + options.pad * 2,
  };
}

export interface FollowEventDetail {
  workflowId?: string;
  nodeIds?: string[];
}

export interface IdentifiedNode extends FollowNodeLike {
  id: string;
}

/**
 * The frame a canvas should move to for a follow event, or null to ignore the event.
 *
 * Holds the whole consumer contract in one testable place, including the part that is
 * easy to get wrong and impossible to notice: an event belonging to ANOTHER workflow
 * must be ignored. Several canvases are mounted at once, so without that check a run
 * in a side-panel tab drags the page canvas around, and a canvas in edit mode has no
 * toolbar control to stop it.
 */
export function resolveFollowFrame(
  nodes: IdentifiedNode[],
  detail: FollowEventDetail | null | undefined,
  workflowId: string | undefined,
  options: FollowBoundsOptions,
): FollowRect | null {
  if (!detail || !Array.isArray(detail.nodeIds) || detail.nodeIds.length === 0) return null;
  // A canvas with no id follows nothing. Comparing two undefined ids would be
  // vacuously true and would wave through any dispatcher that omitted the field.
  if (!workflowId || detail.workflowId !== workflowId) return null;
  const wanted = new Set(detail.nodeIds);
  return computeFollowBounds(nodes.filter((node) => wanted.has(node.id)), options);
}
