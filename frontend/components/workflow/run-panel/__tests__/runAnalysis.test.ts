import { describe, expect, it } from 'vitest';
import type { RunAnalysisEpoch, RunAnalysisNodeCell } from '@/lib/api/orchestrator/types';
import {
  buildNodeRows,
  cellStatus,
  compareEpochs,
  computeInsights,
  computeKpis,
  defaultComparison,
  heatLevel,
  referenceFor,
} from '../runAnalysis';

function cell(alias: string, status: string, elapsedMs: number | null = 1000, extra: Partial<RunAnalysisNodeCell> = {}): RunAnalysisNodeCell {
  return { alias, status, executionTimeMs: elapsedMs ?? 0, elapsedMs, ...extra };
}

function epoch(n: number, status: string | null, nodes: RunAnalysisNodeCell[] = [], extra: Partial<RunAnalysisEpoch> = {}): RunAnalysisEpoch {
  return {
    epoch: n,
    startedAt: '2026-09-26T10:00:00Z',
    endedAt: status ? '2026-09-26T10:00:05Z' : null,
    workDurationMs: 5000,
    status,
    costCredits: null,
    nodes,
    ...extra,
  };
}

describe('cellStatus', () => {
  it('maps the aggregated step vocabulary onto the grid statuses', () => {
    expect(cellStatus(cell('a', 'completed'))).toBe('ok');
    expect(cellStatus(cell('a', 'error'))).toBe('failed');
    expect(cellStatus(cell('a', 'failed'))).toBe('failed');
    expect(cellStatus(cell('a', 'partial_success'))).toBe('partial');
    expect(cellStatus(cell('a', 'skipped'))).toBe('skipped');
    expect(cellStatus(cell('a', 'awaiting_signal'))).toBe('waiting');
    expect(cellStatus(cell('a', 'running'))).toBe('running');
  });

  it('reads "pending" (rows written, none processed) as "did not run", never as a pulsing run', () => {
    expect(cellStatus(cell('a', 'pending'))).toBe('none');
  });

  it('reads a node absent from the epoch, or an unknown status, as "did not run"', () => {
    expect(cellStatus(undefined)).toBe('none');
    expect(cellStatus(cell('a', 'something-new'))).toBe('none');
  });
});

describe('computeKpis', () => {
  it('counts only settled epochs in the success rate and the duration mean', () => {
    const epochs = [
      epoch(1, 'COMPLETED', [], { workDurationMs: 2000, costCredits: 1 }),
      epoch(2, 'FAILED', [], { workDurationMs: 4000, costCredits: 3 }),
      // Open epoch of a running run: live, so neither in the rate nor in the mean.
      epoch(3, null, [], { workDurationMs: 100, costCredits: null }),
    ];

    const kpis = computeKpis(epochs, 10, 'RUNNING');

    expect(kpis).toMatchObject({ shown: 3, total: 10, completed: 1, failed: 1, successRate: 50 });
    expect(kpis.avgDurationMs).toBe(3000);
    expect(kpis.maxDurationMs).toBe(4000);
    expect(kpis.totalCost).toBe(4);
    expect(kpis.avgCost).toBe(2);
  });

  it('says nothing rather than 0% or 0s when no epoch has settled or recorded a cost', () => {
    const kpis = computeKpis([epoch(1, null)], 1, 'RUNNING');

    expect(kpis.successRate).toBeNull();
    expect(kpis.avgDurationMs).toBeNull();
    expect(kpis.totalCost).toBeNull();
    expect(kpis.avgCost).toBeNull();
  });

  it('counts an epoch a stopped run abandoned as neither a success nor a failure', () => {
    const kpis = computeKpis([epoch(1, 'COMPLETED'), epoch(2, null)], 2, 'STOPPED');

    expect(kpis.completed).toBe(1);
    expect(kpis.failed).toBe(0);
    expect(kpis.successRate).toBe(100);
  });
});

