/**
 * Source-text wiring assertion (no live execution): server.mjs must spawn the agent CLI
 * with cwd resolved from AGENT_REPO_PATH via resolveAgentCwd, so the native Bash/Read/Edit
 * tools run FROM the source checkout. The branch logic itself is unit-tested in
 * spawnCwd.test.mjs; this guards the one line that wires it into spawn(). server.mjs can't
 * be imported (it calls app.listen at module load), so we assert against its source text -
 * mirrors shellAndMetaWiring.test.mjs / repoToolWiring.test.mjs.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const server = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../server.mjs'), 'utf8');

test('server imports resolveAgentCwd from the spawnCwd helper', () => {
  assert.match(server, /import\s*\{\s*resolveAgentCwd\s*\}\s*from\s*'\.\/lib\/spawnCwd\.mjs'/);
});

test('server computes spawnCwd from AGENT_REPO_PATH via resolveAgentCwd (default, non-restricted branch)', () => {
  // spawnCwd is now a ternary: restricted runs use an empty temp dir; the default branch
  // still resolves from AGENT_REPO_PATH via resolveAgentCwd so native tools run from the checkout.
  assert.match(server, /const\s+spawnCwd\s*=[\s\S]*?resolveAgentCwd\(\s*process\.env\.AGENT_REPO_PATH[^)]*\)/);
});

test('server runs a RESTRICTED (model-execution-link) call from a fresh empty temp dir, never the repo checkout', () => {
  // No AGENTS.md / CLAUDE.md / project files reachable in restricted "API mode".
  assert.match(server, /restrictedToolset\s*\?\s*\n?\s*mkdtempSync\(resolve\(tmpdir\(\),\s*'bridge-restricted-'\)\)/);
});

test('server passes cwd: spawnCwd into the spawn() options', () => {
  assert.match(server, /cwd:\s*spawnCwd/);
});
