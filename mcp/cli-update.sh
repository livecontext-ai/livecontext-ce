#!/bin/bash
# Keeps the agent CLIs lc-bridge spawns (Claude Code + Codex) on the PINNED,
# integrity-verified builds listed in bridge/agent-cli-versions.txt - the same
# manifest the CE bridge image installs from - and REFUSES to leave prod on a
# build that cannot answer.
#
# Provisioned to /opt/livecontext/services/bridge/cli-update.sh by deploy.yml /
# deploy-direct.sh and scheduled by lc-cli-update.timer (daily, 04:17 UTC). The
# manifest ships in the same bridge tarball, at bridge/agent-cli-versions.txt
# next to this script.
#
# A systemd timer and NOT root cron, unlike the two token keep-alives beside it:
# `crontab` is absent from the deploy user's sudoers whitelist on websearch-host
# while `systemctl enable|start lc-*` is present, so cron cannot be written from
# either deploy lane. The timer also gives the run journald output and a
# `systemctl list-timers` entry, which cron does not.
#
# ─── Why pinned, and no longer "latest" (LC-046) ────────────────────────────
# This script used to run `claude update` and install `@openai/codex@<latest>`
# every morning: whatever the registry served that day ran, unattended, inside
# the process that receives full agent prompts and holds the provider
# credentials. A compromised publish would have reached prod within a day with
# no git diff. The CE image had already moved to exact versions + tarball
# integrity; prod now installs from the SAME manifest with the SAME check:
# `npm pack` the pinned version, refuse it unless its sha512 equals the
# manifest's, install that verified file. A line without an integrity is
# refused here (the image's BRIDGE_CLI_REQUIRE_INTEGRITY=1 default).
#
# Claude Code's own auto-updater would replace the pinned build on its own, so
# every `claude` run here sets DISABLE_AUTOUPDATER=1, and so do lc-bridge.service
# and refresh-token.sh. The native installer has no updater of its own outside
# a running `claude` (no timer, no cron), so that variable is the whole switch.
# A `claude` started without it (by hand, as the service account) can still
# update itself and point the link back at a native build; the next run sees no
# verified-install record for it and migrates back to the pin.
#
# ─── What the daily run does ────────────────────────────────────────────────
# It RE-ASSERTS the pin. A CLI that THIS script installed from the verified
# tarball of the current pin, and that has not changed since, is left alone (no
# install, no provider turn). "Reports the pinned version" is not enough: prod's
# Claude Code once reported the pinned version from the native installer's
# layout, a build that never passed the integrity check. So every verified
# install leaves a record (state dir, <cliId>.pin: pin version + integrity, the
# file the binary resolves to, its sha256), and the run only skips a CLI whose
# record still matches. A CLI that drifted (hand-installed, self-updated, edited,
# wiped, or still on the native installer's layout, even at the pinned version)
# is put back on the pinned build, smoke-tested, and rolled back if it cannot
# answer. The run also reads
# the registry's latest version and logs how far the pin lags, so falling
# behind upstream is visible and bumping stays a reviewed decision.
#
# The state file's `latest` field now carries the PINNED version, so
# lc_cli_update_up_to_date means "installed == pinned" and AgentCliOutOfDate
# means drift the run could not repair.
#
# ─── How to bump a CLI ──────────────────────────────────────────────────────
#   1. Edit mcp/bridge/agent-cli-versions.txt: version AND integrity in ONE
#      commit, following its header (changelog, adapter flags). The CE image
#      build and agentCliPinning.test.mjs check the same line.
#   2. Ship it with a lane-2 deploy (deploy.yml, or deploy-direct.sh --bridge).
#   3. The next timer run installs it (or now: `sudo systemctl start
#      lc-cli-update.service`), smoke-tests it, and returns to the previous
#      build if it cannot complete a turn. Read the result in cli-update.log.
#
# ─── Why the smoke test ─────────────────────────────────────────────────────
# The bridge PARSES these CLIs' output, so a changed flag or stream format
# breaks every agent run. After any version change this script therefore spends
# one real turn proving the new build still works, and goes back to the
# previous build if it does not.
#
# The rollback has a guard of its own: if the OLD build fails the same smoke
# test, the failure is not the change (provider outage, dead token, network),
# so we stay on the pinned build and report `failed` rather than leaving prod
# on an off-pin version for an unrelated reason.
#
# And a rollback is remembered. Without that, a pinned build that cannot answer
# is re-installed, re-tested and re-rolled-back EVERY DAY, and
# AgentCliUpdateRolledBack - a critical - repeats hourly about a fact
# understood on the first morning. So the next run skips the SAME pinned
# version and reports `skipped_known_bad` until the pin changes.
#
# Everything it learns is written to the state dir, exported by the bridge as
# lc_cli_update_* and alerted on. A log nobody queries is not a safety net.
set -u

# Every path is overridable. The defaults ARE production; the overrides exist so
# the state machine below can be exercised against a temporary tree with stub
# CLIs (mcp/bridge/test/cliUpdateScript.test.mjs). Code that replaces the binary
# prod depends on, unattended, as root, should not be code that can only be
# tested by running it in prod.
LC_USER="${LC_USER:-livecontext}"
LC_HOME="${LC_HOME:-/opt/livecontext}"
LC_PATH="${LC_PATH:-/opt/livecontext/.local/bin:/usr/local/bin:/usr/bin:/bin}"

