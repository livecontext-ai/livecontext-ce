/**
 * CLI health metrics - turns what the box knows about the agent CLIs into
 * Prometheus series the bridge's /metrics endpoint serves.
 *
 * Three facts get merged here, because no single one of them is sufficient:
 *
 *   1. LIVE PROBE (cli-detector.mjs) - is the binary there, what version, is a
 *      credential file present. Cheap, but "a credential file exists" is not
 *      "the credential still works": a dead refresh token leaves the file in
 *      place, so this alone reports a healthy CLI that fails on first use.
 *   2. AUTH HEARTBEAT (<cli>.auth.json, written by refresh-token.sh /
 *      codex-refresh-token.sh) - the timestamp of the last time a real one-turn
 *      call to the provider actually succeeded. Only a live call proves auth.
 *   3. UPDATE STATE (<cli>.update.json, written by cli-update.sh) - what the
 *      daily freshness run found and did.
 *
 * THE INVARIANT: an absent state file must never read as healthy. A box that
 * was rebuilt, a cron that was never installed and a script that has been
 * crashing for a week all produce NO file - and a missing series makes
 * `time() - lc_cli_auth_last_success_timestamp_seconds > 9h` match nothing,
 * i.e. silence exactly when the alert is needed. So every MANAGED cli always
 * gets its timestamp series, with 0 standing for "never seen", which is far in
 * the past and therefore fires. Unknown is stale, never fresh.
 *
 * ITS MIRROR IMAGE, which is just as important: a probe that could not reach a
 * conclusion must not be rendered as a conclusion. cli-detector reports a 4s
 * TIMEOUT and a genuinely absent binary with the same `installed: false`, and
 * `--version` really can take longer than 4s on a box busy running agent turns.
 * Publishing 0 for both would page "claudeCode is not installed" at severity
 * critical because the host was briefly loaded. So `installed` is published
 * ONLY when the probe concluded (it answered, or the binary is genuinely not in
 * PATH); anything else sets lc_cli_probe_ok to 0 and publishes no verdict.
 */

import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * CLIs this deployment actually relies on, so alerts only fire for those.
 * Gemini CLI and Mistral Vibe are legitimately absent on the cloud bridge host;
 * paging about them would be noise. Override with LC_MANAGED_CLIS (comma list).
 */
export const DEFAULT_MANAGED_CLIS = ['claudeCode', 'codex'];

/**
 * The one cli-detector error that is a real ANSWER rather than a failure to
 * answer. Every other error (a timeout, EACCES, a non-zero exit, a signal)
 * means we did not find out.
 */
const CONCLUSIVE_ABSENCE = 'binary not found in PATH';

export function managedClis(env = process.env) {
  const raw = (env.LC_MANAGED_CLIS || '').trim();
  if (!raw) return [...DEFAULT_MANAGED_CLIS];
  return raw.split(',').map((s) => s.trim()).filter(Boolean);
}

/** Prometheus label values escape backslash, double quote and newline - nothing else. */
export function escapeLabelValue(value) {
  return String(value ?? '')
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n');
}

/**
 * Did this probe reach a verdict on whether the binary is there?
 * Installed => yes. Not installed BECAUSE it is not in PATH => also yes.
 * Not installed because the probe timed out / was killed / errored => NO.
 */
export function probeConcluded(entry) {
  if (!entry) return false;
  if (entry.installed) return true;
  return entry.error === CONCLUSIVE_ABSENCE;
}

function readJsonFile(path) {
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8'));
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
  } catch {
    // Missing, unreadable, truncated mid-write: all mean "we do not know", and
    // the caller's default (0 / absent) already encodes that as unhealthy.
    return null;
  }
}

/**
 * Read the per-CLI state files a shell script drops in `dir`.
 * Returns { auth: {cliId: obj|null}, update: {cliId: obj|null} }.
 */
export function readCliState(dir, clis) {
  const auth = {};
  const update = {};
  for (const cli of clis) {
    auth[cli] = readJsonFile(join(dir, `${cli}.auth.json`));
    update[cli] = readJsonFile(join(dir, `${cli}.update.json`));
  }
  return { auth, update };
}

/** Coerce a state-file field to a finite non-negative epoch-seconds number, else 0. */
function epochSeconds(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
}

/**
 * Strictly true only. Anything else - false, missing, a string, undefined - is
 * 0, because this gauge answers "did we PROVE it" and an unproven claim is not
 * a positive one.
 */
function boolGauge(value) {
  return value === true ? 1 : 0;
}

