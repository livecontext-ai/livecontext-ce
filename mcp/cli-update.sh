#!/bin/bash
# Keeps the agent CLIs lc-bridge spawns (Claude Code + Codex) on the latest
# published build, and REFUSES to leave prod on a build that cannot answer.
#
# Provisioned to /opt/livecontext/services/bridge/cli-update.sh by deploy.yml /
# deploy-direct.sh and scheduled by lc-cli-update.timer (daily, 04:17 UTC).
#
# A systemd timer and NOT root cron, unlike the two token keep-alives beside it:
# `crontab` is absent from the deploy user's sudoers whitelist on websearch-host
# while `systemctl enable|start lc-*` is present, so cron cannot be written from
# either deploy lane. The timer also gives the run journald output and a
# `systemctl list-timers` entry, which cron does not.
#
# ─── Why an updater at all ──────────────────────────────────────────────────
# lc-bridge spawns CLAUDE_BIN / CODEX_BIN from the livecontext prefix. Those
# installs drift, and a build too old for a model makes the provider answer 400
# "Claude Code X does not support this model"; an old Codex stopped parsing the
# current models cache ("missing field `base_instructions`"). Both failures look
# like "the product is broken", not "a CLI is old".
#
# ─── Why the smoke test, which is the part that did not exist before ────────
# "Always the latest version" applied blind is a standing bet that no upstream
# release ever breaks us, taken unattended at 04:17 while nobody is watching.
# The bridge PARSES these CLIs' output, so a changed flag or stream format
# breaks every agent run. After any version change this script therefore spends
# one real turn proving the new build still works, and repoints to the previous
# build if it does not.
#
# The rollback has a guard of its own: if the OLD build fails the same smoke
# test, the failure is not the update (provider outage, dead token, network),
# so we stay on the NEW build and report `failed` rather than pinning prod to a
# stale version for an unrelated reason. Rolling back on a transient error
# would be its own outage.
#
# And a rollback is remembered. Without that, a genuinely broken upstream build
# is re-downloaded, re-installed, re-tested and re-rolled-back EVERY DAY, and
# AgentCliUpdateRolledBack - a critical - repeats hourly forever about a fact
# understood on the first morning. So the next run skips the SAME version and
# reports `skipped_known_bad`, which drops the page to the
# already-behind warning. The day upstream publishes anything else, it retries
# normally: this is a memory of one bad build, not a pause switch.
#
# Everything it learns is written to the state dir, exported by the bridge as
# lc_cli_update_* and alerted on. A log nobody queries is not a safety net.
set -u

# Every path is overridable. The defaults ARE production; the overrides exist so
# the rollback state machine below can be exercised against a temporary tree with
# stub CLIs (mcp/bridge/test/cliUpdateScript.test.mjs). Code that repoints the
# binary prod depends on, unattended, as root, should not be code that can only
# be tested by running it in prod.
LC_USER="${LC_USER:-livecontext}"
LC_HOME="${LC_HOME:-/opt/livecontext}"
LC_PATH="${LC_PATH:-/opt/livecontext/.local/bin:/usr/local/bin:/usr/bin:/bin}"

CLAUDE_BIN="${CLAUDE_BIN:-$LC_HOME/.local/bin/claude}"
CLAUDE_VERSIONS="${CLAUDE_VERSIONS:-$LC_HOME/.local/share/claude/versions}"
CODEX_BIN="${CODEX_BIN:-$LC_HOME/.local/bin/codex}"
NPM_PREFIX="${NPM_PREFIX:-$LC_HOME/.local}"

LOG="${LOG:-/opt/livecontext/services/bridge/cli-update.log}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The state writers live in a sibling file. If it is missing (a partial deploy,
# a tarball that dropped it) every write_cli_* call below would be a bare
# "command not found": the token refresh itself would still work, but no
# heartbeat would ever be written, the metric would sit at 0 and
# AgentCliAuthStale would page forever about a login that is perfectly fine.
# So say it once, loudly and specifically, and keep the refresh running.
if [ -r "$SCRIPT_DIR/cli-health-state.sh" ]; then
  # shellcheck source=cli-health-state.sh
  . "$SCRIPT_DIR/cli-health-state.sh"
