/**
 * Behavioural tests for the two shell watchdogs that run as ROOT, unattended,
 * and repoint the binary the product depends on: mcp/cli-health-state.sh and
 * mcp/cli-update.sh.
 *
 * They are driven for real - bash executes the actual scripts - against a
 * temporary tree with stub `claude`, `codex`, `npm` and `su` on PATH. Nothing
 * here mocks the script's own logic; only the world around it.
 *
 * Why this file exists at all: every terminal path of the rollback state machine
 * decides which build production runs tomorrow morning, and the failure shapes
 * are all silent (a dangling symlink, a version reported current because nothing
 * checked, a rollback triggered by an unrelated provider outage). There is no
 * observable difference between "the update worked" and "the update quietly did
 * nothing" other than these assertions.
 *
 * Portability: symlink creation is required for the Claude paths. On Windows
 * without developer mode `ln -s` copies instead of linking, so those cases are
 * skipped there. CI runs this suite on Linux, where nothing is skipped.
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, mkdirSync, writeFileSync, readFileSync, existsSync, lstatSync, chmodSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const MCP_DIR = resolve(__dirname, '..', '..');

const BASH = process.platform === 'win32' ? 'bash' : '/bin/bash';

function haveBash() {
  const r = spawnSync(BASH, ['-c', 'echo ok'], { encoding: 'utf8' });
  return r.status === 0 && r.stdout.trim() === 'ok';
}

function haveSymlinks(dir) {
  try {
    writeFileSync(join(dir, 'lnsrc'), 'x');
    execFileSync(BASH, ['-c', `ln -sfn "${toPosix(join(dir, 'lnsrc'))}" "${toPosix(join(dir, 'lndst'))}"`]);
    return lstatSync(join(dir, 'lndst')).isSymbolicLink();
  } catch {
    return false;
  }
}

/** Git Bash needs POSIX paths; on Linux this is a no-op. */
function toPosix(p) {
  return p.replace(/^([A-Za-z]):\\/, (_m, d) => `/${d.toLowerCase()}/`).replace(/\\/g, '/');
}

const BASH_AVAILABLE = haveBash();

