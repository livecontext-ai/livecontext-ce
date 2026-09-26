import {
  isWorkflowLayoutDirection,
  type WorkflowLayoutDirection,
} from '@/contexts/WorkflowLayoutDirectionContext';

/**
 * Which way a plan is read on a canvas, and whether its stored positions can be kept.
 *
 * <p>A stored position only means something in the direction it was computed in: a
 * left-to-right graph drawn with top-to-bottom handles is the "every workflow looks broken"
 * defect. So a plan's positions have a direction of their own, and the canvas either renders
 * in that direction or re-lays the graph out.
 *
 * <p>The positions' direction is, in order:
 * <ol>
 *   <li>the plan's own stamp (`plan.layoutDirection`, written on every save);</li>
 *   <li>`unstampedPositionsDirection` for a plan that has positions but no stamp. Horizontal by
 *       default: every plan saved before the stamp existed was authored left to right. A caller
 *       that already knows better passes its own (the agent plan-sync passes the canvas's, since
 *       the plan it re-reads is the one this canvas has been showing);</li>
 *   <li>none, for a plan without a single stored position (an agent build, a new workflow).</li>
 * </ol>
 *
 * <p>The canvas direction is then the pinned one when the surface pins it (a marketplace
 * preview renders the publisher's direction whatever the viewer prefers), else the positions'
 * direction, else the viewer's default: a plan that carries no layout information at all is
 * the only one the viewer's preference decides.
 */
export interface PlanLayoutInput {
  /** `plan.layoutDirection` as stored, unvalidated. */
  storedDirection: unknown;
  /** At least one node of the plan carries a stored position. */
  hasStoredPositions: boolean;
  /** The viewer's default, used only when the plan says nothing. */
  fallbackDirection: WorkflowLayoutDirection;
  /** A surface that fixes its reading direction. Wins over everything. */
  forcedDirection?: WorkflowLayoutDirection;
  /** Direction assumed for positions saved without a stamp. Defaults to horizontal. */
  unstampedPositionsDirection?: WorkflowLayoutDirection;
}

export interface PlanLayoutResolution {
  /** The direction the canvas renders this plan in. */
  direction: WorkflowLayoutDirection;
  /**
   * The stored positions were computed in the OTHER direction, so none of them can be kept:
   * the whole graph must be laid out again in `direction`.
   */
  relayout: boolean;
}

export const LEGACY_POSITIONS_DIRECTION: WorkflowLayoutDirection = 'horizontal';

export function resolvePlanLayout(input: PlanLayoutInput): PlanLayoutResolution {
  const positionsDirection: WorkflowLayoutDirection | null = isWorkflowLayoutDirection(
    typeof input.storedDirection === 'string' ? input.storedDirection : null,
  )
    ? (input.storedDirection as WorkflowLayoutDirection)
    : input.hasStoredPositions
      ? (input.unstampedPositionsDirection ?? LEGACY_POSITIONS_DIRECTION)
      : null;

  const direction = input.forcedDirection ?? positionsDirection ?? input.fallbackDirection;
  return {
    direction,
    relayout: input.hasStoredPositions && positionsDirection !== null && positionsDirection !== direction,
  };
}

/** How a surface reads a plan, as handed to `WorkflowPlanImporter.importPlan`. */
export interface PlanLayoutOptions {
  /** The viewer's default, used only for a plan with no stamp and no stored position. */
  fallbackDirection: WorkflowLayoutDirection;
  /** The surface fixes the direction (a preview, or a plan merged into an open canvas). */
  forcedDirection?: WorkflowLayoutDirection;
  /** Direction assumed for positions saved without a stamp. Horizontal when omitted. */
  unstampedPositionsDirection?: WorkflowLayoutDirection;
}

/**
 * The options for loading a plan onto a canvas: a pinned canvas lays every plan out in its
 * pin, any other one lets the plan decide and falls back to the user's default.
 */
export function canvasLoadLayoutOptions(canvas: {
  layoutDirection: WorkflowLayoutDirection;
  defaultDirection: WorkflowLayoutDirection;
  isPinned: boolean;
}): PlanLayoutOptions {
  return canvas.isPinned
    ? { fallbackDirection: canvas.layoutDirection, forcedDirection: canvas.layoutDirection }
    : { fallbackDirection: canvas.defaultDirection };
}

/**
 * The options for the agent plan-sync, which re-reads the plan of the canvas it is open on.
 * A direction the plan states wins (an agent may set one with set_plan, and the canvas then
 * adopts it); otherwise the plan is the canvas's own, so its unstamped positions are taken
 * to be in the canvas direction. Assuming horizontal there would re-lay a vertical workflow
 * whose stamp an older writer dropped.
 */
export function planSyncLayoutOptions(direction: WorkflowLayoutDirection): PlanLayoutOptions {
  return { fallbackDirection: direction, unstampedPositionsDirection: direction };
}
