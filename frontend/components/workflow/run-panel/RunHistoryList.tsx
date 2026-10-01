'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import {
  History, RefreshCw, XCircle, StepForward, Pin, Calendar, FlaskConical,
  CheckCircle2, Clock, AlertCircle, CircleSlash, PauseCircle, PlayCircle,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { orchestratorApi, type WorkflowRun } from '@/lib/api/orchestrator';
import { isEventForWorkflow } from '@/lib/workflow/workflowEventScope';
import { getClientLocale } from '@/lib/utils/locale';
import { formatRelativeDateI18n, formatUtcDateTime, parseUtcAware } from '@/lib/utils/dateFormatters';
import { getRunDisplayStatus } from '@/lib/utils/runStatusUtils';
import { scrollToAndFlash } from '@/lib/utils/flashHighlight';
import { formatCompactDuration, RUN_ROW_FLASH_CLASS } from './runFormatting';
import { useWsReconnected } from '@/lib/websocket';
import { getWorkflowRunManager, hasWorkflowRunManager } from '@/contexts/workflow-run';

interface RunHistoryListProps {
  workflowId?: string;
  /** Run currently displayed by the canvas - rendered as the selected row. */
  currentRunId?: string | null;
  /** Called with the picked run: the caller binds the canvas + drills into it. */
  onSelectRun: (run: WorkflowRun) => void;
}

const LIMIT = 15;

/**
 * Most rows one in-place refresh re-reads. The endpoint serves at most 200 rows per call and
 * pages by `offset / limit`, so the re-read stays a whole number of LIMIT pages: the next
 * load-more then asks for a page that starts exactly where the refreshed rows end.
 */
const MAX_REFRESH_ROWS = Math.floor(200 / LIMIT) * LIMIT;

type RunRowStatus =
  | 'pending' | 'waiting_trigger' | 'running' | 'paused' | 'awaiting_signal'
  | 'completed' | 'failed' | 'partial_success' | 'cancelled' | 'timeout' | 'stopped' | 'skipped';

/**
 * Every RunStatus the backend can report gets its own row visual.
 *
 * The former history panel mapped six values and defaulted the rest to
 * "pending", so a live reusable run - which RESTS in WAITING_TRIGGER between
 * fires - showed up as a pending amber clock in its own history. This covers all
 * eleven values of the backend enum, plus `stopped`, which the enum does not have
 * but older rows and the streaming layer still carry (normalized to "cancelled"
 * elsewhere in the app). An unknown value degrades to `pending` rather than
 * rendering nothing.
 */
const mapRunStatus = (status: string): RunRowStatus => {
  const normalized = (status || '').toLowerCase();
  switch (normalized) {
    case 'pending':
    case 'waiting_trigger':
    case 'running':
    case 'paused':
    case 'awaiting_signal':
    case 'completed':
    case 'failed':
    case 'partial_success':
    case 'cancelled':
    case 'timeout':
    case 'stopped':
    case 'skipped':
      return normalized as RunRowStatus;
    default:
      return 'pending';
  }
};

/**
 * Status as a single ICON in the row's leading column.
 *
 * Deliberately not `StatusBadge`: even its `noBackground` variant renders the
 * translated LABEL next to the icon, which blows out the fixed `w-6` column the
 * rows share with the epoch list and wraps the whole row. The label is carried
 * by the `title` instead, so the row keeps the epoch-row geometry.
 */
