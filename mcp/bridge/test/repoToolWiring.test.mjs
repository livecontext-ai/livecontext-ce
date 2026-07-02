/**
 * Wiring tests: the `repo` tool only works if it is (1) advertised + intercepted
 * by the MCP server and (2) handed AGENT_REPO_PATH by the bridge. agent-cli-server.mjs
 * and server.mjs boot side effects on import, so - per the convention in
 * effectiveOrgIdScopeChain.test.mjs - assert against source text.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const mcpDir = resolve(__dirname, '..', '..');           // mcp/
const cli = readFileSync(resolve(mcpDir, 'agent-cli-server.mjs'), 'utf8');
const server = readFileSync(resolve(mcpDir, 'bridge', 'server.mjs'), 'utf8');

test('agent-cli-server imports the repo tool', () => {
  assert.match(cli, /import\s*\{[^}]*REPO_TOOL_DEF[^}]*handleRepoTool[^}]*isRepoEnabled[^}]*\}\s*from\s*'\.\/repo-tool\.mjs'/);
});

test('agent-cli-server advertises repo in ListTools when enabled', () => {
  // The ListTools body lives in the named listToolsResult() (registered via
  // setRequestHandler) so it can be unit-tested for session-resilience.
  assert.match(cli, /setRequestHandler\(ListToolsRequestSchema,\s*listToolsResult\s*\)/);
  const idx = cli.indexOf('async function listToolsResult');
  assert.notStrictEqual(idx, -1, 'listToolsResult not found');
  const block = cli.slice(idx, idx + 1000);
  assert.match(block, /isRepoEnabled\(\)/);
  assert.match(block, /REPO_TOOL_DEF/);
});

test('agent-cli-server intercepts repo calls locally (no backend proxy)', () => {
  const idx = cli.indexOf('setRequestHandler(CallToolRequestSchema');
  assert.notStrictEqual(idx, -1, 'CallTool handler not found');
  const block = cli.slice(idx, idx + 400);
  assert.match(block, /name === REPO_TOOL_DEF\.name/);
  assert.match(block, /handleRepoTool\(/);
});

test('bridge passes AGENT_REPO_PATH into the MCP subprocess env (blanked for restricted toolsets)', () => {
  // Current wiring: a restricted toolset must NOT expose the host repo to the
  // subprocess, so the env value is gated on restrictedToolset before falling
  // back to process.env.AGENT_REPO_PATH.
  assert.match(server,
    /AGENT_REPO_PATH:\s*restrictedToolset\s*\?\s*''\s*:\s*\(process\.env\.AGENT_REPO_PATH\s*\|\|\s*''\)/);
});

test('main() connects the MCP transport BEFORE warming the session (prevents "0 MCP connected")', () => {
  // The headline resilience fix: a slow/unreachable backend must never delay the MCP
  // handshake. Lock the ordering so a refactor cannot silently reintroduce the bug.
  const idx = cli.indexOf('async function main()');
  assert.notStrictEqual(idx, -1, 'main() not found');
  const body = cli.slice(idx, idx + 900);
  // Match the actual statements, not the prose comment (which mentions ensureSession()).
  const connectIdx = body.indexOf('await server.connect(');
  const sessionIdx = body.indexOf('ensureSession().catch(');
  assert.ok(connectIdx !== -1, 'await server.connect present in main()');
  assert.ok(sessionIdx !== -1, 'ensureSession().catch warm-up present in main()');
  assert.ok(connectIdx < sessionIdx, 'server.connect MUST precede the session warm-up');
});