else
  logger -t lc-cli-watchdog -p user.crit "cli-health-state.sh missing at $SCRIPT_DIR - no CLI health metric will be written, so the staleness alerts will fire about a healthy login. Re-run the lane-2 deploy."
  write_cli_auth_state()   { :; }
  write_cli_update_state() { :; }
  known_bad_version()      { :; }
fi

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { echo "$(now) $*" >> "$LOG"; }

# Every version string here comes from parsing a CLI's `--version` output or the
# npm registry, and every one of them ends up inside a path, an `ln -sfn` target
# or a `su -c` command string. If an upstream release ever changes that output
# shape, an unvalidated parse silently becomes a wrong path (see the rollback,
# where it would mean a DANGLING claude symlink) or a shell injection point. So
# nothing is used unless it looks like a version.
is_version() { printf '%s' "${1:-}" | grep -qE '^[0-9][0-9A-Za-z.+-]*$'; }

# Run a command as the service account, with the same HOME/PATH the bridge uses.
# Every CLI action goes through here: running them as root would write
# root-owned files into the livecontext home and break the bridge at the next
# spawn (a class of breakage that is invisible until the next agent run).
as_lc() { su -s /bin/bash "$LC_USER" -c "export HOME='$LC_HOME' PATH='$LC_PATH'; $1"; }

# ─── Smoke test: one real turn through the CLI ──────────────────────────────
# Same invocation the token keep-alives use, so it exercises spawn + auth +
# model + output parsing, not just `--version`.
# SMOKE_CWD is explicit rather than inherited. codex exec runs with approvals
# and the sandbox both off, so whatever directory this script happens to be in
# becomes that agent's workspace. /tmp is inert; the Claude versions directory,
# which an earlier `cd` used to leak, is the opposite of inert.
SMOKE_CWD=/tmp
smoke_claude() { as_lc "cd '$SMOKE_CWD' && echo hi | claude -p 'reply ok' --max-turns 1" >/dev/null 2>&1; }
smoke_codex()  { as_lc "cd '$SMOKE_CWD' && echo 'reply ok' | codex exec - --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox" >/dev/null 2>&1; }

# repoint_claude <target> - move $CLAUDE_BIN to <target>, or refuse and say so.
#
# Two refusals, both learned from how `ln -sfn` behaves rather than from how it
# reads: it creates a DANGLING link without complaining, and `ln -sfn X X`
# unlinks the destination first, so repointing a path at itself DELETES it.
# `readlink -f` returns the path itself whenever $CLAUDE_BIN is a regular file
# (a wrapper script, an npm-style install, a hand-replaced link), which is
# exactly the input that turns a rollback into "there is no claude any more".
repoint_claude() {
  target="$1"
  if [ -z "$target" ] || [ ! -e "$target" ]; then
    log "claude: refusing to repoint at '$target' (does not exist)"
    return 1
  fi
  if [ ! -L "$CLAUDE_BIN" ]; then
    log "claude: $CLAUDE_BIN is not a symlink - refusing to replace it (not our layout)"
    return 1
  fi
  if [ "$target" = "$CLAUDE_BIN" ]; then
    log "claude: refusing to repoint $CLAUDE_BIN at itself (ln -sfn would delete it)"
    return 1
  fi
  ln -sfn "$target" "$CLAUDE_BIN" || return 1
  chown -h "$LC_USER:$LC_USER" "$CLAUDE_BIN" 2>/dev/null
  return 0
}

# smoke_with_retry <cli> - one retry, because a single provider blip must not
# be read as "the new build is broken". The delay is overridable so the tests do
# not have to spend a real minute per rollback path; production uses 30s.
SMOKE_RETRY_DELAY="${SMOKE_RETRY_DELAY:-30}"
# A non-numeric override would make the `-gt` below write "integer expression
# expected" to the journal on every run, forever, and this script has no set -e
# to stop it. Fall back rather than nag.
case "$SMOKE_RETRY_DELAY" in
  ''|*[!0-9]*) SMOKE_RETRY_DELAY=30 ;;
esac
smoke_with_retry() {
  if "smoke_$1"; then return 0; fi
  log "  smoke test for $1 failed, retrying in ${SMOKE_RETRY_DELAY}s"
  [ "$SMOKE_RETRY_DELAY" -gt 0 ] && sleep "$SMOKE_RETRY_DELAY"
  "smoke_$1"
}