const RUN_STATUS_ICON: Record<RunRowStatus, { icon: React.ReactNode; className: string }> = {
  completed: { icon: <CheckCircle2 className="w-3.5 h-3.5" />, className: 'text-emerald-600 dark:text-emerald-400' },
  failed: { icon: <XCircle className="w-3.5 h-3.5" />, className: 'text-red-500 dark:text-red-400' },
  cancelled: { icon: <XCircle className="w-3.5 h-3.5" />, className: 'text-gray-400 dark:text-gray-500' },
  running: { icon: <RefreshCw className="w-3.5 h-3.5 animate-spin" />, className: 'text-blue-500 dark:text-blue-400' },
  // The resting state of a live reusable run, between two fires. It is neither
  // "pending" (something already ran) nor "running" (nothing is executing right
  // now), so it takes the same idle warm tone the run bar gives it (getStatusClasses
  // falls through to yellow for WAITING_TRIGGER) - blue plus the running pulse
  // made an idle run indistinguishable from an executing one.
  waiting_trigger: { icon: <PlayCircle className="w-3.5 h-3.5" />, className: 'text-amber-500 dark:text-amber-400' },
  paused: { icon: <PauseCircle className="w-3.5 h-3.5" />, className: 'text-amber-500 dark:text-amber-400' },
  // Blocked on an approval / timer / webhook: the run is alive and waiting on
  // someone, which is not the same thing as "pending".
  awaiting_signal: { icon: <PauseCircle className="w-3.5 h-3.5" />, className: 'text-violet-500 dark:text-violet-400' },
  pending: { icon: <Clock className="w-3.5 h-3.5" />, className: 'text-amber-500 dark:text-amber-400' },
  partial_success: { icon: <AlertCircle className="w-3.5 h-3.5" />, className: 'text-amber-600 dark:text-amber-400' },
  timeout: { icon: <Clock className="w-3.5 h-3.5" />, className: 'text-orange-500 dark:text-orange-400' },
  stopped: { icon: <CircleSlash className="w-3.5 h-3.5" />, className: 'text-gray-500 dark:text-gray-400' },
  skipped: { icon: <CircleSlash className="w-3.5 h-3.5" />, className: 'text-gray-400 dark:text-gray-500' },
};

/**
 * How long the workflow's last execution took.
 *
 * The column used to print a bare "-" on every row, and not by accident: a
 * reusable run never ends. It rests in WAITING_TRIGGER between fires and
 * accumulates epochs, so it has neither `durationMs` nor `endedAt`, and the
 * formatter answers "-" when it cannot measure. Since runs aggregate rather than
 * fork, that is the state of MOST rows.
 *
 * With nothing to measure at all - nothing has executed and the run has not
 * finished - the cell is EMPTY: a blank reads as "not applicable", a dash reads
 * as a broken value.
 */
const formatRunDuration = (run: WorkflowRun): { text: string; isLastEpoch: boolean } => {
  // The LAST EXECUTION comes first. The backend measures it on the step rows of
  // the latest epoch (first node start -> last node end), so it is the time the
  // workflow spent WORKING. Three tempting alternatives are all wrong:
  //
  //  - the whole-run span is not a fallback, it is a trap. `cancelStaleRuns`
  //    stamps `endedAt` on every resting run whenever a new one is created, so a
  //    reusable run fired daily for a week reports SEVEN DAYS as its "duration" -
  //    a plausible-looking number that answers a question nobody asked;
  //  - `lastCycleAt` minus `lastFireAt` looks equivalent but is not: the engine
  //    runs concurrent epochs across trigger DAGs, so those two timestamps can
  //    belong to different epochs and even different triggers, and the difference
  //    comes out positive and confidently wrong;
  //  - the epoch's OWN duration, which this column used to show, is the same trap
  //    one level down. An epoch closes only when it is reconciled (the next fire, a
  //    resume, a restart recovery sweep), so it counts the idle tail: prod printed
  //    32h42m and 6h01m for epochs whose nodes really ran for 5 to 35 seconds.
  //
  // A wait INSIDE the graph (an approval, a timer) is still counted, because the
  // waiting node's own step spans it. That one is the truth, not an artefact.
  //
  // Every branch goes through formatCompactDuration: the column is fixed-width and
  // tabular, and mixing it with dateFormatters' spaced style ("1m 30s" next to
  // "1m30s") makes the same quantity look like two different fields.
  if (run.lastEpochDurationMs != null) {
    return { text: formatCompactDuration(run.lastEpochDurationMs), isLastEpoch: true };
  }

  // Last resort: a run that finished WITHOUT ever opening an epoch AND without
  // leaving a single step row - it ended before anything executed. There the whole
  // run IS the execution, so its span means what it says. A single-shot
  // step-by-step run does not land here any more: it writes step rows, so the
  // measured window above wins, which is the better answer.
  //
  // Gated on having no epoch at all, which is what separates it from the trap
  // above: a stale-cancelled reusable run also carries `endedAt`, but it has
  // epochs, so it never reaches here. (`durationMs` is not usable - nothing in
  // the orchestrator ever writes it on a run.)
  const neverOpenedAnEpoch = !run.currentEpoch;
  if (neverOpenedAnEpoch && run.startedAt && (run.endedAt || run.completedAt)) {
    const span = parseUtcAware((run.endedAt || run.completedAt)!).getTime()
      - parseUtcAware(run.startedAt).getTime();
    if (span >= 0) return { text: formatCompactDuration(span), isLastEpoch: false };
  }

  return { text: '', isLastEpoch: false };
};

