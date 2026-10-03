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
import { mkdtempSync, rmSync, mkdirSync, writeFileSync, readFileSync, existsSync, lstatSync, chmodSync, readdirSync, utimesSync } from 'node:fs';
import { createHash } from 'node:crypto';
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
const HAVE_FLOCK = BASH_AVAILABLE && spawnSync(BASH, ['-c', 'command -v flock'], { encoding: 'utf8' }).status === 0;

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
  write('logger', `echo "$*" >> "${toPosix(join(root, 'logger.log'))}"`);

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
/** The run lock of a test world: a 0700 dir the script creates, like /run/lc-cli-update. */
const lockOf = (world) => join(world.root, 'run', 'cli-update.lock');

function runScript(world, script, env = {}, { holdLock } = {}) {
  // holdLock: run the script while ANOTHER open file description holds an
  // flock on that path (fd 8 of the wrapper, inherited by the script), the way
  // a concurrent timer or manual run would.
  const path = toPosix(join(MCP_DIR, script));
  const args = holdLock
    ? ['-c', 'mkdir -p -m 700 "$(dirname "$1")"; umask 077; exec 8>>"$1"; flock -n 8 || exit 99; bash "$2"; exit $?', 'lock-holder', toPosix(holdLock), path]
    : [path];
  const res = spawnSync(BASH, args, {
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${toPosix(world.bin)}:${process.env.PATH}`,
      LC_PATH: `${toPosix(world.bin)}:${POSIX_PATH}`,
      CLI_STATE_DIR: toPosix(world.state),
      CLI_UPDATE_LOCK: toPosix(lockOf(world)),
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

/**
 * cli-update.sh installs the PINNED builds of mcp/bridge/agent-cli-versions.txt
 * (LC-046), verified against the manifest's sha512, never "the latest".
 *
 * The npm stub below models the three npm calls the script makes:
 *   - `npm view <pkg> version`   -> the registry's latest (informational only)
 *   - `npm pack <pkg>@<v>`       -> writes `<name>-<v>.tgz` whose bytes are
 *                                   "tarball:<pkg>@<v>" (or "tampered:..." when
 *                                   the world says the registry is compromised)
 *   - `npm install -g --prefix P [--ignore-scripts] <tgz | pkg@v>`
 * The manifest a test writes carries the sha512 of the honest bytes, so the
 * script's integrity check is exercised for real.
 */
function sriOf(content) {
  return `sha512-${createHash('sha512').update(content).digest('base64')}`;
}

/** One manifest line per pin, `integrity` defaulting to the honest tarball's. */
function writeManifest(w, pins) {
  const lines = ['# test manifest'];
  for (const p of pins) {
    const integrity = p.integrity ?? sriOf(`tarball:${p.pkg}@${p.version}`);
    lines.push(`${p.pkg}@${p.version}  ${p.policy || 'ignore-scripts'}  ${integrity}`);
  }
  const file = join(w.root, 'agent-cli-versions.txt');
  writeFileSync(file, `${lines.join('\n')}\n`);
  return file;
}

/** A real build's version script: prints its version, smoke-fails when listed. */
function buildScript(w, version) {
  return `#!/bin/bash
if [[ "\${1:-}" == "--version" ]]; then echo "${version} (Claude Code)"; exit 0; fi
grep -qFx "${version}" "${toPosix(join(w.root, 'smoke-fails'))}" 2>/dev/null && exit 1
exit 0
`;
}

function writeNpmStub(w) {
  const root = toPosix(w.root);
  w.write('npm', `
echo "$*" >> "${root}/npm-calls.log"
case "\${1:-}" in
  view)
    cat "${root}/npm-latest" 2>/dev/null; exit 0 ;;
  pack)
    spec="$2"
    [[ -f "${root}/npm-pack-fails" ]] && exit 1
    name="\${spec#@}"; name="\${name%@*}"; name="\${name//\\//-}"; ver="\${spec##*@}"
    file="$name-$ver.tgz"
    if [[ -f "${root}/registry-tampered" ]]; then printf 'tampered:%s' "$spec" > "$file"; else printf 'tarball:%s' "$spec" > "$file"; fi
    echo "$file"; exit 0 ;;
  install)
    target="\${@: -1}"
    [[ -f "${root}/npm-install-exit" ]] && exit "$(cat "${root}/npm-install-exit")"
    case "$target" in
      *.tgz) base=$(basename "$target" .tgz); ver="\${base##*-}"
             case "$base" in anthropic-ai-claude-code-*) pkg=claude ;; *) pkg=codex ;; esac ;;
      @anthropic-ai/claude-code@*) pkg=claude; ver="\${target##*@}" ;;
      @openai/codex@*) pkg=codex; ver="\${target##*@}" ;;
      *) exit 2 ;;
    esac
    prefix=""
    for ((i=1; i<=$#; i++)); do [[ "\${!i}" == "--prefix" ]] && { j=$((i+1)); prefix="\${!j}"; }; done
    if [[ "$pkg" == codex ]]; then
      # The \`codex\` stub on PATH answers from codex-version; the package and its
      # bin link land under the prefix like a real npm install, which is what
      # the verified-install record describes.
      echo "$ver" > "${root}/codex-version"
      cdir="$prefix/lib/node_modules/@openai/codex"
      mkdir -p "$cdir/bin" "$prefix/bin"
      printf '#!/bin/bash\\n# codex %s\\n' "$ver" > "$cdir/bin/codex.js"
      chmod 755 "$cdir/bin/codex.js"
      ln -sfn "$cdir/bin/codex.js" "$prefix/bin/codex"
      exit 0
    fi
    # Claude: npm writes the package, then its bin link. Like real npm (arborist's
    # check-bin, which runs BEFORE extraction and ignores --no-bin-links) it
    # REFUSES a global install whose prefix/bin entry exists and is not a link
    # into this package (EEXIST); --no-bin-links only skips creating the link.
    # That is what failed on prod 2026-10-02: this stub used to let
    # --no-bin-links through the check, so the native-layout tests passed against
    # an npm that does not exist. While it installs, record whether the LIVE
    # \`claude\` (the world's prefix/bin/claude, whatever --prefix this call got)
    # still resolves: an agent spawned at that moment runs whatever it points at.
    pkgdir="$prefix/lib/node_modules/@anthropic-ai/claude-code"
    link="$prefix/bin/claude"
    live="${root}/prefix/bin/claude"
    { [[ -e "$live" ]] && echo present || echo missing; } >> "${root}/claude-during-install"
    nobin=""
    for a in "$@"; do [[ "$a" == "--no-bin-links" ]] && nobin=1; done
    # bin-links checkLink, string for string: readlink ONE level (a non-link is
    # EEXIST), path.resolve() it against the link's directory (lexical: no
    # symlink is resolved), lowercase, and require the package path as a plain
    # string prefix (indexOf === 0, no trailing slash).
    if [[ -e "$link" || -L "$link" ]]; then
      if ! cur=$(readlink -- "$link"); then
        echo "npm ERR! code EEXIST: $link (not a link)" >&2; exit 1
      fi
      [[ "$cur" == /* ]] || cur="$(dirname -- "$link")/$cur"
      resolved=$(realpath -m -s -- "$cur"); want=$(realpath -m -s -- "$pkgdir")
      if [[ "\${resolved,,}" != "\${want,,}"* ]]; then
        echo "npm ERR! code EEXIST: $link" >&2; exit 1
      fi
    fi
    mkdir -p "$pkgdir/bin" "$prefix/bin"
    cp "${root}/build-template" "$pkgdir/bin/claude.exe"
    sed -i "s/__VERSION__/$ver/g" "$pkgdir/bin/claude.exe"
    chmod 644 "$pkgdir/bin/claude.exe"   # npm's bin linking is what makes it executable
    if [[ -f "${root}/npm-package-no-bin" ]]; then
      printf '{"name":"@anthropic-ai/claude-code","version":"%s"}\\n' "$ver" > "$pkgdir/package.json"
    else
      printf '{\\n  "name": "@anthropic-ai/claude-code",\\n  "version": "%s",\\n  "bin": {\\n    "claude": "bin/claude.exe"\\n  }\\n}\\n' "$ver" > "$pkgdir/package.json"
    fi
    [[ -n "$nobin" ]] && exit 0
    chmod 755 "$pkgdir/bin/claude.exe"
    ln -sfn "$pkgdir/bin/claude.exe" "$link"
    exit 0 ;;
esac
exit 0`);
}

function npmCalls(w) {
  return readIfExists(join(w.root, 'npm-calls.log')).split('\n').filter(Boolean);
}

describe('cli-update.sh - Codex follows the pin', { skip: BASH_AVAILABLE ? false : 'bash unavailable' }, () => {
  function codexWorld({ installed, pinned = '0.154.0', upstream = '0.160.0', smokeFailsOn = [], pin } = {}) {
    const w = makeWorld();
    const versionFile = join(w.root, 'codex-version');
    if (installed !== undefined) writeFileSync(versionFile, installed);
    writeFileSync(join(w.root, 'npm-latest'), upstream);
    writeFileSync(join(w.root, 'smoke-fails'), smokeFailsOn.join('\n'));
    w.write('codex', `
V=$(cat "${toPosix(versionFile)}" 2>/dev/null) || exit 127
[[ -n "$V" ]] || exit 127
if [[ "\${1:-}" == "--version" ]]; then echo "codex-cli $V"; exit 0; fi
echo $(( $(cat "${toPosix(join(w.root, 'smoke-count'))}" 2>/dev/null || echo 0) + 1 )) > "${toPosix(join(w.root, 'smoke-count'))}"
grep -qFx "$V" "${toPosix(join(w.root, 'smoke-fails'))}" 2>/dev/null && exit 1
exit 0`);
    writeNpmStub(w);
    const manifest = writeManifest(w, [pin || { pkg: '@openai/codex', version: pinned }]);
    const prefix = join(w.root, 'prefix');
    const run = (extraEnv = {}, opts = {}) => runScript(w, 'cli-update.sh', {
      CLI_UPDATE_CLIS: 'codex',
      CLI_MANIFEST: toPosix(manifest),
      CODEX_BIN: toPosix(join(prefix, 'bin', 'codex')),
      NPM_PREFIX: toPosix(prefix),
      TMPDIR: toPosix(w.root),
      ...extraEnv,
    }, opts);
    /** Reset what a run leaves behind, to observe the NEXT run alone. */
    const forgetCalls = () => {
      writeFileSync(join(w.root, 'npm-calls.log'), '');
      writeFileSync(join(w.root, 'smoke-count'), '0');
    };
    return {
      w, run, manifest, prefix, forgetCalls,
      current: () => readIfExists(versionFile).trim(),
      smokes: () => Number(readIfExists(join(w.root, 'smoke-count')) || 0),
      state: () => readState(w, 'codex.update.json'),
      record: () => readIfExists(join(w.state, 'codex.pin')),
    };
  }

  test('already on the pin: reports none, installs nothing, spends no provider turn, logs the registry lag', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      assert.equal((c.run(), c.state().result), 'updated', 'setup: a verified install of the pin');
      c.forgetCalls();
      const r = c.run();
      const s = c.state();
      assert.equal(s.result, 'none', r.log);
      assert.equal(s.upToDate, true);
      assert.equal(s.latest, '0.154.0', 'latest carries the PIN, not the registry');
      assert.match(s.detail, /registry latest is 0\.160\.0/);
      assert.equal(c.current(), '0.154.0');
      assert.equal(c.smokes(), 0);
      assert.ok(!npmCalls(c.w).some(l => l.startsWith('install') || l.startsWith('pack')));
    } finally { c.w.cleanup(); }
  });

  test('audit B #6: a CLI that only REPORTS the pinned version is reinstalled from the verified tarball', () => {
    // Pre-fix `--version == pin` was the whole compliance check, so a build
    // installed any other way (native installer, hand install) was reported
    // compliant without ever passing the integrity check.
    const c = codexWorld({ installed: '0.154.0' });
    try {
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.match(r.log, /reports the pinned 0\.154\.0 but no record of a verified install/);
      const install = npmCalls(c.w).find(l => l.startsWith('install'));
      assert.match(install, /lc-cli-verified\.[^/ ]+\/openai-codex-0\.154\.0\.tgz$/);
      const [version, integrity, target, sha] = c.record().trim().split(' ');
      assert.equal(version, '0.154.0');
      assert.match(integrity, /^sha512-/);
      assert.match(target, /prefix\/lib\/node_modules\/@openai\/codex\/bin\/codex\.js$/);
      assert.match(sha, /^[0-9a-f]{64}$/);
    } finally { c.w.cleanup(); }
  });

  test('a verified install whose file changed afterwards is reinstalled', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      c.run();
      writeFileSync(join(c.prefix, 'lib', 'node_modules', '@openai', 'codex', 'bin', 'codex.js'), '#!/bin/bash\n# edited\n');
      c.forgetCalls();
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.match(r.log, /changed since the verified install/);
      assert.ok(npmCalls(c.w).some(l => l.startsWith('install')));
    } finally { c.w.cleanup(); }
  });

  test('a binary link that no longer points at the verified install is reinstalled', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      c.run();
      const elsewhere = join(c.w.root, 'elsewhere-codex');
      writeFileSync(elsewhere, '#!/bin/bash\n');
      execFileSync(BASH, ['-c', `ln -sfn "${toPosix(elsewhere)}" "${toPosix(join(c.prefix, 'bin', 'codex'))}"`]);
      c.forgetCalls();
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.match(r.log, /not the verified install/);
    } finally { c.w.cleanup(); }
  });

  test('a rollback leaves no verified-install record behind', () => {
    const c = codexWorld({ installed: '0.153.0', smokeFailsOn: ['0.154.0'] });
    try {
      c.run();
      assert.equal(c.state().result, 'rolled_back');
      assert.equal(c.record(), '');
    } finally { c.w.cleanup(); }
  });

  test('LC-046 regression: a newer registry build is NEVER installed, the drifted CLI goes back to the pin', () => {
    // Pre-fix the run installed `npm view` latest (0.160.0) every morning.
    const c = codexWorld({ installed: '0.160.0', pinned: '0.154.0', upstream: '0.160.0' });
    try {
      const r = c.run();
      const s = c.state();
      assert.equal(s.result, 'updated', r.log);
      assert.equal(c.current(), '0.154.0');
      assert.equal(s.installed, '0.154.0');
      assert.equal(s.upToDate, true);
      assert.ok(!npmCalls(c.w).some(l => /install .*codex@0\.160\.0/.test(l)), 'the registry latest must never be installed');
    } finally { c.w.cleanup(); }
  });

  test('installs the VERIFIED tarball, from a root-side copy, with the manifest script policy', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      c.run();
      const install = npmCalls(c.w).find(l => l.startsWith('install'));
      assert.ok(install, 'expected an npm install');
      assert.match(install, /--ignore-scripts/, 'ignore-scripts from the manifest must reach npm');
      assert.match(install, /lc-cli-verified\.[^/ ]+\/openai-codex-0\.154\.0\.tgz$/, 'must install the verified copy, not a registry spec');
      assert.equal(c.state().result, 'updated');
      assert.deepEqual(readdirSync(c.w.root).filter(f => f.startsWith('lc-cli-')), [], 'temporary tarball directories must be removed');
    } finally { c.w.cleanup(); }
  });

  test('a tarball whose sha512 does not match the manifest is NOT installed', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      writeFileSync(join(c.w.root, 'registry-tampered'), '');
      const r = c.run();
      const s = c.state();
      assert.equal(s.result, 'failed', r.log);
      assert.match(s.detail, /integrity mismatch/);
      assert.equal(c.current(), '0.153.0');
      assert.ok(!npmCalls(c.w).some(l => l.startsWith('install')), 'nothing may be installed after a mismatch');
    } finally { c.w.cleanup(); }
  });

  for (const [why, pin] of [
    ['no integrity pin', { pkg: '@openai/codex', version: '0.154.0', integrity: '-' }],
    ['a version range', { pkg: '@openai/codex', version: '^0.154.0' }],
    ['a dist-tag', { pkg: '@openai/codex', version: 'latest' }],
    ['no install-script policy', { pkg: '@openai/codex', version: '0.154.0', policy: 'whatever' }],
  ]) {
    test(`a manifest line with ${why} is refused before anything is downloaded`, () => {
      const c = codexWorld({ installed: '0.153.0', pin });
      try {
        const r = c.run();
        assert.equal(c.state().result, 'failed', r.log);
        assert.equal(c.current(), '0.153.0');
        assert.ok(!npmCalls(c.w).some(l => l.startsWith('pack') || l.startsWith('install')));
      } finally { c.w.cleanup(); }
    });
  }

  test('a missing manifest fails the run for that CLI and changes nothing', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      rmSync(c.manifest);
      const r = c.run();
      const s = c.state();
      assert.equal(s.result, 'failed', r.log);
      assert.match(s.detail, /manifest not readable/);
      assert.equal(c.current(), '0.153.0');
    } finally { c.w.cleanup(); }
  });

  test('a registry that cannot serve the pinned tarball reports failed and NOT up to date', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      writeFileSync(join(c.w.root, 'npm-pack-fails'), '');
      c.run();
      const s = c.state();
      assert.equal(s.result, 'failed');
      assert.equal(s.upToDate, false);
      assert.equal(c.current(), '0.153.0');
    } finally { c.w.cleanup(); }
  });

  test('npm install failing leaves the old build and reports failed', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      writeFileSync(join(c.w.root, 'npm-install-exit'), '1');
      c.run();
      const s = c.state();
      assert.equal(s.result, 'failed');
      assert.equal(s.upToDate, false);
      assert.equal(c.current(), '0.153.0');
    } finally { c.w.cleanup(); }
  });

  test('a missing CLI is (re)installed on the pin: the run re-asserts it, it does not just report', () => {
    const c = codexWorld({ installed: undefined });
    try {
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.equal(c.current(), '0.154.0');
    } finally { c.w.cleanup(); }
  });

  test('a smoke test that fails ONCE then succeeds is not treated as a broken build', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      const counter = toPosix(join(c.w.root, 'smoke-count'));
      c.w.write('codex', `
V=$(cat "${toPosix(join(c.w.root, 'codex-version'))}")
if [[ "\${1:-}" == "--version" ]]; then echo "codex-cli $V"; exit 0; fi
N=$(cat "${counter}" 2>/dev/null || echo 0)
echo $((N+1)) > "${counter}"
[[ "$N" == "0" ]] && exit 1
exit 0`);
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.equal(c.current(), '0.154.0', 'a blip must not roll production back');
      assert.ok(c.smokes() >= 2, 'the retry must actually have happened');
    } finally { c.w.cleanup(); }
  });

  test('a pinned build that fails its smoke test is ROLLED BACK to the previous one', () => {
    const c = codexWorld({ installed: '0.153.0', smokeFailsOn: ['0.154.0'] });
    try {
      const r = c.run();
      const s = c.state();
      assert.equal(s.result, 'rolled_back', r.log);
      assert.equal(c.current(), '0.153.0', 'production must be back on the build that answers');
      assert.equal(s.upToDate, false, 'off the pin, and it must say so');
      assert.equal(s.knownBadVersion, '0.154.0');
    } finally { c.w.cleanup(); }
  });

  test('the same pin is NOT retried after a rollback, run after run, until the pin changes', () => {
    const c = codexWorld({ installed: '0.153.0', smokeFailsOn: ['0.154.0'] });
    try {
      c.run();
      for (let i = 0; i < 2; i++) {
        const before = npmCalls(c.w).length;
        const r = c.run();
        assert.equal(c.state().result, 'skipped_known_bad', r.log);
        assert.equal(c.current(), '0.153.0');
        assert.ok(!npmCalls(c.w).slice(before).some(l => l.startsWith('install') || l.startsWith('pack')));
      }
      // A bump of the pin is tried normally.
      writeManifest(c.w, [{ pkg: '@openai/codex', version: '0.155.0' }]);
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.equal(c.current(), '0.155.0');
      assert.equal(c.state().knownBadVersion, '');
    } finally { c.w.cleanup(); }
  });

  test('when the OLD build fails too, the fault is not the pin: back on the pin, report failed', () => {
    const c = codexWorld({ installed: '0.153.0', smokeFailsOn: ['0.154.0', '0.153.0'] });
    try {
      const r = c.run();
      const s = c.state();
      assert.equal(s.result, 'failed', r.log);
      assert.equal(c.current(), '0.154.0');
      assert.equal(s.installed, '0.154.0');
      assert.equal(s.knownBadVersion, '', 'a failure that is not the pin must not block the next attempt');
      assert.match(c.record(), /^0\.154\.0 /, 'the restored verified pin is recorded, so it is not reinstalled daily');
    } finally { c.w.cleanup(); }
  });

  test('a pinned build that cannot report its version is rolled back', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      c.w.write('codex', `
V=$(cat "${toPosix(join(c.w.root, 'codex-version'))}")
if [[ "\${1:-}" == "--version" ]]; then [[ "$V" == "0.154.0" ]] && exit 3; echo "codex-cli $V"; exit 0; fi
exit 0`);
      const r = c.run();
      assert.equal(c.state().result, 'rolled_back', r.log);
      assert.equal(c.current(), '0.153.0');
    } finally { c.w.cleanup(); }
  });

  test('audit r3 #2: a second run while another holds the lock exits cleanly and changes nothing', { skip: HAVE_FLOCK ? false : 'flock unavailable' }, () => {
    // A timer run and a manual run used to proceed together and could delete
    // each other's staging prefix mid-install. The default lock path is in the
    // state dir, which is what the holder below locks.
    const c = codexWorld({ installed: '0.153.0' });
    try {
      const r = runScript(c.w, 'cli-update.sh', {
        CLI_UPDATE_CLIS: 'codex',
        CLI_MANIFEST: toPosix(c.manifest),
        CODEX_BIN: toPosix(join(c.prefix, 'bin', 'codex')),
        NPM_PREFIX: toPosix(c.prefix),
        TMPDIR: toPosix(c.w.root),
      }, { holdLock: lockOf(c.w) });
      assert.equal(r.status, 0, `a locked-out run is not a failure (exit ${r.status}): ${r.stderr}`);
      assert.match(r.log, /another run holds .*cli-update.lock - exiting without changes/);
      assert.match(readIfExists(join(c.w.root, 'logger.log')), /-p user.warning .*another run holds/, 'a locked-out run must be visible in the journal');
      assert.doesNotMatch(r.log, /=== agent CLI pin run/);
      assert.deepEqual(npmCalls(c.w), [], 'nothing may be fetched or installed');
      assert.ok(!existsSync(join(c.w.state, 'codex.update.json')), 'the run holding the lock writes the state, not this one');
      assert.equal(c.current(), '0.153.0');
      // Once the other run is gone, the next one proceeds normally.
      const next = c.run();
      assert.equal(c.state().result, 'updated', next.log);
    } finally { c.w.cleanup(); }
  });

  const lockSkip = HAVE_FLOCK && process.platform === 'linux' ? false : 'flock and /proc needed';
  const modeOf = (p) => (lstatSync(p).mode & 0o777).toString(8);

  test('re-audit #1: a lock file anyone else can open is replaced, so an agent holding it cannot stop the pin check', { skip: lockSkip }, () => {
    // flock works on a read-only descriptor. Pre-fix the lock was created 0644
    // in a directory the bridge account can read, so an agent running as that
    // account could hold it forever and every daily run exited "locked out".
    const c = codexWorld({ installed: '0.153.0' });
    try {
      const lock = lockOf(c.w);
      mkdirSync(dirname(lock), { mode: 0o700 });
      writeFileSync(lock, '');
      chmodSync(lock, 0o644);
      // The "agent" holds the world-readable file (no umask 077 here).
      const r = spawnSync(BASH, ['-c', 'exec 8<"$1"; flock -n 8 || exit 99; bash "$2"; exit $?', 'agent', toPosix(lock), toPosix(join(MCP_DIR, 'cli-update.sh'))], {
        encoding: 'utf8',
        env: {
          ...process.env,
          PATH: `${toPosix(c.w.bin)}:${process.env.PATH}`,
          LC_PATH: `${toPosix(c.w.bin)}:${POSIX_PATH}`,
          CLI_STATE_DIR: toPosix(c.w.state),
          CLI_UPDATE_LOCK: toPosix(lock),
          LOG: toPosix(join(c.w.root, 'cli-update.log')),
          SMOKE_RETRY_DELAY: '0',
          CLI_UPDATE_CLIS: 'codex',
          CLI_MANIFEST: toPosix(c.manifest),
          CODEX_BIN: toPosix(join(c.prefix, 'bin', 'codex')),
          NPM_PREFIX: toPosix(c.prefix),
          TMPDIR: toPosix(c.w.root),
        },
      });
      const log = readIfExists(join(c.w.root, 'cli-update.log'));
      assert.equal(r.status, 0, r.stderr);
      assert.match(log, /is not a root-only lock file - replacing it/);
      assert.doesNotMatch(log, /another run holds/);
      assert.equal(c.state().result, 'updated', log);
      assert.equal(modeOf(lock), '600', 'the replacement lock is readable by its owner only');
    } finally { c.w.cleanup(); }
  });

  test('re-audit #1: a lock path that is a symlink is replaced, never followed', { skip: lockSkip }, () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      const victim = join(c.w.root, 'victim');
      writeFileSync(victim, 'do not touch\n');
      chmodSync(victim, 0o644);
      const lock = lockOf(c.w);
      mkdirSync(dirname(lock), { mode: 0o700 });
      execFileSync(BASH, ['-c', `ln -s "${toPosix(victim)}" "${toPosix(lock)}"`]);
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.ok(!lstatSync(lock).isSymbolicLink(), 'the lock is a regular file now');
      assert.equal(modeOf(lock), '600');
      assert.equal(readFileSync(victim, 'utf8'), 'do not touch\n');
      assert.equal(modeOf(victim), '644', 'the link target keeps its mode');
    } finally { c.w.cleanup(); }
  });

  test('final audit #1: a missing lock directory is created root-only (0700) and the lock inside 0600', { skip: lockSkip }, () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      const lock = lockOf(c.w);
      assert.ok(!existsSync(dirname(lock)), 'setup: no lock directory yet');
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.doesNotMatch(r.log, /running unlocked/);
      assert.equal(modeOf(dirname(lock)), '700');
      assert.equal(modeOf(lock), '600');
    } finally { c.w.cleanup(); }
  });

  test('final audit #1: the lock is in /run/lc-cli-update, the directory the unit creates root-only', () => {
    const script = readFileSync(join(MCP_DIR, 'cli-update.sh'), 'utf8');
    assert.match(script, /^CLI_UPDATE_LOCK="\$\{CLI_UPDATE_LOCK:-\/run\/lc-cli-update\/cli-update\.lock\}"$/m,
      'not in the state dir or the bridge tree: the bridge account can write those');
    const unit = readFileSync(resolve(MCP_DIR, '..', 'deploy', 'systemd', 'lc-cli-update.service'), 'utf8');
    assert.match(unit, /^RuntimeDirectory=lc-cli-update$/m);
    assert.match(unit, /^RuntimeDirectoryMode=0700$/m);
    assert.match(unit, /^RuntimeDirectoryPreserve=yes$/m,
      'without it systemd deletes the directory (and the lock) at the end of every timer run');
    assert.doesNotMatch(unit, /^User=/m, 'the directory must be root\'s: the unit runs as root');
  });

  for (const [why, setup, expect] of [
    ['is a symlink', (c, dir) => {
      const elsewhere = join(c.w.root, 'elsewhere');
      mkdirSync(elsewhere, { mode: 0o700 });
      execFileSync(BASH, ['-c', `ln -s "${toPosix(elsewhere)}" "${toPosix(dir)}"`]);
      return () => assert.deepEqual(readdirSync(elsewhere), [], 'nothing is created through the link');
    }, /is not a plain directory - running unlocked/],
    ['is group/other accessible', (c, dir) => { mkdirSync(dir); chmodSync(dir, 0o755); return () => {}; }, /is not owned by uid \d+ with mode 700 - running unlocked/],
    ['cannot be created', (c, dir) => {
      writeFileSync(join(c.w.root, 'not-a-dir'), '');
      return () => {};
    }, /cannot create the lock directory .* - running unlocked/],
  ]) {
    test(`final audit #1: a lock directory that ${why} is refused -> the run proceeds unlocked and says so`, { skip: lockSkip }, () => {
      const c = codexWorld({ installed: '0.153.0' });
      try {
        let dir = dirname(lockOf(c.w));
        const after = setup(c, dir);
        if (why === 'cannot be created') dir = join(c.w.root, 'not-a-dir', 'run');
        const r = c.run({ CLI_UPDATE_LOCK: toPosix(join(dir, 'cli-update.lock')) });
        assert.match(r.log, expect);
        assert.match(readIfExists(join(c.w.root, 'logger.log')), /-p user\.warning .*running unlocked/);
        assert.equal(c.state().result, 'updated', r.log);
        after();
      } finally { c.w.cleanup(); }
    });
  }

  test('re-audit #4: a lock file that cannot be opened -> the run proceeds unlocked and says so', { skip: lockSkip }, () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      const lock = lockOf(c.w);
      mkdirSync(join(lock, 'not-empty'), { recursive: true, mode: 0o700 });
      chmodSync(dirname(lock), 0o700);
      const r = c.run();
      assert.match(r.log, /cannot open the lock file .* - running unlocked/);
      assert.match(readIfExists(join(c.w.root, 'logger.log')), /-p user\.warning .*running unlocked/);
      assert.equal(c.state().result, 'updated', r.log);
    } finally { c.w.cleanup(); }
  });

  test('re-audit #4: no flock -> the run proceeds unlocked and says so', () => {
    const c = codexWorld({ installed: '0.153.0' });
    try {
      const r = c.run({ FLOCK_BIN: '/nonexistent/flock' });
      assert.match(r.log, /flock not available - running unlocked/);
      assert.match(readIfExists(join(c.w.root, 'logger.log')), /-p user\.warning .*flock not available/);
      assert.equal(c.state().result, 'updated', r.log);
    } finally { c.w.cleanup(); }
  });

  test('re-audit #2: the lock is taken before the log is trimmed', { skip: lockSkip }, () => {
    // Two runs trimming the same log at once lose lines; only the lock holder may.
    const c = codexWorld({ installed: '0.153.0' });
    try {
      const logPath = join(c.w.root, 'cli-update.log');
      writeFileSync(logPath, 'x'.repeat(1_100_000));
      const r = c.run({}, { holdLock: lockOf(c.w) });
      assert.match(r.log, /another run holds/);
      assert.ok(readFileSync(logPath).length > 1_100_000, 'a locked-out run must not touch the log beyond its own line');
    } finally { c.w.cleanup(); }
  });

  test('the run lock is not handed to the CLIs it spawns', { skip: HAVE_FLOCK ? false : 'flock unavailable' }, () => {
    // A process a CLI leaves behind would otherwise hold fd 9, and with it the
    // lock, and every later run would exit as "locked out".
    const c = codexWorld({ installed: '0.154.0' });
    try {
      c.w.write('codex', `
{ true >&9; } 2>/dev/null && echo open > "${toPosix(join(c.w.root, 'fd9'))}" || echo closed > "${toPosix(join(c.w.root, 'fd9'))}"
echo "codex-cli 0.154.0"`);
      const r = c.run();
      assert.match(r.log, /=== agent CLI pin run/, 'setup: the run took the lock');
      assert.equal(readIfExists(join(c.w.root, 'fd9')).trim(), 'closed');
    } finally { c.w.cleanup(); }
  });

  test('every CLI action runs with the Claude auto-updater disabled', () => {
    const c = codexWorld({ installed: '0.154.0' });
    try {
      c.w.write('codex', `
echo "$DISABLE_AUTOUPDATER" > "${toPosix(join(c.w.root, 'autoupdater-env'))}"
echo "codex-cli 0.154.0"`);
      c.run();
      assert.equal(readIfExists(join(c.w.root, 'autoupdater-env')).trim(), '1');
    } finally { c.w.cleanup(); }
  });
});

