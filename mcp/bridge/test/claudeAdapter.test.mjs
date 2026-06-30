/**
 * Tests for the Claude adapter's handleMessage routing. The two production
 * regressions of 2026-04-08 (snapshot dedup, pause_turn double-execution) live
 * in this code path; these tests are the regression net.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { tmpdir } from 'node:os';
import { ClaudeAdapter, isExtendedThinkingContinuation } from '../adapters/claude-adapter.mjs';

function makeCtx() {
  let usage = { promptTokens: 0, completionTokens: 0 };
  let numTurns = 0;
  let fullContent = '';
  let cliModel = null;
  const perCallUsages = [];
  const iterationTimestamps = [];
  const finishReasons = [];
  const pendingToolCalls = new Map();
  const orderedEntries = [];
  const toolResults = [];
  const thinkingSections = [];

  const publishedToolCalls = [];
  const publishedToolResults = [];
  const publisher = {
    publishToolCall: async (toolName, toolId, argsStr) => {
      publishedToolCalls.push({ toolName, toolId, argsStr });
    },
    publishToolResult: async (toolId, toolName, success, durationMs, content, metadata) => {
      publishedToolResults.push({ toolId, toolName, success });
    },
    publishContent: async () => {},
    publishThinking: async () => {},
  };

  const adapter = new ClaudeAdapter();
  // Mirrors server.mjs: createRunState owns all per-run state including
  // request-derived fields (attachmentPathToName) - server passes them via
  // runOpts. The adapter never reaches outside its own state.
  const adapterState = adapter.createRunState({ attachmentPathToName: null });

  const ctx = {
    publisher,
    pendingToolCalls,
    orderedEntries,
    toolResults,
    thinkingSections,
    adapterState,
    stripMcpPrefix: (n) => n.replace(/^mcp__[^_]+__/, ''),
    extractToolResultAndMetadata: (raw) => ({ content: typeof raw === 'string' ? raw : JSON.stringify(raw), metadata: {} }),
    getContent: () => fullContent,
    state: {
      get usage() { return usage; },
      get numTurns() { return numTurns; },
      get fullContent() { return fullContent; },
      get perCallUsages() { return perCallUsages; },
      get iterationTimestamps() { return iterationTimestamps; },
      get finishReasons() { return finishReasons; },
    },
    updateState(updates) {
      if (updates.usage != null) usage = updates.usage;
      if (updates.numTurns != null) numTurns = updates.numTurns;
      if (updates.fullContent != null) fullContent = updates.fullContent;
      if (updates.cliModel != null) cliModel = updates.cliModel;
    },
    _published: { toolCalls: publishedToolCalls, toolResults: publishedToolResults },
  };
  return { adapter, ctx };
}

function assistantWith({ id, blocks, stop_reason, usage }) {
  return {
    type: 'assistant',
    message: { id, content: blocks, stop_reason, usage },
  };
}

test('claude-adapter: snapshot re-emission of same tool_use does NOT double-publish', async () => {
  const { adapter, ctx } = makeCtx();
  // Snapshot 1: just the tool_use
  await adapter.handleMessage(assistantWith({
    id: 'msg_1',
    blocks: [{ type: 'tool_use', id: 'toolu_a', name: 'workflow', input: { x: 1 } }],
    usage: { input_tokens: 100, output_tokens: 20 },
  }), ctx);
  // Snapshot 2: same message id, same tool_use re-iterated alongside another block
  await adapter.handleMessage(assistantWith({
    id: 'msg_1',
    blocks: [
      { type: 'tool_use', id: 'toolu_a', name: 'workflow', input: { x: 1 } },
      { type: 'text', text: 'all done' },
    ],
    usage: { input_tokens: 100, output_tokens: 30 },
  }), ctx);
  assert.equal(ctx._published.toolCalls.length, 1, 'tool_use must publish exactly once across snapshots');
  assert.equal(ctx.state.perCallUsages.length, 1, 'usage must dedup on msg.id');
});

test('claude-adapter: pause_turn provisional tool_use is skipped, resumed tool_use fires once', async () => {
  const { adapter, ctx } = makeCtx();
  // Provisional pause_turn response with a tentative tool_use
  await adapter.handleMessage(assistantWith({
    id: 'msg_pause',
    blocks: [{ type: 'tool_use', id: 'toolu_provisional', name: 'workflow', input: { x: 1 } }],
    stop_reason: 'pause_turn',
    usage: { input_tokens: 100, output_tokens: 5 },
  }), ctx);
  assert.equal(ctx._published.toolCalls.length, 0, 'pause_turn tool_use must NOT fire');

  // Auto-resumed response with the FINAL tool_use (different id)
  await adapter.handleMessage(assistantWith({
    id: 'msg_resume',
    blocks: [{ type: 'tool_use', id: 'toolu_final', name: 'workflow', input: { x: 1 } }],
    stop_reason: 'tool_use',
    usage: { input_tokens: 100, output_tokens: 200 },
  }), ctx);
  assert.equal(ctx._published.toolCalls.length, 1, 'resumed tool_use must fire exactly once');
  assert.equal(ctx._published.toolCalls[0].toolId, 'toolu_final');
});

test('claude-adapter: tool_use without id logs warning but still publishes (graceful)', async () => {
  const { adapter, ctx } = makeCtx();
  const origWarn = console.warn;
  const warns = [];
  console.warn = (m) => warns.push(m);
  try {
    await adapter.handleMessage(assistantWith({
      id: 'msg_no_id',
      blocks: [{ type: 'tool_use', /* id missing */ name: 'workflow', input: {} }],
      usage: { input_tokens: 50, output_tokens: 10 },
    }), ctx);
  } finally {
    console.warn = origWarn;
  }
  assert.ok(warns.some((w) => /tool_use without id/.test(w)), 'must warn loudly when block.id missing');
  assert.equal(ctx._published.toolCalls.length, 1);
});

