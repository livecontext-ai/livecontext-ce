/**
 * Source-text wiring assertions (no live execution) - guarantee agent-cli-server.mjs
 * advertises + intercepts the `shell` tool, and re-emits the local `repo` tool's
 * `metadata` as a __BRIDGE_META__ sentinel (so diff/gitStatus reach the frontend).
 * Mirrors repoToolWiring.test.mjs.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const server = readFileSync(resolve(root, 'agent-cli-server.mjs'), 'utf8');

test('agent-cli-server imports the shell tool', () => {
  assert.match(server, /import\s*\{[^}]*SHELL_TOOL_DEF[^}]*\}\s*from\s*'\.\/shell-tool\.mjs'/);
  assert.match(server, /handleShellTool/);
  assert.match(server, /isShellEnabled/);
});

test('shell is advertised in the local tools list (gated on isShellEnabled)', () => {
  assert.match(server, /isShellEnabled\(\)\s*&&[^\n]*SHELL_TOOL_DEF/);
  assert.match(server, /localTools\.push\(SHELL_TOOL_DEF\)/);
});

test('shell is intercepted locally in the CallTool handler', () => {
  assert.match(server, /name === SHELL_TOOL_DEF\.name/);
  assert.match(server, /handleShellTool\(params \|\| \{\}\)/);
});

test('repo result is wrapped with withBridgeMeta (metadata → __BRIDGE_META__)', () => {
  assert.match(server, /withBridgeMeta\(await handleRepoTool\(params \|\| \{\}\)\)/);
  // withBridgeMeta / buildSuccessContent now live in the dependency-free lib module
  // (so they can be unit-tested without the MCP SDK) and are imported here.
  assert.match(server, /import\s*\{[^}]*withBridgeMeta[^}]*\}\s*from\s*'\.\/bridge\/lib\/toolContent\.mjs'/);
});
