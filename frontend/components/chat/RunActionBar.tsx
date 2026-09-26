'use client';

import * as React from 'react';
import clsx from 'clsx';
import { PauseCircle, StepForward, CheckCircle, XCircle, ChevronDown, ChevronUp } from 'lucide-react';
import { useTranslations } from 'next-intl';
import LoadingSpinner from '@/components/LoadingSpinner';
import { blockerNodeLabel, type RunBlocker } from '@/lib/workflow/runBlockers';
import { track } from '@/lib/analytics/analytics';

/** See the floor effect below. Matches the interface Continue button's window. */
const RESOLVE_RESET_MS = 10_000;

export interface RunActionBarProps {
  /** Everything blocking the run on a human, in the order to ask about it. */
  blockers: RunBlocker[];
  /** Backend step id of the interface this application is showing. */
  displayedInterfaceNodeId?: string | null;
  /** Continue past the displayed interface node. */
  onContinue: () => void;
  /** Resolve one approval signal. */
  onResolveApproval: (
    blocker: Extract<RunBlocker, { kind: 'approval' }>,
    resolution: 'APPROVED' | 'REJECTED',
  ) => Promise<void>;
  /** The continue is already in flight (the application owns that state). */
  isContinuing?: boolean;
  /** The continue would be a no-op for the item on screen (stale render data). */
  continueDisabled?: boolean;
  /** The last continue was refused. Owned by the application, which awaits it. */
  continueFailure?: 'failed' | 'forbidden' | null;
}

/**
 * The bar an application shows while its run is parked on a human.
 *
 * <p>Why it exists: the application surface knew about exactly one thing that
 * can park a run, the `__continue` of the interface it happens to be showing,
 * and it put that button INSIDE a toolbar that is collapsed by default. An
 * approval was not represented at all. So a person using a published app and
 * never opening its workflow saw a frozen screen with nothing to click and no
 * explanation - while the canvas, one panel away, had the buttons all along.
 *
 * <p>Rendered inside the shared `iframeContent`, which is what gives it to the
 * right side panel, the application page, the chat card and fullscreen from one
 * place - the same route the run-state indicator takes.
 *
 * <p>ONE blocker at a time, the first the run wants answered, with a count of
 * what is left behind it. A bar that listed every pending approval of a
 * five-item split would cover the application it is supposed to unblock.
 */