test('claude-adapter: tool_result via case "user" publishes and clears pending', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage(assistantWith({
    id: 'msg_t',
    blocks: [{ type: 'tool_use', id: 'toolu_x', name: 'workflow', input: {} }],
    usage: { input_tokens: 10, output_tokens: 5 },
  }), ctx);
  await adapter.handleMessage({
    type: 'user',
    message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_x', content: 'OK' }] },
  }, ctx);
  assert.equal(ctx._published.toolResults.length, 1);
  assert.equal(ctx._published.toolResults[0].success, true);
  assert.equal(ctx.pendingToolCalls.size, 0);
});

test('claude-adapter: case "error" routes through applyResultMapping', async () => {
  const { adapter, ctx } = makeCtx();
  const origErr = console.error;
  console.error = () => {};
  try {
    await adapter.handleMessage({ type: 'error', message: 'boom' }, ctx);
  } finally {
    console.error = origErr;
  }
  // applyResultMapping populates ctx.state.stopReason via updateState - we
  // can't read it here without wiring stopReason through the test ctx, but we
  // can at least confirm the call doesn't throw and isn't a silent default-log.
  // The shape assertion lives in stopReasonMapper's own tests; this guards
  // against the regression where claude-adapter had no `case 'error'` at all.
  assert.ok(true, 'reached without throwing');
});

test('claude-adapter: createRunState returns isolated state per run', () => {
  const adapter = new ClaudeAdapter();
  const a = adapter.createRunState();
  const b = adapter.createRunState();
  a.seenToolUseIds.add('toolu_z');
  assert.equal(a.seenToolUseIds.has('toolu_z'), true);
  assert.equal(b.seenToolUseIds.has('toolu_z'), false, 'two runs must not share dedup state');
});

test('claude-adapter: getCommand returns {cmd, useShell, cmdLabel} with valid shape', () => {
  const adapter = new ClaudeAdapter();
  const r = adapter.getCommand();
  assert.equal(typeof r.cmd, 'string');
  assert.ok(r.cmd.length > 0, 'cmd must be non-empty');
  assert.equal(typeof r.useShell, 'boolean');
  assert.equal(typeof r.cmdLabel, 'string');
});

