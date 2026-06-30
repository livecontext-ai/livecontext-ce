/**
 * Regression: a model-execution-link (restricted "API mode") run must NOT receive the
 * platform MCP `repo` / `shell` tools. Those run arbitrary commands + file access INSIDE
 * the source checkout, so a routed agent reached the repo via mcp__agent-cli__shell (it ran
 * `git status` in prod 2026-06-26) - the native-tool lockdown (--tools "" / --disallowedTools)
 * and empty cwd do NOT touch MCP tools.
 *
 * agent-cli-server.mjs advertises repo/shell iff isRepoEnabled() (= AGENT_REPO_PATH points at
 * an existing checkout). The fix gates them off by passing AGENT_REPO_PATH='' to the MCP
 * subprocess when restrictedToolset is set. server.mjs can't be imported (it calls
 * app.listen at module load), so we assert against its source text - same approach as
 * spawnCwdWiring.test.mjs.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const server = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../server.mjs'), 'utf8');

test('server empties AGENT_REPO_PATH for the MCP subprocess when restrictedToolset is set (no repo/shell tool in API mode)', () => {
  assert.match(
    server,
    /AGENT_REPO_PATH:\s*restrictedToolset\s*\?\s*''\s*:\s*\(?\s*process\.env\.AGENT_REPO_PATH/,
    'restricted runs must pass AGENT_REPO_PATH="" so isRepoEnabled() is false and repo/shell are not advertised',
  );
});

test('the non-restricted (agent-builder) path still forwards the real AGENT_REPO_PATH', () => {
  // The conditional keeps the real checkout path on the false branch, so direct
  // (non-link) bridge runs keep the repo/shell tools.
  assert.match(server, /restrictedToolset\s*\?\s*''\s*:\s*\(?\s*process\.env\.AGENT_REPO_PATH\s*\|\|\s*''\)?/);
});