/**
 * Every run of the workflow, as the PARENT level of the Run tab: picking a row
 * binds that run and drills into its epochs / steps.
 *
 * Rows deliberately mirror the epoch rows of {@link EpochSelector} (same
 * `px-3 py-2.5` metrics, same `w-6` leading column, same left-border
 * selected state) so moving between "which run" and "which epoch" reads as one
 * continuous hierarchy instead of two unrelated lists.
 */
export function RunHistoryList({ workflowId, currentRunId, onSelectRun }: RunHistoryListProps) {
  const t = useTranslations();
  const tStatus = useTranslations('status');
  const [runs, setRuns] = useState<WorkflowRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  /** First page resolved (with rows or with an error). Anchors the focus flash. */
  const [firstPageSettled, setFirstPageSettled] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const [pinnedVersion, setPinnedVersion] = useState<number | null>(null);
  const [pinnedRun, setPinnedRun] = useState<WorkflowRun | null>(null);
  const observerTarget = useRef<HTMLDivElement>(null);
  const offsetRef = useRef(0);
  /** Generations of the two fetches, so a stale one cannot write this list. */
  const resetGenerationRef = useRef(0);
  const pinnedGenerationRef = useRef(0);
  /**
   * Workflow the rows currently on screen actually came from.
   *
   * This component is NOT remounted when `workflowId` changes (the pinned
   * workflow-panel tab survives navigating from one workflow to another), so for
   * one commit the props describe workflow B while the DOM, the refs and every
   * settled flag still describe A.
   */
  const loadedWorkflowRef = useRef<string | undefined>(undefined);
  /** The row of the run being shown, and the arming already played on it. */
  const currentRowRef = useRef<HTMLButtonElement | null>(null);
  const flashedKeyRef = useRef<string | null>(null);
  /** Pinned (production) run resolved - it is prepended, so it can be the target. */
  const [pinnedSettled, setPinnedSettled] = useState(false);

  // Pinned version + pinned (production) run - drives the pin badge and the
  // "production run first" ordering.
  useEffect(() => {
    if (!workflowId) { setPinnedSettled(true); return; }
    setPinnedSettled(false);
    // Same generation stamp as the runs fetch: this component is not remounted
    // when `workflowId` changes, so a slow response for the PREVIOUS workflow
    // would otherwise prepend a foreign run to this list and, worse, declare
    // this list's pinned fetch settled while it is still in flight - which spends
    // the focus cue on a run whose row has not arrived.
    const myGeneration = ++pinnedGenerationRef.current;
    const isStale = () => myGeneration !== pinnedGenerationRef.current;
    orchestratorApi.listVersions(workflowId)
      .then((data) => { if (!isStale()) setPinnedVersion(data.pinnedVersion ?? null); })
      .catch(() => {});
    orchestratorApi.getPinnedWorkflowRun(workflowId)
      .then((run) => { if (!isStale()) setPinnedRun(run); })
      .catch(() => { if (!isStale()) setPinnedRun(null); })
      // The pinned run is PREPENDED to the list, so it can be the flash target:
      // giving up before this resolves would drop the cue on the very run a
      // sub-workflow tab is most often opened on.
      .finally(() => { if (!isStale()) setPinnedSettled(true); });
  }, [workflowId]);

  // Listen for pin/unpin changes
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      // Another workflow's pin says nothing about this history, and refetching
      // its production run on that signal is a request nobody asked for.
      if (!isEventForWorkflow(detail, workflowId)) return;
      const newPinned = detail?.pinnedVersion ?? null;
      setPinnedVersion(newPinned);
      if (workflowId && newPinned != null) {
        orchestratorApi.getPinnedWorkflowRun(workflowId)
          .then((run) => setPinnedRun(run))
          .catch(() => setPinnedRun(null));
      } else {
        setPinnedRun(null);
      }
    };
    window.addEventListener('workflowPinnedVersionChange', handler);
    return () => window.removeEventListener('workflowPinnedVersionChange', handler);
  }, [workflowId]);

  const fetchRuns = useCallback(async (reset: boolean = false) => {
    if (!workflowId) return;
    const currentOffset = reset ? 0 : offsetRef.current;
    // Every fetch is stamped with the list generation it belongs to, and a reset
    // opens a new one. Anything started before the latest reset - an older reset,
    // or a load-more for the PREVIOUS list - is discarded when it lands: it would
    // otherwise append one workflow's page 2 under another's header and rewind
    // the paging offset with it.
    const myGeneration = reset ? ++resetGenerationRef.current : resetGenerationRef.current;
    const isStale = () => myGeneration !== resetGenerationRef.current;
    try {
      if (reset) {
        setLoading(true);
        setRuns([]);
        offsetRef.current = 0;
        setHasMore(true);
        // A reset means a new list (another workflow, or a manual refresh): the
        // previous settle must not anchor the flash of the one being loaded.
        setFirstPageSettled(false);
      } else {
        setLoadingMore(true);
      }
      setError(null);
      const data = await orchestratorApi.getWorkflowRuns(workflowId, LIMIT, currentOffset);
      if (isStale()) return;
      loadedWorkflowRef.current = workflowId;
      setRuns(prev => (reset ? (data || []) : [...prev, ...(data || [])]));
      setHasMore(!!data && data.length === LIMIT);
      offsetRef.current = currentOffset + LIMIT;
    } catch (err) {
      if (isStale()) return;
      // An errored list belongs to this workflow too: without this, the focus cue
      // would wait forever for rows that are never coming.
      loadedWorkflowRef.current = workflowId;
      console.error('[RunHistoryList] Error fetching runs:', err);
      setError(t('runs.loadError'));
    } finally {
      if (!isStale()) {
        setLoading(false);
        setLoadingMore(false);
        // Tells the focus cue the first page is in (see below). Set on failure
        // too: an errored list has no row to highlight and must not stay armed.
        if (reset) setFirstPageSettled(true);
      }
    }
  }, [workflowId, t]);

  useEffect(() => {
    fetchRuns(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the workflow changes
  }, [workflowId]);

  /**
   * Re-read the rows already on screen, in place.
   *
   * Not `fetchRuns(true)`: a reset blanks the list and shows the empty state while it
   * loads, which would flash "no runs" every time a scheduled run closes an epoch. The pages
   * loaded so far (up to MAX_REFRESH_ROWS) in one read, same generation discipline as a
   * reset, and dropped if the paging moved while it was in flight so a load-more is never
   * undone.
   *
   * One read at a time, plus at most one follow-up for the signals that arrive meanwhile
   * (as the run manager does): two overlapping reads could land in either order, and the
   * older would overwrite the fresher.
   */
  const refreshInFlightRef = useRef(false);
  const refreshAgainRef = useRef(false);
  const refreshLatestRef = useRef<() => Promise<void>>(async () => {});
  const refreshLoadedRuns = useCallback(async () => {
    if (!workflowId) return;
    if (refreshInFlightRef.current) {
      refreshAgainRef.current = true;
      return;
    }
    refreshInFlightRef.current = true;
    const myGeneration = resetGenerationRef.current;
    const pinnedGeneration = pinnedGenerationRef.current;
    const offsetAtStart = offsetRef.current;
    const requested = Math.min(Math.max(offsetAtStart, LIMIT), MAX_REFRESH_ROWS);
    try {
      const pinnedRead = orchestratorApi.getPinnedWorkflowRun(workflowId).catch(() => null);
      const data = await orchestratorApi.getWorkflowRuns(workflowId, requested, 0);
      if (myGeneration === resetGenerationRef.current && offsetRef.current === offsetAtStart) {
        const rows = data || [];
        setRuns(rows);
        // Whole pages, like fetchRuns: the endpoint pages by offset / limit, so an offset
        // that is not a multiple of LIMIT would make the next load-more repeat rows.
        offsetRef.current = requested;
        setHasMore(rows.length === requested);
        setError(null); // the list is readable again: drop the error a failed load left up
      }
      const pinned = await pinnedRead;
      // The pinned-run read answers null for an ERROR too (a 5xx during a rolling deploy), so
      // null never erases a production run already on screen. An unpin clears it through
      // its own event.
      if (pinned && pinnedGeneration === pinnedGenerationRef.current) setPinnedRun(pinned);
    } catch {
      // Keep the rows on screen; the next signal re-reads them.
    } finally {
      refreshInFlightRef.current = false;
      if (refreshAgainRef.current) {
        refreshAgainRef.current = false;
        // The LATEST callback: the workflow may have changed while this read was in flight.
        void refreshLatestRef.current();
      }
    }
  }, [workflowId]);
  refreshLatestRef.current = refreshLoadedRuns;

  // Infinite scroll
  useEffect(() => {
    if (!hasMore || loadingMore) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMore && !loadingMore) fetchRuns(false);
      },
      { threshold: 0.1 },
    );
    const currentTarget = observerTarget.current;
    if (currentTarget) observer.observe(currentTarget);
    return () => { if (currentTarget) observer.unobserve(currentTarget); };
  }, [hasMore, loadingMore, fetchRuns, runs.length]);

  // Production run first, then the rest in the order the API returned them.
  const displayRuns = pinnedRun ? [pinnedRun, ...runs.filter(r => r.id !== pinnedRun.id)] : runs;

  // A run this list shows as executing ("running" pulse) is read once, when the list
  // loads, and nothing moves it afterwards. When a surface follows that run live (its
  // manager exists), the manager learns first that it stopped running (epoch closed, run
  // finished): re-read the rows then.
  //
  // Only a stop SEEN happening counts: a change to "not running" after the run was running,
  // starting from what the row itself says (running). The status the manager holds when the
  // watch starts is not read: it may be left from an earlier visit (the previous epoch's
  // waiting_trigger) and says nothing about the row. And a list endpoint lagging one read
  // behind must not turn every later store update of a stopped run into a request.
  //
  // The list WATCHES the manager rather than subscribing to it: it does not render the run,
  // so it must not count as the surface showing it (which would swallow the re-read a
  // surface showing the run later is owed).
  const runningRowIds = displayRuns
    .filter(run => run.runId && mapRunStatus(getRunDisplayStatus(run.status, run.metadata)) === 'running')
    .map(run => run.runId as string);
  const runningRowKey = runningRowIds.join(',');
  useEffect(() => {
    const unsubscribes = runningRowKey.split(',')
      .filter(runId => runId && hasWorkflowRunManager(runId))
      .map(runId => {
        let wasRunning = true; // the row says so
        return getWorkflowRunManager(runId).watch((state) => {
          const running = state.runStatus === 'running';
          if (wasRunning && !running && state.rawRunState) void refreshLoadedRuns();
          wasRunning = running;
        });
      });
    return () => unsubscribes.forEach(unsubscribe => unsubscribe());
  }, [runningRowKey, refreshLoadedRuns]);

  // Status changes published while the socket was down never reached any manager.
  useWsReconnected(() => { void refreshLoadedRuns(); });

  /**
   * Coming back up from a run: scroll to its row and flash it, so the user sees
   * WHICH run (and which version) they were on instead of scanning a list of
   * near-identical rows for the one thin selected border.
   *
   * Driven imperatively through the shared `scrollToAndFlash` - the same helper
   * the chat's "go to message" jump uses - because the cue is a one-shot event,
   * not a state: the class restarts on a forced reflow and comes off on
   * `animationend`, so nothing in React holds "is it flashing", nothing mirrors
   * the CSS duration, and the row is never remounted under the user's caret.
   *
   * The arming is identified by list AND run: switching workflow is a new list
   * and deserves its own cue, even when the run happens to carry the same id.
   */
  const flashKey = `${workflowId ?? ''}|${currentRunId ?? ''}`;

  useEffect(() => {
    if (flashedKeyRef.current === flashKey) return;
    if (!currentRunId) { flashedKeyRef.current = flashKey; return; }
    // Nothing on screen belongs to this list yet: neither the row under
    // `currentRowRef` (it is the previous workflow's, and about to be replaced)
    // nor the settled flags below, which still describe that list. Flashing here
    // would cue a doomed row, and giving up here would spend the arming before
    // this workflow's rows have even been requested.
    if (loadedWorkflowRef.current !== workflowId) return;

    const row = currentRowRef.current;
    if (row) {
      flashedKeyRef.current = flashKey;
      scrollToAndFlash(row, RUN_ROW_FLASH_CLASS);
      return;
    }
    // No row for it yet. The first page is a fetch away and the pinned run comes
    // from a second one, so we wait for BOTH before giving up - then the arming
    // is spent, because a row surfacing minutes later through infinite scroll
    // would light up and drag the list under a user who is reading it.
    if (firstPageSettled && pinnedSettled) flashedKeyRef.current = flashKey;
    // Both real arrival paths flip one of those flags in the same commit that
    // adds the row, so they are also what re-runs this effect when it appears.
  }, [flashKey, workflowId, currentRunId, firstPageSettled, pinnedSettled, runs, pinnedRun]);

  return (
    // `data-runs-history-panel` is kept from the former floating panel: it is the
    // stable hook the run-history e2e suites locate this list by.
    <div className="flex-1 min-h-0 flex flex-col" data-run-history-list data-runs-history-panel>
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-theme flex-shrink-0">
        <div className="flex items-center gap-2">
          <History className="w-3.5 h-3.5 text-theme-secondary" />
          <span className="text-sm font-medium text-theme-primary">{t('runs.title')}</span>
        </div>
        <Button
          variant="ghost"
          size="icon"
          onClick={() => fetchRuns(true)}
          disabled={loading}
          className="w-7 h-7"
          title={t('actions.refresh')}
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
        </Button>
      </div>

      {/* Rows */}
      <div className="flex-1 min-h-0 overflow-y-auto py-1">
        {error ? (
          <div className="flex flex-col items-center justify-center py-12 text-center text-theme-secondary">
            <XCircle className="w-10 h-10 mb-3 opacity-50" />
            <p className="text-sm mb-3">{error}</p>
            <Button variant="outline" size="sm" onClick={() => fetchRuns(true)}>
              {t('errors.retry')}
            </Button>
          </div>
        ) : displayRuns.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center text-theme-secondary">
            <History className="w-10 h-10 mb-3 opacity-50" />
            <p className="text-sm">{t('runs.noRuns')}</p>
            <p className="text-xs mt-1.5 opacity-70">{t('runs.runWorkflow')}</p>
          </div>
        ) : (
          displayRuns.map((run) => {
            const isCurrentRun = !!currentRunId && (currentRunId === run.id || currentRunId === run.runId);
            const displayStatus = getRunDisplayStatus(run.status, run.metadata);
            const status = mapRunStatus(displayStatus);
            // The pulse means "work is happening right now". A run resting in
            // WAITING_TRIGGER is armed, not executing: pulsing it made every live
            // reusable run read as running in its own history.
            const isRunning = status === 'running';
            const duration = formatRunDuration(run);
            // Reusable triggers keep one run across many fires: `lastFireAt` is the
            // most recent epoch's start (what the user reads as "last execution"),
            // `startedAt` the run's birth. Show the former, explain both on hover.
            const display = run.lastFireAt ?? run.startedAt;
            const locale = getClientLocale();
            const statusVisual = RUN_STATUS_ICON[status] ?? RUN_STATUS_ICON.pending;
            const tooltipParts: string[] = [tStatus(status), run.runId ?? ''];
            if (run.startedAt) tooltipParts.push(`${t('runs.runStarted')}: ${formatUtcDateTime(run.startedAt, { locale })}`);
            if (run.lastFireAt && run.lastFireAt !== run.startedAt) {
              tooltipParts.push(`${t('runs.lastFire')}: ${formatUtcDateTime(run.lastFireAt, { locale })}`);
            }
            // The duration column shows the whole run for a finished one and the
            // last execution for a live one - say which, or the number is a riddle.
            if (duration.text) {
              tooltipParts.push(duration.isLastEpoch
                ? `${t('runs.lastFireDuration')}: ${duration.text}`
                : `${t('runs.duration')}: ${duration.text}`);
            }

            return (
              <button
                key={run.id}
                // The flash is applied to this element imperatively, so the row
                // is never remounted (and never loses focus) when it ends.
                ref={isCurrentRun ? currentRowRef : undefined}
                type="button"
                data-run-history-row
                data-run-item
                data-selected={isCurrentRun || undefined}
                // The flash answers "which run was I on?" visually; aria-current
                // answers it for a screen reader, which sees neither the ring nor
                // the left border.
                aria-current={isCurrentRun || undefined}
                title={tooltipParts.filter(Boolean).join(' • ')}
                onClick={() => onSelectRun(run)}
                className={`w-full flex items-center gap-1.5 px-3 py-2.5 text-xs transition-colors ${
                  isCurrentRun
                    ? 'border-l-2 border-gray-900 dark:border-gray-100 bg-gray-50 dark:bg-white/[0.04] font-semibold text-gray-900 dark:text-gray-100'
                    : 'border-l-2 border-transparent hover:bg-gray-50/80 dark:hover:bg-white/[0.03]'
                }`}
              >
                {/* Status - w-6 leading column, same as the epoch number column */}
                <span className={`w-6 shrink-0 flex items-center justify-center ${statusVisual.className}`}>
                  {statusVisual.icon}
                </span>

                {/* Running pulse - slot always reserved so the columns never shift */}
                <span className="relative flex h-1.5 w-1.5 shrink-0">
                  {isRunning && (
                    <>
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
                      <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
                    </>
                  )}
                </span>

                {/* Status label + version + epoch + mode markers.
                    Type scale follows the project convention: `text-sm` for the
                    row identity (the content you read), `text-xs` for the
                    trailing timestamp/duration (meta). Icons are sized to their
                    text (`h-3.5` next to `text-sm`, `h-3` next to `text-xs`). */}
                <span className="flex-1 min-w-0 inline-flex items-center gap-1.5 overflow-hidden">
                  <span className={`text-sm font-medium truncate ${statusVisual.className}`}>
                    {tStatus(status)}
                  </span>
                  {run.planVersion != null && (
                    <span className="inline-flex items-center gap-0.5 text-sm font-medium text-gray-600 dark:text-gray-300 tabular-nums shrink-0">
                      {t('runs.version', { version: run.planVersion })}
                      {pinnedVersion != null && run.planVersion === pinnedVersion && (
                        <Pin className="w-3.5 h-3.5 text-amber-500 dark:text-amber-400" />
                      )}
                    </span>
                  )}
                  {/* This chip is NOT the one the run bar prints. It reads
                      `currentEpoch` from the run list, which the backend fills
                      with MAX(epoch) over the run's real epoch header rows - so
                      it never showed the phantom count the bar did (the bar was
                      fed the engine's cursor, which already points at the next,
                      dormant epoch). It has a narrower divergence of its own:
                      epochs are numbered PER TRIGGER, so on a multi-trigger run
                      MAX(epoch) is lower than the number of epochs that exist.
                      Aligning the two means serving the count instead
                      (`WorkflowEpochRepository.getEpochCountByRunIds`, already
                      used by the board), which is a backend change and belongs
                      to its own commit with its own tests. */}
                  {run.currentEpoch != null && run.currentEpoch > 0 && (
                    <span className="inline-flex items-center gap-0.5 text-sm font-medium text-gray-500 dark:text-gray-400 tabular-nums shrink-0">
                      <Calendar className="w-3.5 h-3.5" />
                      {run.currentEpoch}
                    </span>
                  )}
                  {run.executionMode === 'step_by_step' && (
                    <StepForward className="w-3.5 h-3.5 text-purple-600 dark:text-purple-300 shrink-0" aria-label={t('runs.stepByStepMode')} />
                  )}
                  {/* Mock-run marker: only the all_mcp dry-run override gets the flask
                      ('off' means the run explicitly IGNORED all mocks) */}
                  {run.metadata?.__mockMode__ === 'all_mcp' && (
                    <FlaskConical
                      className="w-3.5 h-3.5 text-indigo-600 dark:text-indigo-300 shrink-0"
                      data-testid="run-history-mock-icon"
                      aria-label={t('workflowBuilder.canvas.mockRun')}
                    />
                  )}
                </span>

                {/* Relative last-fire time */}
                <span className="text-xs text-gray-500 dark:text-gray-400 whitespace-nowrap shrink-0">
                  {display ? formatRelativeDateI18n(display, (key, params) => t(`runs.${key}`, params)) : ''}
                </span>

                {/* Duration - fixed column so the ticking value never reflows the row */}
                <span className="w-14 text-right text-xs tabular-nums font-medium text-gray-500 dark:text-gray-400 shrink-0">
                  {duration.text}
                </span>
              </button>
            );
          })
        )}

        {loadingMore && (
          <div className="flex items-center justify-center py-3">
            <RefreshCw className="w-3.5 h-3.5 animate-spin text-theme-secondary" />
          </div>
        )}

        {hasMore && !loadingMore && displayRuns.length > 0 && (
          <div ref={observerTarget} className="h-6 flex items-center justify-center">
            <span className="text-[11px] text-theme-secondary opacity-50">{t('runs.scrollToLoadMore')}</span>
          </div>
        )}

        {!hasMore && displayRuns.length > 0 && (
          <div className="text-center py-2 text-[11px] text-theme-secondary opacity-50">
            {t('runs.noMoreRuns')}
          </div>
        )}
      </div>
    </div>
  );
}