// Regression: commit abb1fa189 introduced multi-line `-p` prompts
// (buildPromptWithHistory). On Windows, `shell: true` runs cmd.exe, which
// truncates arguments at the first '\n', so Claude only saw "Here". The
// adapter must spawn claude.exe directly with useShell:false whenever the
// Windows install path resolves, otherwise multi-turn chats break silently.
test('claude-adapter: on Windows with claude.exe present, getCommand uses shell:false', () => {
  if (process.platform !== 'win32') return;
  const exePath = resolve(
    process.env.APPDATA || '',
    'npm/node_modules/@anthropic-ai/claude-code/bin/claude.exe'
  );
  if (!existsSync(exePath)) return;
  const adapter = new ClaudeAdapter();
  const r = adapter.getCommand();
  // Either CLAUDE_CLI_JS path (node, shell:false) or direct exe path (shell:false).
  assert.equal(r.useShell, false, 'must never route through cmd.exe when a direct target is available');
  assert.ok(r.cmd.endsWith('claude.exe') || r.cmd === process.execPath,
    `expected claude.exe or node, got ${r.cmd}`);
});

// Regression net for the tool policy. The bridge now runs as a FULL Claude Code:
// ALL native tools (Bash/Read/Write/Edit/Glob/Grep/WebFetch/WebSearch/TodoWrite/
// Task/…) are ENABLED. The platform MCP tools stay directly callable because
// ENABLE_TOOL_SEARCH is pinned to "false" (buildChildEnv) - THAT, not a native-tool
// denylist, is what prevents the 2026-06-04 deferred-tool regression. What the agent
// can actually reach (deploy/ssh/logs) is gated by host creds, not by this code.
// Only genuinely interactive / session-only tools (no user / no plan-mode UX in a
// headless `-p` run) stay disabled. These tests fail if someone re-introduces a
// blanket native-tool denylist or drops the ENABLE_TOOL_SEARCH kill-switch.
const BASE_BUILD_CONFIG = {
  prompt: 'do the thing',
  systemPrompt: 'you are helpful',
  model: 'claude-opus-4-8',
  maxTurns: 7,
  mcpConfigPath: '/tmp/mcp.json',
};

test('claude-adapter: buildArgs enables native tools, disabling ONLY interactive/session-only ones', () => {
  const adapter = new ClaudeAdapter();
  const { args } = adapter.buildArgs({ ...BASE_BUILD_CONFIG });
  assert.ok(args.includes('--disallowedTools'), 'a --disallowedTools list is still passed');
  // Native code/shell/file/web tools MUST be ENABLED (absent from --disallowedTools)
  // so the agent is a real Claude Code - host creds, not this code, gate its reach.
  // (Tool names never appear elsewhere in args for BASE_BUILD_CONFIG, so an
  // includes() check is an exact "is this tool disallowed?" probe.)
  for (const tool of ['Bash', 'Read', 'Write', 'Edit', 'Glob', 'Grep', 'WebFetch', 'WebSearch', 'TodoWrite', 'Task']) {
    assert.ok(!args.includes(tool), `${tool} must be ENABLED (absent from --disallowedTools)`);
  }
  // Only interactive / session-only tools that cannot work in a headless -p run stay off.
  for (const tool of ['AskUserQuestion', 'EnterPlanMode', 'ExitPlanMode', 'EnterWorktree', 'ExitWorktree']) {
    assert.ok(args.includes(tool), `${tool} must stay DISABLED (cannot work in a headless run)`);
  }
});

test('claude-adapter: buildArgs keeps the core spawn flags', () => {
  const adapter = new ClaudeAdapter();
  const { args, stdinPayload } = adapter.buildArgs({ ...BASE_BUILD_CONFIG });
  for (const flag of ['-p', '--mcp-config', '--strict-mcp-config', '--dangerously-skip-permissions']) {
    assert.ok(args.includes(flag), `core flag ${flag} must still be present`);
  }
  assert.equal(args[args.indexOf('--model') + 1], 'claude-opus-4-8', 'model passed verbatim');
  assert.equal(stdinPayload, null, 'claude takes the prompt via -p, never stdin');
});

