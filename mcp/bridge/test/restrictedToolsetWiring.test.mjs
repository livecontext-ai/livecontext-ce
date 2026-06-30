/**
 * Source-text wiring assertions for the CLOUD model-execution-link "restricted toolset"
 * (API mode) in server.mjs. server.mjs can't be imported (it calls app.listen at module
 * load), so - like spawnCwdWiring.test.mjs - we assert against its source text. The
 * per-adapter arg building is unit-tested in restrictedToolset.test.mjs; this guards the
 * server-side wiring that feeds the flag in.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const server = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../server.mjs'), 'utf8');

test('server derives restrictedToolset from the credentials map (not a top-level DTO field)', () => {
  assert.match(server, /const\s+restrictedToolset\s*=\s*!!\(credentials\s*&&\s*credentials\.__restrictedToolset__\s*===\s*true\)/);
});

test('server forwards restrictedToolset into executeViaCli', () => {
  // executeViaCli destructures restrictedToolset, and the caller passes it through.
  assert.match(server, /async function executeViaCli\(\{[^}]*restrictedToolset[^}]*\}\)/);
  assert.match(server, /executeViaCli\(\{[\s\S]*?restrictedToolset,[\s\S]*?\}\)/);
});

test('server passes restrictedToolset AND the MCP server name into adapter.buildArgs', () => {
  assert.match(server, /buildArgs\(\{[\s\S]*?restrictedToolset,[\s\S]*?mcpServerName:\s*mcpServerConfig\.serverName[\s\S]*?\}\)/);
});

test('server forwards restrictedToolset into adapter.buildChildEnv', () => {
  assert.match(server, /buildChildEnv\(tmpDir,\s*reasoningEffort,\s*restrictedToolset\)/);
});
