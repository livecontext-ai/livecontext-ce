/**
 * Wiring regression test for the `enabledModules` → `ENABLED_MODULES` bridge path
 * (tool-schema over-billing fix, 2026-06-17).
 *
 * The bridge MUST forward the agent's canonical enabled-module set down to the MCP
 * subprocess so CliAgentService scopes the core tool schemas to the agent's
 * toolsConfig.mode (parity with the direct loop). Before this, the bridge omitted the
 * modules entirely → every bridge agent (chat/task/schedule on claude-code/codex/gemini)
 * advertised ALL core tools and paid their schema on every turn, regardless of mode.
 *
 * The data threads through 5 sites; if any breaks (a refactor drops a field, a copy-paste
 * adds an env var without the parameter, etc.) the scoping silently reverts to "all tools".
 * Static-text assertions are sufficient - the wiring is a parse-time forwarding contract,
 * no need to spin up Express + a real subprocess. Mirrors effectiveOrgIdScopeChain.test.mjs.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const serverSource = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
const cliSource = readFileSync(resolve(__dirname, '..', '..', 'agent-cli-server.mjs'), 'utf8');

test('server.mjs: executeViaCli signature destructures enabledModules', () => {
  const match = serverSource.match(/async function executeViaCli\(\{([^}]+)\}\)/);
  assert.ok(match, 'executeViaCli signature not found - has the function been renamed?');
  assert.ok(
    match[1].includes('enabledModules'),
    'executeViaCli MUST destructure enabledModules; without it the ENABLED_MODULES env ' +
    'reference resolves undefined and the subprocess silently falls back to ALL modules.'
  );
});

test('server.mjs: the /execute DTO destructure reads enabledModules off the request body', () => {
  // The handler pulls fields off `dto`; enabledModules must be among them or the value
  // never leaves the request body (the BridgeClient serialized it from the DTO).
  const dtoBlock = serverSource.slice(serverSource.indexOf('= dto;') - 4000, serverSource.indexOf('= dto;'));
  assert.ok(
    /\benabledModules\b/.test(dtoBlock),
    'The /execute handler MUST destructure enabledModules from dto - otherwise the field ' +
    'the orchestrator/conversation producer set on the DTO is dropped before reaching the env.'
  );
});

test('server.mjs: handler call site passes enabledModules to executeViaCli', () => {
  const callIdx = serverSource.indexOf('await executeViaCli({');
  assert.notStrictEqual(callIdx, -1, 'executeViaCli call site not found');
  const argsStart = serverSource.indexOf('{', callIdx);
  let depth = 0, end = argsStart;
  for (let i = argsStart; i < serverSource.length; i++) {
    if (serverSource[i] === '{') depth++;
    else if (serverSource[i] === '}') { depth--; if (depth === 0) { end = i; break; } }
  }
  assert.ok(
    /\benabledModules\b/.test(serverSource.slice(argsStart, end + 1)),
    'Handler call to executeViaCli MUST pass enabledModules; otherwise the destructured ' +
    'binding is undefined → ENABLED_MODULES env empty → subprocess uses all modules.'
  );
});

test('server.mjs: mcpServerConfig.env wires ENABLED_MODULES (JSON, array-guarded) from enabledModules', () => {
  assert.match(
    serverSource,
    /ENABLED_MODULES:\s*Array\.isArray\(enabledModules\)\s*\?\s*JSON\.stringify\(enabledModules\)\s*:\s*''/,
    'mcpServerConfig.env.ENABLED_MODULES must JSON-encode enabledModules when it is an array, ' +
    'else empty string. The array guard keeps a non-array/undefined from stamping "undefined".'
  );
});

test('agent-cli-server.mjs: ENABLED_MODULES env is JSON-parsed with an array guard (malformed ⇒ null = unrestricted)', () => {
  assert.match(
    cliSource,
    /process\.env\.ENABLED_MODULES/,
    'agent-cli-server.mjs must read process.env.ENABLED_MODULES (the env server.mjs sets).'
  );
  assert.match(
    cliSource,
    /JSON\.parse\(process\.env\.ENABLED_MODULES\)/,
    'agent-cli-server.mjs must JSON.parse the ENABLED_MODULES env (it is a JSON array string).'
  );
  assert.match(
    cliSource,
    /if \(Array\.isArray\(parsed\)\) ENABLED_MODULES = parsed/,
    'Only an ARRAY may become ENABLED_MODULES; a non-array/malformed value must stay null so ' +
    'CliAgentService.resolveModules(null) treats the session as unrestricted (legacy behaviour).'
  );
});

test('agent-cli-server.mjs: startSession includes enabledModules in the body only when present', () => {
  // Conditional inclusion is load-bearing: omitting the field (null) makes CliAgentService
  // treat the session as unrestricted; sending an empty array would mean "table only".
  const startIdx = cliSource.indexOf('async function startSession()');
  assert.notStrictEqual(startIdx, -1, 'startSession not found');
  const body = cliSource.slice(startIdx, startIdx + 2500);
  assert.match(
    body,
    /if \(ENABLED_MODULES\)\s*\{\s*body\.enabledModules = ENABLED_MODULES;/,
    'startSession MUST set body.enabledModules only when ENABLED_MODULES is non-null. ' +
    'CliAgentService reads request.enabledModules() to scope the core tool set.'
  );
});