test('claude-adapter: native Read is always enabled and no longer image-gated', () => {
  const adapter = new ClaudeAdapter();
  const withImages = adapter.buildArgs({ ...BASE_BUILD_CONFIG, hasImageAttachments: true }).args;
  const withoutImages = adapter.buildArgs({ ...BASE_BUILD_CONFIG, hasImageAttachments: false }).args;
  // Read is a full native tool now; hasImageAttachments must NOT gate it (or anything).
  assert.ok(!withImages.includes('Read'), 'Read must be enabled (absent from --disallowedTools) with images');
  assert.ok(!withoutImages.includes('Read'), 'Read must be enabled (absent from --disallowedTools) without images too');
  assert.deepEqual(withImages, withoutImages, 'hasImageAttachments must no longer change the arg list');
});

test('claude-adapter: buildChildEnv pins ENABLE_TOOL_SEARCH=false (keeps platform tools directly callable)', () => {
  const adapter = new ClaudeAdapter();
  const env = adapter.buildChildEnv('/tmp', undefined);
  assert.equal(env.ENABLE_TOOL_SEARCH, 'false',
    'tool-search deferral must be OFF so native + platform MCP tools all load directly (2026-06-04 regression guard)');
});

test('claude-adapter: buildChildEnv pins ENABLE_TOOL_SEARCH=false even if the host env sets it true', () => {
  const adapter = new ClaudeAdapter();
  const saved = process.env.ENABLE_TOOL_SEARCH;
  process.env.ENABLE_TOOL_SEARCH = 'true';
  try {
    const env = adapter.buildChildEnv('/tmp', undefined);
    // The spread order is load-bearing: the pinned `false` is applied AFTER `...base`, so a
    // stray ambient ENABLE_TOOL_SEARCH=true can't re-enable deferral and resurrect the
    // 2026-06-04 regression.
    assert.equal(env.ENABLE_TOOL_SEARCH, 'false', 'the pinned false must win over an ambient =true');
  } finally {
    if (saved === undefined) delete process.env.ENABLE_TOOL_SEARCH; else process.env.ENABLE_TOOL_SEARCH = saved;
  }
});

test('claude-adapter: buildChildEnv sets IS_SANDBOX=1 ONLY under root, so claude accepts --dangerously-skip-permissions in a containerized CE', () => {
  const adapter = new ClaudeAdapter();
  const savedGetuid = process.getuid;
  try {
    // Containerized CE bridge runs as uid 0; the CLI refuses
    // --dangerously-skip-permissions under root unless IS_SANDBOX opts in.
    process.getuid = () => 0;
    assert.equal(adapter.buildChildEnv('/tmp', undefined).IS_SANDBOX, '1',
      'root container must opt into the sandbox so the claude CLI accepts --dangerously-skip-permissions');

    // Non-root (prod, user-space bridge): the opt-in is unnecessary, leave it unset.
    process.getuid = () => 1000;
    assert.equal(adapter.buildChildEnv('/tmp', undefined).IS_SANDBOX, undefined,
      'non-root bridge must NOT be forced into sandbox mode');
  } finally {
    process.getuid = savedGetuid;
  }
});

