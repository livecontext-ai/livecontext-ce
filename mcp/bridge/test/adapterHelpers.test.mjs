/**
 * Tests for the shared adapter helpers. These cover the regression that
 * caused tool double-execution + closure-trap-masked usage doubling, and the
 * reduction loop that used to be inlined four times across adapters.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  recordCallUsage,
  dispatchToolCall,
  dispatchToolResult,
  incrementTurn,
  handleFlatCliMessage,
  handleClaudeStyleAssistantMessage,
  buildStdinPayload,
  PROMPT_SEPARATOR,
} from '../lib/adapterHelpers.mjs';

/**
 * Build a fake ctx that mirrors what server.mjs constructs at runtime.
 * Must use getters for primitives so the closure-trap regression cannot
 * silently come back.
 */
function makeCtx() {
  let usage = { promptTokens: 0, completionTokens: 0 };
  let numTurns = 0;
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
      publishedToolResults.push({ toolId, toolName, success, durationMs, content, metadata });
    },
    publishContent: async () => {},
    publishThinking: async () => {},
  };

  return {
    publisher,
    pendingToolCalls,
    orderedEntries,
    toolResults,
    thinkingSections,
    state: {
      get usage() { return usage; },
      get numTurns() { return numTurns; },
      get perCallUsages() { return perCallUsages; },
      get iterationTimestamps() { return iterationTimestamps; },
      get finishReasons() { return finishReasons; },
    },
    updateState(updates) {
      if (updates.usage != null) usage = updates.usage;
      if (updates.numTurns != null) numTurns = updates.numTurns;
    },
    // Test inspection helpers
    _published: { toolCalls: publishedToolCalls, toolResults: publishedToolResults },
  };
}

test('recordCallUsage pushes one entry per call and recomputes the cumulative', () => {
  const ctx = makeCtx();
  const r1 = recordCallUsage(ctx, { promptTokens: 100, completionTokens: 20 });
  assert.equal(r1.callIndex, 1);
  assert.equal(r1.totalInput, 100);
  assert.equal(r1.totalOutput, 20);
  assert.equal(ctx.state.usage.promptTokens, 100);

  const r2 = recordCallUsage(ctx, { promptTokens: 250, completionTokens: 50 });
  assert.equal(r2.callIndex, 2);
  assert.equal(r2.totalInput, 350);
  assert.equal(r2.totalOutput, 70);
  assert.equal(ctx.state.usage.promptTokens, 350);
  assert.equal(ctx.state.usage.completionTokens, 70);

  // Per-call timestamps grow with each push.
  assert.equal(ctx.state.iterationTimestamps.length, 2);
});

test('recordCallUsage preserves cache + reasoning fields', () => {
  const ctx = makeCtx();
  recordCallUsage(ctx, {
    promptTokens: 1,
    completionTokens: 2,
    cacheCreationInputTokens: 3,
    cacheReadInputTokens: 4,
    reasoningTokens: 5,
  });
  const entry = ctx.state.perCallUsages[0];
  assert.equal(entry.cacheCreationInputTokens, 3);
  assert.equal(entry.cacheReadInputTokens, 4);
  assert.equal(entry.reasoningTokens, 5);
});

test('recordCallUsage reads through ctx.state getters (closure-trap regression)', () => {
  // The original bridge bug captured `usage` by value in a state literal.
  // After the fix, ctx.state.usage is a getter and reflects mutations.
  // This test will fail loudly if a contributor reverts the getter pattern.
  const ctx = makeCtx();
  recordCallUsage(ctx, { promptTokens: 5, completionTokens: 1 });
  recordCallUsage(ctx, { promptTokens: 5, completionTokens: 1 });
  // If the closure trap returns, ctx.state.usage will still be {0,0} here.
  assert.equal(ctx.state.usage.promptTokens, 10, 'closure trap regression');
  assert.equal(ctx.state.usage.completionTokens, 2);
});