describe('cli-update.sh - Claude Code follows the pin', { skip: BASH_AVAILABLE ? false : 'bash unavailable' }, () => {
  const probe = mkdtempSync(join(tmpdir(), 'lnprobe-'));
  const SYMLINKS = BASH_AVAILABLE && haveSymlinks(probe);
  rmSync(probe, { recursive: true, force: true });
  const skip = !BASH_AVAILABLE ? 'bash unavailable' : (!SYMLINKS ? 'symlinks unavailable on this platform' : false);

  /**
   * layout 'native': $CLAUDE_BIN is the native installer's symlink into
   * versions/<from> (prod before the pin). layout 'npm': already npm-installed.
   * `claude` on PATH execs $CLAUDE_BIN, like the real PATH entry.
   */
  function claudeWorld({ from, layout = 'native', pinned = '2.1.280', upstream = '2.1.300', smokeFailsOn = [] }) {
    const w = makeWorld();
    const prefix = join(w.root, 'prefix');
    const versions = join(w.root, 'versions');
    const link = join(prefix, 'bin', 'claude');
    mkdirSync(join(prefix, 'bin'), { recursive: true });
    mkdirSync(versions, { recursive: true });
    writeFileSync(join(w.root, 'npm-latest'), upstream);
    writeFileSync(join(w.root, 'smoke-fails'), smokeFailsOn.join('\n'));
    writeFileSync(join(w.root, 'build-template'), buildScript(w, '__VERSION__'));
    writeNpmStub(w);
    if (layout === 'native') {
      // An older native spare too, so pruning has something to decide about.
      for (const v of ['2.0.1', from]) {
        writeFileSync(join(versions, v), buildScript(w, v));
        chmodSync(join(versions, v), 0o755);
      }
      // The spare is older than the live build, as on a real box.
      const past = new Date(Date.now() - 86_400_000);
      utimesSync(join(versions, '2.0.1'), past, past);
      execFileSync(BASH, ['-c', `ln -sfn "${toPosix(join(versions, from))}" "${toPosix(link)}"`]);
    } else {
      execFileSync(BASH, ['-c', `${toPosix(join(w.bin, 'npm'))} install -g --prefix "${toPosix(prefix)}" "@anthropic-ai/claude-code@${from}"`], { env: { ...process.env, PATH: `${toPosix(w.bin)}:/usr/bin:/bin` } });
      writeFileSync(join(w.root, 'npm-calls.log'), '');
    }
    w.write('claude', `exec "${toPosix(link)}" "$@"`);
    const manifest = writeManifest(w, [{ pkg: '@anthropic-ai/claude-code', version: pinned, policy: 'run-scripts' }]);
    const run = () => runScript(w, 'cli-update.sh', {
      CLI_UPDATE_CLIS: 'claudeCode',
      CLI_MANIFEST: toPosix(manifest),
      CLAUDE_BIN: toPosix(link),
      CLAUDE_VERSIONS: toPosix(versions),
      NPM_PREFIX: toPosix(prefix),
      TMPDIR: toPosix(w.root),
    });
    const target = () => execFileSync(BASH, ['-c', `readlink -f "${toPosix(link)}"`], { encoding: 'utf8' }).trim();
    const current = () => execFileSync(BASH, ['-c', `"${toPosix(link)}" --version | awk '{print $1}'`], { encoding: 'utf8' }).trim();
    return { w, run, link, versions, target, current, state: () => readState(w, 'claudeCode.update.json') };
  }

  test('the native installer layout is moved to the verified, npm-installed pin', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      const r = c.run();
      const s = c.state();
      assert.equal(s.result, 'updated', r.log);
      assert.equal(c.current(), '2.1.280');
      assert.match(c.target(), /lib\/node_modules\/@anthropic-ai\/claude-code\/bin\/claude\.exe$/);
      const install = npmCalls(c.w).find(l => l.startsWith('install'));
      assert.doesNotMatch(install, /--ignore-scripts/, 'run-scripts from the manifest: the postinstall picks the native binary');
      assert.ok(existsSync(join(c.versions, '2.1.250')), 'the native build stays on disk as the rollback target');
    } finally { c.w.cleanup(); }
  });

  test('CASA round 4: claude keeps resolving while the pinned build installs, then switches in one step', { skip }, () => {
    // prepare_install deleted the native link BEFORE npm ran, so for the whole
    // install there was no `claude`: an agent the bridge spawned then got ENOENT.
    const c = claudeWorld({ from: '2.1.250' });
    try {
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      const during = readIfExists(join(c.w.root, 'claude-during-install')).split('\n').filter(Boolean);
      assert.deepEqual(during, ['present'], 'the native link must answer for the whole npm install');
      const install = npmCalls(c.w).find(l => l.startsWith('install'));
      assert.match(install, /--prefix '?[^ ]*\/prefix\/\.lc-claude-stage\.[^ /]+'? /, 'npm installs into a staging prefix, never next to a link it does not own');
      assert.ok(lstatSync(c.link).isSymbolicLink());
      assert.match(c.target(), /lib\/node_modules\/@anthropic-ai\/claude-code\/bin\/claude\.exe$/);
      assert.equal(c.current(), '2.1.280', 'the switched-to build is executable');
      assert.deepEqual(readdirSync(join(c.link, '..')).filter(n => n.includes('lc-new')), [], 'no temporary link left behind');
    } finally { c.w.cleanup(); }
  });

  test('prod 2026-10-02 regression: npm check-bin (EEXIST over the native link) no longer blocks the pin', { skip }, () => {
    // On websearch-host the native `claude` 2.1.287 sat at prefix/bin/claude and
    // `npm install -g --prefix <prefix> --no-bin-links <verified tgz>` aborted
    // with EEXIST: npm checks the bin entry whatever --no-bin-links says. The
    // pin was never applied. The stub's npm now refuses exactly like that.
    const c = claudeWorld({ from: '2.1.287', pinned: '2.1.286' });
    try {
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.doesNotMatch(r.log, /EEXIST/);
      assert.equal(c.current(), '2.1.286');
      const prefix = join(c.w.root, 'prefix');
      assert.equal(c.target(), execFileSync(BASH, ['-c', `readlink -f "${toPosix(join(prefix, 'lib', 'node_modules', '@anthropic-ai', 'claude-code', 'bin', 'claude.exe'))}"`], { encoding: 'utf8' }).trim(),
        'the link resolves inside the package at its ordinary npm location, so later bumps take the plain npm path');
      assert.ok(npmCalls(c.w).every(l => !l.includes('--no-bin-links')));
      assert.deepEqual(readdirSync(prefix).filter(n => n.startsWith('.lc-claude-stage')), [], 'the staging prefix is removed');
      assert.deepEqual(readdirSync(join(prefix, 'lib', 'node_modules', '@anthropic-ai')), ['claude-code'], 'nothing set aside is left behind');
      assert.match(readIfExists(join(c.w.state, 'claudeCode.pin')), /^2\.1\.286 sha512-/, 'the migrated build is recorded as the verified install');
      assert.ok(existsSync(join(c.versions, '2.1.287')), 'the native build stays on disk as the rollback target');
    } finally { c.w.cleanup(); }
  });

  test('after the migration, the next pin bump takes the plain npm path and succeeds', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      assert.equal((c.run(), c.state().result), 'updated', 'setup: migrated off the native layout');
      writeFileSync(join(c.w.root, 'npm-calls.log'), '');
      writeManifest(c.w, [{ pkg: '@anthropic-ai/claude-code', version: '2.1.290', policy: 'run-scripts' }]);
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.equal(c.current(), '2.1.290');
      const install = npmCalls(c.w).find(l => l.startsWith('install'));
      assert.match(install, /--prefix [^ ]*\/prefix /, 'an npm-owned link is installed over directly, as before');
      assert.doesNotMatch(install, /lc-claude-stage/);
    } finally { c.w.cleanup(); }
  });

  test('a staged build that does not report the pin never reaches the live link or package', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      writeFileSync(join(c.w.root, 'build-template'), buildScript(c.w, '9.9.9'));
      const r = c.run();
      assert.equal(c.state().result, 'failed', r.log);
      assert.match(r.log, /staged build reports '9\.9\.9' instead of 2\.1\.280/);
      assert.equal(c.current(), '2.1.250', 'the native build keeps answering');
      assert.ok(!existsSync(join(c.w.root, 'prefix', 'lib', 'node_modules', '@anthropic-ai', 'claude-code')), 'nothing moved into the live npm location');
      assert.deepEqual(readdirSync(join(c.w.root, 'prefix')).filter(n => n.startsWith('.lc-claude-stage')), []);
    } finally { c.w.cleanup(); }
  });

  test('leftovers of a killed migration (staging prefix, stale package) are cleared and replaced', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      const prefix = join(c.w.root, 'prefix');
      mkdirSync(join(prefix, '.lc-claude-stage.dead01', 'lib'), { recursive: true });
      const stale = join(prefix, 'lib', 'node_modules', '@anthropic-ai', 'claude-code');
      mkdirSync(stale, { recursive: true });
      writeFileSync(join(stale, 'package.json'), '{"name":"@anthropic-ai/claude-code","version":"0.0.1"}\n');
      mkdirSync(join(`${stale}.lc-aside.4242`, 'bin'), { recursive: true });
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.equal(c.current(), '2.1.280');
      assert.match(readFileSync(join(stale, 'package.json'), 'utf8'), /"version": "2\.1\.280"/);
      assert.deepEqual(readdirSync(prefix).filter(n => n.startsWith('.lc-claude-stage')), []);
      assert.deepEqual(readdirSync(join(prefix, 'lib', 'node_modules', '@anthropic-ai')), ['claude-code'], 'the set-aside package is removed too');
    } finally { c.w.cleanup(); }
  });

  test('audit r3 #3: leftovers are also cleared once the box is on the npm layout (no migration runs any more)', { skip }, () => {
    const c = claudeWorld({ from: '2.1.270', layout: 'npm' });
    try {
      assert.equal((c.run(), c.state().result), 'updated', 'setup: a verified install of the pin');
      const prefix = join(c.w.root, 'prefix');
      mkdirSync(join(prefix, '.lc-claude-stage.dead02', 'lib', 'node_modules'), { recursive: true });
      mkdirSync(join(prefix, 'lib', 'node_modules', '@anthropic-ai', 'claude-code.lc-aside.777', 'bin'), { recursive: true });
      writeFileSync(join(c.w.root, 'npm-calls.log'), '');
      const r = c.run();
      assert.equal(c.state().result, 'none', r.log);
      assert.equal(c.current(), '2.1.280');
      assert.deepEqual(readdirSync(prefix).filter(n => n.startsWith('.lc-claude-stage')), [], 'a staging prefix must not outlive the migration');
      assert.deepEqual(readdirSync(join(prefix, 'lib', 'node_modules', '@anthropic-ai')), ['claude-code'], 'a set-aside package must not outlive the migration');
      assert.ok(!npmCalls(c.w).some(l => l.startsWith('install')), 'cleaning leftovers is not a reinstall');
    } finally { c.w.cleanup(); }
  });

  /** A `mv` on PATH that refuses to move the listed kinds of source, else the real mv. */
  function failMvFrom(c, kinds) {
    const pattern = { stage: '*/.lc-claude-stage.*/lib/*', aside: '*.lc-aside.*', newlink: '*.lc-new.*' };
    const tests = kinds.map(k => pattern[k]).join('|');
    c.w.write('mv', `
src=""
for a in "$@"; do case "$a" in -*) ;; *) src="$a"; break ;; esac; done
case "$src" in ${tests}) echo "mv: refusing $src (test stub)" >&2; exit 1 ;; esac
for m in /usr/bin/mv /bin/mv; do [[ -x "$m" ]] && exec "$m" "$@"; done
exit 127`);
  }

  function plantStalePackage(c) {
    const stale = join(c.w.root, 'prefix', 'lib', 'node_modules', '@anthropic-ai', 'claude-code');
    mkdirSync(stale, { recursive: true });
    writeFileSync(join(stale, 'package.json'), '{"name":"@anthropic-ai/claude-code","version":"0.0.1"}\n');
    return stale;
  }

  test('audit r3 #1: the staged build cannot be moved in -> the set-aside package is restored', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      const stale = plantStalePackage(c);
      failMvFrom(c, ['stage']);
      const r = c.run();
      assert.equal(c.state().result, 'failed', r.log);
      assert.match(r.log, /could not move the staged build/);
      assert.match(r.log, /restored the npm package that was set aside/);
      assert.equal(c.current(), '2.1.250', 'the native build keeps answering');
      assert.match(readFileSync(join(stale, 'package.json'), 'utf8'), /"version":"0\.0\.1"/, 'the original package is back in place');
      assert.deepEqual(readdirSync(join(stale, '..')), ['claude-code'], 'no set-aside copy left');
    } finally { c.w.cleanup(); }
  });

  test('audit r3 #1: if the set-aside package cannot be moved back either, it is left in place, not deleted', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      const stale = plantStalePackage(c);
      failMvFrom(c, ['stage', 'aside']);
      const r = c.run();
      assert.equal(c.state().result, 'failed', r.log);
      assert.match(r.log, /could not restore .*lc-aside.* - left in place/);
      assert.equal(c.current(), '2.1.250');
      const aside = readdirSync(join(stale, '..')).filter(n => n.startsWith('claude-code.lc-aside.'));
      assert.equal(aside.length, 1, 'the only copy of that package must survive the run');
      assert.match(readFileSync(join(stale, '..', aside[0], 'package.json'), 'utf8'), /"version":"0\.0\.1"/);
      // The next healthy run clears it and completes the migration.
      rmSync(join(c.w.bin, 'mv'));
      const next = c.run();
      assert.equal(c.state().result, 'updated', next.log);
      assert.deepEqual(readdirSync(join(stale, '..')), ['claude-code']);
    } finally { c.w.cleanup(); }
  });

  test('audit r3 #4: a staging prefix that cannot be created leaves the native build answering', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      c.w.write('mktemp', `
case "$*" in *.lc-claude-stage.*) echo "mktemp: refusing (test stub)" >&2; exit 1 ;; esac
for m in /usr/bin/mktemp /bin/mktemp; do [[ -x "$m" ]] && exec "$m" "$@"; done
exit 127`);
      const r = c.run();
      assert.equal(c.state().result, 'failed', r.log);
      assert.match(r.log, /could not create a staging prefix/);
      assert.equal(c.current(), '2.1.250');
      assert.ok(native(c), 'the link still points at the native build');
      assert.ok(!npmCalls(c.w).some(l => l.startsWith('install')), 'nothing is installed without a staging prefix');
    } finally { c.w.cleanup(); }
  });

  function native(c) {
    const root = execFileSync(BASH, ['-c', `readlink -f "${toPosix(c.versions)}"`], { encoding: 'utf8' }).trim();
    return c.target().startsWith(`${root}/`);
  }

  test('re-audit #4: a link switch that fails after the package was moved in leaves the native build answering', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      failMvFrom(c, ['newlink']);
      const r = c.run();
      assert.equal(c.state().result, 'failed', r.log);
      assert.equal(c.current(), '2.1.250');
      assert.ok(native(c), 'the link still points at the native build');
      assert.ok(existsSync(join(c.w.root, 'prefix', 'lib', 'node_modules', '@anthropic-ai', 'claude-code', 'package.json')), 'setup: the package had been moved in');
      assert.deepEqual(readdirSync(join(c.link, '..')).filter(n => n.includes('.lc-new.')), [], 'the temporary link is removed');
      // The next run finishes the job over the package already in place.
      rmSync(join(c.w.bin, 'mv'));
      const next = c.run();
      assert.equal(c.state().result, 'updated', next.log);
      assert.equal(c.current(), '2.1.280');
    } finally { c.w.cleanup(); }
  });

  test('re-audit #3: a stale temporary link of a killed switch is removed, nothing else that looks like it', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      const bin = join(c.link, '..');
      const ln = (target, name) => execFileSync(BASH, ['-c', `ln -s "${toPosix(target)}" "${toPosix(join(bin, name))}"`]);
      const target = join(c.w.root, 'link-target');
      writeFileSync(target, 'target\n');
      ln(target, 'claude.lc-new.4242');
      ln(target, 'claude.lc-new.abc');
      writeFileSync(join(bin, 'claude.lc-new.777'), 'a regular file, not ours\n');
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.match(r.log, /removing the stale temporary link .*claude\.lc-new\.4242/);
      const left = readdirSync(bin).filter(n => n.startsWith('claude.lc-new.')).sort();
      assert.deepEqual(left, ['claude.lc-new.777', 'claude.lc-new.abc'], 'only "<pid>" symlinks are removed');
      assert.equal(readFileSync(target, 'utf8'), 'target\n', 'removing a link never removes its target');
      assert.equal(c.current(), '2.1.280', 'the live link is untouched by the cleanup');
    } finally { c.w.cleanup(); }
  });

  test('re-audit #4: cleanup refuses an unexpected name and removes a leftover symlink as a link only', { skip }, () => {
    const c = claudeWorld({ from: '2.1.270', layout: 'npm' });
    try {
      assert.equal((c.run(), c.state().result), 'updated', 'setup: a verified install of the pin');
      const prefix = join(c.w.root, 'prefix');
      mkdirSync(join(prefix, '.lc-claude-stage.a b'), { recursive: true });
      const outside = join(c.w.root, 'outside');
      mkdirSync(outside, { recursive: true });
      writeFileSync(join(outside, 'keep'), 'keep\n');
      execFileSync(BASH, ['-c', `ln -s "${toPosix(outside)}" "${toPosix(join(prefix, '.lc-claude-stage.lnk01'))}"`]);
      const r = c.run();
      assert.equal(c.state().result, 'none', r.log);
      assert.match(r.log, /not removing .*\.lc-claude-stage\.a b' \(unexpected characters\)/);
      assert.ok(existsSync(join(prefix, '.lc-claude-stage.a b')), 'a name this script cannot have created is left alone');
      assert.throws(() => lstatSync(join(prefix, '.lc-claude-stage.lnk01')), /ENOENT/, 'the leftover link is gone');
      assert.equal(readFileSync(join(outside, 'keep'), 'utf8'), 'keep\n', 'and what it pointed at is untouched');
    } finally { c.w.cleanup(); }
  });

  test('re-audit #4: a leftover the claude link resolves into is never removed', { skip }, () => {
    const c = claudeWorld({ from: '2.1.270', layout: 'npm' });
    try {
      assert.equal((c.run(), c.state().result), 'updated', 'setup: a verified install of the pin');
      const prefix = join(c.w.root, 'prefix');
      const live = join(prefix, '.lc-claude-stage.live01', 'bin');
      mkdirSync(live, { recursive: true });
      writeFileSync(join(live, 'claude.exe'), buildScript(c.w, '2.1.280'));
      chmodSync(join(live, 'claude.exe'), 0o755);
      execFileSync(BASH, ['-c', `ln -sfn "${toPosix(join(live, 'claude.exe'))}" "${toPosix(c.link)}"`]);
      const r = c.run();
      assert.match(r.log, /\.lc-claude-stage\.live01 holds the build claude resolves to - left in place/);
      assert.ok(existsSync(join(live, 'claude.exe')), 'the build claude runs must survive the cleanup');
      assert.equal(c.current(), '2.1.280');
    } finally { c.w.cleanup(); }
  });

  test('final audit #2: a DIRECTORY at the temporary link name is refused at once, nothing is linked inside it', { skip }, () => {
    // The temp name carries the script's pid, so repoint_claude is run on its
    // own (extracted from the shipped script) in a shell whose $$ is known.
    const src = readFileSync(join(MCP_DIR, 'cli-update.sh'), 'utf8');
    const start = src.indexOf('repoint_claude() {');
    const fn = src.slice(start, start + src.slice(start).indexOf('\n}\n') + 3);
    const w = makeWorld();
    try {
      const target = join(w.root, 'build');
      const old = join(w.root, 'old-build');
      writeFileSync(target, '#!/bin/bash\n');
      writeFileSync(old, '#!/bin/bash\n');
      const link = join(w.root, 'claude');
      execFileSync(BASH, ['-c', `ln -s "${toPosix(old)}" "${toPosix(link)}"`]);
      const r = spawnSync(BASH, ['-c', [
        'set -u',
        `CLAUDE_BIN="${toPosix(link)}"; LC_USER="$(id -un)"; LOG="${toPosix(join(w.root, 'log'))}"`,
        'log() { echo "$*" >> "$LOG"; }',
        fn,
        'mkdir "$CLAUDE_BIN.lc-new.$$"',
        `repoint_claude "${toPosix(target)}"; echo "rc=$?"; ls -A "$CLAUDE_BIN.lc-new.$$" | wc -l`,
      ].join('\n')], { encoding: 'utf8' });
      const [rc, inside] = r.stdout.trim().split('\n');
      assert.equal(rc, 'rc=1', r.stderr);
      assert.equal(inside.trim(), '0', 'no link may be created inside the directory');
      assert.match(readIfExists(join(w.root, 'log')), /refusing to repoint: .*\.lc-new\.\d+ exists/);
      assert.equal(execFileSync(BASH, ['-c', `readlink "${toPosix(link)}"`], { encoding: 'utf8' }).trim(), toPosix(old), 'claude still points at the old build');
    } finally { w.cleanup(); }
  });

  test('a package whose claude bin cannot be found leaves the native build answering', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      writeFileSync(join(c.w.root, 'npm-package-no-bin'), '');
      const r = c.run();
      assert.equal(c.state().result, 'failed', r.log);
      assert.match(r.log, /names no usable claude bin/);
      assert.equal(c.current(), '2.1.250');
      assert.ok(c.target().startsWith(execFileSync(BASH, ['-c', `readlink -f "${toPosix(c.versions)}"`], { encoding: 'utf8' }).trim()));
    } finally { c.w.cleanup(); }
  });

  test('a pinned build that fails its smoke test returns to the native build it replaced', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250', smokeFailsOn: ['2.1.280'] });
    try {
      const r = c.run();
      const s = c.state();
      assert.equal(s.result, 'rolled_back', r.log);
      assert.equal(c.current(), '2.1.250');
      assert.equal(c.target(), execFileSync(BASH, ['-c', `readlink -f "${toPosix(join(c.versions, '2.1.250'))}"`], { encoding: 'utf8' }).trim());
      assert.equal(s.knownBadVersion, '2.1.280');
    } finally { c.w.cleanup(); }
  });

  test('an npm install that fails puts the native link back, so claude still answers', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      writeFileSync(join(c.w.root, 'npm-install-exit'), '1');
      const r = c.run();
      assert.equal(c.state().result, 'failed', r.log);
      assert.equal(c.current(), '2.1.250');
    } finally { c.w.cleanup(); }
  });

  test('already on the verified pin (npm layout): nothing is installed or smoke-tested', { skip }, () => {
    const c = claudeWorld({ from: '2.1.270', layout: 'npm' });
    try {
      assert.equal((c.run(), c.state().result), 'updated', 'setup: a verified install of the pin');
      writeFileSync(join(c.w.root, 'npm-calls.log'), '');
      const r = c.run();
      assert.equal(c.state().result, 'none', r.log);
      assert.equal(c.state().upToDate, true);
      assert.ok(!npmCalls(c.w).some(l => l.startsWith('install') || l.startsWith('pack')));
    } finally { c.w.cleanup(); }
  });

  test('audit B #6 regression: the NATIVE build already at the pinned version is replaced by the verified npm build', { skip }, () => {
    // Prod's Claude Code 2.1.286 came from the native installer, reported the
    // pinned version, and was called compliant without any integrity check.
    const c = claudeWorld({ from: '2.1.280' });
    try {
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.match(r.log, /reports the pinned 2\.1\.280 but no record of a verified install/);
      assert.match(c.target(), /lib\/node_modules\/@anthropic-ai\/claude-code\/bin\/claude\.exe$/);
      assert.ok(npmCalls(c.w).some(l => /^install .*lc-cli-verified\.[^/ ]+\/anthropic-ai-claude-code-2\.1\.280\.tgz$/.test(l)));
      assert.ok(existsSync(join(c.versions, '2.1.280')), 'the native build stays on disk as the rollback target');
    } finally { c.w.cleanup(); }
  });

  test('npm build at the pin but installed by hand (no record) is reinstalled', { skip }, () => {
    const c = claudeWorld({ from: '2.1.280', layout: 'npm' });
    try {
      const r = c.run();
      assert.equal(c.state().result, 'updated', r.log);
      assert.ok(npmCalls(c.w).some(l => l.startsWith('install')));
    } finally { c.w.cleanup(); }
  });

  test('a pin bump on the npm layout installs the new pin and rolls back by exact version', { skip }, () => {
    const c = claudeWorld({ from: '2.1.270', layout: 'npm', smokeFailsOn: ['2.1.280'] });
    try {
      const r = c.run();
      assert.equal(c.state().result, 'rolled_back', r.log);
      assert.equal(c.current(), '2.1.270');
      assert.ok(npmCalls(c.w).some(l => /install .*@anthropic-ai\/claude-code@2\.1\.270$/.test(l)));
    } finally { c.w.cleanup(); }
  });

  test('when both builds fail, the pin is restored and the link points at a REAL file', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250', smokeFailsOn: ['2.1.280', '2.1.250'] });
    try {
      const r = c.run();
      assert.equal(c.state().result, 'failed', r.log);
      assert.equal(c.current(), '2.1.280');
      assert.ok(existsSync(c.target()), 'the claude link must resolve to a real file');
    } finally { c.w.cleanup(); }
  });

  test('a claude binary that is NOT a symlink is never replaced or deleted', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      rmSync(c.link);
      writeFileSync(c.link, buildScript(c.w, '9.9.9'));
      chmodSync(c.link, 0o755);
      const r = c.run();
      assert.equal(c.state().result, 'failed', r.log);
      assert.ok(!lstatSync(c.link).isSymbolicLink());
      assert.equal(c.current(), '9.9.9');
    } finally { c.w.cleanup(); }
  });

  test('pruning keeps the native rollback spare after the move to npm', { skip }, () => {
    const c = claudeWorld({ from: '2.1.250' });
    try {
      c.run();
      assert.deepEqual(readdirSync(c.versions).length, 1, 'one native spare kept, older ones pruned');
    } finally { c.w.cleanup(); }
  });
});

