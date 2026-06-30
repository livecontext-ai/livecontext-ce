/**
 * Tests for the CLOUD model-execution-link "restricted toolset" (API mode) per adapter.
 * When restrictedToolset is set, each CLI is locked to ONLY the platform MCP tools; when
 * unset, the existing full-freedom behaviour is unchanged.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ClaudeAdapter } from '../adapters/claude-adapter.mjs';
import { CodexAdapter } from '../adapters/codex-adapter.mjs';
import { GeminiAdapter } from '../adapters/gemini-adapter.mjs';
import { MistralAdapter } from '../adapters/mistral-adapter.mjs';

const base = { prompt: 'hi', systemPrompt: 'sys', model: 'm', maxTurns: 5, mcpConfigPath: '/tmp/mcp.json', mcpServerName: 'agent-cli' };
const join = (cfg, adapter) => adapter.buildArgs(cfg).args.join(' ');

test('claude: restricted EMPTIES the built-in tool set via --tools "" (+ --disallowedTools as defence-in-depth); free keeps natives', () => {
  const a = new ClaudeAdapter();
  const restrictedArgs = a.buildArgs({ ...base, restrictedToolset: true }).args;
  const restricted = restrictedArgs.join(' ');

  // PRIMARY (load-bearing) restriction: --tools "" empties the entire built-in tool SET, so
  // Bash/Read/Edit/Glob/Grep/WebFetch/Write are never loaded and cannot be reached in any
  // permission mode. --disallowedTools alone was INSUFFICIENT on claude-code 2.1.x: a routed
  // agent still ran Bash ("git status") in prod 2026-06-26. Assert the exact ['--tools','']
  // pair, not just the substring (the empty value is what disables all built-ins).
  const ti = restrictedArgs.indexOf('--tools');
  assert.ok(ti >= 0, 'restricted must pass --tools');
  assert.equal(restrictedArgs[ti + 1], '', '--tools value must be "" (disable all built-in tools)');

  // Defence-in-depth: the explicit deny list is still present.
  assert.match(restricted, /--disallowedTools/);
  for (const t of ['Bash', 'Read', 'Write', 'Edit', 'Glob', 'Grep', 'WebFetch', 'WebSearch', 'Task']) {
    assert.match(restricted, new RegExp(`\\b${t}\\b`), `restricted must disallow ${t}`);
  }
  // It must NOT use --allowed-tools: that is a permission pre-approval, a no-op under
  // --dangerously-skip-permissions, so it would NOT actually block native tools.
  assert.doesNotMatch(restricted, /--allowed-tools/);

  const freeArgs = a.buildArgs({ ...base, restrictedToolset: false }).args;
  const free = freeArgs.join(' ');
  // Free keeps every native tool: it does NOT empty the built-in set, Bash is NOT disallowed;
  // only the interactive tools (AskUserQuestion, plan/worktree) are blocked.
  assert.equal(freeArgs.indexOf('--tools'), -1, 'free mode keeps the built-in tools');
  assert.match(free, /--disallowedTools/);
  assert.match(free, /AskUserQuestion/);
  assert.doesNotMatch(free, /\bBash\b/);
});

test('codex: restricted keeps a read-only sandbox instead of the full bypass', () => {
  const a = new CodexAdapter();
  const restricted = join({ ...base, restrictedToolset: true }, a);
  assert.doesNotMatch(restricted, /--dangerously-bypass-approvals-and-sandbox/);
  assert.match(restricted, /sandbox_mode="read-only"/);
  assert.match(restricted, /approval_policy="never"/);
  // codex exec aborts in a non-git cwd without this; restricted runs use an empty temp cwd.
  assert.match(restricted, /--skip-git-repo-check/);

  const free = join({ ...base, restrictedToolset: false }, a);
  assert.match(free, /--dangerously-bypass-approvals-and-sandbox/);
  assert.match(free, /--skip-git-repo-check/);
});

test('gemini: restricted runs read-only (plan) and only the platform MCP server', () => {
  const a = new GeminiAdapter();
  const restricted = join({ ...base, restrictedToolset: true }, a);
  assert.match(restricted, /--approval-mode plan/);
  assert.match(restricted, /--allowed-mcp-server-names agent-cli/);

  const free = join({ ...base, restrictedToolset: false }, a);
  assert.doesNotMatch(free, /--approval-mode plan/);
  assert.doesNotMatch(free, /--allowed-mcp-server-names/);
});

test('mistral-vibe: restricted enables ONLY the MCP tools (disables all others in -p mode)', () => {
  const a = new MistralAdapter();
  const restricted = join({ ...base, restrictedToolset: true }, a);
  assert.match(restricted, /--enabled-tools \*agent-cli\*/);

  const free = join({ ...base, restrictedToolset: false }, a);
  assert.doesNotMatch(free, /--enabled-tools/);
});
