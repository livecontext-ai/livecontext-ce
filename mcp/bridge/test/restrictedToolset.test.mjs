/**
 * Tests for the CLOUD model-execution-link "restricted toolset" (API mode) per adapter.
 * When restrictedToolset is set, each CLI is locked to ONLY the platform MCP tools; when
 * unset, the existing full-freedom behaviour is unchanged.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
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

test('codex: restricted runs full-access (so MCP executes headless) with the native tools disabled', () => {
  const a = new CodexAdapter();
  const restrictedArgs = a.buildArgs({ ...base, restrictedToolset: true }).args;
  const restricted = restrictedArgs.join(' ');

  // ROOT-CAUSE FIX: codex-cli 0.142 CANCELS every MCP tool call ("user cancelled MCP tool call")
  // under any sandbox except danger-full-access in headless `exec` (verified live 2026-07-03).
  // A read-only/workspace-write sandbox therefore made the platform MCP tools 100% unusable for
  // every codex-linked model. So restricted mode uses full-access (MCP works) and instead
  // REMOVES codex's native tools with --disable, mirroring claude's --tools "".
  assert.match(restricted, /sandbox_mode="danger-full-access"/);
  assert.doesNotMatch(restricted, /sandbox_mode="read-only"/);
  // The load-bearing shell escape vector must be disabled.
  for (const feat of ['shell_tool', 'unified_exec', 'browser_use', 'computer_use', 'apps', 'image_generation']) {
    const di = restrictedArgs.indexOf(feat);
    assert.ok(di > 0 && restrictedArgs[di - 1] === '--disable', `restricted must --disable ${feat}`);
  }
  // codex exec aborts in a non-git cwd without this; restricted runs use an empty temp cwd.
  assert.match(restricted, /--skip-git-repo-check/);

  const free = join({ ...base, restrictedToolset: false }, a);
  assert.match(free, /--dangerously-bypass-approvals-and-sandbox/);
  assert.doesNotMatch(free, /--disable shell_tool/);
  assert.match(free, /--skip-git-repo-check/);
});

test('gemini: restricted auto-approves MCP (yolo) + strips native tools, pinned to the platform MCP server', () => {
  const a = new GeminiAdapter();
  const restricted = join({ ...base, restrictedToolset: true }, a);
  // FIX: `plan` never executes any tool (blocked MCP entirely, same failure class as codex).
  // `yolo` auto-approves so MCP runs; natives are stripped in settings.json (excludeTools).
  assert.match(restricted, /--approval-mode yolo/);
  assert.doesNotMatch(restricted, /--approval-mode plan/);
  assert.match(restricted, /--allowed-mcp-server-names agent-cli/);

  const free = join({ ...base, restrictedToolset: false }, a);
  assert.doesNotMatch(free, /--approval-mode/);
  assert.doesNotMatch(free, /--allowed-mcp-server-names/);
});

test('gemini: restricted settings.json excludes the native built-in tools; free does not', () => {
  const a = new GeminiAdapter();
  const tmp = mkdtempSync(resolve(tmpdir(), 'gemtest-'));
  try {
    const server = { serverName: 'agent-cli', command: 'node', args: ['/x/server.mjs'], env: {} };

    const restrictedPath = a.writeMcpConfig(tmp, server, true);
    const restrictedCfg = JSON.parse(readFileSync(restrictedPath, 'utf8'));
    assert.ok(Array.isArray(restrictedCfg.excludeTools), 'restricted must set excludeTools');
    for (const t of ['run_shell_command', 'write_file', 'web_fetch', 'read_file']) {
      assert.ok(restrictedCfg.excludeTools.includes(t), `restricted must exclude native tool ${t}`);
    }
    // The platform MCP server is still wired.
    assert.ok(restrictedCfg.mcpServers['agent-cli'], 'restricted must keep the platform MCP server');

    const freePath = a.writeMcpConfig(tmp, server, false);
    const freeCfg = JSON.parse(readFileSync(freePath, 'utf8'));
    assert.equal(freeCfg.excludeTools, undefined, 'free mode keeps all native tools');
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
});

test('mistral-vibe: restricted enables ONLY the MCP tools (disables all others in -p mode)', () => {
  const a = new MistralAdapter();
  const restricted = join({ ...base, restrictedToolset: true }, a);
  assert.match(restricted, /--enabled-tools \*agent-cli\*/);

  const free = join({ ...base, restrictedToolset: false }, a);
  assert.doesNotMatch(free, /--enabled-tools/);
});
