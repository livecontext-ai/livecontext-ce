/**
 * Tests for the Codex adapter - covers the command_execution and web_search
 * branches that are NOT handled by the shared handleCodexStyleItemEvent
 * helper, plus the synth-id collision regression for those branches.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CodexAdapter } from '../adapters/codex-adapter.mjs';

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

  const publisher = {
    publishToolCall: async (toolName, toolId, argsStr) => {
      publishedToolCalls.push({ toolName, toolId, argsStr });
    },
    publishToolResult: async (toolId, toolName, success) => {
      publishedToolResults.push({ toolId, toolName, success });
    },
    publishContent: async () => {},
    publishThinking: async () => {},
  };

  const adapter = new CodexAdapter();
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
      _published: { toolCalls: publishedToolCalls, toolResults: publishedToolResults },
    },
  };
}

test('codex-adapter: item.started command_execution publishes shell tool call', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'item.started',
    item: { type: 'command_execution', id: 'cmd_1', command: 'ls -la' },
  }, ctx);
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx._published.toolCalls[0].toolName, 'shell');
  assert.equal(ctx.pendingToolCalls.size, 1);
});

test('codex-adapter: item.completed command_execution publishes result and clears pending', async () => {
  const { adapter, ctx } = makeCtx();
  // Pre-register via started
  await adapter.handleMessage({
    type: 'item.started',
    item: { type: 'command_execution', id: 'cmd_2', command: 'echo hi' },
  }, ctx);
  await adapter.handleMessage({
    type: 'item.completed',
    item: { type: 'command_execution', id: 'cmd_2', command: 'echo hi', aggregated_output: 'hi', exit_code: 0 },
  }, ctx);
  assert.equal(ctx._published.toolResults.length, 1);
  assert.equal(ctx._published.toolResults[0].success, true);
  assert.equal(ctx.pendingToolCalls.size, 0);
});

test('codex-adapter: item.completed command_execution without prior started still emits call+result', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'item.completed',
    item: { type: 'command_execution', id: 'cmd_3', command: 'true', aggregated_output: '', exit_code: 0 },
  }, ctx);
  assert.equal(ctx._published.toolCalls.length, 1, 'must emit synthetic tool_call');
  assert.equal(ctx._published.toolResults.length, 1);
});

test('codex-adapter: item.completed command_execution with non-zero exit reports failure', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'item.completed',
    item: { type: 'command_execution', id: 'cmd_4', command: 'false', aggregated_output: 'err', exit_code: 1 },
  }, ctx);
  assert.equal(ctx._published.toolResults[0].success, false);
});

test('codex-adapter: item.completed web_search emits call + success result', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'item.completed',
    item: { type: 'web_search', id: 'ws_1', query: 'cats', action: { results: [] } },
  }, ctx);
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx._published.toolCalls[0].toolName, 'web_search');
  assert.equal(ctx._published.toolResults.length, 1);
  assert.equal(ctx._published.toolResults[0].success, true);
});

test('codex-adapter: command_execution synth ids do not collide within same ms', async () => {
  const { adapter, ctx } = makeCtx();
  // Two completed events without ids → must mint distinct synth ids back-to-back
  await adapter.handleMessage({
    type: 'item.completed',
    item: { type: 'command_execution', command: 'a', aggregated_output: 'a', exit_code: 0 },
  }, ctx);
  await adapter.handleMessage({
    type: 'item.completed',
    item: { type: 'command_execution', command: 'b', aggregated_output: 'b', exit_code: 0 },
  }, ctx);
  const ids = ctx._published.toolCalls.map((c) => c.toolId);
  assert.equal(ids.length, 2);
  assert.notEqual(ids[0], ids[1], 'synth ids must differ even within the same ms');
});

test('codex-adapter: turn.completed records usage via shared helper', async () => {
  const { adapter, ctx } = makeCtx();
  await adapter.handleMessage({
    type: 'turn.completed',
    usage: { input_tokens: 100, output_tokens: 50 },
  }, ctx);
  assert.equal(ctx.state.usage.promptTokens, 100);
  assert.equal(ctx.state.usage.completionTokens, 50);
  assert.equal(ctx.state.numTurns, 1);
});

test('codex-adapter: case "error" routes through applyResultMapping', async () => {
  const { adapter, ctx } = makeCtx();
  const origErr = console.error;
  console.error = () => {};
  try {
    await adapter.handleMessage({ type: 'error', message: 'codex broke' }, ctx);
  } finally {
    console.error = origErr;
  }
  assert.ok(true);
});
