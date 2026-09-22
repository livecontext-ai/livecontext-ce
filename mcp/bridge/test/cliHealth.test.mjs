/**
 * Tests for the lc_cli_* metric block the bridge serves on /metrics.
 *
 * The rule these are all circling: an agent CLI whose health we cannot
 * establish must render as UNHEALTHY, never as absent and never as fine. Every
 * alert downstream is a comparison against these series, and a series that is
 * not there matches nothing, fires nothing, and is indistinguishable from a
 * healthy system.
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  renderCliHealthMetrics,
  collectCliHealthMetrics,
  createCliSnapshotCache,
  readCliState,
  managedClis,
  escapeLabelValue,
  probeConcluded,
  DEFAULT_MANAGED_CLIS,
} from '../lib/cliHealth.mjs';

const MANAGED = ['claudeCode', 'codex'];

/** Pull the numeric value of a single-label metric line out of rendered output. */
function valueOf(lines, metric, cli) {
  const prefix = `${metric}{cli="${cli}"}`;
  const line = lines.find((l) => l.startsWith(prefix));
  return line === undefined ? undefined : Number(line.slice(prefix.length).trim());
}

function emptyState() {
  return { auth: { claudeCode: null, codex: null }, update: { claudeCode: null, codex: null } };
}

function detected(overrides = {}) {
  return {
    claudeCode: { id: 'claudeCode', installed: true, version: '2.1.272', authenticated: true, ...overrides.claudeCode },
    codex: { id: 'codex', installed: true, version: '0.154.0', authenticated: true, ...overrides.codex },
  };
}

test('a managed CLI with no state file still gets a heartbeat series, at 0', () => {
  // THE invariant. The alert is `time() - <series> > 9h`; emitting nothing here
  // would make that expression match nothing on a box where the keep-alive was
  // never installed, so the alert could not fire in the one case it exists for.
  // 0 is 1970, which reads as maximally stale and pages.
  const lines = renderCliHealthMetrics({ clis: detected(), state: emptyState(), managed: MANAGED });

  for (const cli of MANAGED) {
    assert.equal(valueOf(lines, 'lc_cli_auth_last_success_timestamp_seconds', cli), 0);
    assert.equal(valueOf(lines, 'lc_cli_update_last_check_timestamp_seconds', cli), 0);
  }
});

test('a CLI we could not prove current is reported as NOT up to date', () => {
  // Same reasoning one level down: "we did not manage to read a version" must
  // not render as "the version is fine".
  const state = emptyState();
  state.update.codex = { checkedAt: 1789467139, installed: '0.154.0', latest: '', upToDate: false, result: 'failed' };

  const lines = renderCliHealthMetrics({ clis: detected(), state, managed: MANAGED });

  assert.equal(valueOf(lines, 'lc_cli_update_up_to_date', 'codex'), 0);
  assert.ok(lines.includes('lc_cli_update_last_result{cli="codex",result="failed"} 1'));
});

test('a state file with no result at all renders result="unknown", not a missing series', () => {
  const lines = renderCliHealthMetrics({ clis: detected(), state: emptyState(), managed: MANAGED });
  assert.ok(lines.includes('lc_cli_update_last_result{cli="claudeCode",result="unknown"} 1'));
});

test('up_to_date is 0 unless PROVEN true, for every shape of "we do not know"', () => {
  // The third application of the never-observed rule, and the one a regression
  // would silence rather than make noisy: if an unknown state rendered as 1,
  // AgentCliOutOfDate would go quiet on exactly the boxes it exists for.
  // Mutating boolGauge to accept anything truthy passes every OTHER test here.
  const state = emptyState();
  for (const value of [undefined, null, false, 'true', 1, 'yes', {}]) {
    state.update.codex = value === undefined ? {} : { upToDate: value };
    const lines = renderCliHealthMetrics({ clis: detected(), state, managed: MANAGED });
    assert.equal(valueOf(lines, 'lc_cli_update_up_to_date', 'codex'), 0, `upToDate: ${JSON.stringify(value)}`);
  }
  // Only a real boolean true counts.
  state.update.codex = { upToDate: true };
  const lines = renderCliHealthMetrics({ clis: detected(), state, managed: MANAGED });
  assert.equal(valueOf(lines, 'lc_cli_update_up_to_date', 'codex'), 1);
});

