/**
 * Sizing rules for the interface node's canvas box.
 *
 * The node box is snapped to the interface's declared format (see
 * `lib/interfaces/interfaceFormats.ts`) so the node IS the format: the rendered
 * content fills it edge-to-edge (no internal letterbox) and any status/selection
 * ring hugs the interface's real shape.
 */

import type { FormatViewport } from '@/lib/interfaces/interfaceFormats';

/**
 * Default bounding box (px) a fresh interface node snaps its format into. 400x400
 * keeps the historical 400x250 for classic/unset formats (1280x800 contained in it)
 * and gives portrait formats a usable size (vertical -> 225x400) instead of a tiny
 * letterboxed strip.
 */
export const DEFAULT_SNAP_BOX = 400;

/** Resizer bounds for the interface preview node (px). */
export const MIN_NODE_WIDTH = 100;
export const MAX_NODE_WIDTH = 800;
export const MIN_NODE_HEIGHT = 80;
export const MAX_NODE_HEIGHT = 800;

/**
 * Relative ratio tolerance under which a stored box is considered "already the
 * format's shape" and is preserved (only corrected). Above it the box is re-snapped
 * from the default snap box: preserving a mismatched box would ratchet the node
 * smaller on every format change (contain(A->B->A) only ever shrinks).
 */
const RATIO_MATCH_TOLERANCE = 0.02;

/**
 * Fit `viewport` (the interface's resolved format) into a box of the exact aspect
 * ratio. A stored box whose ratio already matches the format is preserved (resize
 * survives re-renders); a box with a different ratio - a format change, a legacy
 * free-form box, or unset dims - is re-snapped from the default snap box, so the
 * size never ratchets down across format changes.
 *
 * The result honors the resizer bounds. Min bounds scale ratio-preserving; the max
 * bounds are a final hard cap, so a degenerate custom format (e.g. 2160x16) yields
 * a bounded box whose small letterbox the thumbnail absorbs (the ring hugs the
 * CONTENT frame, not the box, so a broken ratio there stays invisible).
 */
export function snapBoxToFormat(
  viewport: FormatViewport,
  box: { width?: number | null; height?: number | null },
): { width: number; height: number } {
  const vpRatio = viewport.width / viewport.height;
  const ratioMatches =
    box.width != null && box.height != null && box.height > 0 &&
    Math.abs(box.width / box.height - vpRatio) / vpRatio <= RATIO_MATCH_TOLERANCE;
  const baseW = ratioMatches ? box.width! : DEFAULT_SNAP_BOX;
  const baseH = ratioMatches ? box.height! : DEFAULT_SNAP_BOX;

  let scale = Math.min(baseW / viewport.width, baseH / viewport.height);
  scale = Math.min(scale, MAX_NODE_WIDTH / viewport.width, MAX_NODE_HEIGHT / viewport.height);
  scale = Math.max(scale, MIN_NODE_WIDTH / viewport.width, MIN_NODE_HEIGHT / viewport.height);
  return {
    width: Math.min(MAX_NODE_WIDTH, Math.round(viewport.width * scale)),
    height: Math.min(MAX_NODE_HEIGHT, Math.round(viewport.height * scale)),
  };
}
