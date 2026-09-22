'use client';

import * as React from 'react';
import { deriveEffectiveStatus, type StepEntry } from '@/components/workflow/run-panel/runFormatting';
import { getCanvasNodes, subscribeCanvasNodes } from '@/app/workflows/builder/services/canvasNodesStore';
import { nodeMatchesStep } from '@/app/workflows/builder/services/nodeMatcher';
import { isRunCameraFollowEnabled, subscribeRunCameraFollow } from '@/app/workflows/builder/services/runCameraFollowStore';
import {
  WORKFLOW_FOLLOW_NODES_EVENT,
  FOLLOW_DEBOUNCE_MS,
  FOLLOW_MIN_GAP_MS,
} from '@/app/workflows/builder/services/runFollowEvent';

export interface RunCameraFollowInput {
  steps: StepEntry[] | undefined;
  workflowId: string | undefined;
  /**
   * Aliases parked on a signal, as a channel of its own because the step stream cannot
   * always express it: a node waiting on an approval can keep reporting `running`, the
   * last row written for it being the RUNNING one (`statusUpdater.ts`).
   *
   * Know its limit rather than trusting it: in an AUTOMATIC run the frontend never
   * calls executeStepByStep, so this set is largely empty (`UserApprovalNode.tsx` says
   * so and works around it), and the filter is then a no-op. It earns its place in
   * step-by-step runs; it is not a guarantee that the camera never parks on an
   * approval.
   */
  awaitingSignalAliases: Iterable<string> | null | undefined;
  /**
   * True while the user is reading a PAST epoch. The canvas paint is frozen on that
   * epoch, so following the live run would fling the camera at nodes that render as
   * pending. Every effect in `useRunStateProcessing` returns early on the same flag.
   */
  isViewingHistoricalEpoch: boolean;
  /**
   * True on a read-only canvas (a shared application, a marketplace preview).
   * Those mount in RUN mode with steps streaming, and the preference is a module
   * singleton, so a user who armed following on their own canvas would otherwise
   * see a read-only one pan during a run with no control on its toolbar to stop it.
   */
  isPreviewOnly: boolean;
  /**
   * Whether the run itself is still going.
   *
   * REQUIRED, because an empty running set is ambiguous and the camera's two rules
   * depend on telling the two cases apart: the orchestrator sends a snapshot when a
   * node completes and another when the next starts, so "nothing running" arrives
   * on its own BETWEEN two steps as well as at the end. Judging on the set alone
   * either freezes the camera for a whole run of short steps, or sends it to a node
   * that has stopped for good.
   */
  isRunActive: boolean;
}

/**
 * Keeps the canvas camera framed on whatever is currently running.
 *
 * Mount this beside the CANVAS, never beside the run panel. That panel is one tab
 * among several, so a hook living there stops following as soon as the user opens
 * Chat, or the node inspector, or simply collapses the panel to see more of the graph,
 * which is the most natural gesture there is while watching a camera follow a run. The
 * toggle would still read as pressed while nothing moved.
 *
 * Load-bearing details:
 *
 * SCOPE. Several canvases are mounted at once (the page canvas plus a sub-workflow or
 * application canvas in a side-panel tab) and aliases are normalised labels, so
 * `core:merge` collides across workflows constantly. Both the node lookup and the
 * event carry the workflow, and a canvas with NO id follows nothing at all rather than
 * falling back to whichever canvas published last.
 *
 * RESOLUTION HERE, NOT IN THE CANVAS. Opening a run link streams steps before the plan
 * arrives, so nothing resolves yet. Resolving here means the dispatch has simply not
 * happened and the node-store subscription retries it.
 *
 * PARTIAL RESOLUTION STILL MOVES. Unmatched steps are a live reality, so refusing to
 * move until every alias resolves would turn one unmatchable step into a total, silent
 * outage. The camera frames what it can, and the set is recorded with the COUNT it
 * managed: a later publish that resolves more re-frames, one that resolves no more
 * leaves the camera alone.
 *
 * A SCHEDULED MOVE IS NEVER CANCELLED BY AN UNRELATED RE-RENDER, or the debounce
 * starves and the camera never moves at all, silently. It IS cancelled when it is
 * about to frame a set that has stopped running.
 *
 * SPACING. The debounce alone is not smoothness: a set changing every few hundred ms
 * clears it every time and still cuts each travel short.
 */
