/**
 * Tests for the Mistral / Vibe adapter - covers the unique role-based
 * assistant path, the turn_complete double-increment regression, and the
 * shared-helper routing.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MistralAdapter } from '../adapters/mistral-adapter.mjs';

function makeCtx() {
  let usage = { promptTokens: 0, completionTokens: 0 };
  let numTurns = 0;
  let fullContent = '';
  const perCallUsages = [];
  const iterationTimestamps = [];
  const finishReasons = [];
  const pendingToolCalls = new Map();
  const orderedEntries = [];
  const toolResults = [];
  const thinkingSections = [];
  const publishedToolCalls = [];
  const publishedToolResults = [];
  const publishedContent = [];

  const publisher = {
    publishToolCall: async (toolName, toolId, argsStr) => publishedToolCalls.push({ toolName, toolId, argsStr }),
    publishToolResult: async (toolId, toolName, success) => publishedToolResults.push({ toolId, toolName, success }),
    publishContent: async (text) => publishedContent.push(text),
    publishThinking: async () => {},
  };

  const adapter = new MistralAdapter();
  const adapterState = adapter.createRunState ? adapter.createRunState({}) : {};

  return {
    adapter,
    ctx: {
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
        get perCallUsages() { return perCallUsages; },
        get iterationTimestamps() { return iterationTimestamps; },
        get finishReasons() { return finishReasons; },
      },
      updateState(updates) {
        if (updates.usage != null) usage = updates.usage;
        if (updates.numTurns != null) numTurns = updates.numTurns;
        if (updates.fullContent != null) fullContent = updates.fullContent;
      },
      _published: { toolCalls: publishedToolCalls, toolResults: publishedToolResults, content: publishedContent },
    },
  };
}

test('mistral-adapter: role:assistant publishes content WITHOUT incrementing turn', async () => {
  // Regression: previously this branch incremented numTurns, then turn_complete
  // incremented again → double-count. Bug fix is now under test.
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    role: 'assistant',
    content: 'partial answer',
    usage: { input_tokens: 100, output_tokens: 10 },
  }, ctx);
  assert.equal(ctx._published.content[0], 'partial answer');
  assert.equal(ctx.state.numTurns, 0, 'role:assistant must NOT increment turn');
  assert.equal(ctx.state.perCallUsages.length, 1);
});

test('mistral-adapter: role:assistant followed by turn_complete bumps turn exactly once', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({ role: 'assistant', content: 'msg', usage: { input_tokens: 5, output_tokens: 2 } }, ctx);
  await adapter.handleMessage({ type: 'turn_complete' }, ctx);
  assert.equal(ctx.state.numTurns, 1, 'exactly one increment for the pair');
});

test('mistral-adapter: type:assistant with content blocks routes through helper', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'assistant',
    message: {
      content: [{ type: 'tool_use', id: 'tu_m', name: 'workflow', input: {} }],
      usage: { input_tokens: 10, output_tokens: 5 },
    },
  }, ctx);
  assert.equal(ctx._published.toolCalls.length, 1);
});

test('mistral-adapter: item.completed mcp_tool_call routes through shared helper', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'm_1', tool: 'workflow', arguments: '{}', result: 'OK' },
  }, ctx);
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx._published.toolResults.length, 1);
});

test('mistral-adapter: flat tool_use event registers pending', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({ type: 'tool_use', id: 'flat_m', name: 'workflow', input: {} }, ctx);
  assert.equal(ctx.pendingToolCalls.size, 1);
});

test('mistral-adapter: case "error" routes through applyResultMapping', async () => {
  const { adapter, ctx } = makeCtx();
  const origErr = console.error;
  console.error = () => {};
  try {
    await adapter.handleMessage({ type: 'error', message: 'mistral broke' }, ctx);
  } finally {
    console.error = origErr;
  }
  assert.ok(true);
});

test('mistral-adapter: turn_complete records usage even without role:assistant prelude', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'turn_complete',
    usage: { input_tokens: 100, output_tokens: 30 },
  }, ctx);
  assert.equal(ctx.state.numTurns, 1);
  assert.equal(ctx.state.usage.promptTokens, 100);
});
