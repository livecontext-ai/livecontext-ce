import { describe, it, expect } from 'vitest';
import { resolve, sep } from 'path';
import { run, readReport, defaultDeps } from '../scripts/ci-vitest.mjs';

/**
 * scripts/ci-vitest.mjs, the contracts job's vitest step, driven end to end with fakes for the
 * import graph, the vitest CLI, git and the file tree. Every path that decides WHAT runs is
 * exercised: the full suite off a pull request, each fallback, the selected run, and the checks that
 * turn a selected file that did not run, a failure, or a broken selection into a red (or full) run.
 */

// Resolved like the runner resolves it: on Windows `resolve` adds the drive letter.
const ROOT = resolve('/repo/frontend').split(sep).join('/');
const TESTS: Record<string, string> = {
  'components/a/__tests__/A.test.tsx': "import { A } from '../A';",
  'components/b/__tests__/B.test.tsx': "import { B } from '../B';",
  '__tests__/i18n-locale-parity.test.ts': "import en from '@/messages/en.json';",
};
for (let i = 0; i < 20; i += 1) TESTS[`lib/__tests__/filler${i}.test.ts`] = '';
const SOURCES: Record<string, string> = {
  'components/a/A.tsx': '',
  'components/fleet/actions.ts': "const { X } = require('@/components/x/X');",
};
const REPORT = '/tmp/report.json';

interface FakeOptions {
  event?: string;
  argv?: string[];
  diff?: string | Error;
  selected?: string[];
  graphThrows?: boolean;
  status?: number;
  failed?: string[];
  unreported?: string[];
  noReport?: boolean;
}

function fake(o: FakeOptions = {}) {
  const calls = { spawn: [] as string[][], handed: [] as string[] };
  const logs: string[] = [];
  const summaries: string[] = [];
  let lastRun: string[] = [];
  const deps = {
    argv: o.argv ?? ['--shard=1/2'],
    event: o.event ?? 'pull_request',
    githubActions: false,
    root: ROOT,
    log: (l: string) => logs.push(l),
    summary: (t: string) => summaries.push(t),
    spawnVitest: (args: string[]) => {
      calls.spawn.push(args);
      lastRun = args.filter((a) => !a.startsWith('-') && a !== 'run');
      return o.status ?? 0;
    },
    gitDiff: () => { if (o.diff instanceof Error) throw o.diff; return o.diff ?? 'M\tfrontend/components/a/A.tsx\n'; },
    readSources: (keep: (n: string) => boolean) => {
      const all = { ...TESTS, ...SOURCES };
      return Object.fromEntries(Object.entries(all).filter(([p]) => keep(p.split('/').pop()!)));
    },
    relatedTests: (related: string[]) => {
      if (o.graphThrows) throw new Error('the import-graph process exited 137');
      calls.handed = related;
      return o.selected ?? ['components/a/__tests__/A.test.tsx', '__tests__/i18n-locale-parity.test.ts'];
    },
    reportPath: REPORT,
    readJson: (path: string) => {
      expect(path).toBe(REPORT);
      if (o.noReport) throw new Error('ENOENT: no such file');
      const reported = lastRun.filter((f) => !(o.unreported ?? []).includes(f));
      return {
        testResults: reported.map((f) => ({ name: `${ROOT}/${f}`, status: (o.failed ?? []).includes(f) ? 'failed' : 'passed' })),
      };
    },
  };
  return { deps, calls, logs, summaries };
}

describe('off a pull request, the whole suite exactly as before', () => {
  it.each(['push', 'workflow_dispatch', ''])('event %s runs `vitest run --shard=<i>/<n>` and never asks git', async (event) => {
    const f = fake({ event, diff: new Error('git must not be asked') });
    expect(await run(f.deps)).toBe(0);
    expect(f.calls.spawn).toEqual([['run', '--shard=1/2']]);
    expect(f.summaries.join()).toContain('FULL RUN because the event is');
  });

  it("passes vitest's own exit code through", async () => {
    expect(await run(fake({ event: 'push', status: 1 }).deps)).toBe(1);
  });

  it('refuses a malformed shard argument', async () => {
    for (const argv of [[], ['--shard=3/2'], ['--shard=1/2', 'lib/']]) {
      expect(await run(fake({ argv }).deps), JSON.stringify(argv)).toBe(2);
    }
  });
});

describe('a pull request the selection is unsure of runs the whole suite, sharded', () => {
  it('when the diff cannot be read', async () => {
    const f = fake({ diff: new Error('no base commit') });
    expect(await run(f.deps)).toBe(0);
    expect(f.calls.spawn).toEqual([['run', '--shard=1/2']]);
    expect(f.summaries.join()).toContain('could not be read');
  });

  it('when a rule of the selection says so (a locale file)', async () => {
    const f = fake({ diff: 'M\tfrontend/messages/fr.json\n' });
    await run(f.deps);
    expect(f.calls.spawn).toEqual([['run', '--shard=1/2']]);
  });

  it('when the selection is over half the suite', async () => {
    const f = fake({ selected: Object.keys(TESTS).slice(0, 12) });
    await run(f.deps);
    expect(f.calls.spawn).toEqual([['run', '--shard=1/2']]);
    expect(f.summaries.join()).toContain('12 of 23 files');
  });
});

