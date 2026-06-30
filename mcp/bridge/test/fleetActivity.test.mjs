/**
 * Tests for bridge fleet-activity publishing (ws:agent:activity:{agentEntityId}).
 *
 * The bridge is the only place that observes a CLI agent's tool calls in real time,
 * so it must emit tool_call_started/completed itself - the Java side only publishes
 * execution_started/completed for bridge agents, leaving the in-between blind.
 *
 * Fleet emission is wired into RedisPublisher.publishToolCall / publishToolResult -
 * the single canonical points EVERY adapter path funnels through (dispatchToolCall,
 * the codex item.started + synthetic paths, gemini/mistral flat path). These tests pin
 * the channel, payload schema (matching AgentActivityPublisher.java), exactly-once
 * emission across those paths, and the no-op-without-agentEntityId contract.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RedisPublisher } from '../redis-publisher.mjs';
import { dispatchToolCall, dispatchToolResult, handleCodexStyleItemEvent } from '../lib/adapterHelpers.mjs';

function fakeRedis() {
  const published = [];
  return {
    published,
    publish: async (channel, json) => { published.push({ channel, payload: JSON.parse(json) }); return 1; },
    rpush: async () => 1,
    set: async () => 'OK',
    get: async () => null,
    exists: async () => 0,
  };
}

function fleetEvents(redis, agentEntityId) {
  return redis.published.filter((p) => p.channel === `ws:agent:activity:${agentEntityId}`);
}

function agentPublisher(redis, fleet = { agentEntityId: 'agent-9', executionId: 'exec-9', taskId: 'task-9' }) {
  return new RedisPublisher(redis, 'stream-1', 'conv-1', redis, null, fleet);
}

/** Minimal adapter ctx sufficient for the tool-call paths of handleCodexStyleItemEvent. */
function codexCtx(publisher) {
  return {
    publisher,
    pendingToolCalls: new Map(),
    orderedEntries: [],
    thinkingSections: [],
    toolResults: [],
    stripMcpPrefix: (n) => n,
    extractToolResultAndMetadata: (c) => ({ content: c, metadata: {} }),
    updateState: () => {},
    getContent: () => '',
  };
}

test('publishFleetToolCallStarted emits tool_call_started to ws:agent:activity:{agentEntityId}', async () => {
  const redis = fakeRedis();
  await agentPublisher(redis).publishFleetToolCallStarted('web_search', 'call-1');

  const fleet = fleetEvents(redis, 'agent-9');
  assert.equal(fleet.length, 1);
  assert.equal(fleet[0].payload.event, 'tool_call_started');
  assert.equal(fleet[0].payload.agentEntityId, 'agent-9');
  assert.equal(fleet[0].payload.executionId, 'exec-9');
  assert.equal(fleet[0].payload.toolName, 'web_search');
  assert.equal(fleet[0].payload.toolCallId, 'call-1');
  assert.equal(fleet[0].payload.taskId, 'task-9');
  assert.ok(fleet[0].payload.timestamp, 'timestamp present');
});

test('publishFleetToolCallCompleted emits tool_call_completed with success + durationMs, omits taskId when none', async () => {
  const redis = fakeRedis();
  await agentPublisher(redis, { agentEntityId: 'agent-9', executionId: 'exec-9' })
    .publishFleetToolCallCompleted('web_search', 'call-1', true, 1234);

  const fleet = fleetEvents(redis, 'agent-9');
  assert.equal(fleet.length, 1);
  assert.equal(fleet[0].payload.event, 'tool_call_completed');
  assert.equal(fleet[0].payload.success, true);
  assert.equal(fleet[0].payload.durationMs, 1234);
  // No taskId provided → key omitted (matches AgentActivityPublisher.basePayload).
  assert.equal('taskId' in fleet[0].payload, false);
});

test('fleet publish is a NO-OP when no agentEntityId (non-agent bridge calls)', async () => {
  const redis = fakeRedis();
  const pub = new RedisPublisher(redis, 'stream-1', 'conv-1', redis, null, null);

  await pub.publishFleetToolCallStarted('web_search', 'call-1');
  await pub.publishFleetToolCallCompleted('web_search', 'call-1', true, 5);
  // The canonical publish methods must also stay silent on the fleet channel.
  await pub.publishToolCall('web_search', 'call-1', '{}');
  await pub.publishToolResult('call-1', 'web_search', true, 5, 'ok', {});

  const fleet = redis.published.filter((p) => p.channel.startsWith('ws:agent:activity:'));
  assert.equal(fleet.length, 0);
});

