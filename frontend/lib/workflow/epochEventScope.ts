/**
 * Cross-tree "viewing epoch" sync - the `viewingEpochChanged` window event.
 *
 * The canvas RunInfo and the SidePanel application tabs live in separate React
 * trees (separate `WorkflowModeProvider`s), so the selected epoch is broadcast
 * between them through a global window event. That event MUST be scoped by
 * runId: the multi-app side panel keeps every opened app tab mounted at once
 * (`keepMounted`), so an unscoped event slaves every app's epoch selector to
 * whichever app dispatched last - the "two apps share one epoch system" bug.
 *
 * Rule: a listener adopts an epoch event only when it is NOT addressed to a
 * different run. When either side's runId is unknown (null/undefined) we fall
 * back to the legacy global behavior so a dispatcher that cannot name its run
 * (and the canvas before a run is attached) still syncs.
 */
export const VIEWING_EPOCH_EVENT = 'viewingEpochChanged' as const;

export interface EpochEventDetail {
  epoch: number | null;
  /** Run the epoch selection belongs to. Absent on legacy/unscoped dispatches. */
  runId?: string | null;
}

/**
 * True when a `viewingEpochChanged` event addressed to `eventRunId` should be
 * applied by a listener bound to `listenerRunId`. Only a definite mismatch
 * (both runIds known and different) is rejected.
 */
export function shouldAdoptEpochEvent(
  eventRunId: string | null | undefined,
  listenerRunId: string | null | undefined,
): boolean {
  if (eventRunId != null && listenerRunId != null && eventRunId !== listenerRunId) {
    return false;
  }
  return true;
}