# ─── Claude Code ────────────────────────────────────────────────────────────
# `claude update` writes a NEW file into versions/ and repoints the symlink.
# Running sessions keep their old inode, so nothing needs restarting: the next
# turn lc-bridge spawns picks up the new build on its own.
update_claude() {
  if [ ! -x "$CLAUDE_BIN" ]; then
    log "claude: binary missing at $CLAUDE_BIN"
    write_cli_update_state claudeCode "" "" failed "binary missing at $CLAUDE_BIN"
    return 0
  fi

  before=$(as_lc "claude --version" 2>/dev/null | awk '{print $1}')
  before_target=$(readlink -f "$CLAUDE_BIN" 2>/dev/null)
  log "claude: current=$before"

  # The npm registry is Claude's INDEPENDENT source of "what is the latest
  # build", and it is needed twice.
  #
  # Once for reporting: `claude update` is authoritative for the install but
  # tells us nothing about what it was aiming at, so every path used to write
  # latest = installed, which made upToDate true BY CONSTRUCTION and left
  # AgentCliOutOfDate structurally unable to fire for Claude on any happy path.
  #
  # Once for the no-retry guard: `claude update` has no dry run, so this is the
  # only cheap way to ask "has upstream published anything since the build we
  # rolled back from?".
  #
  # The native installer tracks the same releases (verified in prod: both report
  # the same version). If the two streams ever diverge the cost is bounded and
  # visible - a warning saying we cannot prove we are current - never a wrong
  # install, because this value NEVER drives the update. Unreachable registry
  # leaves it empty, which reads as "unknown", which is already "not up to date".
  npm_latest=$(as_lc "npm view @anthropic-ai/claude-code version" 2>/dev/null | tr -d '[:space:]')
  is_version "$npm_latest" || npm_latest=""

  # Do not retry a build we already rolled back from.
  bad=$(known_bad_version claudeCode)
  if [ -n "$bad" ]; then
    candidate="$npm_latest"
    if [ "$candidate" = "$bad" ]; then
      log "claude: upstream is still $bad, the build rolled back last time - not retrying"
      write_cli_update_state claudeCode "$before" "$candidate" skipped_known_bad \
        "$bad failed its smoke test previously and is still the latest published build"
      return 0
    fi
  fi

  if as_lc "claude update" >> "$LOG" 2>&1; then
    update_ok=true
  else
    update_ok=false
    log "claude: 'claude update' exited non-zero"
  fi

  after=$(as_lc "claude --version" 2>/dev/null | awk '{print $1}')
  after_target=$(readlink -f "$CLAUDE_BIN" 2>/dev/null)

  if ! is_version "$after"; then
    # The new build cannot even say what it is. That is a build prod must not
    # keep: a binary that exists but exits non-zero classifies as "exited with
    # code N", which the bridge treats as an INCONCLUSIVE probe, so
    # AgentCliMissing (critical) cannot fire for it and only a warning would ever
    # mention it. Roll back, exactly as for a failed smoke test.
    log "claude: version unreadable after update (got '${after}')"
    if repoint_claude "$before_target"; then
      recovered=$(as_lc "claude --version" 2>/dev/null | awk '{print $1}')
      if is_version "$recovered"; then
        logger -t lc-cli-update -p user.crit "Claude Code update produced a build that cannot report its version on websearch-host; rolled back to $before"
        log "claude: ROLLED BACK to $before (the new build could not report a version)"
        write_cli_update_state claudeCode "$before" "" rolled_back "the updated build could not report a version"
        return 0
      fi
    fi
    logger -t lc-cli-update -p user.crit "Claude Code cannot report a version on websearch-host and could not be rolled back - the claude-code provider is likely broken"
    write_cli_update_state claudeCode "$before" "" failed "version unreadable after update and rollback did not recover it"
    return 0
  fi

  if [ "$before" = "$after" ]; then
    # Unchanged has TWO meanings and they are not interchangeable. Only report
    # "current" when the updater actually said so; a failed update that left the
    # version alone must report `failed` with an UNKNOWN latest, so upToDate is
    # 0 and AgentCliOutOfDate can fire. Reporting latest="$after" here is what
    # made this rule structurally unable to fire for Claude: with no independent
    # source for "latest", every path claimed to be current by construction.
    if [ "$update_ok" = true ]; then
      # latest from the registry, not from ourselves. `claude update` exiting 0
      # without upgrading is a real behaviour (installer disabled, restart
      # required, permission fallback), and reporting latest = installed there
      # would claim we are current on the strength of nothing.
      log "claude: already at $after (latest published: ${npm_latest:-unknown})"
      write_cli_update_state claudeCode "$after" "$npm_latest" none "already current"
    else
      log "claude: update FAILED, still on $after (latest unknown)"
      write_cli_update_state claudeCode "$after" "" failed "'claude update' failed; latest version unknown"
    fi
    return 0
  fi

  log "claude: updated $before -> $after, smoke testing"
  if smoke_with_retry claude; then
    log "claude: smoke test OK on $after"
    write_cli_update_state claudeCode "$after" "${npm_latest:-$after}" updated "$before -> $after"
    return 0
  fi

  # The new build failed. Before blaming it, prove the old one works: if it
  # fails too, the fault is upstream of the CLI and rolling back fixes nothing.
  log "claude: smoke test FAILED on $after, trying rollback to $before"
  if repoint_claude "$before_target"; then
    if smoke_with_retry claude; then
      logger -t lc-cli-update -p user.crit "Claude Code $after failed its smoke test and was rolled back to $before on websearch-host - prod is pinned to an old build until this is investigated"
      log "claude: ROLLED BACK to $before (smoke test passes on the old build)"
      write_cli_update_state claudeCode "$before" "$after" rolled_back "$after failed its smoke test"
      return 0
    fi
    # Old build fails too => not the update's fault. Stay current.
    #
    # Restored from the path READ BACK after the update, never from one rebuilt
    # as "$CLAUDE_VERSIONS/$after": `ln -sfn` creates a dangling link without
    # complaining, and this branch is reached on a TRANSIENT provider outage, so
    # a rebuilt path that is wrong by one character would delete the working
    # claude binary from PATH for good.
    # Restoring can itself fail (the file was pruned, the link is not ours).
    # Report the version ACTUALLY in place: writing "$after" regardless made
    # upToDate true for a Claude sitting on the old build, so AgentCliOutOfDate
    # could never fire for it. Codex has the same guard, for the same reason.
    log "claude: old build $before ALSO fails - restoring $after, fault is not the update"
    if repoint_claude "$after_target"; then
      in_place="$after"
    else
      log "claude: could not restore $after - leaving $before in place"
      in_place="$before"
    fi
  else
    in_place="$before"
  fi

  logger -t lc-cli-update -p user.crit "Claude Code smoke test FAILED on websearch-host after updating $before -> $after, and the previous build fails too - check the provider, the token and the bridge"
  log "claude: smoke test FAILED on both builds (running $in_place)"
  write_cli_update_state claudeCode "$in_place" "${npm_latest:-$after}" failed "smoke test fails on $after and on $before"
}