CLAUDE_BIN="${CLAUDE_BIN:-$LC_HOME/.local/bin/claude}"
# Where the native installer (the pre-pin layout) kept its builds. Only read to
# recognise that layout, to roll back to it, and to prune it.
CLAUDE_VERSIONS="${CLAUDE_VERSIONS:-$LC_HOME/.local/share/claude/versions}"
CODEX_BIN="${CODEX_BIN:-$LC_HOME/.local/bin/codex}"
NPM_PREFIX="${NPM_PREFIX:-$LC_HOME/.local}"

LOG="${LOG:-/opt/livecontext/services/bridge/cli-update.log}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI_MANIFEST="${CLI_MANIFEST:-$SCRIPT_DIR/bridge/agent-cli-versions.txt}"
# Which CLIs this run manages (state-file ids). The tests run one at a time.
CLI_UPDATE_CLIS="${CLI_UPDATE_CLIS:-claudeCode codex}"

# The state writers live in a sibling file. If it is missing (a partial deploy,
# a tarball that dropped it) every write_cli_* call below would be a bare
# "command not found": no state would ever be written, the metric would sit at
# 0 and the staleness alerts would page forever about CLIs that are fine.
# So say it once, loudly and specifically, and keep running.
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

# Every version string here comes from the manifest, a CLI's `--version` output
# or the npm registry, and every one of them ends up inside a path, an `ln -sfn`
# target or a `su -c` command string. If an upstream release ever changes that
# output shape, an unvalidated parse silently becomes a wrong path or a shell
# injection point. So nothing is used unless it looks like a version.
is_version() { printf '%s' "${1:-}" | grep -qE '^[0-9][0-9A-Za-z.+-]*$'; }
# The manifest must name ONE build: no range, no dist-tag (same rule as the image).
is_exact_version() { printf '%s' "${1:-}" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$'; }
# npm's `dist.integrity` for sha512: "sha512-" + 88 base64 characters.
is_sri_sha512() { printf '%s' "${1:-}" | grep -qE '^sha512-[A-Za-z0-9+/]{86}==$'; }

# Run a command as the service account, with the same HOME/PATH the bridge uses.
# Every CLI action goes through here: running them as root would write
# root-owned files into the livecontext home and break the bridge at the next
# spawn (a class of breakage that is invisible until the next agent run).
# DISABLE_AUTOUPDATER: a smoke turn must not be the moment Claude Code replaces
# the pinned build with whatever it finds upstream.
# 9>&-: the run lock (fd 9, see "Run") is not handed to the CLIs, so a process
# one of them leaves behind cannot keep every later run locked out.
as_lc() { su -s /bin/bash "$LC_USER" -c "export HOME='$LC_HOME' PATH='$LC_PATH' DISABLE_AUTOUPDATER=1; $1" 9>&-; }

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

# cli_version <claude|codex> - the version the CLI on the service account's PATH
# reports, or empty when it is missing or prints something that is not a version.
cli_version() {
  local v=""
  case "$1" in
    claude) v=$(as_lc "claude --version" 2>/dev/null | head -n 1 | awk '{print $1}') ;;
    codex)  v=$(as_lc "codex --version" 2>/dev/null | head -n 1 | awk '{print $NF}') ;;
  esac
  is_version "$v" && printf '%s' "$v"
}

# ─── The pin ────────────────────────────────────────────────────────────────
# pin_for <package> - read the manifest line for <package> into PIN_VERSION,
# PIN_POLICY and PIN_INTEGRITY, or set PIN_ERROR and return 1. Same format and
# same refusals as the image build (mcp/bridge/Dockerfile).
pin_for() {
  local spec="" policy="" integrity="" rest="" found=""
  PIN_VERSION=""; PIN_POLICY=""; PIN_INTEGRITY=""; PIN_ERROR=""
  if [ ! -r "$CLI_MANIFEST" ]; then
    PIN_ERROR="pin manifest not readable at $CLI_MANIFEST"
    return 1
  fi
  while read -r spec policy integrity rest; do
    case "$spec" in ''|\#*) continue ;; esac
    case "$spec" in "$1"@*) found=1; break ;; esac
  done < <(tr -d '\r' < "$CLI_MANIFEST")
  if [ -z "$found" ]; then
    PIN_ERROR="$1 is not pinned in $CLI_MANIFEST"
    return 1
  fi
  PIN_VERSION="${spec#"$1"@}"
  if ! is_exact_version "$PIN_VERSION"; then
    PIN_ERROR="$spec is not pinned to an exact version"
    return 1
  fi
  case "$policy" in
    run-scripts|ignore-scripts) PIN_POLICY="$policy" ;;
    *) PIN_ERROR="$spec declares no install-script policy"; return 1 ;;
  esac
  if ! is_sri_sha512 "$integrity"; then
    PIN_ERROR="$spec has no sha512 integrity pin - refusing an unverified install"
    return 1
  fi
  PIN_INTEGRITY="$integrity"
}

# sri_sha512 <file> - the file's npm-style integrity string ("sha512-<base64>").
sri_sha512() {
  python3 - "$1" <<'PY' 2>/dev/null
import base64, hashlib, sys
h = hashlib.sha512()
with open(sys.argv[1], "rb") as fh:
    for chunk in iter(lambda: fh.read(1 << 20), b""):
        h.update(chunk)
print("sha512-" + base64.b64encode(h.digest()).decode())
PY
}

