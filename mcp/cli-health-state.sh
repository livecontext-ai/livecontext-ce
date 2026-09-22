#!/bin/bash
# Shared state-file writers for the agent-CLI watchdogs.
#
# Sourced by refresh-token.sh, codex-refresh-token.sh and cli-update.sh. The
# files they write are read by the bridge's /metrics endpoint
# (mcp/bridge/lib/cliHealth.mjs) and turned into the lc_cli_* series the
# Prometheus alert rules watch. That is the whole point: before this, these
# scripts only ever logged, and a log nobody queries is not an alarm.
#
# Two rules the writers enforce, both learned the hard way:
#
#   1. WRITE ATOMICALLY. Prometheus scrapes every 30s and these scripts run on
#      their own schedule; a reader that catches a half-written file would see
#      invalid JSON. Write to <file>.tmp and mv (same filesystem => rename(2)).
#   2. NEVER CLOBBER lastSuccessAt ON FAILURE. The success timestamp is the
#      heartbeat the alert subtracts from time(); resetting it to 0 on a
#      transient network blip would page for a dead login that is not dead.
#      A failed run updates lastAttemptAt and `dead`, and carries the previous
#      success forward.
#
# State dir: $CLI_STATE_DIR, default <bridge root>/state. Kept in sync with
# server.mjs's CLI_HEALTH_STATE_DIR default ("../state" from bridge/bridge).

CLI_STATE_DIR="${CLI_STATE_DIR:-/opt/livecontext/services/bridge/state}"

# write_cli_auth_state <cliId> <success:0|1> <dead:0|1> <detail>
#
# cliId MUST be the camelCase id cli-detector.mjs uses (claudeCode / codex),
# because that string becomes the `cli` Prometheus label and the alert rules
# join on it against lc_cli_managed.
write_cli_auth_state() {
  # `local`, because these are SOURCED into the caller. Without it, every call
  # assigns `cli_id`, `ok`, `dead`, `detail`, `file` and `now` in the sourcing
  # script's own namespace - and cli-update.sh uses names from the same small
  # vocabulary (`latest`, `result`). No call site is corrupted today; it is one
  # inserted line away, and the symptom would be a state file reporting the
  # wrong version with nothing to show for it.
  local cli_id ok dead detail file now
  cli_id="$1"; ok="$2"; dead="$3"; detail="$4"
  mkdir -p "$CLI_STATE_DIR" 2>/dev/null || return 0
  file="$CLI_STATE_DIR/${cli_id}.auth.json"
  now=$(date +%s)

  python3 - "$file" "$cli_id" "$ok" "$dead" "$detail" "$now" <<'PY' 2>/dev/null || return 0
import json, os, sys

path, cli_id, ok, dead, detail, now = sys.argv[1:7]
now = int(now)

# Carry the previous success forward: a failing run must not erase the heartbeat.
previous_success = 0
try:
    with open(path) as fh:
        previous_success = int(json.load(fh).get("lastSuccessAt", 0) or 0)
except Exception:
    pass

payload = {
    "cli": cli_id,
    "lastAttemptAt": now,
    "lastSuccessAt": now if ok == "1" else previous_success,
    "dead": dead == "1",
    # Bounded: this ends up in a log line and an operator's eyes, not a parser.
    "detail": (detail or "")[:500],
}

tmp = path + ".tmp"
with open(tmp, "w") as fh:
    json.dump(payload, fh)
os.replace(tmp, path)
os.chmod(path, 0o644)
PY
}

# write_cli_update_state <cliId> <installed> <latest> <result> <detail>
#
# result is one of: none (already current) | updated | rolled_back |
# skipped_known_bad | failed.
#
# upToDate is DERIVED here rather than passed in, so a caller cannot report
# "up to date" while naming two different versions.
#
# knownBadVersion is derived here too, and it is the field that makes the
# no-retry guard actually work. Deriving it from `result` at READ time did not:
# the skip writes result=skipped_known_bad, which erased the rolled_back marker
# it had just acted on, so the guard fired every OTHER day - the broken build
# was reinstalled and re-rolled-back on a 48h cycle, and the critical alert
# flapped with a "resolved" on the off day while production was still pinned.
# Measured over four simulated runs, not reasoned about.
#
# It is BOUNDED by construction rather than by a timeout: any `updated` or `none`
# clears it, because both mean we are running something current. Only
# skipped_known_bad and failed carry it forward. So it can pin a CLI exactly as
# long as upstream keeps offering the same broken build, and not one run longer.
write_cli_update_state() {
  # See write_cli_auth_state: `latest` and `result` in particular are names
  # cli-update.sh holds live values in while it calls this.
  local cli_id installed latest result detail file now
  cli_id="$1"; installed="$2"; latest="$3"; result="$4"; detail="$5"
  mkdir -p "$CLI_STATE_DIR" 2>/dev/null || return 0
  file="$CLI_STATE_DIR/${cli_id}.update.json"
  now=$(date +%s)

  python3 - "$file" "$cli_id" "$installed" "$latest" "$result" "$detail" "$now" <<'PY' 2>/dev/null || return 0
import json, os, sys

path, cli_id, installed, latest, result, detail, now = sys.argv[1:8]

# Unknown on either side is NOT up to date: a version we could not read cannot
# be proven current, and the alert treats 0 as "behind" on purpose.
up_to_date = bool(installed) and bool(latest) and installed == latest

previous_bad = ""
try:
    with open(path) as fh:
        previous_bad = json.load(fh).get("knownBadVersion") or ""
except Exception:
    pass

if result == "rolled_back":
    # `latest` is empty when the build could not even report its version, and an
    # empty marker means "remember nothing" - so the most broken case is the one
    # that is NOT remembered, and it retries daily. Deliberate: with no version
    # to compare against there is nothing to skip on.
    known_bad = latest
elif result in ("updated", "none"):
    known_bad = ""
else:
    known_bad = previous_bad

payload = {
    "cli": cli_id,
    "checkedAt": int(now),
    "installed": installed,
    "latest": latest,
    "upToDate": up_to_date,
    "result": result,
    "knownBadVersion": known_bad,
    "detail": (detail or "")[:500],
}

tmp = path + ".tmp"
with open(tmp, "w") as fh:
    json.dump(payload, fh)
os.replace(tmp, path)
os.chmod(path, 0o644)
PY
}

# known_bad_version <cliId>
#
# The version this CLI was rolled back from, or empty. Read from the persisted
# `knownBadVersion` field, NOT inferred from `result` - see write_cli_update_state
# for why that inference made the guard work every other day.
#
# Why it exists: without it the freshness run retries the same broken upstream
# build EVERY DAY - download, install, smoke test, fail, roll back - and
# AgentCliUpdateRolledBack, being critical, repeats hourly forever for a fact the
# operator understood the first morning. That is not an exponential runaway, it
# is worse in practice: a steady drip that trains everyone to ignore the channel.
#
# Only a rollback sets the marker. A `failed` run deliberately does NOT: it may
# have failed for a reason that has nothing to do with the version (registry
# down, provider outage), and skipping the next attempt over that would pin the
# CLI for a transient.
known_bad_version() {
  local cli_id file
  cli_id="$1"
  file="$CLI_STATE_DIR/${cli_id}.update.json"
  [ -r "$file" ] || return 0
  python3 - "$file" <<'PY' 2>/dev/null
import json, sys
try:
    with open(sys.argv[1]) as fh:
        d = json.load(fh)
except Exception:
    raise SystemExit(0)
print(d.get("knownBadVersion") or "")
PY
}
