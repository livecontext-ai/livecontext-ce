// Which frontend vitest files a pull request runs: the decision, without vitest (ci-vitest.mjs asks
// vitest for the import graph and runs the result). Pure functions, so every rule is pinned by
// frontend/__tests__/ci-vitest-select.test.ts.
//
// A push to main and a manual run take the WHOLE suite (ci-vitest.mjs never calls this for them).
// A pull request runs the test files whose import graph reaches a changed file (`vitest related`),
// plus the ALWAYS-RUN files: the ones that read files instead of importing them (node:fs,
// child_process, globs, `?raw`), which the import graph cannot see, and the guards named for what
// they guard (parity, contract, wiring, invariant, guard, ci-). Anything the graph cannot see
// runs the whole suite instead: the vitest configuration and setup, the dependencies, the locale
// files (next-intl also loads them through a template import the graph does not follow), shared
// test helpers and mocks, a deleted or renamed source file, the CI configuration, and a selection
// so large that a full run costs little more. Never fewer by mistake: uncertain means everything.

/** [pattern, why]: a changed path matching one runs the whole suite. Paths are repository-relative. */
export const FULL_RUN_TRIGGERS = [
  [/^frontend\/vitest\.[^/]+$/, 'the vitest configuration or setup changed'],
  [/^frontend\/vitest\.stubs\//, 'a vitest stub changed'],
  [/^frontend\/package(-lock)?\.json$/, 'the frontend dependencies changed'],
  [/^frontend\/tsconfig[^/]*\.json$/, 'the TypeScript configuration changed'],
  [/^frontend\/(next|postcss|tailwind)\.config\.[^/]+$/, 'a build configuration changed'],
  [/^frontend\/messages\//, 'a locale file changed (next-intl also loads them through a template import the import graph does not follow)'],
  [/^frontend\/scripts\/ci-vitest/, 'the frontend test selection itself changed'],
  [/(^|\/)__mocks__\//, 'a shared mock changed'],
  [/(^|\/)(test-?utils|test-?helpers|setup-?tests)[^/]*(\/|$)/i, 'a shared test helper changed'],
  [/^\.github\//, 'the CI workflows changed'],
  [/^scripts\/ci\//, 'the CI scripts changed'],
  [/^deploy\/scripts\/ci-select-jobs/, 'the CI job selector changed'],
  [/^package(-lock)?\.json$/, 'a root build file changed'],
];

/** A vitest file, by the include rule of frontend/vitest.config.ts (*.test.ts, *.test.tsx). */
export const isTestFile = (path) => /\.test\.tsx?$/.test(path);

/** Code that reads files rather than importing them: invisible to the import graph. */
export const READS_FILES = new RegExp([
  String.raw`from\s+['"](?:node:)?(?:fs|fs\/promises|child_process|path|url|module)['"]`,
  String.raw`require\(\s*['"](?:node:)?(?:fs|fs\/promises|child_process|path)['"]\s*\)`,
  String.raw`import\(\s*['"](?:node:)?(?:fs|fs\/promises|child_process)['"]\s*\)`,
  String.raw`import\.meta\.(?:glob|url|dirname|filename)`,
  String.raw`\b(?:readFileSync|readdirSync|existsSync|statSync|globSync|execSync|execFileSync|spawnSync)\b`,
  String.raw`\bprocess\.cwd\(\)`,
  String.raw`\b__dirname\b`,
  String.raw`\?raw['"]`,
].join('|'));

/**
 * A source file that loads a local module with CommonJS `require()`: vitest's related graph does
 * not follow that edge, so a change to the required module would not reach the tests of the file
 * that requires it. Such a file is handed to vitest as if it had changed, on every pull request.
 */
export const REQUIRES_LOCAL = /\brequire\(\s*['"](?:\.{1,2}\/|@\/)/;

/** The non-test files among `sources` ({path: text}) that `require()` a local module. */
export function requireUsers(sources) {
  return Object.keys(sources).filter((p) => !isTestFile(p) && REQUIRES_LOCAL.test(sources[p])).sort();
}

/** Source code that reads files at run time: what it reads is no import, so the graph cannot see it. */
export const SOURCE_READS_FILES = new RegExp([
  String.raw`from\s+['"](?:node:)?(?:fs|fs\/promises)['"]`,
  String.raw`require\(\s*['"](?:node:)?(?:fs|fs\/promises)['"]\s*\)`,
  String.raw`import\(\s*['"](?:node:)?(?:fs|fs\/promises)['"]\s*\)`,
  String.raw`\b(?:readFileSync|readdirSync|readFile|readdir)\s*\(`,
  String.raw`import\.meta\.glob`,
].join('|'));

/**
 * The non-test sources whose dependencies the import graph cannot follow: each is handed to vitest
 * as if it had changed, on EVERY pull request, so the tests that import it always run. A file that
 * reads files at run time (what it reads is no module at all), and a file that require()s a local
 * module. The latter cannot be narrowed to "the change touches a required module": the graph misses
 * the require() edge AND everything behind it (what the required module imports, and so on), so a
 * change deep under a required panel would go unseen (audit of 2026-09-30).
 */
export function graphHoles(sources) {
  const readers = Object.keys(sources).filter((p) => !isTestFile(p) && SOURCE_READS_FILES.test(sources[p]));
  return [...new Set([...requireUsers(sources), ...readers])].sort();
}

/** Guards named for what they guard: always run, whatever changed. */
export const GUARD_NAME = /(parity|contract|wiring|invariant|guard|(^|\/)ci-)[^/]*\.test\.tsx?$/i;

/**
 * The files among `tests` ({path: source text}, frontend-relative paths) that always run:
 * {path: why}.
 */
export function alwaysRun(tests) {
  const out = {};
  for (const [path, text] of Object.entries(tests)) {
    if (READS_FILES.test(text)) out[path] = 'reads files the import graph cannot see';
    else if (GUARD_NAME.test(path)) out[path] = 'a guard, run on every change';
  }
  return out;
}

/**
 * The decision for a pull request's `changes` ([{status, path}], repository-relative, from
 * `git diff --no-renames --name-status`): {mode: 'full', reason} or {mode: 'related', related},
 * `related` being the frontend-relative paths to hand vitest as related sources.
 */
export function decide(changes) {
  if (!changes || changes.length === 0) return { mode: 'full', reason: 'the diff lists no file' };
  const related = [];
  for (const { status, path } of changes) {
    const trigger = FULL_RUN_TRIGGERS.find(([re]) => re.test(path));
    if (trigger) return { mode: 'full', reason: `${trigger[1]} (${path})` };
    const inFrontend = path.startsWith('frontend/');
    const rel = inFrontend ? path.slice('frontend/'.length) : `../${path}`;
    if (inFrontend && /(^|\/)__tests__\//.test(rel) && !isTestFile(rel)) {
      return { mode: 'full', reason: `a shared test file that is not a test changed (${path})` };
    }
    if (status !== 'A' && status !== 'M') {
      // A deleted file leaves nothing for the graph to follow, so the tests that still import it
      // could not be found; a deleted test is simply gone. Backend files are never imported by a
      // frontend test, so deleting one says nothing about the frontend suite.
      if (!isTestFile(rel) && !path.startsWith('backend/')) {
        return { mode: 'full', reason: `a file was deleted or changed type (${path})` };
      }
      continue;
    }
    related.push(rel);
  }
  return { mode: 'related', related };
}

/** Above this share of the suite, the selection runs everything: the graph saved nothing. */
export const MAX_SHARE = 0.5;

/** Whether a selection of `selected` files out of `total` is too large to be worth selecting. */
export const tooLarge = (selected, total) => total === 0 || selected > total * MAX_SHARE;

/**
 * This shard's part of `files`: sorted, every n-th, so the shards of one run are disjoint and
 * together cover every file, whatever vitest's own --shard would do.
 */
export function shardOf(files, index, total) {
  return [...files].sort().filter((_, i) => i % total === index - 1);
}

/**
 * The whole suite's command line after `vitest`, for a `<i>/<n>` shard: exactly what main ran
 * before the selection existed. No positional argument: one would be a substring FILTER on the
 * test path, and vitest.config.ts `include` must stay the selection of a full run.
 */
export const fullRunArgs = (shard) => (shard ? ['run', `--shard=${shard}`] : ['run']);

/** `--shard=<i>/<n>` -> [i, n], or null when it is not exactly that. */
export function parseShard(arg) {
  const m = /^--shard=(\d+)\/(\d+)$/.exec(arg ?? '');
  if (!m) return null;
  const [i, n] = [Number(m[1]), Number(m[2])];
  return i >= 1 && i <= n ? [i, n] : null;
}

/** `git diff --no-renames --name-status` output -> [{status, path}]. */
export function parseNameStatus(text) {
  return text.split('\n').map((l) => l.replace(/\r$/, '')).filter(Boolean).map((line) => {
    const [status, ...rest] = line.split('\t');
    return { status: status.charAt(0), path: rest.join('\t') };
  });
}