# Old Claude builds are ~250 MB each. Pruned only AFTER the smoke test, so the
# rollback target is still on disk when it is needed. Keeps the live build plus
# the newest spare.
prune_claude_versions() {
  cur=$(basename "$(readlink -f "$CLAUDE_BIN" 2>/dev/null)" 2>/dev/null)
  [ -z "$cur" ] && return 0
  # The cd runs in a SUBSHELL. Without one it leaked into the rest of the
  # script, and the Codex smoke test - which runs with approvals and sandbox
  # both disabled - would then execute with its working directory set to the
  # directory holding the live Claude build.
  (
    cd "$CLAUDE_VERSIONS" 2>/dev/null || exit 0
    ls -1t 2>/dev/null | grep -Fxv "$cur" | tail -n +2 | while read -r v; do
      log "claude: pruning old build $v"
      rm -f -- "$v"
    done
  )
}

# ─── Codex CLI ──────────────────────────────────────────────────────────────
# The root-owned npm global at /usr/bin/codex never self-updates; this copy
# lives under the livecontext prefix precisely so this script can update it
# with no root privileges of its own.
update_codex() {
  if [ ! -x "$CODEX_BIN" ]; then
    log "codex: binary missing at $CODEX_BIN"
    write_cli_update_state codex "" "" failed "binary missing at $CODEX_BIN"
    return 0
  fi

  before=$(as_lc "codex --version" 2>/dev/null | awk '{print $NF}')
  latest=$(as_lc "npm view @openai/codex version" 2>/dev/null | tr -d '[:space:]')

  # $before is interpolated into an `npm install @openai/codex@$before` that runs
  # inside a `su -c` string, so it gets the same shape check as $latest and
  # $after. The header promises "nothing is used unless it looks like a version";
  # it was true of the other two only.
  if ! is_version "$before"; then
    log "codex: version unreadable (got '${before}')"
    write_cli_update_state codex "" "$latest" failed "codex --version did not report a version"
    return 0
  fi

  if ! is_version "$latest"; then
    # The registry is the only source for "latest". Unreachable means unknown,
    # and unknown must not be reported as current.
    log "codex: could not read the latest published version (registry unreachable?)"
    write_cli_update_state codex "$before" "" failed "npm view returned nothing"
    return 0
  fi

  if [ "$before" = "$latest" ]; then
    log "codex: already at $before"
    write_cli_update_state codex "$before" "$latest" none "already current"
    return 0
  fi

  # Same guard as Claude: the version on offer is the one that failed its smoke
  # test last time, so there is nothing to learn by installing it again.
  bad=$(known_bad_version codex)
  if [ -n "$bad" ] && [ "$latest" = "$bad" ]; then
    log "codex: upstream is still $bad, the build rolled back last time - not retrying"
    write_cli_update_state codex "$before" "$latest" skipped_known_bad \
      "$bad failed its smoke test previously and is still the latest published build"
    return 0
  fi

  log "codex: updating $before -> $latest"
  if ! as_lc "npm install -g --prefix '$NPM_PREFIX' '@openai/codex@$latest'" >> "$LOG" 2>&1; then
    log "codex: npm install failed, staying on $before"
    write_cli_update_state codex "$before" "$latest" failed "npm install of $latest failed"
    return 0
  fi

  after=$(as_lc "codex --version" 2>/dev/null | awk '{print $NF}')
  log "codex: now at $after, smoke testing"

  if smoke_with_retry codex; then
    log "codex: smoke test OK on $after"
    write_cli_update_state codex "$after" "$latest" updated "$before -> $after"
    return 0
  fi

  log "codex: smoke test FAILED on $after, trying rollback to $before"
  if [ -n "$before" ] && as_lc "npm install -g --prefix '$NPM_PREFIX' '@openai/codex@$before'" >> "$LOG" 2>&1; then
    if smoke_with_retry codex; then
      logger -t lc-cli-update -p user.crit "Codex $after failed its smoke test and was rolled back to $before on websearch-host - prod is pinned to an old build until this is investigated"
      log "codex: ROLLED BACK to $before (smoke test passes on the old build)"
      write_cli_update_state codex "$before" "$latest" rolled_back "$after failed its smoke test"
      return 0
    fi
    # Restoring the new build can itself fail (registry down, disk full). Reporting
    # installed=$latest regardless would set upToDate=1 for a CLI that is actually
    # on $before, and AgentCliOutOfDate could then never fire for it.
    log "codex: old build $before ALSO fails - restoring $latest, fault is not the update"
    if ! as_lc "npm install -g --prefix '$NPM_PREFIX' '@openai/codex@$latest'" >> "$LOG" 2>&1; then
      logger -t lc-cli-update -p user.crit "Codex could not be restored to $latest on websearch-host and is stuck on $before, which also fails its smoke test"
      log "codex: could NOT restore $latest - stuck on $before"
      write_cli_update_state codex "$before" "$latest" failed "smoke test fails on both builds and $latest could not be reinstalled"
      return 0
    fi
  fi

  logger -t lc-cli-update -p user.crit "Codex smoke test FAILED on websearch-host after updating $before -> $latest, and the previous build fails too - check the provider, the token and the bridge"
  log "codex: smoke test FAILED on both builds"
  write_cli_update_state codex "$latest" "$latest" failed "smoke test fails on $latest and on $before"
}

# ─── Run ────────────────────────────────────────────────────────────────────
mkdir -p "$(dirname "$LOG")" 2>/dev/null
# Keep the log bounded: the timer has no logrotate for this file.
if [ -f "$LOG" ] && [ "$(wc -c <"$LOG" 2>/dev/null || echo 0)" -gt 1048576 ]; then
  tail -c 262144 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
fi

log "=== agent CLI freshness run ==="
update_claude
prune_claude_versions
update_codex
log "=== done ==="

exit 0