test('dispatchToolCall registers pending entry, orderedEntries, and publishes', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, { toolId: 'tu_1', toolName: 'workflow', argsStr: '{"x":1}' });
  assert.equal(ctx.pendingToolCalls.size, 1);
  assert.equal(ctx.pendingToolCalls.get('tu_1').toolName, 'workflow');
  assert.equal(ctx.orderedEntries.length, 1);
  assert.equal(ctx.orderedEntries[0].type, 'tool_call');
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.deepEqual(ctx._published.toolCalls[0], { toolName: 'workflow', toolId: 'tu_1', argsStr: '{"x":1}' });
});

test('dispatchToolCall passes extras into pending entry', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, {
    toolId: 'tu_2',
    toolName: 'Read',
    argsStr: '{"file_path":"x"}',
    extras: { attachmentFileName: 'pic.png' },
  });
  assert.equal(ctx.pendingToolCalls.get('tu_2').attachmentFileName, 'pic.png');
});

test('dispatchToolResult publishes, records, and clears pending', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, { toolId: 'tu_3', toolName: 'workflow', argsStr: '{}' });
  await dispatchToolResult(ctx, { toolId: 'tu_3', isError: false, content: 'OK' });
  assert.equal(ctx.toolResults.length, 1);
  assert.equal(ctx.toolResults[0].success, true);
  assert.equal(ctx.toolResults[0].content, 'OK');
  assert.equal(ctx.pendingToolCalls.has('tu_3'), false);
  assert.equal(ctx._published.toolResults.length, 1);
  assert.equal(ctx._published.toolResults[0].success, true);
});

test('dispatchToolResult uses errorMsg when isError', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, { toolId: 'tu_4', toolName: 'workflow', argsStr: '{}' });
  await dispatchToolResult(ctx, { toolId: 'tu_4', isError: true, content: 'fail body', errorMsg: 'specific reason' });
  assert.equal(ctx.toolResults[0].error, 'specific reason');
  assert.equal(ctx.toolResults[0].success, false);
});

test('dispatchToolResult propagates attachmentFileName into metadata.label', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, {
    toolId: 'tu_5',
    toolName: 'Read',
    argsStr: '{}',
    extras: { attachmentFileName: 'screenshot.png' },
  });
  await dispatchToolResult(ctx, { toolId: 'tu_5', isError: false, content: 'data' });
  const meta = ctx.toolResults[0].metadata;
  assert.equal(meta.label, 'screenshot.png');
  assert.equal(meta.toolName, 'view_attachment');
});

test('dispatchToolResult tolerates missing pending entry but warns loudly', async () => {
  const ctx = makeCtx();
  const origWarn = console.warn;
  const warns = [];
  console.warn = (m) => warns.push(m);
  try {
    await dispatchToolResult(ctx, { toolId: 'tu_unknown', isError: false, content: 'data', fallbackToolName: 'workflow' });
  } finally {
    console.warn = origWarn;
  }
  assert.equal(ctx.toolResults[0].toolCall.toolName, 'workflow');
  assert.equal(ctx.toolResults[0].durationMs, null);
  assert.ok(warns.some((w) => /no pending tool for id=tu_unknown/.test(w)), 'must warn on missing pending');
});

test('incrementTurn bumps numTurns through updateState', () => {
  const ctx = makeCtx();
  incrementTurn(ctx);
  incrementTurn(ctx);
  incrementTurn(ctx);
  assert.equal(ctx.state.numTurns, 3);
});

// ─── isExtendedThinkingContinuation ───────────────────────────────────────

import { isExtendedThinkingContinuation } from '../adapters/claude-adapter.mjs';

test('isExtendedThinkingContinuation requires pause_turn as prev stop_reason', () => {
  const prev = { promptTokens: 13333 };
  // Same tokens + pause_turn → continuation
  assert.equal(isExtendedThinkingContinuation(prev, 13333, 'pause_turn'), true);
  // Same tokens but stop was tool_use → NOT a continuation (independent turn)
  assert.equal(isExtendedThinkingContinuation(prev, 13333, 'tool_use'), false);
  // Same tokens, end_turn → NOT a continuation
  assert.equal(isExtendedThinkingContinuation(prev, 13333, 'end_turn'), false);
});