describe('buildNodeRows', () => {
  it('collects every node once with its cell per epoch, its failures and its average success time', () => {
    const rows = buildNodeRows([
      epoch(1, 'COMPLETED', [cell('fetch', 'completed', 1000), cell('send', 'completed', 200)]),
      epoch(2, 'FAILED', [cell('fetch', 'error', 300)]),
      epoch(3, 'COMPLETED', [cell('fetch', 'completed', 3000), cell('send', 'partial_success', 400)]),
    ]);

    expect(rows.map(r => r.alias)).toEqual(['fetch', 'send']);
    const fetch = rows[0];
    expect([...fetch.cells.keys()]).toEqual([1, 2, 3]);
    expect(fetch.failures).toBe(1);
    expect(fetch.partials).toBe(0);
    expect(fetch.avgElapsedMs).toBe(2000);
    expect(fetch.maxElapsedMs).toBe(3000);
    expect(fetch.minElapsedMs).toBe(1000);
    // A node whose items only PARTLY failed mostly worked: counted apart, never as "failed".
    expect(rows[1].failures).toBe(0);
    expect(rows[1].partials).toBe(1);
    expect(rows[1].avgElapsedMs).toBe(200);
  });

  it('scales durations on successes only, so one slow failure does not make every success look fast', () => {
    const [row] = buildNodeRows([
      epoch(1, 'COMPLETED', [cell('fetch', 'completed', 1_000)]),
      epoch(2, 'FAILED', [cell('fetch', 'error', 300_000)]), // a 5-minute timeout
    ]);

    expect(row.maxElapsedMs).toBe(1_000);
    expect(row.minElapsedMs).toBe(1_000);
  });

  it('leaves the average empty when the node never succeeded with a measured time', () => {
    const [row] = buildNodeRows([epoch(1, 'FAILED', [cell('fetch', 'error', null)])]);

    expect(row.avgElapsedMs).toBeNull();
    expect(row.maxElapsedMs).toBe(0);
    expect(row.minElapsedMs).toBe(0);
  });
});

describe('heatLevel', () => {
  it('grades a cell against its own row, so a slow agent does not make every other node look fast', () => {
    expect(heatLevel(100, 0, 1000)).toBe(0);
    expect(heatLevel(300, 0, 1000)).toBe(1);
    expect(heatLevel(500, 0, 1000)).toBe(2);
    expect(heatLevel(700, 0, 1000)).toBe(3);
    expect(heatLevel(1000, 0, 1000)).toBe(4);
  });

  it('switches level exactly at each fifth of the span between the fastest and slowest run', () => {
    expect(heatLevel(1199, 1000, 2000)).toBe(0);
    expect(heatLevel(1200, 1000, 2000)).toBe(1);
    expect(heatLevel(1799, 1000, 2000)).toBe(3);
    expect(heatLevel(1800, 1000, 2000)).toBe(4);
  });

  it('paints the fastest run of a row palest even when it is far from zero', () => {
    expect(heatLevel(10_000, 10_000, 20_000)).toBe(0);
    expect(heatLevel(20_000, 10_000, 20_000)).toBe(4);
  });

  // Regression: the only epoch of a run was its own maximum, so a sub-second cell read "slowest".
  it('paints a lone success palest instead of darkest', () => {
    expect(heatLevel(300, 300, 300)).toBe(0);
  });

  it('treats durations that barely differ as equal, in absolute and in relative terms', () => {
    // 60 ms apart: under the 100 ms floor.
    expect(heatLevel(260, 200, 260)).toBe(0);
    // 200 ms apart on a 30 s node: under 10% of its slowest run.
    expect(heatLevel(30_000, 29_800, 30_000)).toBe(0);
    // Exactly at the threshold, the scale applies.
    expect(heatLevel(1_000, 900, 1_000)).toBe(4);
  });

  it('has no level for a cell without an elapsed time, and level 0 on an all-zero row', () => {
    expect(heatLevel(null, 0, 1000)).toBeNull();
    expect(heatLevel(undefined, 0, 1000)).toBeNull();
    expect(heatLevel(0, 0, 0)).toBe(0);
  });
});