/**
 * A non-blocking snapshot of the CLI probe.
 *
 * /metrics must not await a spawn. cli-detector caches for 30s and Prometheus
 * scrapes this job every 30s, so awaiting it would spawn all four CLIs on
 * essentially every scrape - ~11.5k `--version` processes a day on the host
 * that also runs the agent turns - and a slow probe would push the scrape past
 * its timeout, turning a healthy bridge into `up{job="bridge"} == 0` and losing
 * bridge_up along with it.
 *
 * So the scrape is served from the last snapshot and the refresh happens behind
 * it. Before the first refresh completes the snapshot is null, which renders as
 * "we do not know" rather than as a verdict.
 */
export function createCliSnapshotCache({ detect, maxAgeMs = 120_000, now = Date.now }) {
  let value = null;
  let at = 0;
  let inFlight = null;

  const refresh = () => {
    if (inFlight) return inFlight;
    // detect() is called SYNCHRONOUSLY so the probe is genuinely under way by the
    // time get() returns; only the waiting is deferred. A `Promise.resolve().then`
    // wrapper would delay the call by a microtask, which makes "the refresh has
    // started" untestable and, at startup, needlessly delays the first snapshot.
    let started;
    try {
      started = Promise.resolve(detect());
    } catch (err) {
      started = Promise.reject(err);
    }
    inFlight = started
      .then((v) => { value = v; at = now(); })
      .catch(() => { /* keep the previous snapshot: stale beats fabricated */ })
      .finally(() => { inFlight = null; });
    return inFlight;
  };

  return {
    /** Current snapshot (possibly null), triggering a background refresh when stale. */
    get() {
      // `value === null` is checked explicitly rather than relying on the age
      // comparison: with at = 0 the age is only "huge" because the real clock is,
      // and an injected clock starting at 0 would never trigger the FIRST probe.
      // "We have no snapshot" is its own reason to refresh, not a special case of
      // staleness.
      if (value === null || now() - at >= maxAgeMs) refresh();
      return value;
    },
    /** Seconds since the snapshot was taken, or null when there has never been one. */
    ageSeconds() {
      return value === null ? null : Math.max(0, Math.floor((now() - at) / 1000));
    },
    /** Await the in-flight refresh. Tests use it; the request path never does. */
    settled() {
      return inFlight || Promise.resolve();
    },
  };
}

/**
 * Render the lc_cli_* metric block.
 *
 * @param {object}  args
 * @param {object|null} args.clis    detectAll() snapshot, or null when there is none yet
 * @param {object}  args.state       readCliState() result
 * @param {string[]} args.managed    CLI ids that must always produce series
 * @param {number|null} [args.probeAgeSeconds]  age of the snapshot, for diagnosis
 * @returns {string[]} metric lines (no trailing newline)
 */
