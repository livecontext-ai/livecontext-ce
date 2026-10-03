// The frontend unit-test step of ci.yml's contracts job (one run per shard of its matrix):
//   node scripts/ci-vitest.mjs --shard=<i>/<n>      (from frontend/, EVENT = the GitHub event)
//
// A push to main, a manual run, and any pull request the selection is unsure of: the WHOLE suite,
// exactly as before, `vitest run --shard=<i>/<n>` with no filter (vitest.config.ts `include` is
// the selection, --shard splits it). That path never narrows. Those decisions (the event, the
// diff, ci-vitest-select.mjs's rules, the size of the selection) come out the same in every shard,
// so the parts of vitest's own split still cover the suite.
// A pull request otherwise: the files vitest's own import graph relates to the changed files
// (`related`), plus the always-run files (ci-vitest-select.mjs says which and why). This shard
// runs every n-th of them, and fails unless each one it was given reported a result: a file that
// did not run is not a file that passed. If the selection itself breaks in one shard (an error the
// other shards may not meet), that shard runs the WHOLE suite unsharded: its part of vitest's split
// beside the other shards' parts of the selection would leave files in none. The job summary
// says what ran and why, or "FULL RUN because ...".
//
// Two processes, never one. Building the import graph transforms the whole suite's imports and
// holds ~0.7 GiB; the test pool then starts one fork per core. In ONE process the two add up, and on
// the 3.5 GiB arc-ci runner that killed the runner outright ("lost communication", 2026-09-30, both
// shards of two probe PRs). So the graph is built by a child process that exits (`--graph <in>
// <out>`), and the selected files then run in a fresh `vitest run` exactly like a full run's, their
// results read back from its JSON report.
//
// `run(deps)` takes everything it touches (the graph, the vitest CLI, git, the file tree, the
// summary) as parameters, so __tests__/ci-vitest-runner.test.ts drives every path with fakes.
import { spawnSync, execFileSync } from 'node:child_process';
import { appendFileSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  alwaysRun, decide, fullRunArgs, graphHoles, isTestFile, parseNameStatus, parseShard, shardOf, tooLarge,
} from './ci-vitest-select.mjs';

const FRONTEND = resolve(import.meta.dirname, '..');
const SELF = fileURLToPath(import.meta.url);
const toPosix = (p) => p.split(sep).join('/');
// Build output and dependencies anywhere; Playwright's tree only at the root (a nested `e2e/` folder
// of vitest files is still part of the suite).
const SKIP_ANYWHERE = ['node_modules', '.next', 'dist', 'coverage', '.git'];

/** {frontend-relative path: source} of the files under frontend/ that `keep` accepts. */
export function readSources(keep, root = FRONTEND, dir = root, out = {}) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_ANYWHERE.includes(entry.name) || (dir === root && entry.name === 'e2e')) continue;
    const path = join(dir, entry.name);
    if (entry.isDirectory()) readSources(keep, root, path, out);
    else if (keep(entry.name)) out[toPosix(relative(root, path))] = readFileSync(path, 'utf8');
  }
  return out;
}

/**
 * The files of a `vitest run` JSON report ({testResults: [{name, status}]}), frontend-relative:
 * {ran: Set, failed: [path]}.
 */
export function readReport(report, root) {
  const results = report?.testResults ?? [];
  const rel = (p) => toPosix(relative(root, p));
  return {
    ran: new Set(results.map((r) => rel(r.name))),
    failed: results.filter((r) => r.status !== 'passed').map((r) => rel(r.name)),
  };
}

/** The graph step, in its own process: the related test files of `related` (absolute paths). */
async function graphMain(input, output) {
  const related = JSON.parse(readFileSync(input, 'utf8'));
  const { createVitest } = await import('vitest/node');
  const ctx = await createVitest('test', { watch: false, run: true, related });
  try {
    const specs = await ctx.getRelevantTestSpecifications();
    writeFileSync(output, JSON.stringify(specs.map((s) => toPosix(relative(FRONTEND, s.moduleId)))));
  } finally {
    await ctx.close();
  }
}

