'use client';

import * as React from 'react';
import clsx from 'clsx';
import { StepForward, AlertTriangle } from 'lucide-react';
import { useTranslations } from 'next-intl';
import LoadingSpinner from '@/components/LoadingSpinner';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import { requestInterfaceContinue } from '@/lib/workflow/interfaceContinue';
import { itemIndexOf, pickInterfaceContinueTarget } from '@/lib/workflow/pendingSignals';

/**
 * How long the failure message stays on the button before it returns to its
 * ordinary state. Long enough to read, short enough that a stale error never
 * outlives the run state it described.
 */
const ERROR_VISIBLE_MS = 8_000;

export interface InterfaceContinueButtonProps {
  /** Backend step id of the interface node (e.g. `interface:my_form`). */
  stepId?: string;
  runId?: string | null;
  /** Names the workflow so only its own canvas bridge answers the event. */
  workflowId?: string;
  /**
   * Pending INTERFACE_SIGNAL waits parked on this node. MAY be empty while the
   * node is genuinely awaiting: this list is hydrated best-effort (a REST fetch
   * beside the run state), so empty means "not known yet", never "nothing to
   * continue" - which is why an empty list DISABLES the button rather than
   * firing without an item. See the `hasTarget` note below.
   */
  signals: PendingSignal[];
  /** The node is parked on its interface signal. The ONLY state where this renders. */
  awaiting: boolean;
}

/**
 * "Continue" button for an interface node parked on a blocking `__continue`,
 * shown ON the node in run mode - the counterpart of the Approve / Reject
 * buttons a parked User Approval node already offers.
 *
 * <p>Why it exists: an interface node that yields is waiting for a HUMAN, and
 * until now the only place to tell it to go on was inside the application
 * itself. On the canvas the run simply looked stalled on an amber node, with
 * the action one panel away. An approval and an interface `__continue` are the
 * same kind of pause, so they now carry the same kind of control.
 *
 * <p>ONE item per click, deliberately, exactly like the application toolbar's
 * Continue: in a split each item owns its own signal, and a canvas button that
 * silently released all of them would advance items the user never looked at.
 * The badge says how many are still parked; the click takes the one the FIRE
 * ENDPOINT will actually resolve (see `pickInterfaceContinueTarget`), and the
 * tooltip names that epoch and item, so the label and the action cannot drift
 * apart.
 */
