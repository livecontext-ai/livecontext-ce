import { Position } from 'reactflow';

/**
 * Where a hero edge really starts and ends, whatever CSS scale the hero is drawn at.
 *
 * <p>The hero draws its workflow window at a fixed design size and scales it to fit with a
 * CSS `transform: scale()` (0.317 on a 390px phone). ReactFlow 11 is not aware of that outer
 * scale. For each handle it stores (`getHandleBounds`):
 *   - `x`, `y`: from `getBoundingClientRect()` divided by its own zoom, so SHRUNK by the
 *     outer scale `s`;
 *   - `width`, `height`: from `offsetWidth`/`offsetHeight`, so NOT shrunk.
 * and it places the edge (`getHandlePosition`) at `x + width / 2`, plus `height` on y for a
 * bottom handle. Measured on production at `s = 0.317`, an edge left its 260px node 45.4px
 * from the left instead of at 130px (`0.317 * (130 - 12/2) + 12/2 = 45.3`), so every
 * connection on a phone sat off to the left of its card.
 *
 * <p>The hero's handles are horizontally centred on the node (the builder's
 * `left: 50%; translateX(-50%)` geometry, top or bottom). That known centre gives the scale
 * that was in effect when ReactFlow measured: `s = x / (width / 2 - handleWidth / 2)`, and
 * dividing the stored `y` by it recovers the true offset. This is exact, and it does not
 * depend on when the measurement happened or on the scale changing after it.
 */
export interface NodeBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** A handle as ReactFlow stores it: offsets relative to the node, size unscaled. */
export interface MeasuredHandle {
  x: number;
  y: number;
  width: number;
  height: number;
  position: Position;
}

export function correctHandlePoint(
  node: NodeBox | undefined,
  handle: MeasuredHandle | undefined,
  fallback: { x: number; y: number },
): { x: number; y: number } {
  // Only a top or bottom handle is centred horizontally; anything else keeps ReactFlow's point.
  if (!node || !handle || (handle.position !== Position.Top && handle.position !== Position.Bottom)) return fallback;
  const trueLeft = node.width / 2 - handle.width / 2;
  if (!(trueLeft > 0)) return fallback;
  const scale = handle.x / trueLeft;
  // Not a plausible scale means the handle is not the centred one this relies on: do not guess.
  if (!(scale > 0.05 && scale <= 1.2)) return fallback;
  return {
    x: node.x + node.width / 2,
    y: node.y + handle.y / scale + (handle.position === Position.Bottom ? handle.height : 0),
  };
}