export function defaultDeps() {
  // vitest's own entry, run by this node with no shell: a selected run hands it one path per file,
  // and through a shell (cmd.exe caps a line at 8191 characters) that list broke on Windows.
  const vitest = join(FRONTEND, 'node_modules', 'vitest', 'vitest.mjs');
  const work = mkdtempSync(join(tmpdir(), 'ci-vitest-'));
  return {
    argv: process.argv.slice(2),
    event: process.env.EVENT || '',
    githubActions: process.env.GITHUB_ACTIONS === 'true',
    root: FRONTEND,
    log: (line) => console.log(line),
    summary: (text) => {
      console.log(text);
      if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${text}\n`);
    },
    spawnVitest: (args) => spawnSync(process.execPath, [vitest, ...args], { cwd: FRONTEND, stdio: 'inherit' }).status ?? 1,
    // core.quotePath=false: git quotes a non-ASCII path by default ("frontend/Caf\303\251.tsx"),
    // which would no longer name the file and select nothing for it.
    gitDiff: () => execFileSync('git', ['-c', 'core.quotePath=false', 'diff', '--no-renames', '--name-status', 'HEAD^1', 'HEAD'],
      { cwd: FRONTEND, encoding: 'utf8', maxBuffer: 64 << 20 }),
    readSources: (keep) => readSources(keep),
    relatedTests: (related) => {
      const input = join(work, 'related.json');
      const output = join(work, 'selected.json');
      writeFileSync(input, JSON.stringify(related));
      const child = spawnSync(process.execPath, [SELF, '--graph', input, output], { cwd: FRONTEND, stdio: 'inherit' });
      if (child.status !== 0) throw new Error(`the import-graph process exited ${child.status ?? child.signal}`);
      return JSON.parse(readFileSync(output, 'utf8'));
    },
    reportPath: join(work, 'report.json'),
    readJson: (path) => JSON.parse(readFileSync(path, 'utf8')),
    cleanup: () => rmSync(work, { recursive: true, force: true }),
  };
}

/** Runs the step with `deps` (defaultDeps() in CI); resolves to its exit code. */
export async function run(deps) {
  const { log, summary } = deps;

  /** The whole suite, this shard's part of it (or all of it when `shard` is null); its exit code. */
  const fullRun = (shard, reason) => {
    const scope = shard ? "this shard's part" : 'unsharded, the whole suite in this shard';
    summary(`### Frontend unit tests, shard ${shard ?? 'fallback'}\n\n**FULL RUN because ${reason}.** Every vitest file, ${scope}.\n`);
    return deps.spawnVitest(fullRunArgs(shard));
  };

  /**
   * The selection: this shard's files ({mine, ...} for the summary), or a string saying why the
   * whole suite runs instead (a reason every shard reaches alike). Throws when it breaks.
   */
  const select = (shard, index, total, related) => {
    const tests = deps.readSources(isTestFile);
    const always = alwaysRun(tests);
    const holes = graphHoles(deps.readSources((n) => /\.(ts|tsx|js|mjs|cjs)$/.test(n) && !isTestFile(n)));
    const handed = [...new Set([...related, ...holes, ...Object.keys(always)])];
    const files = deps.relatedTests(handed.map((p) => toPosix(resolve(deps.root, p))));
    const suite = Object.keys(tests).length;
    if (tooLarge(files.length, suite)) {
      return `the selection holds ${files.length} of ${suite} files, a full run costs little more`;
    }
    const alwaysCount = files.filter((f) => always[f]).length;
    const mine = shardOf(files, index, total);
    summary([
      `### Frontend unit tests, shard ${shard}`, '',
      `Changed files handed to vitest as related sources: ${related.length}.`,
      ...related.slice(0, 30).map((f) => `- \`${f}\``), related.length > 30 ? `- ... and ${related.length - 30} more` : '',
      '', `Also handed over on every pull request: ${holes.length} source file(s) whose dependencies the graph cannot `
        + `follow (require() of a local module, or files read at run time): ${holes.map((f) => `\`${f}\``).join(', ') || 'none'}.`,
      `Selected: ${files.length} of ${suite} files (${files.length - alwaysCount} through the import graph, `
        + `${alwaysCount} always-run: they read files the graph cannot see, or guard the whole tree). `
        + `This shard runs ${mine.length} of them.`, '',
    ].join('\n'));
    return { mine };
  };

  /** Runs `mine` in a fresh vitest, then checks every one of them reported; its exit code. */
  const runSelected = (shard, mine) => {
    if (mine.length === 0) {
      log('Nothing for this shard: the other shards run the whole selection.');
      return 0;
    }
    // Each path is a positional FILTER (a substring of the file path): it selects the file itself,
    // and at most a few more whose path contains it (`x.test.ts` in `x.test.tsx`): more, never fewer.
    // Naming reporters drops the github-actions one vitest adds by itself in CI (inline failure
    // annotations on the PR), so it is named too when running under GitHub Actions.
    const reporters = ['--reporter=default', ...(deps.githubActions ? ['--reporter=github-actions'] : []), '--reporter=json'];
    const status = deps.spawnVitest(['run', ...reporters, `--outputFile.json=${deps.reportPath}`, ...mine]);
    let report;
    try {
      report = readReport(deps.readJson(deps.reportPath), deps.root);
    } catch (e) {
      log(`::error title=Vitest report unreadable::${String(e?.message ?? e).split('\n')[0]}`);
      return 1;
    }
    const missing = mine.filter((f) => !report.ran.has(f));
    for (const f of missing) log(`::error title=Vitest file did not run::${f} was selected and reported no result`);
    summary(`Shard ${shard}: ${report.ran.size} files ran, ${report.failed.length} failed, ${missing.length} of the `
      + `${mine.length} selected reported nothing, vitest exited ${status}.\n`);
    return status !== 0 || missing.length || report.failed.length ? 1 : 0;
  };

  const shard = parseShard(deps.argv[0]);
  if (!shard || deps.argv.length !== 1) {
    log('usage: node scripts/ci-vitest.mjs --shard=<i>/<n>');
    return 2;
  }
  const label = `${shard[0]}/${shard[1]}`;
  if (deps.event !== 'pull_request') {
    return fullRun(label, `the event is ${deps.event || 'not given'}: a push to main and a manual run always run every test`);
  }
  let changes;
  try {
    changes = parseNameStatus(deps.gitDiff());
  } catch (e) {
    return fullRun(label, `the diff against the base commit could not be read (${String(e?.message ?? e).split('\n')[0]})`);
  }
  const decision = decide(changes);
  if (decision.mode === 'full') return fullRun(label, decision.reason);
  let selection;
  try {
    selection = select(label, shard[0], shard[1], decision.related);
  } catch (e) {
    const why = String(e?.message ?? e).split('\n')[0];
    log(`::warning title=Frontend selection failed::${why}`);
    return fullRun(null, `the selection failed in this shard (${why})`);
  }
  if (typeof selection === 'string') return fullRun(label, selection);
  return runSelected(label, selection.mine);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (process.argv[2] === '--graph') {
    await graphMain(process.argv[3], process.argv[4]);
  } else {
    const deps = defaultDeps();
    try {
      process.exitCode = await run(deps);
    } finally {
      deps.cleanup();
    }
  }
}