export function InterfaceContinueButton({
  stepId,
  runId,
  workflowId,
  signals,
  awaiting,
}: InterfaceContinueButtonProps) {
  const tRun = useTranslations('runMode');
  const [isContinuing, setIsContinuing] = React.useState(false);
  const [error, setError] = React.useState<'failed' | 'forbidden' | null>(null);

  // Not memoized: `signals` is a fresh `.filter()` array on every render of the
  // context selector, so a useMemo keyed on it would recompute every time while
  // claiming not to.
  const target = pickInterfaceContinueTarget(signals);
  const pendingCount = signals.length;
  /**
   * Firing without an item is only safe when there is no item to get wrong, and
   * an empty queue cannot promise that: the node may be a split whose signal
   * list simply has not arrived. The endpoint's no-item branch then resolves an
   * arbitrary max-epoch signal, releasing an item nobody named - the one thing
   * this button says it will never do. So an unknown queue disables the button
   * and says why, instead of guessing. The application's own Continue applies
   * the same rule (its fallback requires a single page).
   */
  const hasTarget = target != null;

  // Released as soon as the node stops waiting (the normal outcome) or as soon
  // as the parked set changes (a split item resolved while others remain).
  React.useEffect(() => {
    if (!awaiting) setIsContinuing(false);
  }, [awaiting]);
  // Keyed on the TARGET, not on the count: a new epoch parking its own signal
  // also moves the count, and releasing the button there would let a second
  // click fire while the first is still in flight (two promises, and the later
  // answer painting over the earlier one).
  React.useEffect(() => {
    setIsContinuing(false);
  }, [target?.id]);
  React.useEffect(() => {
    if (!error) return;
    const timer = window.setTimeout(() => setError(null), ERROR_VISIBLE_MS);
    return () => window.clearTimeout(timer);
  }, [error]);

  const handleClick = React.useCallback(async (event: React.MouseEvent) => {
    event.stopPropagation();
    if (!runId || !stepId || !target || isContinuing) return;
    setError(null);
    setIsContinuing(true);
    // Awaited, unlike the application surfaces: they watch the run state they
    // already subscribe to, while a node has no other way to learn that the
    // fire was refused. A timed-out answer resolves ok (nothing replied, which
    // proves nothing), so only a real failure paints the error.
    const response = await requestInterfaceContinue({
      runId,
      nodeId: stepId,
      actionKey: '__continue',
      data: {},
      // `itemIndexOf` coerces a non-numeric itemId to 0. The whole fire chain is
      // number-typed and the engine only ever registers "0", "1", ... (
      // `registerSignal` defaults a null itemId to "0"), so that coercion has no
      // production input - but it is the one place where "the signal the
      // endpoint will resolve" could stop being true if that ever changes.
      itemIndex: itemIndexOf(target),
      workflowId,
    });
    setIsContinuing(false);
    // Already resolved is NOT a failure: someone continued from the application,
    // or two canvases of the same workflow both answered one click. The run did
    // move; only this call did not move it, and the refresh will unpark the node.
    if (response.ok || response.alreadyResolved) return;
    // Never `response.error`: that is the client's own English
    // (`HTTP 403: Forbidden`) and would reach a user reading any of the six
    // locales. The status picks a translated sentence instead; the raw text is
    // already in the console from the bridge.
    setError(response.status === 403 ? 'forbidden' : 'failed');
  }, [runId, stepId, isContinuing, target, workflowId]);

  if (!awaiting || !runId || !stepId) return null;

  const itemNumber = target ? itemIndexOf(target) + 1 : null;
  const errorLabel = error === 'forbidden'
    ? tRun('continueInterfaceForbidden')
    : tRun('continueInterfaceFailed');
  let title: string;
  if (error) {
    title = errorLabel;
  } else if (!hasTarget) {
    title = tRun('continueInterfaceLoading');
  } else if (pendingCount > 1) {
    // Names the EPOCH as well as the item: with several fires parked the
    // endpoint resolves the newest, and a label that hid that would describe a
    // different item than the one about to move.
    title = tRun('continueInterfaceRemaining', {
      count: pendingCount,
      item: itemNumber ?? 1,
      epoch: target?.epoch ?? 0,
    });
  } else {
    title = tRun('continueInterfaceTooltip');
  }

  const disabled = isContinuing || !hasTarget;

  return (
    <button
      type="button"
      onClick={handleClick}
      onMouseDown={(e) => e.stopPropagation()}
      disabled={disabled}
      title={title}
      data-testid="interface-node-continue"
      data-state={error ? 'error' : isContinuing ? 'continuing' : hasTarget ? 'ready' : 'loading'}
      className={clsx(
        'nodrag nopan flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium shadow-sm transition-colors',
        error
          ? 'bg-red-100 text-red-700 hover:bg-red-200 dark:bg-red-500/20 dark:text-red-300'
          : 'bg-amber-100 text-amber-800 hover:bg-amber-200 dark:bg-amber-500/20 dark:text-amber-200 dark:hover:bg-amber-500/30',
        disabled && 'opacity-50 cursor-not-allowed',
      )}
    >
      {isContinuing ? (
        <LoadingSpinner size="xs" />
      ) : error ? (
        <AlertTriangle className="h-3.5 w-3.5" />
      ) : (
        <StepForward className="h-3.5 w-3.5" />
      )}
      {/* A refusal is only drawn on the button, so it is also announced: a
          screen-reader user who activated Continue otherwise hears nothing at
          all about the refusal. */}
      <span role={error ? 'alert' : undefined}>{error ? errorLabel : tRun('continueInterface')}</span>
      {!error && pendingCount > 1 && (
        <span
          data-testid="interface-node-continue-count"
          className="inline-flex items-center justify-center h-4 min-w-[16px] px-1 rounded-md bg-amber-200/80 dark:bg-amber-400/25 text-[10px] font-semibold tabular-nums"
        >
          {pendingCount}
        </span>
      )}
    </button>
  );
}