describe('computeInsights', () => {
  it('names the node that failed most often, with its latest error', () => {
    const epochs = [
      epoch(1, 'FAILED', [cell('fetch', 'error', 10, { errorMessage: 'HTTP 500' })]),
      epoch(2, 'COMPLETED', [cell('fetch', 'completed')]),
      epoch(3, 'FAILED', [cell('fetch', 'error', 10, { errorMessage: 'HTTP 429' })]),
    ];

    const [failing] = computeInsights(epochs, buildNodeRows(epochs));

    expect(failing).toEqual({ kind: 'failing', alias: 'fetch', count: 2, total: 3, lastError: 'HTTP 429' });
  });

  it('keeps looking back past a newer failure that carries no message', () => {
    const epochs = [
      epoch(1, 'FAILED', [cell('fetch', 'error', 10, { errorMessage: 'HTTP 500' })]),
      epoch(2, 'FAILED', [cell('fetch', 'error', 10, { errorMessage: null })]),
    ];

    const [failing] = computeInsights(epochs, buildNodeRows(epochs));

    expect(failing).toMatchObject({ kind: 'failing', count: 2, lastError: 'HTTP 500' });
  });

  it('a partly failed node that FAILED its epoch is the failing node, never "no failure"', () => {
    // A split with 1 of 10 items down: the node reads partial_success, the epoch reads FAILED.
    const epochs = [
      epoch(1, 'FAILED', [cell('split', 'partial_success', 10, { errorMessage: 'item 3 failed' })]),
      epoch(2, 'COMPLETED', [cell('split', 'completed', 10)]),
    ];

    expect(computeInsights(epochs, buildNodeRows(epochs))).toEqual([
      { kind: 'failing', alias: 'split', count: 1, total: 2, lastError: 'item 3 failed' },
    ]);
  });

  it('a partly failed node in an epoch that still completed (continue on failure) is partial, not failing', () => {
    const epochs = [epoch(1, 'COMPLETED', [cell('split', 'partial_success', 10, { errorMessage: 'item 3 failed' })])];

    const [row] = buildNodeRows(epochs);
    expect(row).toMatchObject({ failures: 0, partials: 1 });
    expect(computeInsights(epochs, [row])).toEqual([]);
  });

  it('does not count the epochs where the node was skipped (a branch not taken) as epochs it ran in', () => {
    const epochs = [
      epoch(1, 'FAILED', [cell('branch', 'error', 10, { errorMessage: 'x' })]),
      epoch(2, 'COMPLETED', [cell('branch', 'skipped', 0)]),
      epoch(3, 'COMPLETED', [cell('branch', 'skipped', 0)]),
      epoch(4, 'COMPLETED', [cell('branch', 'completed')]),
    ];

    expect(computeInsights(epochs, buildNodeRows(epochs))[0]).toMatchObject({ alias: 'branch', count: 1, total: 2 });
  });

  it('counts the failing total over the epochs the node ran in, not the whole window', () => {
    const epochs = [
      epoch(1, 'COMPLETED', [cell('other', 'completed')]),
      epoch(2, 'COMPLETED', [cell('other', 'completed')]),
      epoch(3, 'FAILED', [cell('rare', 'error', 10, { errorMessage: 'x' })]),
      epoch(4, 'COMPLETED', [cell('rare', 'completed')]),
    ];

    expect(computeInsights(epochs, buildNodeRows(epochs))[0]).toMatchObject({ alias: 'rare', count: 1, total: 2 });
  });

  it('reports epochs that failed with no failing node, alongside the other findings', () => {
    const epochs = [
      epoch(1, 'FAILED', [cell('fetch', 'error', 10, { errorMessage: 'boom' })]),
      epoch(2, 'FAILED', [cell('fetch', 'completed', 10)]),            // trigger or engine failure
      epoch(3, 'FAILED', []),                                           // nothing but the trigger
      epoch(4, 'COMPLETED', [cell('fetch', 'completed', 10)]),
    ];

    const insights = computeInsights(epochs, buildNodeRows(epochs));

    expect(insights.map(i => i.kind)).toEqual(['failing', 'epochFailures']);
    expect(insights[1]).toEqual({ kind: 'epochFailures', count: 2 });
  });

  it('does not count an epoch still running as an unexplained failure', () => {
    const epochs = [epoch(1, null, [cell('fetch', 'running', null)])];

    expect(computeInsights(epochs, buildNodeRows(epochs), 'RUNNING')).toEqual([]);
  });

  it('reports a node whose successful runs got clearly slower, from the epoch the newer half starts', () => {
    const times = [1000, 1100, 900, 1000, 3000, 3200, 2900, 3100];
    const epochs = times.map((ms, i) => epoch(i + 1, 'COMPLETED', [cell('summarize', 'completed', ms)]));

    const insights = computeInsights(epochs, buildNodeRows(epochs));

    expect(insights).toEqual([{ kind: 'slower', alias: 'summarize', sinceEpoch: 5, beforeMs: 1000, afterMs: 3050 }]);
  });

  it('does not claim a trend from too few samples, a small relative change, or a tiny absolute one', () => {
    const fewSamples = [1000, 1000, 5000, 5000].map((ms, i) => epoch(i + 1, 'COMPLETED', [cell('a', 'completed', ms)]));
    const smallRatio = [1000, 1000, 1000, 1300, 1300, 1300].map((ms, i) => epoch(i + 1, 'COMPLETED', [cell('a', 'completed', ms)]));
    const tinyDelta = [20, 20, 20, 60, 60, 60].map((ms, i) => epoch(i + 1, 'COMPLETED', [cell('a', 'completed', ms)]));

    for (const epochs of [fewSamples, smallRatio, tinyDelta]) {
      expect(computeInsights(epochs, buildNodeRows(epochs))).toEqual([]);
    }
  });

  it('ignores failed runs when measuring a slowdown (a fast failure is not a speed-up)', () => {
    const epochs = [
      ...[1000, 1000, 1000].map((ms, i) => epoch(i + 1, 'COMPLETED', [cell('a', 'completed', ms)])),
      ...[100, 100, 100].map((ms, i) => epoch(i + 4, 'FAILED', [cell('a', 'error', ms)])),
    ];

    const insights = computeInsights(epochs, buildNodeRows(epochs));

    expect(insights.map(i => i.kind)).toEqual(['failing']);
  });
});