/** A throwaway world: bin/ with stubs, a state dir, a log. */
function makeWorld() {
  const root = mkdtempSync(join(tmpdir(), 'cli-update-'));
  const bin = join(root, 'bin');
  const state = join(root, 'state');
  mkdirSync(bin, { recursive: true });
  mkdirSync(state, { recursive: true });

  const write = (name, body) => {
    const p = join(bin, name);
    writeFileSync(p, `#!/bin/bash\n${body}\n`);
    chmodSync(p, 0o755);
    return p;
  };

  // `su -s /bin/bash <user> -c "<cmd>"` -> just run the command. This keeps the
  // real as_lc() call shape under test instead of stubbing the function out.
  write('su', `
args=("$@")
for ((i=0; i<\${#args[@]}; i++)); do
  if [[ "\${args[$i]}" == "-c" ]]; then
    exec /bin/bash -c "\${args[$((i+1))]}"
  fi
done
exit 64`);
  write('logger', 'exit 0');

  return { root, bin, state, write, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

/**
 * LC_PATH is the PATH the script exports inside `su -c`, so it must be a POSIX
 * list. Handing it the host PATH verbatim looks harmless and is not: on Windows
 * that is `C:\...;C:\...`, which MSYS rewrites for PATH but NOT for another
 * variable, so the inner shell loses awk/cat/basename. The symptom is subtle -
 * `codex --version` still resolves (the stub dir is first), the awk that parses
 * its output does not, the version reads empty, and the script takes the UPDATE
 * branch for a CLI that is already current. A test harness that quietly changes
 * which branch runs is worse than no test.
 */
const POSIX_PATH = '/usr/local/bin:/usr/bin:/bin';

/** Run a script with the world's stubs first on PATH. */
function runScript(world, script, env = {}) {
  const res = spawnSync(BASH, [toPosix(join(MCP_DIR, script))], {
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${toPosix(world.bin)}:${process.env.PATH}`,
      LC_PATH: `${toPosix(world.bin)}:${POSIX_PATH}`,
      CLI_STATE_DIR: toPosix(world.state),
      LOG: toPosix(join(world.root, 'cli-update.log')),
      // Production waits 30s before retrying a failed smoke test, which is right
      // there and would add minutes to this suite for nothing: the retry is
      // exercised either way, only the waiting is skipped.
      SMOKE_RETRY_DELAY: '0',
      ...env,
    },
  });
  return { ...res, log: readIfExists(join(world.root, 'cli-update.log')) };
}

function readIfExists(p) {
  try { return readFileSync(p, 'utf8'); } catch { return ''; }
}

function readState(world, file) {
  return JSON.parse(readFileSync(join(world.state, file), 'utf8'));
}

/** Source cli-health-state.sh and call one of its writers. */
function callWriter(world, snippet) {
  const script = `
set -u
CLI_STATE_DIR="${toPosix(world.state)}"
. "${toPosix(join(MCP_DIR, 'cli-health-state.sh'))}"
${snippet}
`;
  return spawnSync(BASH, ['-c', script], { encoding: 'utf8' });
}

describe('cli-health-state.sh', { skip: BASH_AVAILABLE ? false : 'bash unavailable' }, () => {
  test('writes an auth state file the metric renderer can read', () => {
    const w = makeWorld();
    try {
      const r = callWriter(w, 'write_cli_auth_state claudeCode 1 0 "ok"');
      assert.equal(r.status, 0, r.stderr);
      const s = readState(w, 'claudeCode.auth.json');
      assert.equal(s.cli, 'claudeCode');
      assert.equal(s.dead, false);
      assert.ok(s.lastSuccessAt > 0 && s.lastSuccessAt === s.lastAttemptAt);
    } finally { w.cleanup(); }
  });

  test('a FAILED run never clobbers lastSuccessAt', () => {
    // The heartbeat is what the staleness alert subtracts from time(). Resetting
    // it on a transient network blip would page for a login that is not dead -
    // the exact false-critical class this whole change removes.
    const w = makeWorld();
    try {
      callWriter(w, 'write_cli_auth_state codex 1 0 "ok"');
      const before = readState(w, 'codex.auth.json').lastSuccessAt;
      callWriter(w, 'write_cli_auth_state codex 0 1 "auth dead"');
      const after = readState(w, 'codex.auth.json');
      assert.equal(after.lastSuccessAt, before, 'the success timestamp must survive a failure');
      assert.equal(after.dead, true);
      assert.ok(after.lastAttemptAt >= before, 'the attempt timestamp must move');
    } finally { w.cleanup(); }
  });

  test('a first-ever FAILED run reports never-observed rather than inventing a success', () => {
    const w = makeWorld();
    try {
      callWriter(w, 'write_cli_auth_state codex 0 1 "auth dead"');
      assert.equal(readState(w, 'codex.auth.json').lastSuccessAt, 0);
    } finally { w.cleanup(); }
  });

  test('upToDate is DERIVED, so no caller can report "current" while naming two versions', () => {
    const w = makeWorld();
    try {
      callWriter(w, 'write_cli_update_state codex "0.154.0" "0.155.0" none "lying"');
      assert.equal(readState(w, 'codex.update.json').upToDate, false);

      callWriter(w, 'write_cli_update_state codex "0.155.0" "0.155.0" none "honest"');
      assert.equal(readState(w, 'codex.update.json').upToDate, true);
    } finally { w.cleanup(); }
  });

  test('an unknown latest version is NOT up to date', () => {
    // "the registry did not answer" must not render as "we are current".
    const w = makeWorld();
    try {
      callWriter(w, 'write_cli_update_state codex "0.154.0" "" failed "registry unreachable"');
      const s = readState(w, 'codex.update.json');
      assert.equal(s.upToDate, false);
      assert.equal(s.result, 'failed');
    } finally { w.cleanup(); }
  });

  test('a CLI with NO version on either side is not "up to date"', () => {
    // The binary-missing branch writes empty strings for both versions, and
    // `installed == latest` alone makes "" == "" true - so a CLI that has
    // vanished from the box would report lc_cli_update_up_to_date 1 and
    // AgentCliOutOfDate would stay silent about it. That branch executes on
    // every run of this suite and was asserted by nothing.
    const w = makeWorld();
    try {
      callWriter(w, 'write_cli_update_state claudeCode "" "" failed "binary missing"');
      const st = readState(w, 'claudeCode.update.json');
      assert.equal(st.upToDate, false, 'two unknowns are not a match');
      assert.equal(st.result, 'failed');
    } finally { w.cleanup(); }
  });

  test('writes are atomic and leave no .tmp behind for a scrape to read', () => {
    const w = makeWorld();
    try {
      callWriter(w, 'write_cli_auth_state claudeCode 1 0 "ok"');
      const leftovers = readdirSync(w.state).filter((f) => f.endsWith('.tmp'));
      assert.deepEqual(leftovers, [], 'a .tmp left behind means a reader can catch a half-written file');
    } finally { w.cleanup(); }
  });

  test('the writers do not clobber the variables of the script that sourced them', () => {
    // They are SOURCED, so an unscoped assignment lands in the calling script's
    // namespace - and cli-update.sh holds live values in `latest` and `result`
    // while it calls write_cli_update_state. Nothing is corrupted today; this
    // pins that, because the failure would be a state file quietly naming the
    // wrong version.
    const w = makeWorld();
    try {
      const r = callWriter(w, [
        'latest="0.155.0"; result="updated"; cli_id="caller"; detail="mine"',
        'write_cli_update_state codex "0.154.0" "0.154.0" none "inner"',
        'write_cli_auth_state codex 1 0 "inner"',
        'echo "latest=$latest result=$result cli_id=$cli_id detail=$detail"',
      ].join('\n'));
      assert.equal(r.status, 0, r.stderr);
      assert.match(r.stdout, /latest=0\.155\.0 result=updated cli_id=caller detail=mine/);
    } finally { w.cleanup(); }
  });

  test('detail is bounded, so a stack trace cannot become the state file', () => {
    const w = makeWorld();
    try {
      callWriter(w, `write_cli_auth_state codex 0 1 "$(printf 'x%.0s' {1..2000})"`);
      assert.ok(readState(w, 'codex.auth.json').detail.length <= 500);
    } finally { w.cleanup(); }
  });
});

describe('cli-update.sh - Codex paths', { skip: BASH_AVAILABLE ? false : 'bash unavailable' }, () => {
  /** A world where claude is absent (its own case is covered below). */
  function codexWorld({ installed, latest, smokeExit = 0, npmExit = 0, installedAfter }) {
    const w = makeWorld();
    const versionFile = join(w.root, 'codex-version');
    writeFileSync(versionFile, installed);
    w.write('codex', `
if [[ "\${1:-}" == "--version" ]]; then echo "codex-cli $(cat "${toPosix(versionFile)}")"; exit 0; fi
exit ${smokeExit}`);
    w.write('npm', `
if [[ "\${1:-}" == "view" ]]; then echo "${latest}"; exit 0; fi
if [[ "\${1:-}" == "install" ]]; then
  # A FAILED install must leave the installed version alone, exactly as npm does.
  # Rewriting it anyway made the "npm install failed" case indistinguishable from
  # a successful one, which is the stub lying, not the script.
  if [[ ${npmExit} -eq 0 ]]; then
    ${installedAfter !== undefined ? `echo "${installedAfter}" > "${toPosix(versionFile)}"` : ''}
    # An explicit @version argument wins, so a rollback records what it asked for.
    for a in "$@"; do case "$a" in @openai/codex@*) echo "\${a#@openai/codex@}" > "${toPosix(versionFile)}";; esac; done
  fi
  exit ${npmExit}
fi
exit 0`);
    return { w, versionFile, current: () => readFileSync(versionFile, 'utf8').trim() };
  }

  function runCodexOnly(w) {
    return runScript(w, 'cli-update.sh', {
      CLAUDE_BIN: toPosix(join(w.root, 'no-such-claude')),
      CLAUDE_VERSIONS: toPosix(join(w.root, 'versions')),
      CODEX_BIN: toPosix(join(w.bin, 'codex')),
      NPM_PREFIX: toPosix(w.root),
    });
  }

  test('already current: reports none, changes nothing, spends no provider turn', () => {
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.154.0' });
    try {
      runCodexOnly(w);
      const s = readState(w, 'codex.update.json');
      assert.equal(s.result, 'none');
      assert.equal(s.upToDate, true);
      assert.equal(current(), '0.154.0');
    } finally { w.cleanup(); }
  });

  test('a registry that does not answer reports failed and NOT up to date', () => {
    // Silence from npm means the latest version is unknown, and unknown must
    // never be rendered as current - that is how a CLI drifts for months.
    const { w } = codexWorld({ installed: '0.154.0', latest: '' });
    try {
      runCodexOnly(w);
      const s = readState(w, 'codex.update.json');
      assert.equal(s.result, 'failed');
      assert.equal(s.upToDate, false);
    } finally { w.cleanup(); }
  });

  test('a junk version string from the registry is refused rather than interpolated into a shell', () => {
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '; touch /tmp/pwned' });
    try {
      runCodexOnly(w);
      assert.equal(readState(w, 'codex.update.json').result, 'failed');
      assert.equal(current(), '0.154.0', 'no install should have been attempted');
    } finally { w.cleanup(); }
  });

  test('update + passing smoke test: reports updated and stays on the new build', () => {
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.155.0', installedAfter: '0.155.0' });
    try {
      runCodexOnly(w);
      const s = readState(w, 'codex.update.json');
      assert.equal(s.result, 'updated');
      assert.equal(s.upToDate, true);
      assert.equal(current(), '0.155.0');
    } finally { w.cleanup(); }
  });

  test('npm install failing leaves the old build and reports failed', () => {
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.155.0', npmExit: 1 });
    try {
      runCodexOnly(w);
      const s = readState(w, 'codex.update.json');
      assert.equal(s.result, 'failed');
      assert.equal(s.upToDate, false);
      assert.equal(current(), '0.154.0');
    } finally { w.cleanup(); }
  });

  test('a smoke test that fails ONCE then succeeds is not treated as a broken build', () => {
    // The retry exists because a single provider blip must not pin production to
    // an old CLI and page AgentCliUpdateRolledBack at severity critical. Without
    // a test, deleting the retry is invisible: every other case passes either
    // way, because they fail consistently or succeed consistently.
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.155.0', installedAfter: '0.155.0' });
    try {
      const counter = toPosix(join(w.root, 'smoke-attempts'));
      w.write('codex', `
V=$(cat "${toPosix(join(w.root, 'codex-version'))}")
if [[ "\${1:-}" == "--version" ]]; then echo "codex-cli $V"; exit 0; fi
N=$(cat "${counter}" 2>/dev/null || echo 0)
echo $((N+1)) > "${counter}"
# First attempt fails, every later one succeeds: a transient provider blip.
[[ "$N" == "0" ]] && exit 1
exit 0`);
      const r = runCodexOnly(w);
      const s = readState(w, 'codex.update.json');
      assert.equal(s.result, 'updated', r.log);
      assert.equal(current(), '0.155.0', 'a blip must not roll production back');
      assert.ok(Number(readFileSync(join(w.root, 'smoke-attempts'), 'utf8')) >= 2, 'the retry must actually have happened');
    } finally { w.cleanup(); }
  });

  test('a new build that fails its smoke test is ROLLED BACK to the previous one', () => {
    // smokeExit 1 for every codex invocation that is not --version, so the new
    // build fails and the rolled-back old build fails too... which is the NEXT
    // test. Here the stub passes once it has been rolled back.
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.155.0', installedAfter: '0.155.0' });
    try {
      // Re-stub: the smoke test fails while 0.155.0 is installed, passes otherwise.
      w.write('codex', `
V=$(cat "${toPosix(join(w.root, 'codex-version'))}")
if [[ "\${1:-}" == "--version" ]]; then echo "codex-cli $V"; exit 0; fi
[[ "$V" == "0.155.0" ]] && exit 1
exit 0`);
      const r = runCodexOnly(w);
      const s = readState(w, 'codex.update.json');
      assert.equal(s.result, 'rolled_back', r.log);
      assert.equal(current(), '0.154.0', 'production must be back on the build that answers');
      assert.equal(s.upToDate, false, 'a rolled-back CLI is behind, and must say so');
    } finally { w.cleanup(); }
  });

  test('a build already rolled back from is NOT retried while it is still the latest', () => {
    // The runaway this closes: without it the same broken upstream build is
    // downloaded, installed, smoke-tested, and rolled back EVERY day, and
    // AgentCliUpdateRolledBack - a critical - repeats hourly forever about a
    // fact understood the first morning.
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.155.0' });
    try {
      // Yesterday's verdict.
      callWriter(w, 'write_cli_update_state codex "0.154.0" "0.155.0" rolled_back "0.155.0 failed"');
      // Count install attempts, so "did not retry" is measured, not inferred.
      const attempts = toPosix(join(w.root, 'npm-installs'));
      w.write('npm', `
if [[ "\${1:-}" == "view" ]]; then echo "0.155.0"; exit 0; fi
if [[ "\${1:-}" == "install" ]]; then N=$(cat "${attempts}" 2>/dev/null || echo 0); echo $((N+1)) > "${attempts}"; fi
exit 0`);

      const r = runCodexOnly(w);
      const st = readState(w, 'codex.update.json');
      assert.equal(st.result, 'skipped_known_bad', r.log);
      assert.equal(st.upToDate, false, 'still behind, and it must still say so');
      assert.equal(current(), '0.154.0');
      assert.ok(!existsSync(join(w.root, 'npm-installs')), 'no install should have been attempted at all');
    } finally { w.cleanup(); }
  });

  test('the skip HOLDS across consecutive runs - it does not skip every other day', () => {
    // The headline property of the guard is about consecutive runs, and the two
    // cases above each hand-write yesterday's verdict and run once - which is
    // exactly how the first implementation shipped a guard that worked every
    // OTHER day: the skip wrote result=skipped_known_bad, erasing the
    // rolled_back marker it had just acted on. Measured, four runs, upstream
    // frozen on the broken build.
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.155.0', installedAfter: '0.155.0' });
    try {
      const attempts = toPosix(join(w.root, 'npm-installs'));
      // 0.155.0 is installable but never passes its smoke test.
      w.write('codex', `
V=$(cat "${toPosix(join(w.root, 'codex-version'))}")
if [[ "\${1:-}" == "--version" ]]; then echo "codex-cli $V"; exit 0; fi
[[ "$V" == "0.155.0" ]] && exit 1
exit 0`);
      w.write('npm', `
if [[ "\${1:-}" == "view" ]]; then echo "0.155.0"; exit 0; fi
if [[ "\${1:-}" == "install" ]]; then
  N=$(cat "${attempts}" 2>/dev/null || echo 0); echo $((N+1)) > "${attempts}"
  for a in "$@"; do case "$a" in @openai/codex@*) echo "\${a#@openai/codex@}" > "${toPosix(join(w.root, 'codex-version'))}";; esac; done
fi
exit 0`);

      const timeline = [];
      for (let day = 1; day <= 4; day += 1) {
        runCodexOnly(w);
        timeline.push({
          day,
          result: readState(w, 'codex.update.json').result,
          installs: Number(readIfExists(join(w.root, 'npm-installs')).trim() || 0),
        });
      }

      assert.equal(timeline[0].result, 'rolled_back', 'day 1 discovers the bad build');
      const installsAfterDay1 = timeline[0].installs;
      for (const day of timeline.slice(1)) {
        assert.equal(day.result, 'skipped_known_bad', `day ${day.day} must keep skipping`);
        assert.equal(day.installs, installsAfterDay1,
          `day ${day.day} reinstalled the known-bad build (${day.installs} installs vs ${installsAfterDay1} after day 1)`);
      }
      assert.equal(current(), '0.154.0', 'production stays on the build that answers');
    } finally { w.cleanup(); }
  });

  test('a successful update clears the memory, so a LATER bad build is caught again', () => {
    // The marker must be bounded by success, not by a timer: carrying it past a
    // healthy update would let one ancient bad version suppress a future one.
    const w = makeWorld();
    try {
      callWriter(w, 'write_cli_update_state codex "0.154.0" "0.155.0" rolled_back "bad"');
      let r = callWriter(w, 'known_bad_version codex');
      assert.equal(r.stdout.trim(), '0.155.0', 'the rollback is remembered');

      callWriter(w, 'write_cli_update_state codex "0.156.0" "0.156.0" updated "0.154.0 -> 0.156.0"');
      r = callWriter(w, 'known_bad_version codex');
      assert.equal(r.stdout.trim(), '', 'a successful update clears it');
    } finally { w.cleanup(); }
  });

  test('a NEWER upstream build is retried normally after a rollback', () => {
    // The guard remembers ONE bad build, it is not a pause switch. The day
    // upstream publishes anything else, the freshness run resumes.
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.156.0', installedAfter: '0.156.0' });
    try {
      callWriter(w, 'write_cli_update_state codex "0.154.0" "0.155.0" rolled_back "0.155.0 failed"');
      const r = runCodexOnly(w);
      const st = readState(w, 'codex.update.json');
      assert.equal(st.result, 'updated', r.log);
      assert.equal(current(), '0.156.0');
    } finally { w.cleanup(); }
  });

  test('a previous FAILED run does not block a retry', () => {
    // Only `rolled_back` is remembered. A `failed` run may have failed for a
    // reason unrelated to the version (registry down, provider outage), and
    // skipping over that would pin the CLI for a transient.
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.155.0', installedAfter: '0.155.0' });
    try {
      callWriter(w, 'write_cli_update_state codex "0.154.0" "0.155.0" failed "npm was down"');
      const r = runCodexOnly(w);
      assert.equal(readState(w, 'codex.update.json').result, 'updated', r.log);
      assert.equal(current(), '0.155.0');
    } finally { w.cleanup(); }
  });

  test('when the OLD build fails too, the fault is not the update: stay current, report failed', () => {
    // A provider outage makes every build fail its smoke test. Pinning prod to a
    // stale CLI for that would be an outage of our own making.
    const { w, current } = codexWorld({ installed: '0.154.0', latest: '0.155.0', installedAfter: '0.155.0', smokeExit: 1 });
    try {
      const r = runCodexOnly(w);
      const s = readState(w, 'codex.update.json');
      assert.equal(s.result, 'failed', r.log);
      assert.equal(current(), '0.155.0', 'must NOT be pinned to the old build for an unrelated failure');
    } finally { w.cleanup(); }
  });
});

describe('cli-update.sh - Claude paths', { skip: BASH_AVAILABLE ? false : 'bash unavailable' }, () => {
  // npmLatest: what the registry answers for @anthropic-ai/claude-code. Claude
  // has no other source for "latest", so it is what makes AgentCliOutOfDate able
  // to fire at all - a stub that answers nothing models an UNREACHABLE registry,
  // not a healthy one, and reports "not up to date" on purpose.
  function claudeWorld({ from, to, updateExit = 0, smokeFailsOn = null, npmLatest }) {
    const w = makeWorld();
    const versions = join(w.root, 'versions');
    mkdirSync(versions, { recursive: true });
    for (const v of [from, to].filter(Boolean)) {
      writeFileSync(join(versions, v), `#!/bin/sh\necho "${v}"\n`);
      // 0755 matters: cli-update.sh gates on `[ -x "$CLAUDE_BIN" ]`, and the real
      // builds in .local/share/claude/versions are executables. A 0644 fixture
      // made every Claude case report "binary missing" instead of exercising the
      // path under test.
      chmodSync(join(versions, v), 0o755);
    }

    const link = join(w.root, 'claude-link');
    execFileSync(BASH, ['-c', `ln -sfn "${toPosix(join(versions, from))}" "${toPosix(link)}"`]);

    // The stub reads the version from whatever the symlink currently points at,
    // exactly as the real launcher does.
    w.write('claude', `
TARGET=$(readlink -f "${toPosix(link)}")
V=$(basename "$TARGET")
if [[ "\${1:-}" == "--version" ]]; then echo "$V (Claude Code)"; exit 0; fi
if [[ "\${1:-}" == "update" ]]; then
  ${to ? `ln -sfn "${toPosix(versions)}/${to}" "${toPosix(link)}"` : ''}
  exit ${updateExit}
fi
${smokeFailsOn ? `[[ "$V" == "${smokeFailsOn}" ]] && exit 1` : ''}
exit 0`);
    const published = npmLatest === undefined ? (to || from) : npmLatest;
    w.write('npm', `
if [[ "\${1:-}" == "view" ]]; then ${published ? `echo "${published}"` : 'exit 0'}; exit 0; fi
exit 0`);
    return { w, versions, link, current: () => execFileSync(BASH, ['-c', `basename "$(readlink -f "${toPosix(link)}")"`], { encoding: 'utf8' }).trim() };
  }

  function runClaudeOnly(w, link, versions) {
    return runScript(w, 'cli-update.sh', {
      CLAUDE_BIN: toPosix(link),
      CLAUDE_VERSIONS: toPosix(versions),
      CODEX_BIN: toPosix(join(w.root, 'no-such-codex')),
      NPM_PREFIX: toPosix(w.root),
    });
  }

  const probe = mkdtempSync(join(tmpdir(), 'lnprobe-'));
  const SYMLINKS = BASH_AVAILABLE && haveSymlinks(probe);
  rmSync(probe, { recursive: true, force: true });
  const skip = !BASH_AVAILABLE ? 'bash unavailable' : (!SYMLINKS ? 'symlinks unavailable on this platform' : false);

  test('a FAILED `claude update` must not be reported as "already current"', { skip }, () => {
    // This is the defect that made AgentCliOutOfDate unable to fire for Claude:
    // there is no independent "latest" for it, so an update whose exit code was
    // ignored reported latest = installed and upToDate = 1 while drifting.
    const { w, link, versions } = claudeWorld({ from: '2.1.270', to: null, updateExit: 1 });
    try {
      runClaudeOnly(w, link, versions);
      const s = readState(w, 'claudeCode.update.json');
      assert.equal(s.result, 'failed');
      assert.equal(s.upToDate, false, 'an update that failed cannot prove we are current');
      assert.equal(s.latest, '', 'latest is UNKNOWN here, not "whatever is installed"');
    } finally { w.cleanup(); }
  });

  test('Claude does not re-run the updater for a build it already rolled back from', { skip }, () => {
    // `claude update` has no dry run, so the npm registry stands in as the
    // "has upstream moved on?" check. The assertion is that the updater is not
    // invoked at all - which is what saves the download AND the smoke-test
    // turns, not just the alert noise.
    const { w, link, versions, current } = claudeWorld({ from: '2.1.270', to: '2.1.272' });
    try {
      callWriter(w, 'write_cli_update_state claudeCode "2.1.270" "2.1.272" rolled_back "2.1.272 failed"');
      const updates = toPosix(join(w.root, 'update-calls'));
      w.write('claude', `
TARGET=$(readlink -f "${toPosix(link)}")
V=$(basename "$TARGET")
if [[ "\${1:-}" == "--version" ]]; then echo "$V (Claude Code)"; exit 0; fi
if [[ "\${1:-}" == "update" ]]; then N=$(cat "${updates}" 2>/dev/null || echo 0); echo $((N+1)) > "${updates}"; ln -sfn "${toPosix(versions)}/2.1.272" "${toPosix(link)}"; exit 0; fi
exit 0`);
      w.write('npm', `
if [[ "\${1:-}" == "view" ]]; then echo "2.1.272"; exit 0; fi
exit 0`);

      const r = runClaudeOnly(w, link, versions);
      const st = readState(w, 'claudeCode.update.json');
      assert.equal(st.result, 'skipped_known_bad', r.log);
      assert.equal(current(), '2.1.270', 'must stay on the build that works');
      assert.ok(!existsSync(join(w.root, 'update-calls')), 'claude update must not have been invoked');
    } finally { w.cleanup(); }
  });

  test('a successful no-op update reports none and up to date', { skip }, () => {
    const { w, link, versions } = claudeWorld({ from: '2.1.272', to: null, updateExit: 0 });
    try {
      runClaudeOnly(w, link, versions);
      const s = readState(w, 'claudeCode.update.json');
      assert.equal(s.result, 'none');
      assert.equal(s.upToDate, true);
    } finally { w.cleanup(); }
  });

  test('an unreachable registry makes Claude report "not up to date", never "current"', { skip }, () => {
    // Claude's updater is authoritative for the install but says nothing about
    // what it was aiming at, so `latest` has to come from the registry. When the
    // registry is silent, the honest answer is "we cannot prove we are current",
    // which is what AgentCliOutOfDate acts on. Reporting latest = installed here
    // is what made that alert structurally unable to fire for Claude.
    const { w, link, versions } = claudeWorld({ from: '2.1.272', to: null, npmLatest: '' });
    try {
      runClaudeOnly(w, link, versions);
      const st = readState(w, 'claudeCode.update.json');
      assert.equal(st.result, 'none');
      assert.equal(st.latest, '', 'unknown, not echoed back');
      assert.equal(st.upToDate, false);
    } finally { w.cleanup(); }
  });

  test('Claude reports being behind when the registry has moved past it', { skip }, () => {
    const { w, link, versions } = claudeWorld({ from: '2.1.272', to: null, npmLatest: '2.1.280' });
    try {
      runClaudeOnly(w, link, versions);
      const st = readState(w, 'claudeCode.update.json');
      assert.equal(st.latest, '2.1.280');
      assert.equal(st.upToDate, false, 'installed 2.1.272 while 2.1.280 is published');
    } finally { w.cleanup(); }
  });

  test('a new build that fails its smoke test is rolled back by repointing the symlink', { skip }, () => {
    const { w, link, versions, current } = claudeWorld({ from: '2.1.270', to: '2.1.272', smokeFailsOn: '2.1.272' });
    try {
      const r = runClaudeOnly(w, link, versions);
      const s = readState(w, 'claudeCode.update.json');
      assert.equal(s.result, 'rolled_back', r.log);
      assert.equal(current(), '2.1.270');
    } finally { w.cleanup(); }
  });

  test('the rollback target is never pruned before the smoke test decides', { skip }, () => {
    const { w, link, versions, current } = claudeWorld({ from: '2.1.270', to: '2.1.272', smokeFailsOn: '2.1.272' });
    try {
      runClaudeOnly(w, link, versions);
      assert.ok(existsSync(join(versions, '2.1.270')), 'pruning before the verdict would make rollback impossible');
      assert.equal(current(), '2.1.270');
    } finally { w.cleanup(); }
  });

  test('when both builds fail, the symlink still points at a REAL file', { skip }, () => {
    // The un-rollback used to rebuild the path from a parsed version string, and
    // `ln -sfn` creates a dangling link without complaining. This branch is
    // reached on a transient provider outage, so a wrong path there would delete
    // claude from PATH permanently over a blip.
    const { w, link, versions, current } = claudeWorld({ from: '2.1.270', to: '2.1.272', smokeFailsOn: null });
    try {
      // Every non---version invocation fails: new build and old build alike.
      w.write('claude', `
TARGET=$(readlink -f "${toPosix(link)}")
V=$(basename "$TARGET")
if [[ "\${1:-}" == "--version" ]]; then echo "$V (Claude Code)"; exit 0; fi
if [[ "\${1:-}" == "update" ]]; then ln -sfn "${toPosix(versions)}/2.1.272" "${toPosix(link)}"; exit 0; fi
exit 1`);
      const r = runClaudeOnly(w, link, versions);
      assert.equal(readState(w, 'claudeCode.update.json').result, 'failed', r.log);
      const target = execFileSync(BASH, ['-c', `readlink -f "${toPosix(link)}"`], { encoding: 'utf8' }).trim();
      assert.ok(existsSync(target), `claude symlink dangles at ${target}`);
      assert.equal(current(), '2.1.272', 'the fault was not the update, so stay on the new build');
    } finally { w.cleanup(); }
  });

  test('a claude binary that is NOT a symlink is never replaced or deleted', { skip }, () => {
    // `readlink -f` returns the path itself for a regular file, and `ln -sfn X X`
    // unlinks the destination first - so an unguarded rollback DELETES the
    // binary. The rollback path is reached on a transient provider outage, which
    // makes this the difference between a bad morning and no claude at all.
    const w = makeWorld();
    try {
      const real = join(w.root, 'claude-real');
      writeFileSync(real, '#!/bin/sh\necho "2.1.270 (Claude Code)"\n');
      chmodSync(real, 0o755);
      const versions = join(w.root, 'versions');
      mkdirSync(versions, { recursive: true });

      // Every non---version call fails, so the smoke test fails and the rollback
      // path is entered with CLAUDE_BIN pointing at a regular file.
      w.write('claude', `
if [[ "\${1:-}" == "--version" ]]; then echo "2.1.270 (Claude Code)"; exit 0; fi
if [[ "\${1:-}" == "update" ]]; then exit 0; fi
exit 1`);
      w.write('npm', 'exit 0');

      runScript(w, 'cli-update.sh', {
        CLAUDE_BIN: toPosix(real),
        CLAUDE_VERSIONS: toPosix(versions),
        CODEX_BIN: toPosix(join(w.root, 'no-codex')),
        NPM_PREFIX: toPosix(w.root),
      });

      assert.ok(existsSync(real), 'the claude binary must still exist');
      assert.ok(readFileSync(real, 'utf8').includes('Claude Code'), 'and must still be the real file');
    } finally { w.cleanup(); }
  });

  test('a version string the parser cannot recognise stops the run instead of building a path from it', { skip }, () => {
    const { w, link, versions } = claudeWorld({ from: '2.1.270', to: null });
    try {
      w.write('claude', `
if [[ "\${1:-}" == "--version" ]]; then echo "Claude Code v2.1.272"; exit 0; fi
exit 0`);
      runClaudeOnly(w, link, versions);
      const s = readState(w, 'claudeCode.update.json');
      assert.equal(s.result, 'failed');
      assert.equal(s.upToDate, false);
    } finally { w.cleanup(); }
  });
});

