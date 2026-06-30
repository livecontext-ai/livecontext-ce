import { describe, it, expect } from 'vitest';
import { shouldFetchFileOutput } from '../fileFetchPredicate';

/**
 * Regression guard for the run-page mount fetch storm (prod 2026-05-02:
 * ~80 calls in 11s for a 10-MCP-node workflow). The bug was every completed
 * FlowNode mounting `FileNodePreview` and eager-fetching `useRunOutputData`
 * to extract a FileRef thumbnail. The predicate gates that fetch.
 *
 * Each test names the scenario it locks; together they cover the full
 * decision tree (see helper docstring) plus the streaming-preservation
 * branches the user explicitly required.
 */
describe('shouldFetchFileOutput', () => {
  // ─────────────────── Pre-conditions ───────────────────

  it('notInRunMode_returnsFalse', () => {
    expect(
      shouldFetchFileOutput({
        isRunMode: false,
        isCompleted: true,
        selected: true,
        isStaticFileProducingNode: true,
        runStatus: 'running',
      }),
    ).toBe(false);
  });

  it('notCompleted_returnsFalse', () => {
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: false,
        selected: true,
        isStaticFileProducingNode: true,
        runStatus: 'running',
      }),
    ).toBe(false);
  });

  // ─────────────────── Terminal-run unselected MCP (the bug) ───────────────────

  it('unselectedMcpNodeOnCompletedRunDoesNotFetch', () => {
    // The exact prod scenario: terminal run, unselected MCP node - the 80-call storm.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: false,
        runStatus: 'completed',
      }),
    ).toBe(false);
  });

  it('unselectedMcpNodeOnPartialSuccessDoesNotFetch', () => {
    // C6 audit catch: partial_success is engine-final but NOT in
    // RunStateStore.TERMINAL_STATUSES - must be in our FINISHED_STATUSES.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: false,
        runStatus: 'partial_success',
      }),
    ).toBe(false);
  });

  it('stoppedRunDoesNotFetch', () => {
    // C6 BLOCKER catch: 'stopped' is in RunStatus union (test/manual dispatch
    // can leak past the upstream stopped→cancelled normalization). With STOP
    // cascade now active in prod, exclusion would reproduce the 80-call bug
    // for stopped runs.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: false,
        runStatus: 'stopped',
      }),
    ).toBe(false);
  });

  it('staticStoppedRunFetches', () => {
    // B7 audit: static short-circuits regardless of run status.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: true,
        runStatus: 'stopped',
      }),
    ).toBe(true);
  });

  // ─────────────────── Live-run preservation (user red-line) ───────────────────

  it('unselectedMcpNodeDuringLiveRunFetches', () => {
    // Streaming preservation: thumbnails must appear as nodes complete on a
    // live run. User flagged this explicitly - regression here breaks the
    // run-monitoring UX.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: false,
        runStatus: 'running',
      }),
    ).toBe(true);
  });

  it('runningRunIsLive', () => {
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: false,
        runStatus: 'running',
      }),
    ).toBe(true);
  });

  it('waitingTriggerRunIsTreatedAsFinished', () => {
    // Regression - production runId run_<id> (2026-05-02):
    // cron workflow sits in waiting_trigger 99% of the time between fires.
    // Initial v8 predicate had this as live → permanent eager-fetch storm
    // → 429 cascade. Now treated as finished; new-epoch thumbnails appear
    // on click instead of polling.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: false,
        runStatus: 'waiting_trigger',
      }),
    ).toBe(false);
  });

  it('pausedRunIsLive', () => {
    // Paused workflow can be resumed → new completions imminent.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: false,
        runStatus: 'paused',
      }),
    ).toBe(true);
  });

  // ─────────────────── Selected always wins ───────────────────

  it('selectedNodeFetchesEvenOnTerminalRun', () => {
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: true,
        isStaticFileProducingNode: false,
        runStatus: 'completed',
      }),
    ).toBe(true);
  });

  it('selectedLiveRunFetches', () => {
    // C7 audit: lock OR-precedence - selected branch independent of live.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: true,
        isStaticFileProducingNode: false,
        runStatus: 'running',
      }),
    ).toBe(true);
  });

  // ─────────────────── Static always wins (terminal) ───────────────────

  it('unselectedStaticNodeFetches', () => {
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: true,
        runStatus: 'completed',
      }),
    ).toBe(true);
  });

  // ─────────────────── Initial render: useRun returns null ───────────────────

  it('runStatusNullReturnsFalse', () => {
    // First render before useRun resolves: runState=null → runStatus null.
    // Gate stays closed until status arrives, then re-renders open it.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: false,
        runStatus: null,
      }),
    ).toBe(false);
  });

  it('runStatusUndefinedReturnsFalse', () => {
    // Optional-chaining `runState?.runStatus` resolves to undefined when
    // runState is null. Same gate-closed semantics as null.
    expect(
      shouldFetchFileOutput({
        isRunMode: true,
        isCompleted: true,
        selected: false,
        isStaticFileProducingNode: false,
        runStatus: undefined,
      }),
    ).toBe(false);
  });
});