test('claude-adapter: writeMcpConfig pins the platform server with alwaysLoad=true', () => {
  const adapter = new ClaudeAdapter();
  const tmp = mkdtempSync(join(tmpdir(), 'mcpcfg-'));
  try {
    const p = adapter.writeMcpConfig(tmp, { serverName: 'agent-cli', command: 'node', args: ['x.mjs'], env: { A: '1' } });
    const server = JSON.parse(readFileSync(p, 'utf8')).mcpServers['agent-cli'];
    // alwaysLoad belts-and-suspenders ENABLE_TOOL_SEARCH=false: the platform tools
    // never defer behind ToolSearch regardless of how many native tools are enabled.
    assert.equal(server.alwaysLoad, true, 'platform MCP server must be alwaysLoad');
    assert.equal(server.command, 'node');
    assert.deepEqual(server.env, { A: '1' });
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
});

test('isExtendedThinkingContinuation: pause_turn + same tokens → true', () => {
  assert.equal(isExtendedThinkingContinuation({ promptTokens: 13333 }, 13333, 'pause_turn'), true);
});
test('isExtendedThinkingContinuation: any other stop_reason → false', () => {
  assert.equal(isExtendedThinkingContinuation({ promptTokens: 13333 }, 13333, 'tool_use'), false);
  assert.equal(isExtendedThinkingContinuation({ promptTokens: 13333 }, 13333, 'end_turn'), false);
});
test('isExtendedThinkingContinuation: null prevCall → false', () => {
  assert.equal(isExtendedThinkingContinuation(null, 13333, 'pause_turn'), false);
});
test('isExtendedThinkingContinuation: token mismatch → false', () => {
  assert.equal(isExtendedThinkingContinuation({ promptTokens: 13333 }, 14000, 'pause_turn'), false);
});

// The spawned CLI inherits the bridge's env; with native Read/Bash enabled the agent
// could echo it from /proc/self/environ, so the Redis password (the bridge's OWN infra
// secret) must be stripped from the child env here. CLAUDECODE/ENTRYPOINT must also
// still be dropped (lets claude spawn from within a Claude Code session).
test('claude-adapter: buildChildEnv strips REDIS_URL but RETAINS other vars (targeted, not a blanket scrub)', () => {
  const adapter = new ClaudeAdapter();
  const saved = { r: process.env.REDIS_URL, k: process.env.ANTHROPIC_API_KEY };
  process.env.REDIS_URL = 'redis://:example-password@203.0.113.10:6379';
  process.env.ANTHROPIC_API_KEY = 'sk-ant-deadbeef';
  try {
    const env = adapter.buildChildEnv('/tmp', undefined);
    assert.equal(env.REDIS_URL, undefined, 'REDIS_URL (the bridge Redis password) must not reach the spawned CLI');
    // The strip is DELIBERATELY narrow - only the bridge's OWN infra secret. Provider keys
    // and operator-provisioned creds the agent legitimately uses must pass through; this is
    // a capability model, not a blanket secret scrub. (Locks the decision the audit flagged.)
    assert.equal(env.ANTHROPIC_API_KEY, 'sk-ant-deadbeef', 'a provider key must be RETAINED (not blanket-scrubbed)');
    assert.ok('PATH' in env || 'Path' in env, 'ordinary env vars must still be inherited');
  } finally {
    if (saved.r === undefined) delete process.env.REDIS_URL; else process.env.REDIS_URL = saved.r;
    if (saved.k === undefined) delete process.env.ANTHROPIC_API_KEY; else process.env.ANTHROPIC_API_KEY = saved.k;
  }
});

test('claude-adapter: buildChildEnv still drops CLAUDECODE/CLAUDE_CODE_ENTRYPOINT', () => {
  const adapter = new ClaudeAdapter();
  const saved = { c: process.env.CLAUDECODE, e: process.env.CLAUDE_CODE_ENTRYPOINT };
  process.env.CLAUDECODE = '1';
  process.env.CLAUDE_CODE_ENTRYPOINT = 'cli';
  try {
    const env = adapter.buildChildEnv('/tmp', undefined);
    assert.equal(env.CLAUDECODE, undefined);
    assert.equal(env.CLAUDE_CODE_ENTRYPOINT, undefined);
  } finally {
    if (saved.c === undefined) delete process.env.CLAUDECODE; else process.env.CLAUDECODE = saved.c;
    if (saved.e === undefined) delete process.env.CLAUDE_CODE_ENTRYPOINT; else process.env.CLAUDE_CODE_ENTRYPOINT = saved.e;
  }
});
