/**
 * Pure width math for the RunInfo panel (the right-side-panel run popover that
 * hosts the epoch selector). Kept dependency-free so it is trivially unit-testable
 * and importable without pulling in the client component.
 */

/** Comfortable width for the expanded RunInfo panel when there is room for it. */
export const RUN_INFO_PREFERRED_MIN_WIDTH = 340;

/**
 * Compute the `[minWidth, maxWidth]` (px) bounds of the expanded RunInfo panel
 * for a given measured container width.
 *
 * The panel is anchored to the right of the canvas. When the canvas is the
 * embedded application side panel the mode toggle is hidden, so we reserve only
 * a small gutter; otherwise we reserve room for the centered toggle on the left.
 *
 * Invariant (the bug this guards): `minWidth <= maxWidth <= containerWidth`.
 * Previously the panel forced `min-w-[340px]` regardless of the container while
 * the max reserved a fixed 160px for a toggle the side panel doesn't show - so in
 * a narrow side panel the 340px floor exceeded the max and the panel (with its
 * epoch-selector rows) overflowed off-screen to the right, out of reach.
 */
export function computeRunInfoPanelWidths(
  containerWidth: number,
  showToggle: boolean,
): { minWidth: number; maxWidth: number } {
  const safeWidth = Number.isFinite(containerWidth) && containerWidth > 0 ? containerWidth : 0;
  const toggleReserve = showToggle ? 160 : 24;
  // Never wider than the container (minus a small gutter), and never below a
  // readable floor - but the floor still yields to a very narrow container.
  const maxWidth = Math.min(
    Math.max(0, safeWidth - 16),
    Math.max(240, safeWidth - toggleReserve),
  );
  const minWidth = Math.min(RUN_INFO_PREFERRED_MIN_WIDTH, maxWidth);
  return { minWidth, maxWidth };
}
