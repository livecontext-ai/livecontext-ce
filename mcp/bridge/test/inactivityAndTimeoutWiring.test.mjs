/**
 * Wiring regression tests for two related bridge fixes:
 *
 *  (#3) The bridge must honor the per-agent `executionTimeout` as the spawn wall-clock cap
 *       (bounded by MAX_TIMEOUT_MS) instead of always running every CLI to MAX_TIMEOUT_MS.
 *       Before this the DTO field was never destructured and was silently ignored.
 *
 *  (#6) Every bridge run gets an INACTIVITY watchdog: if the CLI emits no stdout for the
 *       configured window it is killed and the run ends as INACTIVITY_TIMEOUT (distinct from
 *       the total TIMEOUT). The window resets on each stdout line.
 *
 *  (#1) agent-cli-server.mjs must lift Node/undici's 300s default headers/body timeout so a long
 *       synchronous tool call (a sub-agent run, a long workflow) returns its real terminal result
 *       instead of a generic "fetch failed" at exactly 5 min.
 *
 * These are parse-time forwarding contracts (no Express + real subprocess needed). Mirrors
 * enabledModulesWiring.test.mjs.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const serverSource = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
const cliSource = readFileSync(resolve(__dirname, '..', '..', 'agent-cli-server.mjs'), 'utf8');

/** Extract the object literal passed to `await executeViaCli({ ... })`. */
function executeViaCliCallArgs() {
  const callIdx = serverSource.indexOf('await executeViaCli({');
  assert.notStrictEqual(callIdx, -1, 'executeViaCli call site not found');
  const argsStart = serverSource.indexOf('{', callIdx);
  let depth = 0, end = argsStart;
  for (let i = argsStart; i < serverSource.length; i++) {
    if (serverSource[i] === '{') depth++;
    else if (serverSource[i] === '}') { depth--; if (depth === 0) { end = i; break; } }
  }
  return serverSource.slice(argsStart, end + 1);
}

// ── #3 executionTimeout → spawn wall-clock ────────────────────────────────────

test('the /execute DTO destructure reads executionTimeout and inactivityTimeout off the body', () => {
  const dtoBlock = serverSource.slice(serverSource.indexOf('= dto;') - 4000, serverSource.indexOf('= dto;'));
  assert.ok(/\bexecutionTimeout\b/.test(dtoBlock),
    'handler MUST destructure executionTimeout from dto, else the per-agent total cap is ignored');
  assert.ok(/\binactivityTimeout\b/.test(dtoBlock),
    'handler MUST destructure inactivityTimeout from dto so a per-agent window can override the default');
});

test('handler passes spawnTimeoutMs (bounded by MAX_TIMEOUT_MS) and inactivityMs to executeViaCli', () => {
  const args = executeViaCliCallArgs();
  assert.ok(/spawnTimeoutMs:\s*Math\.min\(MAX_TIMEOUT_MS,/.test(args),
    'spawnTimeoutMs must be min(MAX_TIMEOUT_MS, executionTimeout*1000) so the agent cap never exceeds the global ceiling');
  assert.ok(/\bexecutionTimeout\b/.test(args),
    'the spawnTimeoutMs expression must derive from executionTimeout');
  assert.ok(/inactivityMs:/.test(args),
    'inactivityMs must be passed so the watchdog window crosses into executeViaCli');
});

test('executeViaCli destructures spawnTimeoutMs and inactivityMs', () => {
  const match = serverSource.match(/async function executeViaCli\(\{([^}]+)\}\)/);
  assert.ok(match, 'executeViaCli signature not found');
  assert.ok(match[1].includes('spawnTimeoutMs'), 'executeViaCli MUST destructure spawnTimeoutMs');
  assert.ok(match[1].includes('inactivityMs'), 'executeViaCli MUST destructure inactivityMs');
});

test('child spawn uses spawnTimeoutMs (not the hardcoded MAX_TIMEOUT_MS) as its timeout', () => {
  assert.match(serverSource, /timeout:\s*spawnTimeoutMs\s*\|\|\s*MAX_TIMEOUT_MS/,
    'spawn timeout must be spawnTimeoutMs (fallback MAX_TIMEOUT_MS) so a configured executionTimeout actually bounds the CLI');
});

// ── #6 inactivity watchdog ────────────────────────────────────────────────────

test('an idle timer kills the child and ends the run as INACTIVITY_TIMEOUT', () => {
  assert.match(serverSource, /killChildOnce\('inactivity'\)/,
    'the inactivity timer must terminate the child via killChildOnce');
  assert.match(serverSource, /inactivityTimedOut\s*=\s*true/,
    'the inactivity timer must set the inactivityTimedOut sentinel');
  assert.match(serverSource, /stopReason\s*=\s*AgentStopReason\.INACTIVITY_TIMEOUT/,
    'the inactivity timer must set stopReason to INACTIVITY_TIMEOUT');
});

test('the idle timer is reset on every stdout line (a working CLI is never killed)', () => {
  const lineIdx = serverSource.indexOf("rl.on('line'");
  assert.notStrictEqual(lineIdx, -1, "rl.on('line') handler not found");
  const handler = serverSource.slice(lineIdx, lineIdx + 300);
  assert.match(handler, /resetIdleTimer\(\)/,
    'the line handler MUST resetIdleTimer so any CLI output restarts the inactivity clock');
});

test('the idle timer is cleared on child close and error (no leak / no fire after exit)', () => {
  const closeIdx = serverSource.indexOf("child.on('close'");
  const errorIdx = serverSource.indexOf("child.on('error'");
  assert.match(serverSource.slice(closeIdx, closeIdx + 200), /clearIdleTimer\(\)/,
    'child close MUST clearIdleTimer');
  assert.match(serverSource.slice(errorIdx, errorIdx + 200), /clearIdleTimer\(\)/,
    'child error MUST clearIdleTimer');
});

test('ctx.state exposes inactivityTimedOut for the stopReason mapper', () => {
  assert.match(serverSource, /get inactivityTimedOut\(\)\s*\{\s*return inactivityTimedOut;/,
    'ctx.state must expose inactivityTimedOut so resolveStopReason can prefer INACTIVITY_TIMEOUT');
});

// ── #1 lift undici's 300s default on tool calls ───────────────────────────────

test('agent-cli-server.mjs disables undici headers/body timeout for the (synchronous, long) tool calls', () => {
  assert.match(cliSource, /import \{ Agent, setGlobalDispatcher \} from 'undici'/,
    'agent-cli-server.mjs must import undici Agent + setGlobalDispatcher');
  assert.match(cliSource, /setGlobalDispatcher\(new Agent\(\{ headersTimeout: 0, bodyTimeout: 0 \}\)\)/,
    'a long sub-agent tool call must not be aborted by undici\'s 300s default - headers/body timeout disabled');
});
