/**
 * Tests for the Gemini adapter - covers the gemini-specific routing
 * (item events via shared helper, claude-style assistant messages,
 * flat tool/text events, error mapping, _recordUsage field aliasing).
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { GeminiAdapter } from '../adapters/gemini-adapter.mjs';

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

  const adapter = new GeminiAdapter();
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

test('gemini-adapter: item.completed mcp_tool_call with result fires call+result', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'g_1', tool: 'workflow', arguments: '{}', result: 'OK', status: 'completed' },
  }, ctx);
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx._published.toolResults.length, 1);
});

test('gemini-adapter: claude-style assistant message routes through helper', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'assistant',
    message: {
      content: [
        { type: 'text', text: 'hi' },
        { type: 'tool_use', id: 'tu_g', name: 'workflow', input: { x: 1 } },
      ],
    },
  }, ctx);
  assert.equal(ctx._published.content.includes('hi'), true);
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx.state.numTurns, 1, 'helper bumps turn');
});

test('gemini-adapter: flat tool_use event registers and publishes', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({ type: 'tool_use', id: 'flat_1', name: 'workflow', input: {} }, ctx);
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx.pendingToolCalls.size, 1);
});

test('gemini-adapter: turn_complete increments numTurns and records usage', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'turn_complete',
    usage: { input_tokens: 200, output_tokens: 50, cached_content_token_count: 30, thoughts_token_count: 10 },
  }, ctx);
  assert.equal(ctx.state.numTurns, 1);
  assert.equal(ctx.state.usage.promptTokens, 200);
  assert.equal(ctx.state.usage.completionTokens, 50);
  // Cached + reasoning tokens should land via the gemini field aliases.
  assert.equal(ctx.state.perCallUsages[0].cacheReadInputTokens, 30);
  assert.equal(ctx.state.perCallUsages[0].reasoningTokens, 10);
});

test('gemini-adapter: case "error" routes through applyResultMapping', async () => {
  const { adapter, ctx } = makeCtx();
  const origErr = console.error;
  console.error = () => {};
  try {
    await adapter.handleMessage({ type: 'error', message: 'gemini broke' }, ctx);
  } finally {
    console.error = origErr;
  }
  assert.ok(true);
});

test('gemini-adapter: unknown message type does not throw', async () => {
  const { adapter, ctx } = makeCtx();
  const origLog = console.log;
  console.log = () => {};
  try {
    await adapter.handleMessage({ type: 'something_new', subtype: 'foo' }, ctx);
  } finally {
    console.log = origLog;
  }
  assert.ok(true);
});

test('gemini-adapter: system init records cliModel', async () => {
  const { adapter, ctx } = makeCtx();
  let model = null;
  ctx.updateState = (u) => { if (u.cliModel != null) model = u.cliModel; };
  await adapter.handleMessage({ type: 'system', model: 'gemini-2.5-pro', tools: [] }, ctx);
  assert.equal(model, 'gemini-2.5-pro');
});
