import { Position } from 'reactflow';
import type { CSSProperties } from 'react';
import type { WorkflowLayoutDirection } from '@/contexts/WorkflowLayoutDirectionContext';

/**
 * Where a node's handles sit for a given reading direction.
 *
 * SINGLE SOURCE OF TRUTH - do not hardcode `Position.Left`/`Position.Right` (or the
 * matching inline offsets) in a node component. Before this module those 44 sites
 * were copy-pasted across 19 files, so a node added later silently kept one
 * direction and its edges connected to the wrong side with no error. Every node
 * resolves its geometry here instead, from `useWorkflowLayoutDirectionSafe()`.
 *
 * The flow axis owns two opposite edges of the box: horizontal takes left/right,
 * vertical takes top/bottom. Everything else the node hangs outside itself (action
 * buttons, file strips, pagination) must therefore move to the FREE axis, which is
 * what `getSideAttachment` is for: in horizontal the bottom edge is free, in
 * vertical the right edge is.
 */

/** Offset (px) that centres a handle dot on the node border. */
const HANDLE_OFFSET = -6;
/**
 * Branch handles are pinned to their OWN ROW, which is inset by the node's padding,
 * so they need a bigger offset to reach the node border. The two values differ
 * because the node box is not padded equally on both axes (`px-5 py-4`): 27 clears
 * the 20px horizontal padding, 23 clears the 16px vertical one, each leaving the
 * dot centred on the border.
 */
const ROW_HANDLE_OFFSET_X = -27;
const ROW_HANDLE_OFFSET_Y = -23;

export interface HandleGeometry {
  position: Position;
  /** Merge into the Handle's `style`; carries only placement, never colour/opacity. */
  style: CSSProperties;
}

/** The incoming edge of the box for this direction. */
export function getTargetHandleGeometry(direction: WorkflowLayoutDirection): HandleGeometry {
  return direction === 'vertical'
    ? { position: Position.Top, style: { top: HANDLE_OFFSET, left: '50%', transform: 'translateX(-50%)' } }
    : { position: Position.Left, style: { left: HANDLE_OFFSET, top: '50%', transform: 'translateY(-50%)' } };
}

/** The outgoing edge of the box for this direction. */
export function getSourceHandleGeometry(direction: WorkflowLayoutDirection): HandleGeometry {
  return direction === 'vertical'
    ? { position: Position.Bottom, style: { bottom: HANDLE_OFFSET, left: '50%', transform: 'translateX(-50%)' } }
    : { position: Position.Right, style: { right: HANDLE_OFFSET, top: '50%', transform: 'translateY(-50%)' } };
}

/**
 * Geometry for a branch handle that is rendered INSIDE its own row (Decision's
 * if/else, Switch's cases, Fork's branches, Guardrail's pass/fail, Merge's inputs,
 * While's entry/exit/loop/body).
 *
 * These stay pinned to their row in BOTH directions, which is why the row container
 * must flip with `getBranchRowFlow()`: rows stack vertically in horizontal mode and
 * sit side by side in vertical mode, so each handle always lands on the node's flow
 * edge directly beside its own label.
 *
 * The alternative (the agent fleet's approach) hoists the handles to node level and
 * spreads them across the edge by percentage. That is the only option when the rows
 * cannot be reflowed, but it silently breaks the row-to-branch association: the
 * reader has to match the Nth label to the Nth dot. Flipping the container keeps
 * each dot under its own label, so it is preferred here.
 *
 * `outgoing` picks the flow edge: true = the node's source side, false = its target
 * side (Merge pins its INPUTS to rows).
 */
export function getBranchHandleGeometry(
  direction: WorkflowLayoutDirection,
  outgoing: boolean,
): HandleGeometry {
  if (direction === 'vertical') {
    // Outgoing rows sit at the node's BOTTOM (the source edge), so a row-pinned
    // `bottom` reaches the border. Incoming rows do NOT sit at the TOP - the header
    // is there - so an incoming handle cannot pin to its row; the caller hoists it to
    // node level with `getBranchHandleGeometryAt` instead. This branch is therefore
    // only used for the OUTGOING case in vertical.
    return { position: Position.Bottom, style: { bottom: ROW_HANDLE_OFFSET_Y, left: '50%', transform: 'translateX(-50%)' } };
  }
  return outgoing
    ? { position: Position.Right, style: { right: ROW_HANDLE_OFFSET_X, top: '50%', transform: 'translateY(-50%)' } }
    : { position: Position.Left, style: { left: ROW_HANDLE_OFFSET_X, top: '50%', transform: 'translateY(-50%)' } };
}

/**
 * NODE-LEVEL geometry for a branch handle spread across an edge by index, used when a
 * handle cannot stay pinned to its row. Today that is the vertical INCOMING case
 * (Merge/While inputs): the rows sit below the header, but the target edge is the
 * node's top, so the handles are rendered at node level and spread along the top
 * border, one above each input column - the same percentage spread the fleet uses.
 */