describe('a selection that breaks runs the WHOLE suite unsharded in that shard', () => {
  it('when the import-graph process fails (killed, out of memory, crashed)', async () => {
    const f = fake({ graphThrows: true });
    expect(await run(f.deps)).toBe(0);
    expect(f.calls.spawn).toEqual([['run']]);
    expect(f.logs.join()).toContain('::warning title=Frontend selection failed::the import-graph process exited 137');
    expect(f.summaries.join()).toContain('unsharded');
  });
});

describe('the selected run', () => {
  it('hands the graph the changed files, the graph holes (a require() user) and the always-run files', async () => {
    const f = fake();
    await run(f.deps);
    expect(f.calls.handed).toEqual(expect.arrayContaining([
      `${ROOT}/components/a/A.tsx`, `${ROOT}/components/fleet/actions.ts`, `${ROOT}/__tests__/i18n-locale-parity.test.ts`,
    ]));
  });

  it('runs its half in a fresh vitest with a JSON report, the files as its only filters', async () => {
    const f = fake();
    expect(await run(f.deps)).toBe(0);
    // Sorted, every other file: shard 1 of 2 takes the first.
    expect(f.calls.spawn).toEqual([['run', '--reporter=default', '--reporter=json', `--outputFile.json=${REPORT}`,
      '__tests__/i18n-locale-parity.test.ts']]);
  });

  it('keeps the github-actions reporter (inline failure annotations) under GitHub Actions', async () => {
    const f = fake();
    f.deps.githubActions = true;
    await run(f.deps);
    expect(f.calls.spawn[0].slice(0, 4)).toEqual(['run', '--reporter=default', '--reporter=github-actions', '--reporter=json']);
  });

  it('an empty half passes without starting vitest', async () => {
    const f = fake({ argv: ['--shard=2/2'], selected: ['components/a/__tests__/A.test.tsx'] });
    expect(await run(f.deps)).toBe(0);
    expect(f.calls.spawn).toEqual([]);
    expect(f.logs.join()).toContain('the other shards run the whole selection');
  });

  it('a failed file fails the shard, even if vitest exited 0', async () => {
    expect(await run(fake({ failed: ['__tests__/i18n-locale-parity.test.ts'] }).deps)).toBe(1);
  });

  it("vitest's non-zero exit fails the shard", async () => {
    expect(await run(fake({ status: 1 }).deps)).toBe(1);
  });

  it('a selected file that reported nothing fails the shard, by name', async () => {
    const f = fake({ unreported: ['__tests__/i18n-locale-parity.test.ts'] });
    expect(await run(f.deps)).toBe(1);
    expect(f.logs.join()).toContain('::error title=Vitest file did not run::__tests__/i18n-locale-parity.test.ts');
  });

  it('a missing or unreadable report fails the shard', async () => {
    const f = fake({ noReport: true });
    expect(await run(f.deps)).toBe(1);
    expect(f.logs.join()).toContain('::error title=Vitest report unreadable::');
  });
});

describe('the real wiring (defaultDeps): the graph process, the vitest CLI and its JSON report', () => {
  // The fakes above cannot see a typo in the spawn of the graph process, its --graph dispatch, the
  // --outputFile.json syntax, or the path form of the report's file names; these run the real ones.
  it('the graph child process starts, answers and exits (an empty change selects nothing)', () => {
    const deps = defaultDeps();
    try {
      expect(deps.relatedTests([])).toEqual([]);
    } finally {
      deps.cleanup();
    }
  }, 120_000);

  it('a one-file vitest run writes a JSON report that names the file as the selection names it', () => {
    const deps = defaultDeps();
    const file = 'lib/utils/__tests__/jwtShape.test.ts';
    try {
      const status = deps.spawnVitest(['run', '--reporter=json', `--outputFile.json=${deps.reportPath}`, file]);
      const report = readReport(deps.readJson(deps.reportPath), deps.root);
      expect(status).toBe(0);
      expect([...report.ran]).toEqual([file]);
      expect(report.failed).toEqual([]);
    } finally {
      deps.cleanup();
    }
  }, 120_000);
});

describe('readReport', () => {
  it('reads the files that ran and those that did not pass, frontend-relative', () => {
    const out = readReport({ testResults: [
      { name: `${ROOT}/a/x.test.ts`, status: 'passed' },
      { name: `${ROOT}/b/y.test.ts`, status: 'failed' },
    ] }, ROOT);
    expect([...out.ran].sort()).toEqual(['a/x.test.ts', 'b/y.test.ts']);
    expect(out.failed).toEqual(['b/y.test.ts']);
    expect(readReport({}, ROOT).ran.size).toBe(0);
  });
});
