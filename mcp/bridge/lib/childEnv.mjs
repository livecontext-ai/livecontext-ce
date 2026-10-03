// Environment handed to a spawned CLI agent child (LC-053).
//
// Every adapter used to build the child's env as `{ ...process.env }` (claude minus three names,
// the other three minus nothing), so the bridge's OWN platform secrets (GATEWAY_SECRET_KEY above
// all) ended up inside a process that runs model-chosen shell commands. This module is the single
// place that decides what never crosses that boundary; every adapter and the server.mjs fallback
// build their child env from it.
//
// Shape: named platform secrets are always removed, a secret-shaped name regex (plus a value check
// for URLs carrying credentials) removes anything nobody thought to name, and an explicit
// allow-list re-admits the provider credentials the CLIs legitimately authenticate with.
// Everything else (PATH, HOME, USERPROFILE, APPDATA, proxy vars without credentials,
// NODE_EXTRA_CA_CERTS / SSL_CERT_FILE, CODEX_HOME, GEMINI_HOME, CLAUDE_* paths...) passes, because
// the CLIs need it to start and a pure allow-list would break a CLI every time it adds a variable.
//
// Scope, stated honestly: removing a name from the child's environment closes `printenv`,
// `process.env` and `/proc/self/environ`. A child under the bridge's uid could still read
// `/proc/<ppid>/environ`; that is what BRIDGE_CHILD_UID (lib/childUser.mjs) plus the systemd
// credential for the gateway secret (lib/bridgeSecurity.mjs createSecretSource) close.

/** Platform secrets that must never reach a spawned agent, whatever their shape. */
export const PLATFORM_SECRET_ENV = Object.freeze([
  // Shared gateway HMAC secret: with it the child could forge X-Gateway-Secret for any user.
  'GATEWAY_SECRET_KEY',
  // Carries the Redis password; the child talks HTTP, never Redis.
  'REDIS_URL',
  // Anti-forgery marker for trusted metadata frames. The MCP subprocess still receives it through
  // the explicit env dict built in server.mjs.
  'BRIDGE_META_NONCE',
]);

/**
 * Secret-shaped names. Also used by the in-process shell tool (mcp/shell-tool.mjs imports it), so
 * a variable hidden from one is hidden from the other.
 *
 * Beyond the obvious (SECRET, PASSWORD, TOKEN, *_KEY, CREDENTIAL, DSN...): PASS as a word
 * (DB_PASS, PASS_X), *_PWD, WEBHOOK (a webhook URL is a bearer credential), *PAT (personal access
 * tokens), JDBC (connection strings carry passwords), and every AWS_* / AZURE_* variable (cloud
 * account credentials the agent has no business holding). An operator who genuinely wants one of
 * these in the agent environment re-admits it by name with BRIDGE_CHILD_ENV_ALLOW.
 */
export const SECRET_ENV_RE =
  /(SECRET|PASSWORD|PASSWD|PASS$|PASS_|_PWD|TOKEN|_KEY$|_KEY_|APIKEY|API_KEY|ACCESS_KEY|PRIVATE_KEY|CREDENTIAL|REDIS_URL|DATABASE_URL|_DSN$|CONNECTION_STRING|_NONCE$|WEBHOOK|PAT$|JDBC|^AWS_|^AZURE_)/i;

/** A *_URL / *_URI / *_PROXY whose value carries userinfo (scheme://user:pass@host) is a credential. */
const URL_NAME_RE = /(_URL|_URI|_PROXY)$/i;
const USERINFO_RE = /^[a-z][a-z0-9+.-]*:\/\/[^/@\s]+@/i;

/** True when an environment entry must not reach a spawned agent (by name or by value shape). */
export function isSecretEnvEntry(key, value) {
  if (SECRET_ENV_RE.test(key)) return true;
  return URL_NAME_RE.test(key) && typeof value === 'string' && USERINFO_RE.test(value);
}

/**
 * Provider credentials the CLIs authenticate with. The shape regex would strip them, and without
 * them claude-code / codex / gemini-cli / mistral-vibe cannot log in.
 */
export const PROVIDER_CREDENTIAL_ALLOW = Object.freeze([
  'ANTHROPIC_API_KEY',
  'ANTHROPIC_AUTH_TOKEN',
  'CLAUDE_CODE_OAUTH_TOKEN',
  'OPENAI_API_KEY',
  'CODEX_API_KEY',
  'GEMINI_API_KEY',
  'GOOGLE_API_KEY',
  'GOOGLE_APPLICATION_CREDENTIALS',
  'MISTRAL_API_KEY',
]);

/**
 * Build the base environment for a spawned CLI agent child.
 *
 * BRIDGE_CHILD_ENV_ALLOW (read from `source`): comma-separated names an operator explicitly
 * re-admits (for example an AWS_PROFILE a CLI needs). It can never re-admit a PLATFORM_SECRET_ENV
 * name.
 *
 * @param {string[]} [extraStrip=[]] adapter-specific names to drop as well.
 * @param {NodeJS.ProcessEnv} [source=process.env] overridable for tests.
 * @returns {Record<string,string>} a copy of the environment without the platform secrets.
 */
export function buildBaseChildEnv(extraStrip = [], source = process.env) {
  // Case-insensitive: env names are case-insensitive on Windows.
  const strip = new Set([...PLATFORM_SECRET_ENV, ...extraStrip].map(name => name.toUpperCase()));
  const operatorAllow = String(source.BRIDGE_CHILD_ENV_ALLOW || '')
    .split(',').map(n => n.trim().toUpperCase()).filter(Boolean);
  const allow = new Set([...PROVIDER_CREDENTIAL_ALLOW.map(name => name.toUpperCase()), ...operatorAllow]);
  return Object.fromEntries(
    Object.entries(source).filter(([key, value]) => {
      const upper = key.toUpperCase();
      if (strip.has(upper)) return false;
      if (allow.has(upper)) return true;
      return !isSecretEnvEntry(key, value);
    })
  );
}
