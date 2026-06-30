/**
 * CLI Detector - Cross-OS availability + version probe for the CLI providers
 * the bridge supports (Claude Code, Codex, Gemini CLI, Mistral Vibe).
 *
 * Used by GET /cli-status. Designed to work identically on Linux, macOS and
 * Windows so the same setup wizard can verify a customer's install regardless
 * of the host OS.
 *
 * Strategy per CLI:
 *   1. Resolve the binary (env override → default name).
 *   2. Spawn `<bin> --version` (or a CLI-specific probe) with a 4s timeout.
 *      - POSIX: spawn directly (no shell) to avoid quoting/injection issues.
 *      - Windows: spawn with `shell: true` so `.cmd` / `.ps1` wrappers work.
 *      - Claude with CLAUDE_CLI_JS set: run `node <path> --version` directly.
 *   3. exit 0 → installed; capture version from stdout/stderr.
 *      ENOENT (POSIX) / exit 9009 (Windows "command not found") → not installed.
 *      Other failure → reported with the underlying error message.
 *   4. Cache the result for CACHE_TTL_MS to avoid hammering on rapid retries.
 */

import { spawn } from 'child_process';
import { existsSync, statSync, readFileSync } from 'fs';
import { isAbsolute, sep, join } from 'path';

const IS_WINDOWS = process.platform === 'win32';
const PROBE_TIMEOUT_MS = 4000;
const CACHE_TTL_MS = 30_000;

/**
 * CLI definitions. The keys MUST match the camelCase ids the frontend uses
 * (claudeCode / codex / geminiCli / mistralVibe) so the wizard can index
 * directly into the response.
 */
const CLI_DEFS = [
  {
    id: 'claudeCode',
    label: 'Claude Code',
    resolve: () => {
      const cliJs = process.env.CLAUDE_CLI_JS || '';
      if (cliJs) {
        return { cmd: process.execPath, args: [cliJs, '--version'], display: `node ${cliJs}`, useShell: false };
      }
      const bin = process.env.CLAUDE_BIN || 'claude';
      return { cmd: bin, args: ['--version'], display: bin, useShell: IS_WINDOWS };
    },
  },
  {
    id: 'codex',
    label: 'Codex CLI',
    resolve: () => {
      const bin = process.env.CODEX_BIN || 'codex';
      return { cmd: bin, args: ['--version'], display: bin, useShell: IS_WINDOWS };
    },
  },
  {
    id: 'geminiCli',
    label: 'Gemini CLI',
    resolve: () => {
      const bin = process.env.GEMINI_BIN || 'gemini';
      return { cmd: bin, args: ['--version'], display: bin, useShell: IS_WINDOWS };
    },
  },
  {
    id: 'mistralVibe',
    label: 'Mistral Vibe',
    resolve: () => {
      const bin = process.env.VIBE_BIN || 'vibe';
      return { cmd: bin, args: ['--version'], display: bin, useShell: IS_WINDOWS };
    },
  },
];

const VERSION_REGEX = /(\d+\.\d+(?:\.\d+)?(?:[-+][\w.]+)?)/;

/**
 * If the binary is given as a path (absolute or contains a separator) we can
 * cheaply pre-check that it actually exists on disk before spawning. This
 * sidesteps OS/shell oddities - for example on Windows with `shell: true`,
 * a missing absolute path would otherwise come back as plain "exit code 1"
 * with a localized stderr message that's hard to classify.
 */
function looksLikePath(cmd) {
  if (!cmd) return false;
  return isAbsolute(cmd) || cmd.includes('/') || cmd.includes(sep) || cmd.startsWith('.');
}

/**
 * Spawn `<cmd> <args>` with a hard timeout, capturing stdout/stderr.
 * Resolves to { code, signal, stdout, stderr, spawnError }.
 * spawnError is set when the process could not even be started (ENOENT, EACCES…).
 */
function runProbe(cmd, args, useShell) {
  if (looksLikePath(cmd) && !existsSync(cmd)) {
    const err = new Error('binary not found in PATH');
    err.code = 'ENOENT';
    return Promise.resolve({ code: null, signal: null, stdout: '', stderr: '', spawnError: err });
  }
  return new Promise((resolveProbe) => {
    let stdout = '';
    let stderr = '';
    let settled = false;
    let child = null;
    let timer = null;  // declared up-front so finish() never hits a TDZ if spawn throws synchronously.

    const finish = (result) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      try { child?.kill('SIGKILL'); } catch { /* ignore */ }
      resolveProbe(result);
    };

    try {
      child = spawn(cmd, args, {
        shell: useShell,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
        // Inherit env so PATH lookups work; never pass user input here.
        env: process.env,
      });
    } catch (err) {
      finish({ code: null, signal: null, stdout: '', stderr: '', spawnError: err });
      return;
    }

    timer = setTimeout(() => {
      finish({ code: null, signal: 'SIGKILL', stdout, stderr, spawnError: new Error('timeout') });
    }, PROBE_TIMEOUT_MS);

    child.stdout?.on('data', (chunk) => { stdout += chunk.toString(); });
    child.stderr?.on('data', (chunk) => { stderr += chunk.toString(); });
    child.on('error', (err) => finish({ code: null, signal: null, stdout, stderr, spawnError: err }));
    child.on('close', (code, signal) => finish({ code, signal, stdout, stderr, spawnError: null }));
  });
}