describe('deploy-direct.sh install_root_cron', { skip: BASH_AVAILABLE ? false : 'bash unavailable' }, () => {
  /**
   * The function is executed for real, under the same `set -e` the remote script
   * runs with, with a stub `sudo` and `crontab`.
   *
   * It is extracted from the deploy script's text rather than sourced, because
   * it lives inside a quoted heredoc that the deploy sends over ssh. Extracting
   * keeps the ASSERTIONS on the actual shipped code instead of on a copy that
   * can drift.
   *
   * Why it exists: this function aborted the whole remote script under `set -e`
   * - a bare `probe=$(cmd)` whose command substitution fails exits the shell,
   * even with the declaration split out to preserve `$?`. The abort landed
   * between `systemctl stop lc-bridge` and `systemctl start lc-bridge`, so the
   * fallback lane left production with a DEAD bridge, in exactly the situation
   * you reach for it. Nothing tested it; the bug shipped twice.
   */
  function extractFunction(name) {
    const src = readFileSync(resolve(MCP_DIR, '..', 'deploy', 'scripts', 'deploy-direct.sh'), 'utf8');
    const start = src.indexOf(`${name}() {`);
    assert.notStrictEqual(start, -1, `${name} not found in deploy-direct.sh`);
    // The function body ends at the first line that is exactly "}".
    const rest = src.slice(start);
    const end = rest.indexOf('\n}\n');
    assert.notStrictEqual(end, -1, `could not find the end of ${name}`);
    return rest.slice(0, end + 3);
  }

  /** Run install_root_cron with a stub sudo that behaves like `mode`. */
  function runWithSudo(mode) {
    const w = makeWorld();
    try {
      // denied      -> sudo refuses the command (not whitelisted)
      // empty       -> sudo allowed, but root has no crontab yet
      // has-crontab -> sudo allowed, root already has entries
      w.write('sudo', `
if [[ "\${1:-}" == "-n" ]]; then shift; fi
if [[ "\${1:-}" == "crontab" ]]; then
  shift
  case "${mode}" in
    denied)      echo "sudo: a password is required" >&2; exit 1 ;;
    empty)       if [[ "\${1:-}" == "-l" ]]; then echo "no crontab for root" >&2; exit 1; fi; cat > "${toPosix(join(w.root, 'written-crontab'))}"; exit 0 ;;
    has-crontab) if [[ "\${1:-}" == "-l" ]]; then echo "0 1 * * * /existing"; exit 0; fi; cat > "${toPosix(join(w.root, 'written-crontab'))}"; exit 0 ;;
  esac
fi
exit 0`);

      const script = [
        // `set -e` and nothing more: the same flags deploy-direct.sh's
        // REMOTE_SCRIPT runs with. Testing under stricter flags would have
        // reported the empty-crontab bug as an abort, when its real production
        // shape is an exit code of 0 and a wiped crontab.
        'set -e',
        `DEST="${toPosix(w.root)}"`,
        extractFunction('install_root_cron'),
        'install_root_cron refresh-token.sh "0 */4 * * *"',
        'echo REACHED_THE_END',
      ].join('\n');

      // The host PATH stays on the OUTER spawn - that is how `bash` itself is
      // found on Windows. Only the stub dir is prepended.
      const r = spawnSync(BASH, ['-c', script], {
        encoding: 'utf8',
        env: { ...process.env, PATH: `${toPosix(w.bin)}:${process.env.PATH}` },
      });
      return { ...r, written: readIfExists(join(w.root, 'written-crontab')) };
    } finally { w.cleanup(); }
  }

  test('a lane that may not touch root cron warns and CONTINUES', () => {
    // Continuing is the whole point: the caller has already stopped lc-bridge.
    const r = runWithSudo('denied');
    assert.equal(r.status, 0, `the script aborted (exit ${r.status}): ${r.stderr}`);
    assert.match(r.stdout, /REACHED_THE_END/, 'execution must reach the caller again');
    assert.match(r.stdout, /WARNING: cannot manage root cron/);
  });

  test('a box where root simply has NO crontab yet still gets the entry installed', () => {
    // The other exit-1 case, and the opposite handling: this is a fresh box,
    // which is precisely the one that needs the cron written.
    //
    // The CONTENT assertion is the one that matters. When `crontab -l` fails and
    // `grep` then finds nothing, `set -e` used to kill the subshell before the
    // echo, so an EMPTY crontab was installed - while the pipeline still exited
    // 0 and the script printed "Cron set". An exit-code assertion alone calls
    // that a pass.
    const r = runWithSudo('empty');
    assert.equal(r.status, 0, `the script aborted (exit ${r.status}): ${r.stderr}`);
    assert.match(r.stdout, /REACHED_THE_END/);
    assert.doesNotMatch(r.stdout, /cannot manage root cron/, 'an empty crontab is not a refusal');
    assert.match(r.written, /0 \*\/4 \* \* \* .*refresh-token\.sh/,
      'the entry must actually be written, not just announced');
  });

  test('an existing crontab is rewritten with the entry, keeping the other lines', () => {
    const r = runWithSudo('has-crontab');
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.written, /0 1 \* \* \* \/existing/, 'foreign entries must survive');
    assert.match(r.written, /0 \*\/4 \* \* \* .*refresh-token\.sh/);
  });
});