test('auth_dead is 0 unless PROVEN true, so an unreadable state never pages as a dead login', () => {
  const state = emptyState();
  for (const value of [undefined, null, false, 'true', 1]) {
    state.auth.codex = value === undefined ? {} : { dead: value };
    const lines = renderCliHealthMetrics({ clis: detected(), state, managed: MANAGED });
    assert.equal(valueOf(lines, 'lc_cli_auth_dead', 'codex'), 0, `dead: ${JSON.stringify(value)}`);
  }
  state.auth.codex = { dead: true };
  const lines = renderCliHealthMetrics({ clis: detected(), state, managed: MANAGED });
  assert.equal(valueOf(lines, 'lc_cli_auth_dead', 'codex'), 1);
});

test('the auth heartbeat and the dead flag come from the state file, not from the probe', () => {
  // cli-detector reports `authenticated` from the PRESENCE of a credential file.
  // A dead refresh token leaves that file in place, so the probe says "fine"
  // while the CLI cannot run. The two facts must stay separate series.
  const state = emptyState();
  state.auth.claudeCode = { lastAttemptAt: 1789467134, lastSuccessAt: 1789460000, dead: true, detail: 'auth dead' };

  const lines = renderCliHealthMetrics({ clis: detected(), state, managed: MANAGED });

  assert.equal(valueOf(lines, 'lc_cli_credential_present', 'claudeCode'), 1, 'the probe still sees the file');
  assert.equal(valueOf(lines, 'lc_cli_auth_dead', 'claudeCode'), 1, 'but the live call proved it dead');
  assert.equal(valueOf(lines, 'lc_cli_auth_last_success_timestamp_seconds', 'claudeCode'), 1789460000);
});

test('no snapshot yet reports detect_ok=0 and emits NO per-CLI installed series', () => {
  // Reporting installed=0 from a probe that never ran would fabricate an
  // outage. Absent is correct here, and lc_cli_detect_ok carries the meaning.
  const lines = renderCliHealthMetrics({ clis: null, state: emptyState(), managed: MANAGED });

  assert.ok(lines.includes('lc_cli_detect_ok 0'));
  assert.ok(!lines.some((l) => l.startsWith('lc_cli_installed{')), 'must not claim a CLI is missing without a probe');
  // The state-file series survive: they do not depend on the probe.
  assert.equal(valueOf(lines, 'lc_cli_auth_last_success_timestamp_seconds', 'codex'), 0);
});

test('a probe that TIMED OUT publishes no installed verdict, only probe_ok=0', () => {
  // THE mirror of the never-observed invariant, and the more dangerous half.
  // cli-detector reports a 4s timeout and a genuinely absent binary with the
  // same `installed: false`, and `claude --version` really can exceed 4s on a
  // box busy running agent turns. Publishing 0 for both paged "claudeCode is not
  // installed" at severity critical because the host was briefly loaded.
  const clis = {
    claudeCode: { id: 'claudeCode', installed: false, version: null, authenticated: true, error: 'probe timed out after 4000ms' },
    codex: { id: 'codex', installed: true, version: '0.154.0', authenticated: true, error: null },
  };
  const lines = renderCliHealthMetrics({ clis, state: emptyState(), managed: MANAGED });

  assert.equal(valueOf(lines, 'lc_cli_probe_ok', 'claudeCode'), 0);
  assert.equal(valueOf(lines, 'lc_cli_installed', 'claudeCode'), undefined,
    'a timeout must NOT be published as "not installed" - that is a critical page for a loaded host');
  // The CLI that did answer is unaffected.
  assert.equal(valueOf(lines, 'lc_cli_probe_ok', 'codex'), 1);
  assert.equal(valueOf(lines, 'lc_cli_installed', 'codex'), 1);
});