test('isExtendedThinkingContinuation returns false when no prev call', () => {
  assert.equal(isExtendedThinkingContinuation(null, 13333, 'pause_turn'), false);
});

test('isExtendedThinkingContinuation returns false when token counts differ', () => {
  const prev = { promptTokens: 13333 };
  assert.equal(isExtendedThinkingContinuation(prev, 14000, 'pause_turn'), false);
});

// ─── handleCodexStyleItemEvent (gemini/mistral path) ──────────────────────

import { handleCodexStyleItemEvent } from '../lib/adapterHelpers.mjs';

function makeItemCtx() {
  const base = makeCtx();
  base.stripMcpPrefix = (n) => n.replace(/^mcp__[^_]+__/, '');
  base.extractToolResultAndMetadata = (raw) => ({ content: typeof raw === 'string' ? raw : JSON.stringify(raw), metadata: {} });
  base.getContent = () => '';
  // augment publisher with content/thinking
  base.publisher.publishContent = async () => {};
  base.publisher.publishThinking = async () => {};
  return base;
}

test('handleCodexStyleItemEvent: tool_call with result fires call + result exactly once', async () => {
  const ctx = makeItemCtx();
  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'call_1', tool: 'workflow', arguments: '{"x":1}', result: 'OK', status: 'completed' },
  }, ctx, { providerKey: 'gemini' });
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx._published.toolResults.length, 1);
  assert.equal(ctx._published.toolResults[0].success, true);
  assert.equal(ctx.pendingToolCalls.size, 0);
});

test('handleCodexStyleItemEvent: tool_call without result registers pending only', async () => {
  const ctx = makeItemCtx();
  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'call_2', tool: 'workflow', arguments: '{}' },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx._published.toolResults.length, 0);
  assert.equal(ctx.pendingToolCalls.size, 1);
});

test('handleCodexStyleItemEvent: agent_message records usage via opts.recordUsage', async () => {
  const ctx = makeItemCtx();
  let recorded = null;
  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'agent_message', text: 'hi', usage: { input_tokens: 100, output_tokens: 5 } },
  }, ctx, {
    providerKey: 'gemini',
    recordUsage: (u) => { recorded = u; },
  });
  assert.equal(recorded.input_tokens, 100);
  assert.equal(recorded.output_tokens, 5);
});

// ─── handleFlatCliMessage (gemini/mistral path) ───────────────────────────

test('handleFlatCliMessage: tool_use registers pending and publishes', async () => {
  const ctx = makeItemCtx();
  const handled = await handleFlatCliMessage({ type: 'tool_use', id: 't1', name: 'workflow', input: { x: 1 } }, ctx, { providerKey: 'gemini' });
  assert.equal(handled, true);
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx.pendingToolCalls.size, 1);
});

test('handleFlatCliMessage: tool_result publishes and clears pending', async () => {
  const ctx = makeItemCtx();
  await handleFlatCliMessage({ type: 'tool_use', id: 't2', name: 'workflow', input: {} }, ctx, { providerKey: 'mistral' });
  await handleFlatCliMessage({ type: 'tool_result', tool_use_id: 't2', output: 'ok' }, ctx, { providerKey: 'mistral' });
  assert.equal(ctx._published.toolResults.length, 1);
  assert.equal(ctx.pendingToolCalls.size, 0);
});

test('handleFlatCliMessage: returns false for unknown type', async () => {
  const ctx = makeItemCtx();
  const handled = await handleFlatCliMessage({ type: 'turn_complete' }, ctx, { providerKey: 'gemini' });
  assert.equal(handled, false);
});

test('handleFlatCliMessage: text/content event publishes content', async () => {
  const ctx = makeItemCtx();
  let published = '';
  ctx.publisher.publishContent = async (t) => { published = t; };
  ctx.getContent = () => '';
  const handled = await handleFlatCliMessage({ type: 'text', text: 'hello' }, ctx, { providerKey: 'gemini' });
  assert.equal(handled, true);
  assert.equal(published, 'hello');
});

