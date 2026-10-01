import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ClaudeAdapter } from '../adapters/claude-adapter.mjs';
import { extractToolResultAndMetadata } from '../lib/toolContent.mjs';
import { resolveStopReason } from '../lib/stopReasonMapper.js';

// Captured from Claude Code 2.1.284 on 2026-09-29. Only protocol fields retained;
// session/request identifiers and unrelated diagnostics are deliberately omitted.
const quotaEvents = readFileSync(
  new URL('./fixtures/claude-session-limit.ndjson', import.meta.url), 'utf8'
).trim().split('\n').map(line => JSON.parse(line));
const quotaText = quotaEvents[1].message.content[0].text;

function run(adapter = new ClaudeAdapter()) {
  const published = { content: [], thinking: [], tools: [], errors: [] };
  const ctx = {
    adapterState: adapter.createRunState(),
    state: { fullContent: '', success: false, usage: { promptTokens: 0, completionTokens: 0 },
      perCallUsages: [], iterationTimestamps: [], finishReasons: [] },
    orderedEntries: [], thinkingSections: [], toolResults: [], pendingToolCalls: new Map(),
    publisher: {
      publishContent: async text => published.content.push(text),
      publishThinking: async text => published.thinking.push(text),
      publishToolCall: async (...args) => published.tools.push(args),
      publishToolResult: async (...args) => published.tools.push(args),
      publishError: async text => published.errors.push(text),
    },
    stripMcpPrefix: name => name,
    extractToolResultAndMetadata,
    updateState: updates => Object.assign(ctx.state, updates),
    getContent: () => ctx.state.fullContent,
  };
  return { ctx, published, send: event => adapter.handleMessage(structuredClone(event), ctx) };
}

function assistant(blocks, extra = {}) {
  return { type: 'assistant', message: { id: 'real-answer', model: 'claude-haiku-4-5',
    usage: { input_tokens: 20, output_tokens: 3 }, content: blocks }, ...extra };
}

test('session quota falsely reported as success stays an invisible failure eligible for the existing fallback', async () => {
  const { ctx, published, send } = run();
  // server.mjs invokes each line handler without awaiting the preceding one.
  await Promise.all(quotaEvents.map(send));
  assert.equal(ctx.state.success, false);
  assert.equal(ctx.state.stopReason, 'ERROR');
  assert.equal(ctx.state.error, quotaText);
  assert.equal(ctx.state.fullContent, '');
  assert.deepEqual(ctx.toolResults, []);
  assert.deepEqual(ctx.thinkingSections, []);
  assert.deepEqual(ctx.orderedEntries, []);
  assert.deepEqual(published, { content: [], thinking: [], tools: [], errors: [] });
  assert.deepEqual(ctx.state.usage, { promptTokens: 0, completionTokens: 0 });
  assert.equal(ctx.state.cliModel, undefined, 'a synthetic error is not a model response');
});

test('assistant quota error without a rate-limit event cannot be overwritten by a later success result', async () => {
  const { ctx, published, send } = run();
  await send(quotaEvents[1]);
  await send({ type: 'result', subtype: 'success', result: quotaText });
  assert.equal(ctx.state.success, false);
  assert.equal(ctx.state.error, quotaText);
  assert.equal(ctx.state.fullContent, '');
  assert.deepEqual(published.content, []);
});

test('result-only is_error takes precedence over subtype success and keeps the quota diagnostic out of content', async () => {
  const { ctx, published, send } = run();
  await send(quotaEvents[2]);
  assert.equal(ctx.state.success, false);
  assert.equal(ctx.state.stopReason, 'ERROR');
  assert.equal(ctx.state.error, quotaText);
  assert.equal(ctx.state.fullContent, '');
  assert.deepEqual(published.content, []);
});

for (const subtype of ['error_max_turns', 'error_max_tokens']) {
  test(`${subtype} with is_error=true remains a partial completion, not an API quota failure`, async () => {
    const { ctx, send } = run();
    await send({ type: 'result', subtype, is_error: true, result: 'Partial result' });
    assert.equal(ctx.state.success, true);
    assert.equal(ctx.state.stopReason, 'MAX_ITERATIONS');
    assert.equal(ctx.state.truncated, true);
    assert.equal(ctx.state.fullContent, 'Partial result');
    assert.equal(ctx.state.error, undefined);
  });
}

test('a turn cap cannot mask an explicit assistant API error received before it', async () => {
  const { ctx, send } = run();
  await send(quotaEvents[1]);
  await send({ type: 'result', subtype: 'error_max_turns', is_error: true, result: quotaText });
  assert.equal(ctx.state.success, false);
  assert.equal(ctx.state.stopReason, 'ERROR');
  assert.equal(ctx.state.fullContent, '');
});

for (const [event, expected] of [
  [{ type: 'assistant', error: 'rate_limit', message: { content: [] } }, 'rate_limit'],
  [{ type: 'assistant', is_api_error_message: true, message: { content: [] } }, 'Claude API request failed'],
  [{ type: 'assistant', is_api_error_message: true,
    message: { content: [{ type: 'text', text: 'Authentication failed' }] } }, 'Authentication failed'],
  [{ type: 'result', subtype: 'success', is_error: true, errors: ['Quota exhausted'] }, 'Quota exhausted'],
  [{ type: 'result', subtype: 'success', is_error: true, error: 'Explicit error', result: 'Other text' }, 'Explicit error'],
  [{ type: 'result', subtype: 'success', is_error: true, errors: 'unexpected shape', result: ' ' }, 'Claude API request failed'],
]) {
  test(`${event.type} error keeps a diagnostic even without the full quota envelope: ${expected}`, async () => {
    const { ctx, published, send } = run();
    await send(event);
    assert.equal(ctx.state.success, false);
    assert.equal(ctx.state.stopReason, 'ERROR');
    assert.equal(ctx.state.error, expected);
    assert.equal(ctx.state.fullContent, '');
    assert.deepEqual(published.content, []);
  });
}

