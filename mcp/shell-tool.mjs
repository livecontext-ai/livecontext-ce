/**
 * `shell` - run a shell command on the platform host (the bridge), inside the
 * source checkout. The agent now ALSO has Claude Code's native Bash tool, so `shell`
 * is no longer the only way to run commands; it is kept as a platform-tool sibling of
 * `repo`, advertised alongside the platform tools and EXECUTED here, on the bridge host
 * where the checkout lives.
 *
 * Activation: only when AGENT_REPO_PATH points at an existing directory (set by
 * the lc-bridge systemd unit in prod). Unset/absent → not advertised and every
 * call returns a clean "not available" message (CE/dev no-op) - same gate as repo.
 *
 * Safety (the bridge host is admin-only; defense-in-depth, NOT a sandbox):
 *   - the working directory is contained INSIDE the checkout ('..'/absolute escapes rejected);
 *   - stdout/stderr are capped and token-shaped secrets are masked in the returned text;
 *   - the spawned command's env is scrubbed of secret-shaped vars (so `env`/`printenv`
 *     can't trivially echo provider keys / the Redis password into the chat or logs);
 *   - a bounded wall-clock timeout kills a runaway command.
 */

import { spawnSync } from 'child_process';
import { repoRoot, isRepoEnabled, maskSecrets, _internals } from './repo-tool.mjs';

const DEFAULT_TIMEOUT_MS = 120_000;   // 2 min default for a single command
const MAX_TIMEOUT_MS = 600_000;       // 10 min hard cap (caller-overridable below this)
const MAX_OUTPUT_BYTES = 64 * 1024;   // 64 KB window per stream (stdout/stderr each)

// Secret-shaped environment variable names removed from the spawned child's env.
// Targeted (not a blanket "KEY") so infra vars like PATH/HOME/SSH_AUTH_SOCK survive,
// while provider keys, the Redis password (REDIS_URL), DB URLs, etc. are stripped.
const SECRET_ENV_RE = /(SECRET|PASSWORD|PASSWD|TOKEN|_KEY$|_KEY_|APIKEY|API_KEY|ACCESS_KEY|PRIVATE_KEY|CREDENTIAL|REDIS_URL|DATABASE_URL|_DSN$|CONNECTION_STRING)/i;

export const SHELL_TOOL_DEF = {
  name: 'shell',
  description:
    "Run a shell command on the platform host, inside the source checkout - build, run "
    + "the test suite, execute scripts, or any command-line task. Runs in the repository "
    + "root by default; pass cwd for a repo-relative subdirectory. Returns the exit code "
    + "plus stdout and stderr (each capped; token-shaped secrets are masked). A non-zero "
    + "exit is reported as an error with the full output. For editing source files or "
    + "running git, prefer the `repo` tool - it returns a clean, reviewable diff.",
  inputSchema: {
    type: 'object',
    properties: {
      command: { type: 'string', description: 'The shell command to run (executed via the system shell).' },
      cwd: { type: 'string', description: 'Optional repo-relative working directory (defaults to the repository root).' },
      timeout_ms: { type: 'number', description: `Optional wall-clock timeout in milliseconds (default ${DEFAULT_TIMEOUT_MS}, capped at ${MAX_TIMEOUT_MS}).` },
    },
    required: ['command'],
  },
};

function textResult(text) { return { content: [{ type: 'text', text: String(text) }] }; }
function errorResult(text) { return { content: [{ type: 'text', text: String(text) }], isError: true }; }

/** The tool is advertised/usable only when the checkout exists on this host. */
export function isShellEnabled() {
  return isRepoEnabled();
}

/** Build a child env with secret-shaped vars removed (defense-in-depth, not a sandbox). */
export function scrubbedEnv(source = process.env) {
  const out = {};
  for (const [k, v] of Object.entries(source)) {
    if (SECRET_ENV_RE.test(k)) continue;
    out[k] = v;
  }
  return out;
}

/** Truncate a stream to the byte window, flagging truncation (masking happens later). */
function capStream(s) {
  if (!s) return { text: '', truncated: false };
  const buf = Buffer.from(String(s), 'utf8');
  if (buf.length <= MAX_OUTPUT_BYTES) return { text: String(s), truncated: false };
  return { text: buf.subarray(0, MAX_OUTPUT_BYTES).toString('utf8'), truncated: true };
}

/**
 * Execute a `shell` action. Returns an MCP tool-result object
 * ({ content: [{type:'text', text}], isError? }).
 *
 * @param {object} params  the tool call arguments (must include `command`)
 */
export async function handleShellTool(params = {}) {
  if (!isShellEnabled()) {
    return errorResult('Shell access is not available in this environment (no checkout configured).');
  }
  const command = params && typeof params.command === 'string' ? params.command.trim() : '';
  if (!command) return errorResult("shell requires a non-empty 'command'.");

  let cwd = repoRoot();
  if (params.cwd) {
    try { cwd = _internals.safePath(params.cwd); }
    catch (e) { return errorResult(maskSecrets(String(e && e.message || e))); }
  }

  const timeout = Math.min(MAX_TIMEOUT_MS, Math.max(1000, Number(params.timeout_ms) || DEFAULT_TIMEOUT_MS));

  const res = spawnSync(command, {
    cwd,
    shell: true,
    timeout,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
    env: scrubbedEnv(),
    windowsHide: true,
  });

  if (res.error) {
    // spawnSync sets error.code='ETIMEDOUT' (and kills the child) when `timeout` fires.
    if (res.error.code === 'ETIMEDOUT') {
      return errorResult(maskSecrets(`shell: command timed out after ${timeout}ms and was killed.\n$ ${command}`));
    }
    return errorResult(maskSecrets(`shell: failed to run command: ${res.error.message}\n$ ${command}`));
  }

  const out = capStream(res.stdout);
  const err = capStream(res.stderr);
  const code = res.status == null ? (res.signal ? `killed by ${res.signal}` : 'unknown') : res.status;

  const parts = [`$ ${command}`, `exit: ${code}`];
  if (out.text) parts.push(`--- stdout ---\n${out.text}${out.truncated ? `\n[stdout truncated at ${MAX_OUTPUT_BYTES} bytes]` : ''}`);
  if (err.text) parts.push(`--- stderr ---\n${err.text}${err.truncated ? `\n[stderr truncated at ${MAX_OUTPUT_BYTES} bytes]` : ''}`);
  if (!out.text && !err.text) parts.push('(no output)');

  const text = maskSecrets(parts.join('\n'));
  // Non-zero exit → surface as an error (full output kept) so the agent notices.
  return res.status === 0 ? textResult(text) : errorResult(text);
}

// Exported for tests.
export const _internals2 = { SECRET_ENV_RE, capStream, DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS, MAX_OUTPUT_BYTES };