test('a binary genuinely absent from PATH IS a verdict, and is published as installed=0', () => {
  // The counterpart: this one must page, so withholding it would be the
  // opposite mistake.
  const clis = {
    claudeCode: { id: 'claudeCode', installed: false, version: null, authenticated: false, error: 'binary not found in PATH' },
  };
  const lines = renderCliHealthMetrics({ clis, state: emptyState(), managed: MANAGED });

  assert.equal(valueOf(lines, 'lc_cli_probe_ok', 'claudeCode'), 1, 'not-in-PATH is an answer, not a failure to answer');
  assert.equal(valueOf(lines, 'lc_cli_installed', 'claudeCode'), 0);
});

test('probeConcluded separates an answer from a failure to answer', () => {
  assert.equal(probeConcluded({ installed: true }), true);
  assert.equal(probeConcluded({ installed: false, error: 'binary not found in PATH' }), true);
  for (const error of [
    'probe timed out after 4000ms',
    'exited with code 1',
    'terminated by signal SIGKILL',
    'EACCES: permission denied',
    null,
  ]) {
    assert.equal(probeConcluded({ installed: false, error }), false, `error: ${error}`);
  }
  assert.equal(probeConcluded(null), false);
  assert.equal(probeConcluded(undefined), false);
});

test('lc_cli_managed scopes the alerts to the CLIs this deployment installs', () => {
  // Gemini CLI and Mistral Vibe are legitimately absent on the bridge host.
  // Without this series the AgentCliMissing rule would page for them forever.
  const clis = detected();
  // A CONCLUDED probe (the binary really is not in PATH), so lc_cli_installed is
  // published for it - which is exactly why the alert needs lc_cli_managed to
  // scope it out, rather than relying on the verdict being withheld.
  clis.geminiCli = { id: 'geminiCli', installed: false, version: null, authenticated: false, error: 'binary not found in PATH' };

  const lines = renderCliHealthMetrics({ clis, state: emptyState(), managed: MANAGED });

  assert.ok(lines.includes('lc_cli_managed{cli="claudeCode"} 1'));
  assert.ok(lines.includes('lc_cli_managed{cli="codex"} 1'));
  assert.ok(!lines.includes('lc_cli_managed{cli="geminiCli"} 1'), 'gemini must not be in the alert scope');
  // It is still REPORTED, just not alerted on.
  assert.equal(valueOf(lines, 'lc_cli_installed', 'geminiCli'), 0);
});

test('a version string with quotes or backslashes cannot break the exposition format', () => {
  // A malformed label value corrupts the whole scrape, taking bridge_up down
  // with it - the series ServiceDown watches.
  assert.equal(escapeLabelValue('2.1"x\\y\nz'), '2.1\\"x\\\\y\\nz');

  const clis = detected({ claudeCode: { version: '1.0"evil' } });
  const lines = renderCliHealthMetrics({ clis, state: emptyState(), managed: MANAGED });
  assert.ok(lines.includes('lc_cli_version_info{cli="claudeCode",version="1.0\\"evil"} 1'));
});

test('a CLI with no readable version emits no version_info series rather than an empty label', () => {
  const clis = detected({ codex: { version: null } });
  const lines = renderCliHealthMetrics({ clis, state: emptyState(), managed: MANAGED });
  assert.ok(!lines.some((l) => l.startsWith('lc_cli_version_info{cli="codex"')));
});