export function useRunCameraFollow({
  steps,
  workflowId,
  awaitingSignalAliases,
  isViewingHistoricalEpoch,
  isPreviewOnly,
  isRunActive,
}: RunCameraFollowInput): void {
  const [enabled, setEnabled] = React.useState<boolean>(false);

  // Mount-time read: the store reaches for localStorage, absent while server rendering.
  React.useEffect(() => {
    setEnabled(isRunCameraFollowEnabled());
    return subscribeRunCameraFollow(setEnabled);
  }, []);

  const active = enabled && !!workflowId && !isViewingHistoricalEpoch && !isPreviewOnly;

  // Re-run when THIS workflow's nodes change, so a run that began before the plan
  // arrived still gets framed. Gated, so a user who never turns following on pays
  // nothing: the store notifies on every publish of every canvas.
  const [nodesEpoch, setNodesEpoch] = React.useState(0);
  const knownNodesRef = React.useRef<unknown>(null);
  React.useEffect(() => {
    if (!active) return;
    const check = () => {
      const current = getCanvasNodes(workflowId);
      const known = knownNodesRef.current;
      // Identity compare, so another workflow publishing does not wake this one. With
      // one exception that is not cosmetic: before THIS canvas has published,
      // `getCanvasNodes` hands back a FRESH empty array on every call, so a bare
      // identity test is never equal and every foreign publish would re-render the
      // host. That window is exactly the case this subscription exists for, a run link
      // opened before the plan arrived.
      if (current === known) return;
      if (current.length === 0 && Array.isArray(known) && known.length === 0) {
        knownNodesRef.current = current;
        return;
      }
      knownNodesRef.current = current;
      setNodesEpoch((v) => v + 1);
    };
    check();
    return subscribeCanvasNodes(check);
  }, [active, workflowId]);

  // Also gated: this runs on every step update, several times a second during a run.
  const runningAliases = React.useMemo(() => {
    if (!active || !steps) return [] as string[];
    const parked = awaitingSignalAliases ? new Set(awaitingSignalAliases) : null;
    return steps
      .filter((step) => deriveEffectiveStatus(step.status, step.statusCounts).toLowerCase() === 'running')
      .map((step) => step.alias)
      .filter((alias) => !parked || !parked.has(alias))
      // Sorted, so the same set arriving in a different order is the same set. Step
      // order is not a contract and re-framing on a reshuffle moves the camera for no
      // reason the viewer can see.
      .sort();
  }, [active, steps, awaitingSignalAliases]);

  // A primitive, so the effect below is not re-run by a new array identity. That is the
  // difference between a debounce and a starved timer. The aliases travel beside it
  // rather than being re-split out of it.
  const runningKey = runningAliases.join('\u0000');

  /** Which canvas the framed state below belongs to. */
  const framedWorkflowRef = React.useRef<string | undefined>(undefined);
  const framedKeyRef = React.useRef<string | null>(null);
  /** What was framed, positions included, so a re-layout re-frames and a no-op does not. */
  const framedShapeRef = React.useRef<string>('');
  const pendingKeyRef = React.useRef<string | null>(null);
  /**
   * What the armed timer will send when it fires, read at FIRE time.
   *
   * This is what lets a set change retarget a move in flight instead of cancelling and
   * restarting the clock. Restarting looks harmless and is not: a run whose steps are
   * shorter than the debounce then changes the set before every timer matures, so the
   * camera never moves at all, for the whole run, silently. Reading at fire time also
   * means the event can never carry a workflow id that has since changed.
   */
  const targetRef = React.useRef<
    { key: string; nodeIds: string[]; shape: string; workflowId: string } | null
  >(null);
  const timerRef = React.useRef<number | null>(null);
  // -Infinity, not 0: with a monotonic clock that starts near zero, 0 would make the
  // very first move wait the full spacing floor although nothing precedes it.
  const lastMoveAtRef = React.useRef(Number.NEGATIVE_INFINITY);
  const aliasesRef = React.useRef<string[]>(runningAliases);
  const currentKeyRef = React.useRef<string>(runningKey);
  const runActiveRef = React.useRef<boolean>(isRunActive);
  React.useEffect(() => {
    aliasesRef.current = runningAliases;
    currentKeyRef.current = runningKey;
    runActiveRef.current = isRunActive;
  });

  const clearPending = React.useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    pendingKeyRef.current = null;
    targetRef.current = null;
  }, []);

  // Only on unmount. Clearing on every re-run is precisely the starvation above.
  React.useEffect(() => clearPending, [clearPending]);

  React.useEffect(() => {
    if (!active) {
      // Re-arm, so switching following back on frames what is running now.
      framedKeyRef.current = null;
      framedShapeRef.current = '';
      clearPending();
      return;
    }
    const aliases = aliasesRef.current;
    const nodes = getCanvasNodes(workflowId);
    const matched = aliases
      .map((alias) => nodes.find((node) => nodeMatchesStep(node, { stepAlias: alias, id: alias })))
      .filter((node): node is (typeof nodes)[number] => !!node);
    const nodeIds = matched.map((node) => node.id);

    // Recorded WITH POSITIONS, so an auto-layout that moves the running node re-frames,
    // while a publish that changes neither membership nor geometry leaves it alone.
    const shape = nodeIds
      .map((id) => {
        const n = nodes.find((node) => node.id === id);
        return `${id}@${Math.round(n?.position?.x ?? 0)},${Math.round(n?.position?.y ?? 0)}`;
      })
      .join('\u0000');
    const alreadyFramed =
      framedWorkflowRef.current === workflowId &&
      runningKey === framedKeyRef.current &&
      shape === framedShapeRef.current;
    const armedKey = pendingKeyRef.current;

    if (!runningKey) {
      // Nothing running in THIS snapshot. That is routine rather than exotic: the
      // orchestrator sends a snapshot when a node completes and another when the next
      // one starts, so "none running" arrives on its own between every pair of steps.
      // Cancelling an armed move here is what made a run of 200 to 600 ms steps never
      // move the camera once. Hold the timer instead: a set that starts before it
      // matures retargets it, and a set that never comes is caught at fire time, where
      // staleness is a fact rather than a guess.
      framedKeyRef.current = null;
      framedShapeRef.current = '';
      return;
    }

    if (nodeIds.length === 0) {
      // Nothing in the CURRENT set resolves here: the plan has not arrived, or the
      // running steps belong to an inner loop or a sub-workflow this canvas does not
      // draw. That says nothing about the armed move, whose own nodes may still be on
      // screen, and cancelling on it starved exactly as the idle branch used to: a run
      // that alternates a drawn step with an undrawn one never moved the camera once.
      // Whether the armed move is still worth making is decided when it fires.
      return;
    }

    if (armedKey !== null) {
      if (armedKey === runningKey) {
        // Same set: the move stays, but its aim is refreshed below.
        // The canvas may have changed under the armed move: a node that had not
        // resolved may now exist, the layout may have moved one, or the canvas may have
        // been re-pointed at another workflow. Refresh rather than let the move land on
        // what was true when it was armed.
        const armed = targetRef.current;
        if (armed && (armed.shape !== shape || armed.workflowId !== workflowId)) {
          targetRef.current = { key: runningKey, nodeIds, shape, workflowId };
        }
        return;
      }
      // No cancel here either, even when what is running is already on screen. This was
      // the third and last arm-time cancel, and it starved the same way its two sisters
      // did: a set that alternates (a loop body against its loop node, a fast fork
      // branch against a long one) flips back to the framed set while a move is armed,
      // kills it, and the next flip kills the next one. Measured at ONE move in a
      // fifteen-second run. Retarget and let it land; re-framing a rectangle the camera
      // is already on is a visual no-op, and the gap floor bounds how often it happens.
      // Retarget rather than restart the clock: restarting means a run whose steps are
      // shorter than the debounce never lets a timer mature, so the camera never moves
      // at all, for the whole run, silently.
      pendingKeyRef.current = runningKey;
      targetRef.current = { key: runningKey, nodeIds, shape, workflowId };
      return;
    }

    if (alreadyFramed) return;

    const sinceLast = performance.now() - lastMoveAtRef.current;
    const wait = Math.max(FOLLOW_DEBOUNCE_MS, FOLLOW_MIN_GAP_MS - sinceLast);

    pendingKeyRef.current = runningKey;
    targetRef.current = { key: runningKey, nodeIds, shape, workflowId };
    timerRef.current = window.setTimeout(() => {
      const target = targetRef.current;
      const key = pendingKeyRef.current;
      timerRef.current = null;
      pendingKeyRef.current = null;
      targetRef.current = null;
      // Judged HERE, not at arming. A move is abandoned only when the run is genuinely
      // OVER: nothing running plus a finished run. Nothing running while the run is
      // still going is the ordinary gap between two steps, and abandoning there is what
      // froze the camera for entire runs of short steps. Nothing is stamped when a move
      // is abandoned, so the next set is framed normally.
      const nothingRunning = currentKeyRef.current === '';
      const runOver = nothingRunning && !runActiveRef.current;
      if (!target || key === null || runOver) return;
      // The nodes it was aimed at may have left the canvas since (a plan reload, a
      // relabel). Sending ids nothing can frame would move nothing AND stamp the set as
      // framed, which silently prevents the retry once they come back.
      const nodesNow = getCanvasNodes(target.workflowId);
      const targetStillDrawn = target.nodeIds.every((id) => nodesNow.some((n) => n.id === id));
      if (!targetStillDrawn) return;
      framedWorkflowRef.current = target.workflowId;
      framedKeyRef.current = key;
      framedShapeRef.current = target.shape;
      lastMoveAtRef.current = performance.now();
      window.dispatchEvent(
        new CustomEvent(WORKFLOW_FOLLOW_NODES_EVENT, {
          // The id captured when the move was AIMED, never a later one: the ids were
          // resolved against that canvas, so sending them under another workflow would
          // point a different canvas at nodes that are not running there.
          detail: { workflowId: target.workflowId, nodeIds: target.nodeIds },
        }),
      );
    }, wait);
  }, [active, runningKey, workflowId, nodesEpoch, clearPending]);
}