function classifyError(probeResult) {
  const { spawnError, code, signal, stderr, stdout } = probeResult;
  if (spawnError) {
    if (spawnError.code === 'ENOENT') return 'binary not found in PATH';
    if (spawnError.message === 'timeout') return `probe timed out after ${PROBE_TIMEOUT_MS}ms`;
    return spawnError.message || String(spawnError);
  }
  // Windows "command not found" via shell:true returns exit code 1 or 9009 with
  // a recognizable stderr message - treat both as not-installed.
  if (IS_WINDOWS && code === 1) {
    const tail = `${stderr}\n${stdout}`.toLowerCase();
    if (tail.includes('is not recognized') || tail.includes('cannot find') || tail.includes('not found')) {
      return 'binary not found in PATH';
    }
  }
  if (code === 9009) return 'binary not found in PATH';
  if (signal) return `terminated by signal ${signal}`;
  return `exited with code ${code}`;
}

function parseVersion(stdout, stderr) {
  const text = `${stdout}\n${stderr}`.trim();
  if (!text) return null;
  const m = text.match(VERSION_REGEX);
  // Only return when we actually matched a version-shaped token. Falling back
  // to the first line of arbitrary `--version` output produces garbage like
  // "v Welcome to Foo, type --help" in the UI.
  return m ? m[1] : null;
}

function fileHasContent(p) {
  try { return statSync(p).size > 0; } catch { return false; }
}

function claudeJsonAuthed(p) {
  try {
    const j = JSON.parse(readFileSync(p, 'utf8'));
    return !!(j.oauthAccount || j.primaryApiKey || j.accessToken);
  } catch { return false; }
}

function homeDir() {
  return process.env.HOME || process.env.USERPROFILE || '';
}

/**
 * Best-effort "is this CLI logged in?" check so the status badge can distinguish
 * a usable CLI from one that is merely INSTALLED but has no auth (the old badge
 * equated installed with "connected", which lied: it showed Connected while a
 * bridge-CLI model would still fail with "please log in"). Two signals per CLI:
 *   (1) the provider API-key env the CLI honours (API mode), OR
 *   (2) the on-disk credential file its `login` writes (counted only if non-empty).
 * Heuristic by design: it answers "auth is configured", not "the token is still
 * valid" - only a live call proves that - but it is far better than no check.
 */
export function detectAuth(id) {
  const home = homeDir();
  switch (id) {
    case 'claudeCode':
      return !!process.env.ANTHROPIC_API_KEY
        || fileHasContent(join(home, '.claude', '.credentials.json'))
        || claudeJsonAuthed(join(home, '.claude.json'));
    case 'codex':
      return !!process.env.OPENAI_API_KEY
        || fileHasContent(join(process.env.CODEX_HOME || join(home, '.codex'), 'auth.json'));
    case 'geminiCli': {
      if (process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY) return true;
      const gh = process.env.GEMINI_HOME || join(home, '.gemini');
      return fileHasContent(join(gh, 'oauth_creds.json'))
        || fileHasContent(join(gh, 'credentials.json'))
        || fileHasContent(join(gh, 'access_tokens.json'));
    }
    case 'mistralVibe':
      return !!process.env.MISTRAL_API_KEY
        || fileHasContent(join(home, '.vibe', 'config.toml'));
    default:
      return false;
  }
}

/**
 * Probe a single CLI by id (camelCase). Returns the same shape as the map
 * entries from detectAll().
 */
export async function detectOne(id) {
  const def = CLI_DEFS.find((d) => d.id === id);
  if (!def) {
    return { id, installed: false, binary: null, version: null, error: 'unknown cli id' };
  }
  const { cmd, args, display, useShell } = def.resolve();
  const result = await runProbe(cmd, args, useShell);
  if (!result.spawnError && result.code === 0) {
    return {
      id: def.id,
      label: def.label,
      installed: true,
      binary: display,
      version: parseVersion(result.stdout, result.stderr),
      authenticated: detectAuth(def.id),
      error: null,
    };
  }
  return {
    id: def.id,
    label: def.label,
    installed: false,
    binary: display,
    version: null,
    authenticated: false,
    error: classifyError(result),
  };
}

let cache = null;
let cacheAt = 0;
let inFlight = null;  // Promise - set while a fresh detection is running so concurrent callers coalesce.

/** Drop any cached result. Used by force-refresh paths so the next probe re-spawns. */
export function invalidateCache() {
  cache = null;
  cacheAt = 0;
}

/**
 * Detect every supported CLI in parallel. Result is cached for CACHE_TTL_MS.
 * Pass { force: true } to bypass the cache (used by the verify-button path).
 *
 * Concurrent callers within the TTL share the same in-flight Promise so we
 * never spawn the four CLIs more than once at a time - important on Windows
 * where each spawn forks a cmd.exe.
 */
export async function detectAll({ force = false } = {}) {
  const now = Date.now();
  if (!force && cache && now - cacheAt < CACHE_TTL_MS) {
    return cache;
  }
  if (inFlight) return inFlight;
  inFlight = (async () => {
    try {
      const entries = await Promise.all(CLI_DEFS.map((d) => detectOne(d.id)));
      const map = {};
      for (const e of entries) map[e.id] = e;
      cache = map;
      cacheAt = Date.now();
      return map;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}

export const CLI_IDS = CLI_DEFS.map((d) => d.id);
