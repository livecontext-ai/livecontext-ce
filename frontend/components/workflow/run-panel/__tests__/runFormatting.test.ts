import { describe, expect, it } from 'vitest';
import {
  deriveEffectiveStatus,
  epochTone,
  formatCompactDuration,
  getBarColor,
  isRunStatusActive,
  stepDisplayDurationMs,
} from '@/components/workflow/run-panel/runFormatting';

describe('getBarColor', () => {
  it('paints a partial step amber, the same colour every other surface gives it', () => {
    // It used to fall into the red branch, so one node read "failed" in the waterfall and
    // "partial" on the canvas, badge and edge - the contradiction this whole change removes.
    const partial = getBarColor('partial_success');
    expect(partial).toContain('amber');
    expect(partial).not.toContain('red');
  });

  it('still paints a plainly failed step red, so partial stays distinguishable from failed', () => {
    expect(getBarColor('failed')).toContain('red');
    expect(getBarColor('error')).toContain('red');
  });

  it('leaves a clean step green', () => {
    expect(getBarColor('completed')).toContain('emerald');
  });
});

describe('formatCompactDuration', () => {
  it('collapses sub-second durations to "<1s"', () => {
    expect(formatCompactDuration(0)).toBe('<1s');
    expect(formatCompactDuration(999)).toBe('<1s');
  });

  it('keeps one decimal below 10s, then rounds to whole seconds', () => {
    expect(formatCompactDuration(1400)).toBe('1.4s');
    expect(formatCompactDuration(42_000)).toBe('42s');
  });

  it('pads the seconds/minutes remainder so the column never jitters', () => {
    expect(formatCompactDuration(187_000)).toBe('3m07s');
    expect(formatCompactDuration(7_500_000)).toBe('2h05m');
  });

  it('never prints a 60 in the seconds slot: the whole figure is rounded before it is split', () => {
    // Rounding only the remainder printed "1m60s" and "59m60s" for these.
    expect(formatCompactDuration(119_700)).toBe('2m00s');
    expect(formatCompactDuration(3_599_700)).toBe('1h00m');
    expect(formatCompactDuration(59_600)).toBe('1m00s');
  });

  it('switches to whole seconds before the one-decimal form would print "10.0s"', () => {
    expect(formatCompactDuration(9_940)).toBe('9.9s');
    expect(formatCompactDuration(9_960)).toBe('10s');
  });
});

describe('stepDisplayDurationMs', () => {
  const base = {
    alias: 'mcp:fetch',
    startTime: '2026-09-26T10:00:00Z',
    endTime: '2026-09-26T10:01:00Z',
  };

  it('one epoch: the time the node HELD the epoch, not the summed items of a parallel split', () => {
    // 10 items x 5 s in parallel: 50 s of work, 5 s held.
    const step = { ...base, status: 'completed', executionTimeMs: 50_000, elapsedMs: 5_000, statusCounts: { completed: 10 } };
    expect(stepDisplayDurationMs(step, false)).toBe(5_000);
  });

  it('one epoch: never the first-start/last-end span, which for a loop body counts the other nodes', () => {
    // A loop body: bounds a minute apart, 10 iterations of 1 s. Neither 60 s (the span) nor a
    // recomputation from the bounds: the backend's per-iteration figure.
    const step = { ...base, status: 'completed', executionTimeMs: 10_000, elapsedMs: 10_000, statusCounts: { completed: 10 } };
    expect(stepDisplayDurationMs(step, false)).toBe(10_000);
  });

  it('one epoch from a backend that sends no elapsed time: the reported execution time', () => {
    const step = { ...base, status: 'completed', executionTimeMs: 1_200 };
    expect(stepDisplayDurationMs(step, false)).toBe(1_200);
  });

  it('all epochs: the cumulative total when the backend sends one (negative clamped to 0)', () => {
    const step = { ...base, status: 'completed', executionTimeMs: 800, totalExecutionTimeMs: 24_000, statusCounts: { completed: 30 } };
    expect(stepDisplayDurationMs(step, true)).toBe(24_000);
    expect(stepDisplayDurationMs({ ...step, totalExecutionTimeMs: -5 }, true)).toBe(0);
  });

  it('all epochs: a single execution stands in for the total only when the node ran once', () => {
    const once = { ...base, status: 'completed', executionTimeMs: 800, statusCounts: { completed: 1 } };
    const many = { ...base, status: 'completed', executionTimeMs: 800, statusCounts: { completed: 30 } };
    // Running and waiting executions count too: two executions is not "ran once".
    const twoKinds = { ...base, status: 'completed', executionTimeMs: 800, statusCounts: { completed: 1, awaitingSignal: 1 } };
    expect(stepDisplayDurationMs(once, true)).toBe(800);
    // One execution drawn next to its neighbours' 30-epoch totals would read 30x too short.
    expect(stepDisplayDurationMs(many, true)).toBeNull();
    expect(stepDisplayDurationMs(twoKinds, true)).toBeNull();
  });

  it('a skipped node has no duration at all, never "<1s"', () => {
    const step = { ...base, status: 'skipped', executionTimeMs: 0, elapsedMs: 0, statusCounts: { skipped: 4 } };
    expect(stepDisplayDurationMs(step, false)).toBeNull();
    expect(stepDisplayDurationMs(step, true)).toBeNull();
  });

  it('one epoch: a node still executing ticks from its start until part of it has been timed', () => {
    const now = Date.parse('2026-09-26T10:00:07Z');
    const noEnd = { ...base, endTime: null, status: 'running' };
    expect(stepDisplayDurationMs(noEnd, false, now)).toBe(7_000);
  });

  it('one epoch: a rerun still running, with no timed row of its own, shows nothing rather than hours since the old attempt', () => {
    const now = Date.parse('2026-09-26T13:00:00Z'); // the old attempt started three hours ago
    const rerun = { ...base, status: 'running', statusCounts: { completed: 5, running: 5 } };
    expect(stepDisplayDurationMs(rerun, false, now)).toBeNull();
  });

  it('one epoch: a loop body between iterations shows its timed iterations, not the time since its first start', () => {
    // First start 10:00:00, three 1 s iterations done, the other nodes ran in between: 7 s since
    // the start would count them, then drop to 3 s the moment the node finishes.
    const now = Date.parse('2026-09-26T10:00:07Z');
    const loopBody = { ...base, endTime: '2026-09-26T10:00:05Z', status: 'running', elapsedMs: 3_000, statusCounts: { completed: 3, running: 1 } };
    expect(stepDisplayDurationMs(loopBody, false, now)).toBe(3_000);
  });

  it('all epochs: a node running now keeps its cumulative total, never "elapsed since the first epoch"', () => {
    const now = Date.parse('2026-09-27T10:00:00Z'); // a day after the first start
    const step = { ...base, endTime: null, status: 'running', totalExecutionTimeMs: 4_000, statusCounts: { completed: 3, running: 1 } };
    expect(stepDisplayDurationMs(step, true, now)).toBe(4_000);
  });

  it('nothing to say when nothing was timed', () => {
    const step = { alias: 'mcp:fetch', status: 'completed', startTime: null, endTime: null };
    expect(stepDisplayDurationMs(step, false)).toBeNull();
    expect(stepDisplayDurationMs(step, true)).toBeNull();
  });
});