export function renderCliHealthMetrics({ clis, state, managed, probeAgeSeconds = null }) {
  const lines = [];
  const haveSnapshot = clis && typeof clis === 'object';

  lines.push('# HELP lc_cli_detect_ok Whether the bridge holds a CLI probe snapshot at all (1=yes)');
  lines.push('# TYPE lc_cli_detect_ok gauge');
  lines.push(`lc_cli_detect_ok ${haveSnapshot ? 1 : 0}`);

  if (probeAgeSeconds !== null && Number.isFinite(probeAgeSeconds)) {
    lines.push('# HELP lc_cli_probe_age_seconds Age of the CLI probe snapshot the other lc_cli_ series come from');
    lines.push('# TYPE lc_cli_probe_age_seconds gauge');
    lines.push(`lc_cli_probe_age_seconds ${probeAgeSeconds}`);
  }

  lines.push('# HELP lc_cli_managed Agent CLIs this deployment depends on (alert scope)');
  lines.push('# TYPE lc_cli_managed gauge');
  for (const cli of managed) {
    lines.push(`lc_cli_managed{cli="${escapeLabelValue(cli)}"} 1`);
  }

  // ─── Live probe ─────────────────────────────────────────────────────────
  if (haveSnapshot) {
    const entries = Object.values(clis).filter((e) => e && e.id);

    // Did the probe reach a verdict? A timeout on a loaded host must not be
    // published as "not installed" - see the header. This is the series the
    // inconclusive-probe alert watches.
    lines.push('# HELP lc_cli_probe_ok Whether the last probe reached a verdict for this CLI (0=timed out or errored, verdict withheld)');
    lines.push('# TYPE lc_cli_probe_ok gauge');
    for (const e of entries) {
      lines.push(`lc_cli_probe_ok{cli="${escapeLabelValue(e.id)}"} ${probeConcluded(e) ? 1 : 0}`);
    }

    // Emitted ONLY for a concluded probe. An inconclusive one publishes no
    // verdict at all, so AgentCliMissing cannot fire on it.
    lines.push('# HELP lc_cli_installed Whether the CLI binary answered --version (only published when the probe concluded)');
    lines.push('# TYPE lc_cli_installed gauge');
    for (const e of entries) {
      if (!probeConcluded(e)) continue;
      lines.push(`lc_cli_installed{cli="${escapeLabelValue(e.id)}"} ${e.installed ? 1 : 0}`);
    }

    lines.push('# HELP lc_cli_credential_present Whether a credential file or API key env exists (heuristic, NOT proof the token works)');
    lines.push('# TYPE lc_cli_credential_present gauge');
    for (const e of entries) {
      lines.push(`lc_cli_credential_present{cli="${escapeLabelValue(e.id)}"} ${e.authenticated ? 1 : 0}`);
    }

    lines.push('# HELP lc_cli_version_info Installed CLI version, carried as a label');
    lines.push('# TYPE lc_cli_version_info gauge');
    for (const e of entries) {
      if (!e.version) continue;
      lines.push(`lc_cli_version_info{cli="${escapeLabelValue(e.id)}",version="${escapeLabelValue(e.version)}"} 1`);
    }
  }

  // ─── Auth heartbeat (the series that actually proves the CLI can run) ────
  lines.push('# HELP lc_cli_auth_last_success_timestamp_seconds Unix time of the last SUCCESSFUL live provider call (0=never observed)');
  lines.push('# TYPE lc_cli_auth_last_success_timestamp_seconds gauge');
  for (const cli of managed) {
    lines.push(`lc_cli_auth_last_success_timestamp_seconds{cli="${escapeLabelValue(cli)}"} ${epochSeconds(state.auth?.[cli]?.lastSuccessAt)}`);
  }

  lines.push('# HELP lc_cli_auth_last_attempt_timestamp_seconds Unix time of the last live provider call attempt (0=never observed)');
  lines.push('# TYPE lc_cli_auth_last_attempt_timestamp_seconds gauge');
  for (const cli of managed) {
    lines.push(`lc_cli_auth_last_attempt_timestamp_seconds{cli="${escapeLabelValue(cli)}"} ${epochSeconds(state.auth?.[cli]?.lastAttemptAt)}`);
  }

  lines.push('# HELP lc_cli_auth_dead Live call failed with an authentication error - an interactive login on the bridge host is required (1=dead)');
  lines.push('# TYPE lc_cli_auth_dead gauge');
  for (const cli of managed) {
    lines.push(`lc_cli_auth_dead{cli="${escapeLabelValue(cli)}"} ${boolGauge(state.auth?.[cli]?.dead)}`);
  }

  // ─── Update freshness ───────────────────────────────────────────────────
  lines.push('# HELP lc_cli_update_last_check_timestamp_seconds Unix time of the last freshness check (0=never observed)');
  lines.push('# TYPE lc_cli_update_last_check_timestamp_seconds gauge');
  for (const cli of managed) {
    lines.push(`lc_cli_update_last_check_timestamp_seconds{cli="${escapeLabelValue(cli)}"} ${epochSeconds(state.update?.[cli]?.checkedAt)}`);
  }

  // 0 unless PROVEN true: a CLI we could not prove current is treated as behind.
  lines.push('# HELP lc_cli_update_up_to_date Installed version equals the latest published version (1=proven current, 0=behind or unknown)');
  lines.push('# TYPE lc_cli_update_up_to_date gauge');
  for (const cli of managed) {
    lines.push(`lc_cli_update_up_to_date{cli="${escapeLabelValue(cli)}"} ${boolGauge(state.update?.[cli]?.upToDate)}`);
  }

  lines.push('# HELP lc_cli_update_last_result Outcome of the last freshness run, carried as a label (none|updated|rolled_back|skipped_known_bad|failed|unknown)');
  lines.push('# TYPE lc_cli_update_last_result gauge');
  for (const cli of managed) {
    const result = state.update?.[cli]?.result || 'unknown';
    lines.push(`lc_cli_update_last_result{cli="${escapeLabelValue(cli)}",result="${escapeLabelValue(result)}"} 1`);
  }

  return lines;
}

/**
 * Convenience wrapper used by the /metrics handler: read state from disk and
 * render. Never throws - a metrics endpoint that 500s because one file was
 * unreadable would take every OTHER bridge metric down with it, including
 * bridge_up, which is the one series the ServiceDown alert relies on.
 */
export function collectCliHealthMetrics({ clis, stateDir, probeAgeSeconds = null, env = process.env }) {
  try {
    const managed = managedClis(env);
    const state = readCliState(stateDir, managed);
    return renderCliHealthMetrics({ clis, state, managed, probeAgeSeconds });
  } catch {
    return [
      '# HELP lc_cli_detect_ok Whether the bridge holds a CLI probe snapshot at all (1=yes)',
      '# TYPE lc_cli_detect_ok gauge',
      'lc_cli_detect_ok 0',
    ];
  }
}