describe('watchdog wiring', () => {
  test('the state directory the bridge reads is the one the scripts write', () => {
    // Two files, two languages, no compiler between them. If these drift, every
    // CLI reads as never-observed and the staleness alerts fire about a system
    // that is fine - with nothing anywhere pointing at the cause.
    const shell = readFileSync(join(MCP_DIR, 'cli-health-state.sh'), 'utf8');
    const server = readFileSync(join(MCP_DIR, 'bridge', 'server.mjs'), 'utf8');

    const shellDefault = /CLI_STATE_DIR="\$\{CLI_STATE_DIR:-([^}"]+)\}"/.exec(shell)?.[1];
    assert.equal(shellDefault, '/opt/livecontext/services/bridge/state');

    // server.mjs resolves it relative to its own directory, <bridge root>/bridge,
    // so "../state" is <bridge root>/state - the same path.
    assert.match(server, /CLI_HEALTH_STATE_DIR \|\| resolve\(__dirname, '\.\.', 'state'\)/);

    // And the unit really does run server.mjs from <bridge root>/bridge, which is
    // the only reason "../state" resolves to the same directory the shell writes.
    // Without this the JS side could be correct against an assumption that had
    // quietly changed in the unit file.
    const unit = readFileSync(resolve(MCP_DIR, '..', 'deploy', 'systemd', 'lc-bridge.service'), 'utf8');
    assert.match(unit, /WorkingDirectory=\/opt\/livecontext\/services\/bridge\/bridge/);
  });

  test('/metrics stays synchronous and still emits bridge_up alongside the CLI block', () => {
    // Asserted against the source because importing server.mjs boots the bridge
    // (Redis, MCP), the convention the other wiring tests here follow. What it
    // pins: the handler must not await the CLI probe (a 4s spawn on a 30s scrape
    // can push /metrics past its scrape timeout, which reports a healthy bridge
    // as down and loses bridge_up, the series ServiceDown watches), and the CLI
    // block must be spliced INTO the same response rather than replacing it.
    const server = readFileSync(join(MCP_DIR, 'bridge', 'server.mjs'), 'utf8');
    const handler = server.slice(server.indexOf("app.get('/metrics'"), server.indexOf("app.post('/api/bridge/execute'"));

    assert.ok(handler.length > 0, 'could not locate the /metrics handler');
    assert.doesNotMatch(handler, /async \(/, '/metrics must not be async: it must never await a spawn');
    assert.doesNotMatch(handler, /await /, '/metrics must not await anything');
    assert.match(handler, /cliSnapshot\.get\(\)/, 'the CLI block must come from the background snapshot');
    assert.match(handler, /bridge_up 1/, 'bridge_up must still be emitted');
    assert.match(handler, /\.\.\.collectCliHealthMetrics\(/, 'the CLI block must be spliced in, not replace the response');
  });
});