test('readCliState survives a missing directory, a corrupt file and a non-object payload', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cli-health-'));
  try {
    writeFileSync(join(dir, 'claudeCode.auth.json'), '{"lastSuccessAt": 42');   // truncated mid-write
    writeFileSync(join(dir, 'codex.auth.json'), '"just a string"');             // valid JSON, wrong shape
    const state = readCliState(dir, MANAGED);
    assert.equal(state.auth.claudeCode, null);
    assert.equal(state.auth.codex, null);
    assert.equal(state.update.claudeCode, null);

    // And the rendered output still carries the pessimistic default.
    const lines = renderCliHealthMetrics({ clis: detected(), state, managed: MANAGED });
    assert.equal(valueOf(lines, 'lc_cli_auth_last_success_timestamp_seconds', 'claudeCode'), 0);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('a garbage timestamp in the state file is treated as never observed', () => {
  const state = emptyState();
  for (const bad of [-1, 'yesterday', null, NaN, undefined]) {
    state.auth.codex = { lastSuccessAt: bad };
    const lines = renderCliHealthMetrics({ clis: detected(), state, managed: MANAGED });
    assert.equal(valueOf(lines, 'lc_cli_auth_last_success_timestamp_seconds', 'codex'), 0, `value ${String(bad)}`);
  }
});

test('collectCliHealthMetrics never throws, and still says detect_ok when it cannot read anything', () => {
  // /metrics must not 500 because one file was unreadable: that would take
  // bridge_up with it, losing the service AND the alarm that watches it.
  const lines = collectCliHealthMetrics({ clis: detected(), stateDir: '/nonexistent/path/xyz' });
  assert.ok(lines.includes('lc_cli_detect_ok 1'));
  assert.equal(valueOf(lines, 'lc_cli_auth_last_success_timestamp_seconds', 'claudeCode'), 0);
});

describe('createCliSnapshotCache', () => {
  test('never makes the caller wait: the first read returns null and refreshes behind it', async () => {
    // The whole reason this exists. /metrics is scraped every 30s and the probe
    // spawns four processes with a 4s ceiling; awaiting it would push a loaded
    // host past its scrape timeout, which reports a healthy bridge as down and
    // takes bridge_up - the series ServiceDown watches - with it.
    let calls = 0;
    let release;
    const gate = new Promise((r) => { release = r; });
    const cache = createCliSnapshotCache({
      detect: () => { calls += 1; return gate.then(() => ({ codex: { id: 'codex', installed: true } })); },
    });

    assert.equal(cache.get(), null, 'must return immediately, before the probe resolves');
    assert.equal(calls, 1, 'and must have started the refresh');
    assert.equal(cache.get(), null, 'a second read while in flight must not spawn a second probe');
    assert.equal(calls, 1);

    release();
    await cache.settled();
    assert.deepEqual(cache.get(), { codex: { id: 'codex', installed: true } });
  });

  test('a failing refresh keeps the previous snapshot rather than blanking it', async () => {
    // Stale beats fabricated: dropping to null would republish "we know nothing"
    // and withdraw the installed verdicts over one transient failure.
    let fail = false;
    let now = 0;
    const cache = createCliSnapshotCache({
      detect: () => (fail ? Promise.reject(new Error('spawn failed')) : Promise.resolve({ codex: { id: 'codex', installed: true } })),
      maxAgeMs: 10,
      now: () => now,
    });

    cache.get();
    await cache.settled();
    assert.ok(cache.get(), 'first snapshot taken');

    fail = true;
    now = 1000;
    cache.get();
    await cache.settled();
    assert.deepEqual(cache.get(), { codex: { id: 'codex', installed: true } }, 'the old snapshot must survive');
  });

  test('reports the snapshot age so a frozen probe is visible', async () => {
    let now = 0;
    const cache = createCliSnapshotCache({ detect: async () => ({}), maxAgeMs: 100_000, now: () => now });
    assert.equal(cache.ageSeconds(), null, 'no snapshot, no age - never a fake 0');
    cache.get();
    await cache.settled();
    now = 45_000;
    assert.equal(cache.ageSeconds(), 45);
  });
});

test('managedClis defaults to the two CLIs the bridge host runs, and honours the override', () => {
  assert.deepEqual(managedClis({}), DEFAULT_MANAGED_CLIS);
  assert.deepEqual(managedClis({ LC_MANAGED_CLIS: ' codex , geminiCli ' }), ['codex', 'geminiCli']);
  assert.deepEqual(managedClis({ LC_MANAGED_CLIS: '   ' }), DEFAULT_MANAGED_CLIS, 'blank means default, not empty');
});

test('every rendered metric name is declared with HELP and TYPE', () => {
  // A metric without a TYPE is still scraped, so this never fails loudly in
  // prod; it just makes the series harder to read in Grafana forever.
  const lines = renderCliHealthMetrics({ clis: detected(), state: emptyState(), managed: MANAGED });
  const declared = new Set(
    lines.filter((l) => l.startsWith('# TYPE ')).map((l) => l.split(' ')[2]),
  );
  const emitted = new Set(
    lines.filter((l) => !l.startsWith('#')).map((l) => l.split(/[{ ]/)[0]),
  );
  for (const name of emitted) {
    assert.ok(declared.has(name), `${name} is emitted without a # TYPE line`);
  }
});

/**
 * Config coherence: a probe alert's `for` must outlast its target's scrape
 * interval.
 *
 * A `for` shorter than the scrape interval pages on a SINGLE failed scrape,
 * which for a target probed through Cloudflare means paging on one packet loss.
 * The rule is stated in both alert comments and obeyed by both alerts, and until
 * now nothing enforced it.
 *
 * It is asserted HERE rather than in alert-rules.test.yml because promtool
 * cannot: that file evaluates on a 5-minute grid, so there is no evaluation
 * instant between 30s and 3m at which a `for: 3m` and a `for: 30s` would behave
 * differently. Mutating `ExternalProbeFailing` to `for: 30s` passed the whole
 * promtool suite. Reading the two config files directly catches it, and catches
 * the mirror-image mistake too: lengthening a scrape interval without revisiting
 * the `for` it was chosen against.
 */
test('every sign-in/probe alert waits longer than one scrape of its target', () => {
  const __dirname = dirname(fileURLToPath(import.meta.url));
  const cfgDir = resolve(__dirname, '..', '..', '..', 'deploy', 'config');
  const prom = readFileSync(resolve(cfgDir, 'prometheus.yml'), 'utf8');
  const rules = readFileSync(resolve(cfgDir, 'alert-rules.yml'), 'utf8');

  const seconds = (n, unit) => Number(n) * (unit === 'm' ? 60 : 1);

  /** scrape_interval of one job, falling back to the file's global. */
  function scrapeSeconds(jobName) {
    const start = prom.indexOf(`- job_name: ${jobName}`);
    assert.notStrictEqual(start, -1, `job ${jobName} not found in prometheus.yml`);
    const next = prom.indexOf('\n  - job_name:', start + 1);
    const block = prom.slice(start, next === -1 ? undefined : next);
    const m = /scrape_interval:\s*(\d+)([sm])/.exec(block);
    if (m) return seconds(m[1], m[2]);
    const g = /^global:[\s\S]*?scrape_interval:\s*(\d+)([sm])/m.exec(prom);
    return g ? seconds(g[1], g[2]) : 15;
  }

  /** `for:` of one alert. */
  function forSeconds(alertName) {
    const start = rules.indexOf(`- alert: ${alertName}`);
    assert.notStrictEqual(start, -1, `alert ${alertName} not found in alert-rules.yml`);
    const next = rules.indexOf('- alert:', start + 1);
    const block = rules.slice(start, next === -1 ? undefined : next);
    const m = /^\s*for:\s*(\d+)([sm])\s*$/m.exec(block);
    assert.ok(m, `alert ${alertName} has no for:`);
    return seconds(m[1], m[2]);
  }

  for (const [alertName, jobName] of [
    ['LoginPageBroken', 'blackbox-login-page'],
    ['ExternalProbeFailing', 'blackbox-http'],
    ['FrontendIpv6ProbeFailing', 'blackbox-http-ip6'],
  ]) {
    const scrape = scrapeSeconds(jobName);
    const wait = forSeconds(alertName);
    assert.ok(
      wait >= scrape * 2,
      `${alertName} waits ${wait}s but ${jobName} is scraped every ${scrape}s - `
      + `it would page on a single failed scrape (need at least ${scrape * 2}s)`,
    );
  }
});

/**
 * Cross-file contract: every lc_cli_* series the ALERT RULES select must be one
 * this module actually emits.
 *
 * These live in two repositories of meaning with no compiler between them: a
 * rename here passes every node test and every promtool test (promtool evaluates
 * rules against the series the TEST supplies, not against what production
 * publishes), and the mismatch surfaces only as an alert that can never fire -
 * silent, health=ok, indistinguishable from a healthy fleet. The only other
 * guard is check-alert-metrics.sh sweeping a live Prometheus at deploy time,
 * which is late and on the box.
 */
test('every lc_cli_* metric the alert rules select is one this module emits', () => {
  const __dirname = dirname(fileURLToPath(import.meta.url));
  const rules = readFileSync(
    resolve(__dirname, '..', '..', '..', 'deploy', 'config', 'alert-rules.yml'),
    'utf8',
  );

  // Render with a fully-populated fixture so every conditional branch emits.
  const state = {
    auth: { claudeCode: { lastSuccessAt: 1, lastAttemptAt: 1, dead: true }, codex: { lastSuccessAt: 1, lastAttemptAt: 1, dead: false } },
    update: { claudeCode: { checkedAt: 1, upToDate: true, result: 'none' }, codex: { checkedAt: 1, upToDate: false, result: 'failed' } },
  };
  const lines = renderCliHealthMetrics({ clis: detected(), state, managed: MANAGED, probeAgeSeconds: 3 });
  const emitted = new Set(lines.filter((l) => !l.startsWith('#')).map((l) => l.split(/[{ ]/)[0]));

  // Only the `expr:` lines: a metric named in a comment or an annotation is
  // prose, not a selector, and demanding it exist would be noise.
  const selected = new Set();
  for (const line of rules.split('\n')) {
    const expr = /^\s*expr:\s*(.*)$/.exec(line);
    if (!expr) continue;
    for (const m of expr[1].matchAll(/\blc_cli_[a-z0-9_]+/g)) selected.add(m[0]);
  }

  assert.ok(selected.size >= 8, `expected the rules to select several lc_cli_ metrics, found ${selected.size}`);
  for (const name of selected) {
    assert.ok(emitted.has(name), `alert-rules.yml selects ${name}, which the bridge never emits - that rule can never fire`);
  }
});

/**
 * Cross-language contract: the shell writers and this reader must agree on the
 * field names. They are two files in two languages with no compiler between
 * them, so a rename on one side is silent - and the symptom is the pessimistic
 * default everywhere, i.e. alerts firing for a system that is fine.
 */
test('cli-health-state.sh writes exactly the fields cliHealth.mjs reads', () => {
  const __dirname = dirname(fileURLToPath(import.meta.url));
  const shell = readFileSync(resolve(__dirname, '..', '..', 'cli-health-state.sh'), 'utf8');
  const reader = readFileSync(resolve(__dirname, '..', 'lib', 'cliHealth.mjs'), 'utf8');

  for (const field of ['lastSuccessAt', 'lastAttemptAt', 'dead']) {
    assert.ok(shell.includes(`"${field}"`), `cli-health-state.sh no longer writes ${field}`);
    assert.ok(reader.includes(`?.${field}`), `cliHealth.mjs no longer reads ${field}`);
  }
  for (const field of ['checkedAt', 'upToDate', 'result']) {
    assert.ok(shell.includes(`"${field}"`), `cli-health-state.sh no longer writes ${field}`);
    assert.ok(reader.includes(`?.${field}`), `cliHealth.mjs no longer reads ${field}`);
  }

  // The file NAMES are half the contract too.
  assert.ok(shell.includes('${cli_id}.auth.json'), 'auth state filename changed in the shell writer');
  assert.ok(shell.includes('${cli_id}.update.json'), 'update state filename changed in the shell writer');
  assert.ok(reader.includes('${cli}.auth.json'), 'auth state filename changed in the reader');
  assert.ok(reader.includes('${cli}.update.json'), 'update state filename changed in the reader');
});
