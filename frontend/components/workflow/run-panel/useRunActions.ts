'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { orchestratorApi } from '@/lib/api';
import {
  getCachedRunPanelData,
  requestRunAction,
  subscribeRunPanelData,
  type RunPanelAction,
  type RunPanelData,
} from './runPanelBus';

/**
 * Stop / cancel / reactivate a run, from anywhere.
 *
 * <p>Asks the canvas FIRST: it owns the run manager, so letting it act keeps its
 * status, its ready steps and its websocket view in sync in the same tick. When
 * no canvas claims the request (none mounted on this surface, or it was torn
 * down between the render and the click) the REST call is made directly instead
 * of dropping the action on the floor - which is what a `CustomEvent` with no
 * listener did, silently, leaving a run nobody could stop.
 *
 * <p>A canvas that claims the run but declines it (marketplace preview) counts as
 * handled, so a read-only surface is never worked around from here.
 */
export async function performRunAction(
  action: RunPanelAction,
  target: { workflowId: string; runId?: string | null; surfaceId?: string },
): Promise<void> {
  // Addressed, always: an unaddressed request is claimed by EVERY mounted canvas,
  // so a surface that lost its workflow id would have a foreign canvas answer for
  // it. Nothing renders a control without one, and this keeps it that way.
  if (!target.workflowId) throw new Error('No workflow to act on');

  const claim = requestRunAction({
    action,
    workflowId: target.workflowId,
    runId: target.runId,
    surfaceId: target.surfaceId,
  });
  if (claim.handled) {
    // Await the claimer, so `pending` covers the real work and its failure lands
    // on the control that was pressed rather than only in a toast host that the
    // application page and the share link do not have.
    await claim.result;
    return;
  }

  const runId = target.runId || getCachedRunPanelData(target.workflowId, target.surfaceId).runId;
  if (!runId) throw new Error('No run to act on');
  // Note: unlike the canvas path, this does NOT refresh the run afterwards - the
  // bus is the canvas' to publish, and there is none here by definition. On a
  // surface with no canvas the control therefore stays until something else
  // republishes. Every surface the product has does mount one, so this is the
  // safety net's safety net rather than a path a user waits on.
  if (action === 'stop') await orchestratorApi.stopWorkflow(runId);
  else if (action === 'cancel') await orchestratorApi.cancelWorkflow(runId);
  else await orchestratorApi.reactivateWorkflow(runId);
}

export interface RunActionsState {
  /** The run the canvas of this workflow surface is bound to, if any. */
  runId: string | null;
  /** Raw run status, or undefined while no snapshot has been published. */
  status?: string;
  pinnedVersion: number | null;
  /** Marketplace preview: the run is frozen and must not be acted on. */
  isPreviewOnly: boolean;
  /** The action currently in flight, null when idle. */
  pending: RunPanelAction | null;
  /**
   * Whether the last attempt failed. A FLAG, not a message: the reason goes to
   * the console, because an API string is not translated and this is user-facing.
   * Cleared when the next attempt starts and when the surface changes run.
   */
  failed: boolean;
  perform: (action: RunPanelAction) => void;
}

interface RunSnapshot {
  workflowId: string | null;
  surfaceId?: string;
  runId: string | null;
  status?: string;
  pinnedVersion: number | null;
  isPreviewOnly: boolean;
}

/**
 * The PRIMITIVES a surface needs, and nothing else.
 *
 * Deliberately not the snapshot itself: it changes identity on every streamed
 * step batch (several times a second on a live run), and the surfaces using this
 * hook - a panel tab bar, an application toolbar - sit above large subtrees that
 * must not re-render at that rate.
 */
function readSnapshot(workflowId: string | null, surfaceId?: string): RunSnapshot {
  const data = getCachedRunPanelData(workflowId ?? '', surfaceId);
  return {
    workflowId,
    surfaceId,
    runId: data.runId,
    status: data.runInfo?.status as string | undefined,
    pinnedVersion: data.pinnedVersion,
    isPreviewOnly: data.isPreviewOnly,
  };
}

function sameSnapshot(a: RunSnapshot, b: RunSnapshot): boolean {
  return a.workflowId === b.workflowId
    && (a.surfaceId ?? null) === (b.surfaceId ?? null)
    && a.runId === b.runId
    && a.status === b.status
    && a.pinnedVersion === b.pinnedVersion
    && a.isPreviewOnly === b.isPreviewOnly;
}