// ─── buildStdinPayload ────────────────────────────────────────────────────

test('buildStdinPayload: prepends system prompt with separator when present', () => {
  assert.equal(buildStdinPayload('SYS', 'USER'), `SYS${PROMPT_SEPARATOR}USER`);
});

test('buildStdinPayload: returns prompt as-is when systemPrompt is empty', () => {
  assert.equal(buildStdinPayload('', 'just user'), 'just user');
  assert.equal(buildStdinPayload(null, 'just user'), 'just user');
  assert.equal(buildStdinPayload(undefined, 'just user'), 'just user');
});

// ─── handleClaudeStyleAssistantMessage ────────────────────────────────────

test('handleClaudeStyleAssistantMessage: tool_use block fires dispatchToolCall', async () => {
  const ctx = makeItemCtx();
  await handleClaudeStyleAssistantMessage({
    message: {
      content: [{ type: 'tool_use', id: 'toolu_a', name: 'workflow', input: { x: 1 } }],
    },
  }, ctx, { providerKey: 'gemini' });
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx.pendingToolCalls.size, 1);
});

test('handleClaudeStyleAssistantMessage: text block publishes content + accumulates', async () => {
  const ctx = makeItemCtx();
  let captured = '';
  ctx.publisher.publishContent = async (t) => { captured = t; };
  let acc = '';
  ctx.getContent = () => acc;
  ctx.updateState = (u) => { if (u.fullContent != null) acc = u.fullContent; if (u.numTurns != null) {} };
  await handleClaudeStyleAssistantMessage({
    message: { content: [{ type: 'text', text: 'hi there' }] },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(captured, 'hi there');
});

test('handleClaudeStyleAssistantMessage: thinking block publishes + records section', async () => {
  const ctx = makeItemCtx();
  let captured = '';
  ctx.publisher.publishThinking = async (t) => { captured = t; };
  await handleClaudeStyleAssistantMessage({
    message: { content: [{ type: 'thinking', thinking: 'reasoning…' }] },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(captured, 'reasoning…');
  assert.equal(ctx.thinkingSections.length, 1);
});

test('handleClaudeStyleAssistantMessage: routes usage through opts.recordUsage', async () => {
  const ctx = makeItemCtx();
  let recorded = null;
  await handleClaudeStyleAssistantMessage({
    message: { content: [], usage: { input_tokens: 50, output_tokens: 10 } },
  }, ctx, {
    providerKey: 'gemini',
    recordUsage: (u) => { recorded = u; },
  });
  assert.equal(recorded.input_tokens, 50);
});

// ─── synthId monotonicity ─────────────────────────────────────────────────

test('synthId helpers do not collide within same millisecond', async () => {
  // Force two tool dispatches in the same Date.now() tick by issuing them
  // back-to-back without any await between the calls. The Set behind the
  // counter must mint distinct ids regardless.
  const ctx = makeItemCtx();
  await handleFlatCliMessage({ type: 'tool_use', name: 'a', input: {} }, ctx, { providerKey: 'gemini' });
  await handleFlatCliMessage({ type: 'tool_use', name: 'b', input: {} }, ctx, { providerKey: 'gemini' });
  const ids = Array.from(ctx.pendingToolCalls.keys());
  assert.equal(ids.length, 2);
  assert.notEqual(ids[0], ids[1], 'consecutive synthetic ids must differ even within the same ms');
});

test('handleCodexStyleItemEvent: tool_call_result with prior pending uses pending toolName', async () => {
  const ctx = makeItemCtx();
  // First a started event
  await handleCodexStyleItemEvent({
    type: 'item.started',
    item: { type: 'mcp_tool_call', id: 'call_3', tool: 'workflow', arguments: '{}' },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(ctx.pendingToolCalls.size, 1);
  // Then a result event
  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call_result', call_id: 'call_3', output: 'result body' },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(ctx.toolResults.length, 1);
  assert.equal(ctx.toolResults[0].toolCall.toolName, 'workflow');
  assert.equal(ctx.pendingToolCalls.size, 0);
});