describe('cli-update.sh - the real manifest', () => {
  test('the prod updater reads the SAME manifest the CE image installs from', () => {
    const script = readFileSync(join(MCP_DIR, 'cli-update.sh'), 'utf8');
    assert.match(script, /CLI_MANIFEST="\$\{CLI_MANIFEST:-\$SCRIPT_DIR\/bridge\/agent-cli-versions\.txt\}"/);
    const dockerfile = readFileSync(join(MCP_DIR, 'bridge', 'Dockerfile'), 'utf8');
    assert.match(dockerfile, /COPY bridge\/agent-cli-versions\.txt/);
    assert.doesNotMatch(script, /as_lc "claude update"/, 'the native self-updater must not drive installs any more');
    assert.doesNotMatch(script, /npm install[^\n]*\$(upstream|latest)\b/, 'no install may be driven by the registry latest');
  });

  test('every CLI the prod updater manages has a usable pin in the manifest', () => {
    const manifest = readFileSync(join(MCP_DIR, 'bridge', 'agent-cli-versions.txt'), 'utf8');
    for (const pkg of ['@anthropic-ai/claude-code', '@openai/codex']) {
      const line = manifest.split(/\r?\n/).find(l => l.startsWith(`${pkg}@`));
      assert.ok(line, `${pkg} is not pinned`);
      const [spec, policy, integrity] = line.trim().split(/\s+/);
      assert.match(spec, /@\d+\.\d+\.\d+$/);
      assert.match(policy, /^(run-scripts|ignore-scripts)$/);
      assert.match(integrity, /^sha512-[A-Za-z0-9+/]{86}==$/, `${pkg} must carry an integrity: the prod updater refuses '-'`);
    }
  });

  test('lc-bridge.service disables the Claude Code auto-updater so the pin holds between runs', () => {
    const unit = readFileSync(resolve(MCP_DIR, '..', 'deploy', 'systemd', 'lc-bridge.service'), 'utf8');
    assert.match(unit, /^Environment=DISABLE_AUTOUPDATER=1$/m);
    const keepAlive = readFileSync(join(MCP_DIR, 'refresh-token.sh'), 'utf8');
    assert.match(keepAlive, /DISABLE_AUTOUPDATER=1[^\n]*claude -p/);
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