test('publishToolCall emits BOTH a conversation tool event AND a fleet tool_call_started (single canonical point)', async () => {
  const redis = fakeRedis();
  await agentPublisher(redis).publishToolCall('catalog', 'call-1', '{}');

  // Conversation stream got it (ws:conversation:conv-1) ...
  assert.ok(redis.published.some((p) => p.channel === 'ws:conversation:conv-1' && p.payload.toolName === 'catalog'));
  // ... and the fleet channel got exactly one started.
  const fleet = fleetEvents(redis, 'agent-9');
  assert.equal(fleet.length, 1);
  assert.equal(fleet[0].payload.event, 'tool_call_started');
  assert.equal(fleet[0].payload.toolCallId, 'call-1');
});

test('publishToolResult emits a fleet tool_call_completed paired with the started', async () => {
  const redis = fakeRedis();
  await agentPublisher(redis).publishToolResult('call-1', 'catalog', false, 99, 'boom', {});

  const fleet = fleetEvents(redis, 'agent-9');
  assert.equal(fleet.length, 1);
  assert.equal(fleet[0].payload.event, 'tool_call_completed');
  assert.equal(fleet[0].payload.success, false);
  assert.equal(fleet[0].payload.durationMs, 99);
});

test('dispatchToolCall + dispatchToolResult publish fleet activity through the real publisher', async () => {
  const redis = fakeRedis();
  const ctx = { publisher: agentPublisher(redis, { agentEntityId: 'agent-7', executionId: 'exec-7' }),
                pendingToolCalls: new Map(), orderedEntries: [], toolResults: [] };

  await dispatchToolCall(ctx, { toolId: 't1', toolName: 'catalog', argsStr: '{}' });
  await dispatchToolResult(ctx, { toolId: 't1', isError: false, content: 'ok' });

  const fleet = fleetEvents(redis, 'agent-7');
  assert.deepEqual(fleet.map((p) => p.payload.event), ['tool_call_started', 'tool_call_completed']);
  assert.equal(fleet[0].payload.toolName, 'catalog');
  assert.equal(fleet[0].payload.toolCallId, 't1');
  assert.equal(fleet[1].payload.success, true);
  assert.equal(typeof fleet[1].payload.durationMs, 'number');
});

test('codex item.started → item.completed emits fleet started+completed exactly once (the codex/gemini/mistral path)', async () => {
  const redis = fakeRedis();
  const ctx = codexCtx(agentPublisher(redis, { agentEntityId: 'agent-codex', executionId: 'exec-c' }));

  await handleCodexStyleItemEvent(
    { type: 'item.started', item: { type: 'mcp_tool_call', id: 'tool-1', tool: 'catalog', arguments: '{}' } },
    ctx, { providerKey: 'codex' });
  await handleCodexStyleItemEvent(
    { type: 'item.completed', item: { type: 'mcp_tool_call', id: 'tool-1', tool: 'catalog', arguments: '{}', status: 'completed', result: 'ok' } },
    ctx, { providerKey: 'codex' });

  const fleet = fleetEvents(redis, 'agent-codex');
  assert.deepEqual(fleet.map((p) => p.payload.event), ['tool_call_started', 'tool_call_completed']);
  assert.equal(fleet[0].payload.toolCallId, 'tool-1');
  assert.equal(fleet[1].payload.success, true);
});

test('dispatchToolCall/Result work with a publisher exposing only the two core methods (decoupling)', async () => {
  const calls = [];
  const publisher = {
    publishToolCall: async () => { calls.push('call'); },
    publishToolResult: async () => { calls.push('result'); },
  };
  const ctx = { publisher, pendingToolCalls: new Map(), orderedEntries: [], toolResults: [] };

  await dispatchToolCall(ctx, { toolId: 't1', toolName: 'x', argsStr: '{}' });
  await dispatchToolResult(ctx, { toolId: 't1', isError: false, content: 'ok' });

  // Dispatch depends only on the two core publish methods - fleet emission is the
  // publisher's concern, so a publisher without it is still valid.
  assert.deepEqual(calls, ['call', 'result']);
});
