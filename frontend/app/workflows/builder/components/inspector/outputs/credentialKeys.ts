/**
 * Whether a key's NAME says it HOLDS a credential. A port of the backend's
 * `ReportedParams.isCredentialKey`, word for word and step for step.
 *
 * <p>Why a port and not a list. The configured-parameters fallback renders the plan entry
 * straight off the canvas, so it never touches the backend gate; it was guarded by a static
 * list of nine `<type>.<key>` entries. A list cannot track a predicate: four of its entries
 * were already dead (those nodes emit only a `credentialId` now) while `crypto_jwt.token`,
 * `agent.credentials` and every MCP tool argument - author-typed, and newly displayed - were
 * masked on the resumed row and rendered in clear while the node was parked. One key, two
 * answers in one panel, which is the defect this whole alignment exists to remove.
 *
 * <p>Two copies of one rule can drift, and that is the cost paid here deliberately: the
 * alternative was a list that had already drifted. `credentialKeys.parity.test.ts` asserts
 * the same spellings the Java `ReportedParamsTest` does, in both directions, so a change to
 * one side that is not made on the other turns a test red rather than a panel wrong.
 *
 * <p>Keep in sync with
 * `backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/services/template/ReportedParams.java`.
 */

/** The key IS one, whole. `key` is excluded at step 2: a map entry called `key` is a name. */
const CREDENTIAL_KEYS = new Set([
  'token', 'session', 'sessionid', 'authheadervalue', 'apikey', 'privatekey',
  'clientsecret', 'credentials', 'credential', 'authorization', 'cookie',
  'pwd', 'pass', 'auth', 'sig', 'pat', 'authentication', 'connectionstring', 'key',
]);

/** A word that never means anything else, wherever it appears. */
const ABSOLUTE_CREDENTIAL_WORDS = new Set([
  'password', 'passwd', 'passphrase', 'secret', 'authorization', 'bearer', 'cookie', 'signature',
]);

/** A word before `token` that makes it a quantity or a cursor rather than a credential. */
const QUANTITY_QUALIFIERS = new Set([
  'max', 'min', 'page', 'next', 'prev', 'previous', 'total', 'count', 'num',
  'number', 'limit', 'input', 'output', 'prompt', 'completion', 'cached', 'per',
  'continuation', 'resume', 'sync', 'delta', 'scroll', 'cursor',
]);

/** A whole key the word rules would read wrong. */
const NEVER_CREDENTIAL_KEYS = new Set(['tokens', 'key']);

/** A suffix that turns even an absolute credential word into a description of one. */
const PURE_DESCRIPTOR_SUFFIXES = new Set(['algorithm', 'type', 'kind', 'mode', 'scheme', 'format']);

/** A word before `key` or `session` that makes it a credential. */
const CREDENTIAL_QUALIFIERS = new Set([
  'api', 'access', 'refresh', 'auth', 'id', 'bearer', 'session', 'csrf', 'xsrf',
  'jwt', 'oauth', 'sso', 'private', 'encryption', 'signing', 'client', 'secret',
  'personal', 'service', 'shared', 'consumer', 'app', 'subscription', 'license',
  'master', 'account', 'server', 'deploy', 'hmac', 'webhook', 'bot', 'admin',
  'security', 'cdp', 'publishable', 'anon', 'application', 'developer', 'stream',
  'integration', 'merchant', 'partner', 'vendor', 'rapidapi', 'mashape', 'project',
]);

/** A last word that says the key NAMES or MEASURES a credential rather than holding one. */
const DESCRIBES_RATHER_THAN_HOLDS = new Set([
  'name', 'type', 'location', 'algorithm', 'id', 'kind', 'source', 'mode',
  'count', 'length', 'expiry', 'expiresat', 'required', 'enabled', 'timeout',
  'ttl', 'used', 'remaining', 'limit',
]);

/** Words, not substrings: matching `token` inside `maxTokens` is what this avoids. */
function segments(key: string): string[] {
  const spaced = key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[^A-Za-z0-9]+/g, ' ')
    .toLowerCase()
    .trim();
  const out: string[] = [];
  for (const word of spaced.split(/\s+/)) {
    // Trailing digits first: `apiKey2` is the same words with a counter on the end.
    const stripped = word.replace(/\d+$/, '');
    if (!stripped) continue;
    // Singularise, only the trailing s, and never down to a single letter.
    out.push(
      stripped.length > 3 && stripped.endsWith('s') && !stripped.endsWith('ss')
        ? stripped.slice(0, -1)
        : stripped,
    );
  }
  return out;
}

export function isCredentialKey(key: string | null | undefined): boolean {
  if (!key || !key.trim()) return false;
  const parts = segments(key);
  if (parts.length === 0) return false;
  const whole = parts.join('');

  // 1. A whole key the words cannot read.
  const rawWhole = key.toLowerCase().replace(/[^a-z0-9]/g, '');
  if (NEVER_CREDENTIAL_KEYS.has(rawWhole)) return false;
  // 2. The key IS one, whole.
  if (CREDENTIAL_KEYS.has(whole) && whole !== 'key') return true;
  const last = parts[parts.length - 1];
  // 3. A word that never means anything else, before the broad exemption.
  if (!PURE_DESCRIPTOR_SUFFIXES.has(last)) {
    for (const part of parts) {
      if (ABSOLUTE_CREDENTIAL_WORDS.has(part)) return true;
    }
  }
  // 3b. A session id, at any position, plural included.
  for (let i = 0; i + 1 < parts.length; i++) {
    if (parts[i] === 'session' && (parts[i + 1] === 'id' || parts[i + 1] === 'ids')) return true;
  }
  // 4. The key describes or references one.
  if (DESCRIBES_RATHER_THAN_HOLDS.has(last)) return false;
  // 5. A word that means one unless qualified otherwise.
  for (let i = 0; i < parts.length; i++) {
    const part = parts[i];
    const previous = i > 0 ? parts[i - 1] : null;
    if (part === 'token') {
      if (previous === null || !QUANTITY_QUALIFIERS.has(previous)) return true;
    } else if ((part === 'key' || part === 'session')
        && previous !== null && CREDENTIAL_QUALIFIERS.has(previous)) {
      return true;
    }
  }
  return false;
}