for (const status of ['allowed', 'allowed_warning', 'rejected']) {
  test(`rate-limit status ${status} alone does not fail a valid answer (including rejected overage)`, async () => {
    const { ctx, published, send } = run();
    await send({ type: 'rate_limit_event', rate_limit_info: { status, overageStatus: 'rejected' } });
    await send(assistant([{ type: 'text', text: 'OK' }]));
    await send({ type: 'result', subtype: 'success', is_error: false });
    assert.equal(ctx.state.success, true);
    assert.equal(ctx.state.fullContent, 'OK');
    assert.deepEqual(published.content, ['OK']);
    assert.equal(ctx.state.error, undefined);
  });
}

test('quoting the quota text in an ordinary answer is not a provider failure, even with zero usage', async () => {
  const { ctx, published, send } = run();
  await send({ type: 'assistant', message: { content: [{ type: 'text', text: quotaText }],
    usage: { input_tokens: 0, output_tokens: 0 } } });
  await send({ type: 'result', subtype: 'success', is_error: 'true' });
  assert.equal(ctx.state.success, true);
  assert.equal(ctx.state.fullContent, quotaText);
  assert.deepEqual(published.content, [quotaText]);
});

for (const block of [{ type: 'text', text: 'Partial answer' },
  { type: 'thinking', thinking: 'Working through the request' },
  { type: 'tool_use', id: 'tool-before-quota', name: 'workflow', input: { action: 'list' } }]) {
  test(`quota after ${block.type} keeps prior output so the backend will not replay work`, async () => {
    const { ctx, published, send } = run();
    await send(assistant([block]));
    const before = structuredClone({ fullContent: ctx.state.fullContent,
      orderedEntries: ctx.orderedEntries, thinkingSections: ctx.thinkingSections, published });
    for (const event of quotaEvents) await send(event);
    assert.equal(ctx.state.success, false);
    assert.deepEqual({ fullContent: ctx.state.fullContent, orderedEntries: ctx.orderedEntries,
      thinkingSections: ctx.thinkingSections, published }, before);
    assert.deepEqual(ctx.state.usage, { promptTokens: 20, completionTokens: 3 });
  });
}

for (const [flag, reason] of [['stoppedByUser', 'STOPPED_BY_USER'], ['cancelledBySystem', 'CANCELLED'],
  ['budgetExhausted', 'BUDGET_EXHAUSTED'], ['timedOut', 'TIMEOUT']]) {
  test(`${flag} still determines the stop reason when a quota error also arrives`, async () => {
    const { ctx, send } = run();
    ctx.state[flag] = true;
    for (const event of quotaEvents) await send(event);
    assert.equal(ctx.state.stopReason, reason);
    assert.equal(ctx.state.fullContent, '', 'guard completion must not promote the error text to content');
  });
}

test('a nested subagent API error does not poison a successful parent turn', async () => {
  const { ctx, published, send } = run();
  await send({ ...quotaEvents[1], parent_tool_use_id: 'parent-task-tool' });
  await send(assistant([{ type: 'text', text: 'Recovered without the subagent' }]));
  await send({ type: 'result', subtype: 'success', is_error: false });
  assert.equal(ctx.state.success, true);
  assert.equal(ctx.state.error, undefined);
  assert.deepEqual(published.content, ['Recovered without the subagent']);
});

test('quota after a completed tool preserves its result and usage so fallback cannot repeat it', async () => {
  const { ctx, send } = run();
  await send(assistant([{ type: 'tool_use', id: 'created', name: 'workflow', input: { action: 'create' } }]));
  await send({ type: 'user', message: { content: [
    { type: 'tool_result', tool_use_id: 'created', content: 'Created workflow 123' },
  ] } });
  const before = structuredClone(ctx.toolResults);
  for (const event of quotaEvents) await send(event);
  assert.equal(ctx.state.success, false);
  assert.equal(before.length, 1);
  assert.deepEqual(ctx.toolResults, before);
  assert.deepEqual(ctx.state.usage, { promptTokens: 20, completionTokens: 3 });
});

test('quota state belongs to one run and cannot fail a concurrent healthy run', async () => {
  const adapter = new ClaudeAdapter(); // server.mjs reuses one adapter for concurrent requests.
  const failed = run(adapter);
  const healthy = run(adapter);
  await failed.send(quotaEvents[1]);
  await healthy.send({ type: 'result', subtype: 'success', result: 'Healthy' });
  await failed.send(quotaEvents[2]);
  assert.equal(failed.ctx.state.success, false);
  assert.equal(healthy.ctx.state.success, true);
  assert.equal(healthy.ctx.state.fullContent, 'Healthy');
});

test('Claude-specific is_error precedence does not change another provider result contract', () => {
  for (const provider of ['codex', 'gemini', 'mistral']) {
    assert.deepEqual(resolveStopReason(provider, { subtype: 'success', is_error: true }, { state: {} }),
      { reason: 'COMPLETED', success: true });
  }
});
