/**
 * Resilience tests for mcp/agent-cli-server.mjs - the bridge's MCP stdio server
 * that fetches the platform tool list from agent-service at session start.
 *
 * Regression context: the bridge agent INTERMITTENTLY lost its platform tools
 * (prod: ~31% of sessions showed `Init: 0/8 tools`). Root cause = a transient
 * backend blip at session start (host reboot before k3s networking is ready, a
 * deploy rollout gap) with NO resilience: startSession ran once, before the MCP
 * handshake, with no timeout and no retry, and ListTools never re-fetched - so one
 * blip cost the whole chat its tools.
 *
 * These tests drive the resilience helpers with a mocked fetch (the module is
 * imported with AGENT_CLI_NO_AUTOSTART=1 so it does NOT start the stdio server).
 */
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';

// Set BEFORE importing the module: skip the stdio auto-start and shrink the retry
// budget so the tests run fast. FETCH_TIMEOUT is read once at module load.
process.env.AGENT_CLI_NO_AUTOSTART = '1';
process.env.AGENT_CLI_SESSION_ATTEMPTS = '3';
process.env.AGENT_CLI_SESSION_BACKOFF_MS = '1';
process.env.AGENT_CLI_FETCH_TIMEOUT_MS = '60';
process.env.AGENT_REPO_PATH = ''; // keep the `repo` tool out of the tool list here

const { __test } = await import('../../agent-cli-server.mjs');

const realFetch = globalThis.fetch;
let fetchCount = 0;
let fetchImpl = null;

function setFetch(fn) { fetchImpl = fn; }
function sessionOk(toolNames = ['workflow', 'table']) {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    json: async () => ({
      sessionId: 'sess-' + Math.random().toString(36).slice(2),
      availableTools: toolNames.map((n) => ({ name: n, description: n, inputSchema: { type: 'object' } })),
    }),
  };
}

before(() => {
  globalThis.fetch = async (url, opts) => {
    fetchCount++;
    return fetchImpl(url, opts);
  };
});
after(() => { globalThis.fetch = realFetch; });
beforeEach(() => { __test.reset(); fetchCount = 0; fetchImpl = () => { throw new Error('fetchImpl not set'); }; });

test('startSession success populates sessionId and the tool list', async () => {
  setFetch(async () => sessionOk(['workflow', 'table', 'catalog']));
  await __test.startSession();
  assert.ok(__test.getSessionId(), 'sessionId set');
  assert.deepEqual(__test.getTools().map((t) => t.name), ['workflow', 'table', 'catalog']);
});

test('startSessionWithRetry recovers after transient failures (blip self-heals)', async () => {
  let n = 0;
  setFetch(async () => { n++; if (n < 3) throw new Error('fetch failed'); return sessionOk(); });
  const ok = await __test.startSessionWithRetry('test');
  assert.equal(ok, true);
  assert.ok(__test.getSessionId(), 'session established on the 3rd attempt');
  assert.equal(fetchCount, 3, 'retried exactly until success');
});

test('startSessionWithRetry gives up after all attempts WITHOUT throwing', async () => {
  setFetch(async () => { throw new Error('fetch failed'); });
  const ok = await __test.startSessionWithRetry('test'); // must not reject
  assert.equal(ok, false);
  assert.equal(__test.getSessionId(), null, 'no session');
  assert.equal(fetchCount, 3, 'tried exactly SESSION_START_ATTEMPTS times');
});

test('ensureSession de-dupes concurrent callers, then retries fresh after a failure', async () => {
  setFetch(async () => { throw new Error('fetch failed'); });
  const [a, b] = await Promise.all([__test.ensureSession(), __test.ensureSession()]);
  assert.equal(a, false); assert.equal(b, false);
  assert.equal(fetchCount, 3, 'two concurrent callers share ONE in-flight attempt (not 6 fetches)');
  // The memo was cleared on failure → a later call retries fresh.
  await __test.ensureSession();
  assert.equal(fetchCount, 6, 'a subsequent ensureSession starts a new attempt');
});

test('ensureSession lazily recovers once the backend returns (chat self-heals on a later turn)', async () => {
  let up = false;
  setFetch(async () => { if (!up) throw new Error('fetch failed'); return sessionOk(); });
  assert.equal(await __test.ensureSession(), false, 'first turn: backend still down → no session');
  up = true; // backend comes back
  assert.equal(await __test.ensureSession(), true, 'later turn: session established');
  assert.ok(__test.getSessionId());
});

test('apiPost aborts a hanging backend when a timeout is given (startSession path)', async () => {
  // Backend accepts the connection but never answers; only the abort signal ends it.
  // A timeout MUST be passed (tool calls deliberately get none → unbounded).
  setFetch((url, opts) => new Promise((_, reject) => {
    opts.signal.addEventListener('abort', () =>
      reject(Object.assign(new Error('The operation was aborted'), { name: 'AbortError' })));
  }));
  await assert.rejects(__test.apiPost('/api/agent/cli/session', {}, 60), /abort/i);
});

test('apiPost without a timeout does NOT attach an abort signal (tool calls stay unbounded)', async () => {
  let sawSignal = 'unset';
  setFetch(async (url, opts) => { sawSignal = opts.signal; return sessionOk(); });
  await __test.apiPost('/api/agent/cli/tool', {});
  assert.equal(sawSignal, undefined, 'no AbortSignal on a no-timeout call → a long tool call is never aborted');
});

test('a successful session is NOT re-fetched by ensureSession (no needless churn)', async () => {
  setFetch(async () => sessionOk());
  await __test.startSession();
  assert.equal(fetchCount, 1, 'one fetch to establish the session');
  const ok = await __test.ensureSession(); // sessionId already set → short-circuit
  assert.equal(ok, true);
  assert.equal(fetchCount, 1, 'ensureSession makes NO extra fetch when a session exists');
});

test('listToolsResult returns the recovered tools after a lazy retry (handler level)', async () => {
  let up = false;
  setFetch(async () => { if (!up) throw new Error('fetch failed'); return sessionOk(['workflow', 'table']); });
  let res = await __test.listToolsResult();
  assert.deepEqual(res.tools.map((t) => t.name), [], 'startup blip → empty tools, but the handler did not throw');
  up = true;
  res = await __test.listToolsResult();
  assert.ok(res.tools.some((t) => t.name === 'workflow'), 'platform tools restored without a new chat');
});