export function RunActionBar({
  blockers,
  displayedInterfaceNodeId,
  onContinue,
  onResolveApproval,
  isContinuing = false,
  continueDisabled = false,
  continueFailure = null,
}: RunActionBarProps) {
  const tRun = useTranslations('runMode');
  const [resolving, setResolving] = React.useState<'APPROVED' | 'REJECTED' | null>(null);
  const [failed, setFailed] = React.useState(false);
  /**
   * The bar sits over the application's own bottom strip and a run can stay
   * parked for hours, so it has to be possible to put it away. Collapsed to a
   * pill rather than dismissed: the run is still waiting, and a control that
   * could be closed for good would recreate the problem this bar exists to fix.
   */
  const [collapsed, setCollapsed] = React.useState(false);

  const current = blockers[0] ?? null;
  const remaining = Math.max(0, blockers.length - 1);
  const currentKey = current
    ? (current.kind === 'approval' ? `a${current.signalId}` : `i${current.nodeId}`)
    : null;

  // Released when the run moves on to a different blocker, which is the only
  // signal this component gets that its own action landed.
  React.useEffect(() => {
    setResolving(null);
    setFailed(false);
    // A NEW question re-opens the bar: the user put away the previous one, not
    // this one.
    setCollapsed(false);
  }, [currentKey]);

  /**
   * Floor on the busy state. A resolution clears normally when the run moves to
   * another question, but nothing guarantees that arrives: a re-hydrate can lag
   * or fail quietly, and the bar would then sit with both buttons disabled and
   * a spinner, forever. The sibling Continue button carries the same floor for
   * the same reason.
   */
  React.useEffect(() => {
    if (!resolving) return;
    const timer = window.setTimeout(() => setResolving(null), RESOLVE_RESET_MS);
    return () => window.clearTimeout(timer);
  }, [resolving]);

  const handleApproval = React.useCallback(async (resolution: 'APPROVED' | 'REJECTED') => {
    if (!current || current.kind !== 'approval' || resolving) return;
    setFailed(false);
    setResolving(resolution);
    try {
      await onResolveApproval(current, resolution);
      // Only a resolution the server accepted counts as resolved.
      track('run_blocker_resolved', {
        blocker_kind: 'approval',
        resolution: resolution === 'APPROVED' ? 'approved' : 'rejected',
      });
    } catch {
      // The message is the server's own English, so it is not shown; the bar
      // says the action did not go through and stays clickable.
      setFailed(true);
      setResolving(null);
    }
  }, [current, resolving, onResolveApproval]);

  if (!current) return null;

  const isApproval = current.kind === 'approval';
  let prompt: string;
  // Whether the prompt already NAMES the node decides the second line. Without
  // that link the default approval (no authored context) printed the node name
  // twice, one line under the other - which is every approval whose author did
  // not write a question.
  let promptNamesTheNode = false;
  if (!isApproval) {
    prompt = tRun('actionBar.continuePrompt');
  } else if (current.context) {
    prompt = current.context;
  } else {
    prompt = tRun('actionBar.approvalPrompt', { node: blockerNodeLabel(current.nodeId) });
    promptNamesTheNode = true;
  }
  // An approval on a node OTHER than the interface on screen says so, so the
  // reader can tell "waiting on you, here" from "waiting on something else in
  // the workflow".
  const elsewhere = isApproval && !promptNamesTheNode && current.nodeId !== displayedInterfaceNodeId;
  // Which FIRE it belongs to. In the all-epochs view several are in play at
  // once, and a question with no epoch beside it could be about any of them.
  const epochLabel = isApproval && current.epoch != null
    ? tRun('epochBadge', { number: current.epoch })
    : null;

  const shellClass = clsx(
    'pointer-events-auto max-w-[min(420px,calc(100%-24px))] w-max',
    'rounded-xl shadow-lg backdrop-blur',
    'border border-amber-300/80 bg-amber-50/95 text-amber-900',
    'dark:border-amber-500/40 dark:bg-amber-950/85 dark:text-amber-100',
  );

  if (collapsed) {
    return (
      <button
        type="button"
        data-testid="application-run-action-bar"
        data-blocker-kind={current.kind}
        data-collapsed="true"
        onClick={() => setCollapsed(false)}
        title={prompt}
        className={clsx(shellClass, 'flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium')}
      >
        <PauseCircle className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400" />
        {tRun('actionBar.reopen', { count: blockers.length })}
        <ChevronUp className="h-3.5 w-3.5 opacity-70" />
      </button>
    );
  }

  return (
    <div
      data-testid="application-run-action-bar"
      data-blocker-kind={current.kind}
      className={clsx(shellClass, 'flex flex-col gap-2 px-3.5 py-2.5')}
    >
      <div className="flex items-start gap-2">
        <PauseCircle className="h-4 w-4 shrink-0 mt-px text-amber-600 dark:text-amber-400" />
        <div className="min-w-0 flex-1">
          <p className="text-sm leading-snug" data-testid="application-run-action-prompt">{prompt}</p>
          {(elsewhere || epochLabel) && (
            <p
              className="text-xs mt-0.5 text-amber-700/80 dark:text-amber-300/70"
              data-testid="application-run-action-elsewhere"
            >
              {elsewhere ? tRun('actionBar.elsewhere', { node: blockerNodeLabel(current.nodeId) }) : null}
              {elsewhere && epochLabel ? ' · ' : null}
              {epochLabel}
            </p>
          )}
        </div>
        {/* Put it away without answering: a run can stay parked for hours and
            the bar sits over the application's own bottom strip. */}
        <button
          type="button"
          data-testid="application-run-action-collapse"
          onClick={() => setCollapsed(true)}
          aria-label={tRun('actionBar.hide')}
          title={tRun('actionBar.hide')}
          className="shrink-0 -mr-1 -mt-0.5 p-1 rounded-md text-amber-700/70 hover:text-amber-900 hover:bg-amber-200/50 dark:text-amber-300/60 dark:hover:text-amber-100 dark:hover:bg-amber-500/20 transition-colors"
        >
          <ChevronDown className="h-3.5 w-3.5" />
        </button>
      </div>

      <div className="flex items-center justify-end gap-2 flex-wrap">
        {remaining > 0 && (
          <span
            className="mr-auto text-xs text-amber-700/80 dark:text-amber-300/70 tabular-nums"
            data-testid="application-run-action-remaining"
          >
            {tRun('actionBar.more', { count: remaining })}
          </span>
        )}
        {(failed || continueFailure) && (
          <span
            className="mr-auto text-xs font-medium text-red-700 dark:text-red-300"
            role="alert"
            data-testid="application-run-action-failed"
          >
            {continueFailure === 'forbidden'
              ? tRun('continueInterfaceForbidden')
              : tRun('actionBar.failed')}
          </span>
        )}

        {isApproval ? (
          <>
            <button
              type="button"
              data-testid="application-run-action-reject"
              onClick={() => handleApproval('REJECTED')}
              disabled={resolving != null}
              className={clsx(
                'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
                'bg-white/70 text-red-700 hover:bg-white dark:bg-white/10 dark:text-red-300 dark:hover:bg-white/20',
                resolving != null && 'opacity-50 cursor-not-allowed',
              )}
            >
              {resolving === 'REJECTED' ? <LoadingSpinner size="xs" /> : <XCircle className="h-3.5 w-3.5" />}
              {tRun('approvalBar.reject')}
            </button>
            <button
              type="button"
              data-testid="application-run-action-approve"
              onClick={() => handleApproval('APPROVED')}
              disabled={resolving != null}
              className={clsx(
                'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
                'bg-emerald-600 text-white hover:bg-emerald-700',
                resolving != null && 'opacity-50 cursor-not-allowed',
              )}
            >
              {resolving === 'APPROVED' ? <LoadingSpinner size="xs" /> : <CheckCircle className="h-3.5 w-3.5" />}
              {tRun('approvalBar.approve')}
            </button>
          </>
        ) : (
          <button
            type="button"
            data-testid="application-run-action-continue"
            onClick={() => {
              // The continue's outcome is owned by the application (it awaits it and reports a
              // refusal through continueFailure), so the click is what is counted here.
              track('run_blocker_resolved', { blocker_kind: 'interface_continue', resolution: 'continued' });
              onContinue();
            }}
            disabled={isContinuing || continueDisabled}
            // NOT `continueInterfaceLoading`: that key means "the signal queue
            // has not arrived". Here the queue is known and the item on screen
            // is already resolved, which is what the toolbar button next to
            // this one has always said.
            title={continueDisabled ? tRun('actionBar.alreadyResolved') : undefined}
            className={clsx(
              'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
              'bg-amber-600 text-white hover:bg-amber-700',
              (isContinuing || continueDisabled) && 'opacity-50 cursor-not-allowed',
            )}
          >
            {isContinuing ? <LoadingSpinner size="xs" /> : <StepForward className="h-3.5 w-3.5" />}
            {tRun('continueInterface')}
          </button>
        )}
      </div>
    </div>
  );
}