export function getBranchHandleGeometryAt(
  direction: WorkflowLayoutDirection,
  outgoing: boolean,
  index: number,
  total: number,
): HandleGeometry {
  const pct = branchSpreadPercent(index, total);
  if (direction === 'vertical') {
    return outgoing
      ? { position: Position.Bottom, style: { bottom: HANDLE_OFFSET, left: `${pct}%`, transform: 'translateX(-50%)' } }
      : { position: Position.Top, style: { top: HANDLE_OFFSET, left: `${pct}%`, transform: 'translateX(-50%)' } };
  }
  return outgoing
    ? { position: Position.Right, style: { right: HANDLE_OFFSET, top: `${pct}%`, transform: 'translateY(-50%)' } }
    : { position: Position.Left, style: { left: HANDLE_OFFSET, top: `${pct}%`, transform: 'translateY(-50%)' } };
}

/**
 * Tailwind classes for a branch-row container, so each row sits on the node's flow
 * edge next to its own handle. Replaces a hardcoded `space-y-2`: the gap has to
 * follow the axis, and `space-y` on a flex-row container spaces nothing.
 */
export function getBranchRowFlow(direction: WorkflowLayoutDirection): string {
  // `min-w-0` on the rows is load-bearing in vertical: flex items default to
  // `min-width: auto`, so a long condition label would push the node wider instead
  // of truncating - and the node width is what dagre packs against in TB.
  return direction === 'vertical'
    ? 'flex flex-row items-stretch gap-2 [&>*]:min-w-0'
    : 'flex flex-col gap-2';
}

/**
 * Percentage across an edge for handle `index` of `total`, for the cases that DO
 * hoist handles to node level (the agent fleet's bottom handles). One centres; two
 * sit at 35/65; three or more spread across the middle half (25%..75%) so the
 * outermost dots stay clear of the rounded corners.
 */
export function branchSpreadPercent(index: number, total: number): number {
  if (total <= 1) return 50;
  if (total === 2) return 35 + index * 30;
  return 25 + (index * 50) / (total - 1);
}

/**
 * Where to hang a node's outside attachments (action bar, file strip, pagination).
 * Returns the FREE edge: below the node in horizontal, beside it in vertical, since
 * the flow axis edge is occupied by handles.
 *
 * `gap` is the distance from the node border; `extraOffset` pushes a second row (or
 * column) further out when two attachments share the same edge.
 */
export function getSideAttachment(
  direction: WorkflowLayoutDirection,
  gap: number,
  extraOffset = 0,
): CSSProperties {
  if (direction === 'vertical') {
    // `extraOffset` is a fixed px nudge, tuned to clear a ~24px-tall button row on the
    // horizontal band. Beside the node, the occupant it must clear (the file strip) is
    // as WIDE as the node itself, so ANY px nudge would drop the bar inside it - and
    // the strip is z-20 against the bar's z-10, so the strip would paint over the
    // buttons and swallow their clicks.
    //
    // Percentages here resolve against the NODE (the positioned ancestor), and the
    // strip is `width: 100%` of it, so clearing a whole node-width is exactly one more
    // `100%`: the strip takes the first column beside the node, the bar takes the next.
    return extraOffset > 0
      ? { left: `calc(200% + ${gap * 2}px)`, top: '50%', transform: 'translateY(-50%)' }
      : { left: `calc(100% + ${gap}px)`, top: '50%', transform: 'translateY(-50%)' };
  }
  return {
    top: `calc(100% + ${gap + extraOffset}px)`,
    left: '50%',
    transform: 'translateX(-50%)',
  };
}

/**
 * Geometry for an attachment that SPANS the node rather than centring on it: the
 * run-time file strip and the data-input preview card, which are as wide as the node
 * so their pill/preview lines up with the box above.
 *
 * Separate from `getSideAttachment` because a stretched attachment must NOT be
 * cross-axis centred. Feeding it the centred geometry mixes `left: 50%` +
 * `translateX(-50%)` with the `left-3 right-3` classes these strips carry: the inline
 * `left` wins, `right-3` survives, and the strip computes to half width, off centre -
 * in the HORIZONTAL default, for every existing user. Own both axes here instead, so
 * no class is left to contradict.
 */
export function getStretchedAttachment(
  direction: WorkflowLayoutDirection,
  gap: number,
): CSSProperties {
  return direction === 'vertical'
    ? { left: `calc(100% + ${gap}px)`, top: '50%', transform: 'translateY(-50%)', width: '100%' }
    : { top: `calc(100% + ${gap}px)`, left: 12, right: 12 };
}

/**
 * Does this connection run AGAINST the flow? Backward edges are drawn as smoothstep
 * so they route around the graph instead of cutting back through it as a bezier.
 *
 * Keyed off the reading direction, not the handle position: a top-to-bottom canvas
 * has every forward edge running with sourceY < targetY, so a position-derived test
 * flags them all as backward and misses the real loop-backs. Shared by the rendered
 * edge (`BuilderEdge`) and the drag-preview line (`CustomConnectionLine`), which
 * previously carried two copies of the same inverted condition.
 */
export function isFlowBackward(
  direction: WorkflowLayoutDirection,
  sourceX: number,
  sourceY: number,
  targetX: number,
  targetY: number,
): boolean {
  return direction === 'vertical' ? sourceY > targetY : sourceX > targetX;
}