# fetch_verified <package@version> <integrity> - download the tarball as the
# service account, copy it into a ROOT-owned directory, and verify THAT copy.
# Sets VERIFIED_TGZ (and VERIFIED_DIR, removed by drop_verified) or FETCH_ERROR.
#
# The copy is what makes the check mean something: the download directory
# belongs to the account agents run as, so hashing the file there and then
# installing it would leave a window to swap it. A root-owned 0644 file in a
# root-owned 0755 directory cannot be swapped by that account.
VERIFIED_DIR=""
VERIFIED_TGZ=""
fetch_verified() {
  local spec="$1" want="$2" pack_dir tgz got
  VERIFIED_TGZ=""; FETCH_ERROR=""
  drop_verified
  pack_dir=$(mktemp -d "${TMPDIR:-/tmp}/lc-cli-pack.XXXXXX") || { FETCH_ERROR="mktemp failed"; return 1; }
  chown "$LC_USER" "$pack_dir" 2>/dev/null
  tgz=$(as_lc "cd '$pack_dir' && npm pack '$spec' --silent" 2>>"$LOG" | tail -n 1 | tr -d '\r')
  case "$tgz" in
    ''|.*|*/*|*[!A-Za-z0-9._@-]*)
      FETCH_ERROR="npm pack $spec failed (got '${tgz}')"; rm -rf "$pack_dir"; return 1 ;;
  esac
  if [ ! -f "$pack_dir/$tgz" ]; then
    FETCH_ERROR="npm pack $spec produced no tarball"; rm -rf "$pack_dir"; return 1
  fi
  VERIFIED_DIR=$(mktemp -d "${TMPDIR:-/tmp}/lc-cli-verified.XXXXXX") || { FETCH_ERROR="mktemp failed"; rm -rf "$pack_dir"; return 1; }
  chmod 755 "$VERIFIED_DIR"
  if ! cp -- "$pack_dir/$tgz" "$VERIFIED_DIR/$tgz"; then
    FETCH_ERROR="could not copy the $spec tarball"; rm -rf "$pack_dir"; drop_verified; return 1
  fi
  chmod 644 "$VERIFIED_DIR/$tgz"
  rm -rf "$pack_dir"
  got=$(sri_sha512 "$VERIFIED_DIR/$tgz")
  if [ "$got" != "$want" ]; then
    FETCH_ERROR="integrity mismatch for $spec: expected $want, got ${got:-nothing}"
    drop_verified
    return 1
  fi
  VERIFIED_TGZ="$VERIFIED_DIR/$tgz"
}
drop_verified() {
  [ -n "$VERIFIED_DIR" ] && rm -rf "$VERIFIED_DIR"
  VERIFIED_DIR=""; VERIFIED_TGZ=""
}

# install_verified [prefix] - npm-install the verified tarball into <prefix>
# (default: the service account's prefix), honouring the manifest's
# install-script policy.
install_verified() {
  local prefix="${1:-$NPM_PREFIX}" flag=""
  [ "$PIN_POLICY" = ignore-scripts ] && flag="--ignore-scripts"
  as_lc "npm install -g --prefix '$prefix' $flag '$VERIFIED_TGZ'" >> "$LOG" 2>&1
}

# ─── Claude Code layout ─────────────────────────────────────────────────────
# Before the pin, prod ran the native installer: $CLAUDE_BIN was a symlink into
# $CLAUDE_VERSIONS, moved by `claude update`. The pinned build is npm-installed
# like Codex (and like the CE image). A global npm install into $NPM_PREFIX
# REFUSES to start while $NPM_PREFIX/bin/claude is a link it does not own
# (npm's check-bin, EEXIST), and --no-bin-links does not change that: the check
# runs before extraction whatever the flag says (prod, 2026-10-02: the pin was
# never applied). Removing the native link first is no answer either: it left
# NO `claude` for as long as npm ran, and an agent the bridge spawned in that
# window failed with ENOENT.
#
# So the migration never asks npm to install next to the native link. The
# verified tarball goes into a STAGING prefix (empty bin/, so check-bin has
# nothing to refuse), the staged build must report the pinned version, its
# package directory is renamed into $NPM_PREFIX's node_modules (same
# filesystem: one rename), and only then is $CLAUDE_BIN switched to it in one
# rename (`repoint_claude`). Until that last step the native link keeps
# answering, so the MIGRATION never leaves the box without a `claude`. The
# result is exactly the layout a plain npm install leaves (the link resolves
# inside the package directory), so every later bump takes the ordinary npm
# path, with npm's own in-place replace of the package and its link: that short
# window is npm's, unchanged from before, and this script does not close it.
# The native build stays on disk as the rollback target.
#
# A run killed half-way can leave a staging prefix or a set-aside package
# (~200 MB each). `clean_claude_leftovers` removes them at the start of EVERY
# Claude Code reconcile, not only during a migration, so they do not outlive
# the move to the npm layout.
CLAUDE_PKG_SUBDIR=lib/node_modules/@anthropic-ai/claude-code
native_claude_build() {
  local root
  root=$(readlink -f "$CLAUDE_VERSIONS" 2>/dev/null)
  [ -n "$root" ] && [ -n "${1:-}" ] || return 1
  case "$1" in "$root"/*) return 0 ;; esac
  return 1
}

# repoint_claude <target> - point $CLAUDE_BIN at <target>, or refuse and say so.
#
# Two refusals, both learned from how `ln -sfn` behaves rather than from how it
# reads: it creates a DANGLING link without complaining, and `ln -sfn X X`
# unlinks the destination first, so repointing a path at itself DELETES it.
# `readlink -f` returns the path itself whenever $CLAUDE_BIN is a regular file
# (a wrapper script, a hand-replaced link), which is exactly the input that
# turns a rollback into "there is no claude any more". A MISSING $CLAUDE_BIN is
# allowed (a box where it was deleted by hand gets it back).
repoint_claude() {
  local target="$1"
  if [ -z "$target" ] || [ ! -e "$target" ]; then
    log "claude: refusing to repoint at '$target' (does not exist)"
    return 1
  fi
  if [ ! -L "$CLAUDE_BIN" ] && [ -e "$CLAUDE_BIN" ]; then
    log "claude: $CLAUDE_BIN is not a symlink - refusing to replace it (not our layout)"
    return 1
  fi
  if [ "$target" = "$CLAUDE_BIN" ]; then
    log "claude: refusing to repoint $CLAUDE_BIN at itself (ln -sfn would delete it)"
    return 1
  fi
  # A new link next to it, renamed over it: rename(2) replaces the old link in
  # one step, so `claude` never stops resolving (ln -sfn unlinks first).
  local tmp="$CLAUDE_BIN.lc-new.$$"
  rm -f -- "$tmp"
  # A directory at that name survives rm -f, and a plain `ln -s` would then
  # create the link INSIDE it. Refuse at once instead; -T keeps ln from ever
  # treating the name as a directory.
  if [ -e "$tmp" ] || [ -L "$tmp" ]; then
    log "claude: refusing to repoint: $tmp exists and is not a removable link"
    return 1
  fi
  ln -sT -- "$target" "$tmp" || return 1
  chown -h "$LC_USER:$LC_USER" "$tmp" 2>/dev/null
  if ! mv -Tf -- "$tmp" "$CLAUDE_BIN"; then
    rm -f -- "$tmp"
    return 1
  fi
  return 0
}

# native_claude_link - true while $CLAUDE_BIN is the native installer's link.
native_claude_link() {
  [ -L "$CLAUDE_BIN" ] && native_claude_build "$(readlink -f "$CLAUDE_BIN" 2>/dev/null)"
}

# npm_claude_bin [prefix] - the file npm's `claude` bin link points at, read
# from the package.json of the package installed under <prefix> (default
# $NPM_PREFIX): "bin": {"claude": "<path>"}. Only a plain relative path to an
# existing file inside the package is accepted.
npm_claude_bin() {
  local pkgdir="${1:-$NPM_PREFIX}/$CLAUDE_PKG_SUBDIR" rel
  rel=$(tr -d '\r\n' < "$pkgdir/package.json" 2>/dev/null \
    | grep -oE '"bin"[[:space:]]*:[[:space:]]*\{[^}]*\}' \
    | grep -oE '"claude"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | sed -E 's/.*"([^"]*)"$/\1/')
  rel="${rel#./}"
  case "$rel" in ''|/*|*..*|*[!A-Za-z0-9._/-]*) return 1 ;; esac
  [ -f "$pkgdir/$rel" ] || return 1
  printf '%s' "$pkgdir/$rel"
}

# install_pinned <cliId> - npm-install the verified tarball. On the native
# Claude layout it goes through `migrate_native_claude` (see "Claude Code
# layout" above); if any step of it fails, the native build keeps answering
# and this fails.
install_pinned() {
  if [ "$1" = claudeCode ] && native_claude_link; then
    log "claude: leaving the native installer layout ($CLAUDE_BIN -> npm-managed pinned build)"
    migrate_native_claude
    return
  fi
  install_verified
}

# migrate_native_claude - staging prefix -> version check -> rename the package
# into $NPM_PREFIX -> switch $CLAUDE_BIN, then remove the staging prefix. Every
# file operation runs as the service account (as_lc), not root: nothing
# root-owned lands in its prefix, its later npm runs can still replace the
# package, and root never moves or deletes through a path that account owns.
# Only the final link switch is root's (`repoint_claude`, as before).
CLAUDE_ASIDE=""
migrate_native_claude() {
  local stage live="$NPM_PREFIX/$CLAUDE_PKG_SUBDIR" rc
  stage=$(as_lc "mktemp -d '$NPM_PREFIX/.lc-claude-stage.XXXXXX'" 2>>"$LOG" | tail -n 1)
  case "$stage" in
    "$NPM_PREFIX"/.lc-claude-stage.*[!A-Za-z0-9._/-]*) stage="" ;;
    "$NPM_PREFIX"/.lc-claude-stage.*) [ -d "$stage" ] || stage="" ;;
    *) stage="" ;;
  esac
  if [ -z "$stage" ]; then
    log "claude: could not create a staging prefix under $NPM_PREFIX - staying on the native build"
    return 1
  fi
  CLAUDE_ASIDE=""
  stage_and_switch_claude "$stage" "$live"
  rc=$?
  as_lc "rm -rf -- '$stage'" >>"$LOG" 2>&1
  [ -n "$CLAUDE_ASIDE" ] && as_lc "rm -rf -- '$CLAUDE_ASIDE'" >>"$LOG" 2>&1
  CLAUDE_ASIDE=""
  return "$rc"
}

# stage_and_switch_claude <stage prefix> <live package dir> - the steps of the
# migration, each one leaving the native link answering when it fails.
stage_and_switch_claude() {
  local stage="$1" live="$2" staged_bin bin_target v
  if ! install_verified "$stage"; then
    log "claude: npm install of the verified build into the staging prefix failed - staying on the native build"
    return 1
  fi
  if ! staged_bin=$(npm_claude_bin "$stage"); then
    log "claude: the installed package names no usable claude bin - staying on the native build"
    return 1
  fi
  v=$(as_lc "'$staged_bin' --version" 2>/dev/null | head -n 1 | awk '{print $1}')
  if [ "$v" != "$PIN_VERSION" ]; then
    log "claude: the staged build reports '${v}' instead of $PIN_VERSION - staying on the native build"
    return 1
  fi
  if ! as_lc "mkdir -p '$(dirname "$live")'" >>"$LOG" 2>&1; then
    log "claude: could not create $(dirname "$live") - staying on the native build"
    return 1
  fi
  # A package already at $live is not what `claude` runs (the link is still
  # native): set it aside, and put it back if the staged one cannot take its
  # place. If even that move back fails, the set-aside copy is LEFT where it is
  # (CLAUDE_ASIDE cleared, so this run does not delete it) and the failure is
  # logged; the next run's clean_claude_leftovers removes it, which is safe
  # because a set-aside package is never what the link resolves to.
  if [ -e "$live" ] || [ -L "$live" ]; then
    if ! as_lc "mv -T -- '$live' '$live.lc-aside.$$'" >>"$LOG" 2>&1; then
      log "claude: could not set aside the npm package already at $live - staying on the native build"
      return 1
    fi
    CLAUDE_ASIDE="$live.lc-aside.$$"
  fi
  if ! as_lc "mv -T -- '$stage/$CLAUDE_PKG_SUBDIR' '$live'" >>"$LOG" 2>&1; then
    log "claude: could not move the staged build into $live - staying on the native build"
    if [ -n "$CLAUDE_ASIDE" ]; then
      if as_lc "mv -T -- '$CLAUDE_ASIDE' '$live'" >>"$LOG" 2>&1; then
        log "claude: restored the npm package that was set aside to $live"
      else
        log "claude: could not restore $CLAUDE_ASIDE to $live - left in place (not what claude runs; the next run removes it)"
      fi
      CLAUDE_ASIDE=""
    fi
    return 1
  fi
  if ! bin_target=$(npm_claude_bin); then
    log "claude: the moved package names no usable claude bin - staying on the native build"
    return 1
  fi
  repoint_claude "$bin_target"
}

# clean_claude_leftovers - remove what an interrupted run left behind: staging
# prefixes and set-aside packages (removed as the service account, which owns
# them, and never one $CLAUDE_BIN resolves into: neither name is ever linked,
# so that check only guards against a hand edit), and a temporary link of
# repoint_claude. Only names this script creates. Called at the start of every
# Claude Code reconcile. In the documented unlocked fallback (no flock, no
# trusted lock) this may race a concurrent run's in-progress names: accepted.
clean_claude_leftovers() {
  local live="$NPM_PREFIX/$CLAUDE_PKG_SUBDIR" cur d real
  cur=$(readlink -f "$CLAUDE_BIN" 2>/dev/null)
  for d in "$NPM_PREFIX"/.lc-claude-stage.* "$live".lc-aside.*; do
    [ -e "$d" ] || [ -L "$d" ] || continue
    case "$d" in *[!A-Za-z0-9._/@-]*) log "claude: not removing '$d' (unexpected characters)"; continue ;; esac
    real=$(readlink -f "$d" 2>/dev/null)
    if [ -n "$cur" ] && [ -n "$real" ]; then
      case "$cur" in "$real"/*) log "claude: $d holds the build claude resolves to - left in place"; continue ;; esac
    fi
    log "claude: removing $d, left by an interrupted run"
    as_lc "rm -rf -- '$d'" >>"$LOG" 2>&1
  done
  # The temporary link of a repoint_claude that was killed before its rename:
  # exactly "$CLAUDE_BIN.lc-new.<pid>" and a symlink (what repoint_claude
  # creates), removed as a link, never followed. The live link is never matched.
  for d in "$CLAUDE_BIN".lc-new.*; do
    [ -L "$d" ] || continue
    case "${d#"$CLAUDE_BIN".lc-new.}" in ''|*[!0-9]*) continue ;; esac
    log "claude: removing the stale temporary link $d"
    rm -f -- "$d"
  done
}

# reinstall_previous <cliId> <package> <version> <binary target before> -
# return to the build that ran before this run. A native Claude build is still
# on disk, so that is a symlink; anything else is reinstalled by exact version.
# That reinstall is NOT integrity-pinned (the previous build is, by definition,
# not the manifest's), which is acceptable for an emergency return to the build
# that was already running, and logged as such.
reinstall_previous() {
  if [ "$1" = claudeCode ] && native_claude_build "${4:-}"; then
    repoint_claude "$4"
    return
  fi
  is_version "${3:-}" || return 1
  log "  $1: reinstalling $2@$3 by exact version (not in the manifest, so registry integrity only)"
  as_lc "npm install -g --prefix '$NPM_PREFIX' '$2@$3'" >> "$LOG" 2>&1
}

# ─── Verified-install record ────────────────────────────────────────────────
# `--version` cannot tell the verified npm build from the same version installed
# any other way. After a verified install the run records what it installed, in
# the root-owned state dir: "<version> <integrity> <resolved binary> <sha256>".
pin_record_file() { printf '%s/%s.pin' "$CLI_STATE_DIR" "$1"; }
file_sha256() { sha256sum -- "$1" 2>/dev/null | awk '{print $1}'; }
forget_verified_install() { rm -f -- "$(pin_record_file "$1")"; }

# record_verified_install <cliId> <bin> - after the verified $PIN_VERSION answered.
record_verified_install() {
  local file target sha
  file=$(pin_record_file "$1")
  target=$(readlink -f "$2" 2>/dev/null)
  sha=$(file_sha256 "$target")
  case "$target" in ''|*[[:space:]]*) target="" ;; esac
  if [ -z "$target" ] || [ -z "$sha" ] || ! mkdir -p "$CLI_STATE_DIR" 2>/dev/null; then
    rm -f -- "$file"
    log "  $1: could not record the verified install (the next run will reinstall it)"
    return 1
  fi
  printf '%s %s %s %s\n' "$PIN_VERSION" "$PIN_INTEGRITY" "$target" "$sha" > "$file.tmp" \
    && mv -f -- "$file.tmp" "$file"
}

# verified_pinned_install <cliId> <bin> - true when <bin> is the build this script
# installed from the verified tarball of the CURRENT pin and it has not changed
# since: same pin, same resolved file, under the npm prefix, not a native build,
# same sha256. Sets PIN_RECORD_ERROR otherwise.
verified_pinned_install() {
  local file version integrity target sha rest now_target prefix
  PIN_RECORD_ERROR=""
  file=$(pin_record_file "$1")
  if [ ! -r "$file" ]; then
    PIN_RECORD_ERROR="no record of a verified install by this script"; return 1
  fi
  read -r version integrity target sha rest < "$file"
  if [ "$version" != "$PIN_VERSION" ] || [ "$integrity" != "$PIN_INTEGRITY" ]; then
    PIN_RECORD_ERROR="the recorded verified install is ${version:-unreadable}, not the current pin"; return 1
  fi
  now_target=$(readlink -f "$2" 2>/dev/null)
  if [ -z "$now_target" ] || [ "$now_target" != "$target" ]; then
    PIN_RECORD_ERROR="$2 resolves to ${now_target:-nothing}, not the verified install"; return 1
  fi
  prefix=$(readlink -f "$NPM_PREFIX" 2>/dev/null)
  case "$now_target" in
    "$prefix"/*) [ -n "$prefix" ] || { PIN_RECORD_ERROR="no npm prefix"; return 1; } ;;
    *) PIN_RECORD_ERROR="$now_target is outside the npm prefix $NPM_PREFIX"; return 1 ;;
  esac
  if native_claude_build "$now_target"; then
    PIN_RECORD_ERROR="$now_target is a native installer build"; return 1
  fi
  if [ "$(file_sha256 "$now_target")" != "$sha" ]; then
    PIN_RECORD_ERROR="$now_target changed since the verified install"; return 1
  fi
}

# ─── Reconcile one CLI with its pin ─────────────────────────────────────────
# reconcile_cli <cliId>   (cliId = the state-file / metric label: claudeCode, codex)
reconcile_cli() {
  local cli_id="$1" cmd pkg bin pinned before before_target after upstream lag bad in_place
  case "$cli_id" in
    claudeCode) cmd=claude; pkg=@anthropic-ai/claude-code; bin="$CLAUDE_BIN" ;;
    codex)      cmd=codex;  pkg=@openai/codex;            bin="$CODEX_BIN" ;;
    *) log "unknown CLI id '$cli_id' in CLI_UPDATE_CLIS - skipped"; return 0 ;;
  esac
  [ "$cli_id" = claudeCode ] && clean_claude_leftovers

  if ! pin_for "$pkg"; then
    logger -t lc-cli-update -p user.crit "$cmd pin unusable on websearch-host: $PIN_ERROR"
    log "$cmd: $PIN_ERROR"
    write_cli_update_state "$cli_id" "$(cli_version "$cmd")" "" failed "$PIN_ERROR"
    return 0
  fi
  pinned="$PIN_VERSION"

  before=$(cli_version "$cmd")
  before_target=$(readlink -f "$bin" 2>/dev/null)
  # Informational only: never drives an install.
  upstream=$(as_lc "npm view '$pkg' version" 2>/dev/null | tr -d '[:space:]')
  is_version "$upstream" || upstream=""
  lag=""
  if [ -n "$upstream" ] && [ "$upstream" != "$pinned" ]; then
    lag="; registry latest is $upstream (a bump is a reviewed edit of agent-cli-versions.txt)"
  fi

  if [ "$before" = "$pinned" ]; then
    if verified_pinned_install "$cli_id" "$bin"; then
      log "$cmd: on the pinned $pinned (verified install)${lag}"
      write_cli_update_state "$cli_id" "$before" "$pinned" none "on the pinned $pinned${lag}"
      return 0
    fi
    # Same version, unproven build (native installer, hand install, edited
    # file): reinstall it from the verified tarball like any other drift.
    log "$cmd: reports the pinned $pinned but $PIN_RECORD_ERROR"
  fi

  # Do not retry a pinned build we already rolled back from: nothing new can be
  # learned until the pin changes.
  bad=$(known_bad_version "$cli_id")
  if [ -n "$bad" ] && [ "$bad" = "$pinned" ]; then
    log "$cmd: the pinned $pinned failed its smoke test last time - not retrying until the pin changes"
    write_cli_update_state "$cli_id" "$before" "$pinned" skipped_known_bad \
      "the pinned $pinned failed its smoke test previously; fix or bump the pin"
    return 0
  fi

  log "$cmd: installed ${before:-nothing}, pinned $pinned - installing the pinned build${lag}"
  if ! fetch_verified "$pkg@$pinned" "$PIN_INTEGRITY"; then
    case "$FETCH_ERROR" in
      integrity*) logger -t lc-cli-update -p user.crit "$cmd: $FETCH_ERROR on websearch-host - NOT installed. Treat as a supply-chain incident until explained." ;;
    esac
    log "$cmd: $FETCH_ERROR - staying on ${before:-nothing}"
    write_cli_update_state "$cli_id" "$before" "$pinned" failed "$FETCH_ERROR"
    return 0
  fi

  # Whatever happens next, the previous record no longer describes the binary;
  # only a verified install that answers its smoke test writes a new one.
  forget_verified_install "$cli_id"
  # A failed install leaves the previous build answering: npm keeps the old
  # npm build in place, and the native Claude link is only switched once the
  # new build is installed.
  if ! install_pinned "$cli_id"; then
    log "$cmd: npm install of the verified $pkg@$pinned failed"
    write_cli_update_state "$cli_id" "$(cli_version "$cmd")" "$pinned" failed "npm install of the verified $pinned failed"
    drop_verified
    return 0
  fi

  after=$(cli_version "$cmd")
  if [ -z "$after" ]; then
    # The new build cannot even say what it is. That is a build prod must not
    # keep: a binary that exists but exits non-zero classifies as "exited with
    # code N", which the bridge treats as an INCONCLUSIVE probe, so
    # AgentCliMissing (critical) cannot fire for it. Go back, exactly as for a
    # failed smoke test.
    log "$cmd: the pinned $pinned cannot report its version after install"
    if [ -n "$before" ] && reinstall_previous "$cli_id" "$pkg" "$before" "$before_target" \
       && [ "$(cli_version "$cmd")" = "$before" ]; then
      logger -t lc-cli-update -p user.crit "$cmd: the pinned $pinned cannot report its version on websearch-host; rolled back to $before"
      log "$cmd: ROLLED BACK to $before (the pinned build could not report a version)"
      write_cli_update_state "$cli_id" "$before" "$pinned" rolled_back "the pinned $pinned could not report a version"
    else
      logger -t lc-cli-update -p user.crit "$cmd cannot report a version on websearch-host and could not be rolled back - the provider is likely broken"
      write_cli_update_state "$cli_id" "$(cli_version "$cmd")" "$pinned" failed "the pinned $pinned cannot report a version and the previous build could not be restored"
    fi
    drop_verified
    return 0
  fi
  if [ "$after" != "$pinned" ]; then
    # Installed, but something else answers first on the service account's PATH.
    log "$cmd: installed $pinned into $NPM_PREFIX but '$cmd' on PATH reports $after"
    write_cli_update_state "$cli_id" "$after" "$pinned" failed "installed $pinned but $cmd on PATH reports $after"
    drop_verified
    return 0
  fi

  log "$cmd: now on the pinned $pinned (was ${before:-nothing}), smoke testing"
  if smoke_with_retry "$cmd"; then
    log "$cmd: smoke test OK on $pinned"
    record_verified_install "$cli_id" "$bin"
    write_cli_update_state "$cli_id" "$pinned" "$pinned" updated "${before:-nothing} -> pinned $pinned${lag}"
    drop_verified
    return 0
  fi

  if [ -z "$before" ]; then
    logger -t lc-cli-update -p user.crit "$cmd: the pinned $pinned FAILED its smoke test on websearch-host and there is no previous build to return to - check the provider, the token and the bridge"
    log "$cmd: smoke test FAILED on $pinned, no previous build to roll back to"
    write_cli_update_state "$cli_id" "$pinned" "$pinned" failed "smoke test fails on $pinned and there was no previous build"
    drop_verified
    return 0
  fi

  # The pinned build failed. Before blaming it, prove the old one works: if it
  # fails too, the fault is upstream of the CLI and rolling back fixes nothing.
  log "$cmd: smoke test FAILED on $pinned, trying rollback to $before"
  if reinstall_previous "$cli_id" "$pkg" "$before" "$before_target" \
     && [ "$(cli_version "$cmd")" = "$before" ]; then
    if smoke_with_retry "$cmd"; then
      logger -t lc-cli-update -p user.crit "$cmd: the pinned $pinned failed its smoke test and was rolled back to $before on websearch-host - prod is off its pin until the pin is fixed or bumped"
      log "$cmd: ROLLED BACK to $before (smoke test passes on the old build)"
      write_cli_update_state "$cli_id" "$before" "$pinned" rolled_back "the pinned $pinned failed its smoke test"
      drop_verified
      return 0
    fi
    # Old build fails too => not the pin's fault. Go back to the pinned build,
    # from the tarball verified above (no second download), and report the
    # version ACTUALLY in place: claiming the pin regardless would set
    # upToDate=1 for a CLI that is not on it.
    log "$cmd: old build $before ALSO fails - restoring the pinned $pinned, fault is not the pin"
    if install_pinned "$cli_id" && [ "$(cli_version "$cmd")" = "$pinned" ]; then
      # The verified tarball again: a later run must not reinstall it.
      record_verified_install "$cli_id" "$bin"
    else
      log "$cmd: could not restore the pinned $pinned"
    fi
  else
    log "$cmd: could not return to $before"
  fi
  in_place=$(cli_version "$cmd")
  logger -t lc-cli-update -p user.crit "$cmd smoke test FAILED on websearch-host on the pinned $pinned, and the previous build $before fails too (running ${in_place:-nothing}) - check the provider, the token and the bridge"
  log "$cmd: smoke test FAILED on both builds (running ${in_place:-nothing})"
  write_cli_update_state "$cli_id" "$in_place" "$pinned" failed "smoke test fails on $pinned and on $before"
  drop_verified
}

# Old native Claude builds are ~250 MB each. Pruned only AFTER the smoke test,
# so a native rollback target is still on disk when it is needed. Keeps the
# live build (when native) plus the newest spare.
prune_claude_versions() {
  local cur
  cur=$(basename "$(readlink -f "$CLAUDE_BIN" 2>/dev/null)" 2>/dev/null)
  [ -z "$cur" ] && return 0
  # The cd runs in a SUBSHELL. Without one it leaked into the rest of the
  # script, and the Codex smoke test - which runs with approvals and sandbox
  # both disabled - would then execute with its working directory set to the
  # directory holding the Claude builds.
  (
    cd "$CLAUDE_VERSIONS" 2>/dev/null || exit 0
    ls -1t 2>/dev/null | grep -Fxv "$cur" | tail -n +2 | while read -r v; do
      log "claude: pruning old build $v"
      rm -f -- "$v"
    done
  )
}

# ─── Run ────────────────────────────────────────────────────────────────────
mkdir -p "$(dirname "$LOG")" 2>/dev/null
trap drop_verified EXIT

# One run at a time. A timer run and a manual `systemctl start` (or a hand run)
# would otherwise remove each other's staging prefix mid-install and race on
# the same link. Taken BEFORE anything else touches shared files (the log trim
# below included). A second run logs, warns in the journal and exits 0 without
# writing state: a lock held for days therefore surfaces as the existing
# staleness alert on lc_cli_update_last_check_timestamp_seconds.
#
# The lock must be root's alone. flock works on a read-only descriptor, so a
# lock the bridge account can open would let an agent running as that account
# hold it forever and silently stop every pin check; and a lock in a directory
# that account can write (the bridge tree, the state dir it is chowned through
# during a deploy) lets it swap in a symlink and make root create a file
# anywhere. So the lock lives in /run/lc-cli-update: systemd creates it for the
# unit (RuntimeDirectory=, root, 0700); a run by hand creates it the same way
# (mkdir -m 0700, never through a symlink) and refuses one that is not this
# uid's with mode 700. Inside it the file is created 0600 under umask 077, and
# the descriptor actually obtained is checked (owner = this uid, mode 600);
# anything else is removed and recreated once. If no trusted lock can be had,
# or flock is missing, the run proceeds UNLOCKED and says so: an unchecked pin
# is worse than an unlikely concurrent run. CLI_UPDATE_LOCK is overridable for
# the tests, like every path here.
CLI_UPDATE_LOCK="${CLI_UPDATE_LOCK:-/run/lc-cli-update/cli-update.lock}"
FLOCK_BIN="${FLOCK_BIN:-flock}"
warn_journal() { logger -t lc-cli-update -p user.warning "$1" 2>/dev/null; log "$1"; }

# lock_dir_ready - the lock's directory exists (created 0700 if missing), is
# not a symlink, and is this uid's with mode 700. Sets LOCK_DIR_ERROR.
lock_dir_ready() {
  local dir
  dir=$(dirname "$CLI_UPDATE_LOCK")
  LOCK_DIR_ERROR=""
  if [ ! -e "$dir" ] && [ ! -L "$dir" ]; then
    mkdir -m 0700 -- "$dir" 2>/dev/null || { LOCK_DIR_ERROR="cannot create the lock directory $dir"; return 1; }
  fi
  if [ -L "$dir" ] || [ ! -d "$dir" ]; then
    LOCK_DIR_ERROR="the lock directory $dir is not a plain directory"; return 1
  fi
  if [ "$(stat -c '%u %a' -- "$dir" 2>/dev/null)" != "$(id -u) 700" ]; then
    LOCK_DIR_ERROR="the lock directory $dir is not owned by uid $(id -u) with mode 700"; return 1
  fi
}

# open_run_lock - open fd 9 on $CLI_UPDATE_LOCK as a fresh or trusted file.
open_run_lock() {
  local old_umask rc
  if [ -L "$CLI_UPDATE_LOCK" ] || { [ -e "$CLI_UPDATE_LOCK" ] && [ ! -f "$CLI_UPDATE_LOCK" ]; }; then
    rm -f -- "$CLI_UPDATE_LOCK" 2>/dev/null || return 1
  fi
  old_umask=$(umask)
  umask 077
  exec 9>>"$CLI_UPDATE_LOCK"
  rc=$?
  umask "$old_umask"
  return "$rc"
} 2>/dev/null
# run_lock_trusted - fd 9 is a regular file owned by this uid, mode 600.
# Read through /proc, i.e. the file the descriptor really points at, not
# whatever the path names by now.
run_lock_trusted() {
  [ -f "/proc/$$/fd/9" ] && [ "$(stat -L -c '%u %a' "/proc/$$/fd/9" 2>/dev/null)" = "$(id -u) 600" ]
}

if ! command -v "$FLOCK_BIN" >/dev/null 2>&1; then
  warn_journal "cli-update: flock not available - running unlocked"
elif ! lock_dir_ready; then
  warn_journal "cli-update: $LOCK_DIR_ERROR - running unlocked"
elif ! open_run_lock; then
  warn_journal "cli-update: cannot open the lock file $CLI_UPDATE_LOCK - running unlocked"
else
  if ! run_lock_trusted; then
    log "cli-update: $CLI_UPDATE_LOCK is not a root-only lock file - replacing it"
    exec 9>&-
    if ! rm -f -- "$CLI_UPDATE_LOCK" 2>/dev/null || ! open_run_lock || ! run_lock_trusted; then
      { exec 9>&-; } 2>/dev/null
      warn_journal "cli-update: could not create a trusted lock at $CLI_UPDATE_LOCK - running unlocked"
    fi
  fi
  if { true >&9; } 2>/dev/null && ! "$FLOCK_BIN" -n 9; then
    warn_journal "cli-update: another run holds $CLI_UPDATE_LOCK - exiting without changes (a lock held for days shows as a stale cli-update check)"
    exit 0
  fi
fi

# Keep the log bounded: the timer has no logrotate for this file.
if [ -f "$LOG" ] && [ "$(wc -c <"$LOG" 2>/dev/null || echo 0)" -gt 1048576 ]; then
  tail -c 262144 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
fi

log "=== agent CLI pin run (manifest: $CLI_MANIFEST) ==="
for cli_id in $CLI_UPDATE_CLIS; do
  reconcile_cli "$cli_id"
  [ "$cli_id" = claudeCode ] && prune_claude_versions
done
log "=== done ==="

exit 0