/** The run-action state a surface needs to render `RunActionButton`. */
export function useRunActions(
  workflowId: string | null | undefined,
  /**
   * The run this SURFACE is showing, when it knows better than the bus.
   *
   * It wins over the bus, and the order matters: `RunPanelContent` deliberately
   * prefers the run the user just picked over `data.runId`, because the bus
   * still names the PREVIOUS one until the canvas rebinds. Reading the bus first
   * here would act on that previous run from a bar showing the new one.
   */
  preferredRunId?: string | null,
  /** Canvas/panel pair to observe. Omitted for the route-owned page surface. */
  surfaceId?: string,
): RunActionsState {
  const id = workflowId ?? null;
  const [snapshot, setSnapshot] = useState<RunSnapshot>(() => readSnapshot(id, surfaceId));
  const [pending, setPending] = useState<RunPanelAction | null>(null);
  const [failed, setFailed] = useState(false);
  /** The run `pending` / `failed` are about. */
  const [actedOn, setActedOn] = useState<string | null>(null);

  // Re-read DURING the render that changes workflow, not in an effect: an effect
  // would paint one frame of the previous workflow's run - long enough to offer a
  // stop for a run this surface is no longer showing.
  //
  if (snapshot.workflowId !== id || (snapshot.surfaceId ?? null) !== (surfaceId ?? null)) {
    setSnapshot(readSnapshot(id, surfaceId));
  }

  // `pending` and `failed` describe an attempt on ONE run, so they are dropped
  // the moment the surface points at another - a change of workflow, and equally
  // a change of run, which is what picking one in the run history does. Carried
  // over, a healthy run inherits the red ring and the "this did not work" name;
  // a carried-over `pending` is worse, leaving its stop disabled and spinning
  // until a promise about a different run settles.
  const snapshotMatchesSurface = snapshot.workflowId === id
    && (snapshot.surfaceId ?? null) === (surfaceId ?? null);
  const currentRunId = preferredRunId
    ?? (snapshotMatchesSurface ? snapshot.runId : readSnapshot(id, surfaceId).runId)
    ?? null;
  if (actedOn !== currentRunId) {
    setActedOn(currentRunId);
    if (pending !== null) setPending(null);
    if (failed) setFailed(false);
  }

  useEffect(() => {
    if (!id) return;
    // Re-read before subscribing: the canvas that publishes is a CHILD of some of
    // these hosts, so its publish effect has already run by the time this one
    // does. A live run would self-heal on its next publish; a run that goes quiet
    // (an interface waiting on the user) would not.
    setSnapshot(prev => {
      const cached = readSnapshot(id, surfaceId);
      return sameSnapshot(prev, cached) ? prev : cached;
    });
    return subscribeRunPanelData(id, (data: RunPanelData) => {
      const next: RunSnapshot = {
        workflowId: id,
        surfaceId,
        runId: data.runId,
        status: data.runInfo?.status as string | undefined,
        pinnedVersion: data.pinnedVersion,
        isPreviewOnly: data.isPreviewOnly,
      };
      setSnapshot(prev => (sameSnapshot(prev, next) ? prev : next));
    }, surfaceId);
  }, [id, surfaceId]);

  const runId = currentRunId;
  /** The run on screen right now, for callbacks that settle after a switch. */
  const currentRunRef = useRef<string | null>(runId);
  useEffect(() => { currentRunRef.current = runId; }, [runId]);

  const perform = useCallback((action: RunPanelAction) => {
    if (!id) return;
    // The run this attempt is ABOUT, captured now. Guarding only the start is not
    // enough: a request that rejects AFTER the surface moved on would paint its
    // failure on whatever run is showing by then - a healthy one, wearing the red
    // ring and announcing "this did not work". The canvas pill captures the same
    // way, for the same reason.
    const attemptedRunId = runId;
    setFailed(false);
    setPending(action);
    performRunAction(action, { workflowId: id, runId, surfaceId })
      .catch((err: unknown) => {
        // The reason is for the log; the CONTROL says "this failed" in the user's
        // language, because an API message is not translated and a raw one would
        // be the only untranslated string in the product.
        console.error('[useRunActions] Failed to', action, 'run:', err);
        // Only if this surface is STILL on that run. A state updater is the wrong
        // place for the check - React may call one twice - so the current run is
        // mirrored on a ref instead.
        if (currentRunRef.current === attemptedRunId) setFailed(true);
      })
      // Run-keyed like the mark above, and for the same reason: a request that
      // settles after the surface moved on would clear the CURRENT run's spinner
      // and re-enable its stop while that run's own request is still in flight.
      .finally(() => { if (currentRunRef.current === attemptedRunId) setPending(null); });
  }, [id, runId, surfaceId]);

  return {
    runId,
    status: snapshot.status,
    pinnedVersion: snapshot.pinnedVersion,
    isPreviewOnly: snapshot.isPreviewOnly,
    pending,
    failed,
    perform,
  };
}
