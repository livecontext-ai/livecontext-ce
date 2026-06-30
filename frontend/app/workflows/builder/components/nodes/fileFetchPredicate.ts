import type { RunStatus } from '@/contexts/workflow-run/RunStateStore';

/**
 * Statuses where the run is "finished" for our perf purposes - no more node
 * completions expected for the CURRENT epoch, so eager-fetching all completed
 * nodes' file outputs is wasteful.
 *
 * INTENTIONALLY DISTINCT from RunStateStore.TERMINAL_STATUSES which is a
 * streaming-stickiness invariant (used elsewhere; touching it has cross-
 * consumer risk and excludes partial_success for separate reasons).
 *
 * Live (NOT in this set): running, pending, paused.
 *   - paused: workflow can be resumed → new completions imminent.
 *
 * 'waiting_trigger' is included as finished. Rationale: cyclic triggers (cron,
 * webhook) sit in waiting_trigger 99% of the time between fires. Treating it
 * as live caused a permanent eager-fetch storm on cron workflows (prod
 * incident, run_<id> on 2026-05-02 - the exact bug the
 * predicate was supposed to prevent). Cost of treating it as finished: when
 * the next cron fires, new node completions land in a NEW epoch - frontend
 * still shows cached thumbnails from prior epoch and the user clicks to refresh
 * for the new one. Acceptable for cron/batch workloads.
 *
 * 'stopped' included even though normalized to 'cancelled' upstream - type
 * union still admits it (test code, manual dispatches), defense-in-depth.
 */
const FINISHED_STATUSES: ReadonlySet<RunStatus> = new Set<RunStatus>([
  'completed',
  'failed',
  'cancelled',
  'timeout',
  'partial_success',
  'stopped',
  'waiting_trigger',
]);

/**
 * Decides whether a FileNodePreview's `useRunOutputData` query should fetch.
 *
 * @returns false ⇒ TanStack Query `enabled: false` ⇒ no network call. Used to
 *   prevent the run-page-mount fetch storm (~80 calls / 11s in prod for a
 *   workflow with ~10 MCP nodes) caused by every completed node eager-loading
 *   its file output to extract a FileRef thumbnail.
 *
 * Decision tree:
 *   - !isRunMode || !isCompleted ⇒ false (we have nothing to show yet)
 *   - selected ⇒ true (user is inspecting this node - fetch on demand)
 *   - isStaticFileProducingNode ⇒ true (4 node types known to always
 *     produce a file: download_file, convert_to_file, compression, sftp;
 *     no runtime detection needed, fetch is cheap and always relevant)
 *   - runIsLive (status NOT in FINISHED_STATUSES) ⇒ true (live runs keep
 *     the eager-fetch behaviour so thumbnails appear as nodes complete; the
 *     30s `staleTime` cache prevents poll loops)
 *   - else ⇒ false (terminal-run unselected MCP nodes wait for click)
 */
export function shouldFetchFileOutput(args: {
  isRunMode: boolean;
  isCompleted: boolean;
  selected: boolean;
  isStaticFileProducingNode: boolean;
  runStatus: RunStatus | null | undefined;
}): boolean {
  if (!args.isRunMode || !args.isCompleted) return false;
  const runIsLive =
    args.runStatus != null && !FINISHED_STATUSES.has(args.runStatus);
  return args.selected || args.isStaticFileProducingNode || runIsLive;
}