describe('deriveEffectiveStatus', () => {
  it('returns the raw status when the step has no per-epoch counts', () => {
    expect(deriveEffectiveStatus('completed')).toBe('completed');
    expect(deriveEffectiveStatus('pending', undefined)).toBe('pending');
  });

  it('prioritises in-flight executions over finished ones', () => {
    expect(deriveEffectiveStatus('completed', { completed: 3, running: 1 })).toBe('running');
    expect(deriveEffectiveStatus('completed', { completed: 3, awaitingSignal: 1 })).toBe('awaiting_signal');
  });

  it('reports partial_success when some executions failed and some completed', () => {
    expect(deriveEffectiveStatus('completed', { completed: 2, failed: 1 })).toBe('partial_success');
    expect(deriveEffectiveStatus('failed', { failed: 2 })).toBe('failed');
  });

  it('falls back to skipped only when nothing else happened', () => {
    expect(deriveEffectiveStatus('pending', { skipped: 4 })).toBe('skipped');
    expect(deriveEffectiveStatus('pending', {})).toBe('pending');
  });
});

describe('isRunStatusActive', () => {
  it('treats every terminal status as inactive - including the ones a local copy forgot', () => {
    // Literal list on purpose, transcribed from the BACKEND enum
    // (RunStatus.isTerminal), not from the store: deriving it from a set the
    // implementation itself reads makes the test structurally unable to catch a
    // missing status, which is how first `stopped` and then `skipped` went missing
    // (no Reactivate button, and a fire button the dispatcher refuses).
    // `STOPPED` is not in the enum but still reaches the UI from the streaming layer.
    for (const s of ['COMPLETED', 'SKIPPED', 'FAILED', 'PARTIAL_SUCCESS', 'CANCELLED', 'TIMEOUT', 'STOPPED']) {
      expect(isRunStatusActive(s)).toBe(false);
    }
  });

  it('stays in sync with the store terminal set (single source of truth)', async () => {
    const { TERMINAL_STATUSES } = await import('@/contexts/workflow-run/RunStateStore');
    for (const s of TERMINAL_STATUSES) {
      expect(isRunStatusActive(s.toUpperCase())).toBe(false);
    }
  });

  it('treats a live run as active, case-insensitively', () => {
    expect(isRunStatusActive('RUNNING')).toBe(true);
    expect(isRunStatusActive('waiting_trigger')).toBe(true);
  });

  it('is false when the status is unknown', () => {
    expect(isRunStatusActive(null)).toBe(false);
    expect(isRunStatusActive('')).toBe(false);
  });
});

describe('epochTone', () => {
  it('maps every epoch badge status onto the colour family both epoch surfaces paint', () => {
    expect(epochTone('RUNNING')).toBe('running');
    expect(epochTone('FAILED')).toBe('failed');
    expect(epochTone('CANCELLED')).toBe('stopped');
    expect(epochTone('STOPPED')).toBe('stopped');
    expect(epochTone('TIMEOUT')).toBe('stopped');
    expect(epochTone('COMPLETED')).toBe('ok');
  });

  it('gives no outcome to an epoch that carries none yet', () => {
    expect(epochTone(null)).toBe('none');
    expect(epochTone(undefined)).toBe('none');
  });
});