describe('defaultComparison / referenceFor', () => {
  it('opens on the latest failure against the last success before it', () => {
    const epochs = [epoch(1, 'COMPLETED'), epoch(2, 'FAILED'), epoch(3, 'COMPLETED'), epoch(4, 'FAILED'), epoch(5, 'COMPLETED')];

    expect(defaultComparison(epochs)).toEqual({ reference: 3, target: 4 });
  });

  it('without a failure, compares the two most recent settled epochs', () => {
    const epochs = [epoch(1, 'COMPLETED'), epoch(2, 'COMPLETED'), epoch(3, null)];

    expect(defaultComparison(epochs, 'RUNNING')).toEqual({ reference: 1, target: 2 });
  });

  it('needs two epochs', () => {
    expect(defaultComparison([epoch(1, 'FAILED')])).toBeNull();
  });

  it('falls back to the previous epoch, then to the next one when the target is the oldest', () => {
    const epochs = [epoch(1, 'FAILED'), epoch(2, 'FAILED'), epoch(3, 'COMPLETED')];

    expect(referenceFor(epochs, 2)).toBe(1);
    expect(referenceFor(epochs, 1)).toBe(2);
  });
});

describe('compareEpochs - slowdown threshold', () => {
  const pair = (a: number, b: number) => compareEpochs(
    epoch(1, 'COMPLETED', [cell('n', 'completed', a)]),
    epoch(2, 'COMPLETED', [cell('n', 'completed', b)]),
    ['n'],
  )[0].differs;

  it('flags a success at least 1.5x and 1 s slower, in either direction, and nothing short of that', () => {
    expect(pair(2000, 3000)).toBe(true);    // exactly 1.5x and exactly +1 s
    expect(pair(3000, 2000)).toBe(true);    // faster is a change too
    expect(pair(2000, 2999)).toBe(false);   // just under 1.5x
    expect(pair(100, 900)).toBe(false);     // 9x but under a second: noise
  });

  it('does not compare durations when one side has none', () => {
    expect(compareEpochs(
      epoch(1, 'COMPLETED', [cell('n', 'completed', null)]),
      epoch(2, 'COMPLETED', [cell('n', 'completed', 9000)]),
      ['n'],
    )[0].differs).toBe(false);
  });
});

describe('compareEpochs', () => {
  it('lists every node of either epoch in DAG order and flags what changed', () => {
    const reference = epoch(1, 'COMPLETED', [
      cell('trigger', 'completed', 10),
      cell('fetch', 'completed', 1000),
      cell('summarize', 'completed', 2000),
      cell('send', 'completed', 300),
    ]);
    const target = epoch(2, 'FAILED', [
      cell('trigger', 'completed', 12),
      cell('fetch', 'completed', 4000),
      cell('summarize', 'error', 50),
      cell('extra', 'completed', 10),
    ]);

    const rows = compareEpochs(reference, target, ['trigger', 'fetch', 'summarize', 'send']);

    expect(rows.map(r => [r.alias, r.differs])).toEqual([
      ['trigger', false],   // same status, 10 ms vs 12 ms is noise
      ['fetch', true],      // 4x slower and 3 s more
      ['summarize', true],  // completed -> error
      ['send', true],       // ran, then did not run
      ['extra', true],      // unknown to the DAG order: last
    ]);
  });
});
