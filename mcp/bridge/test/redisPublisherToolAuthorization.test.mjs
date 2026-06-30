/**
 * Bridge parity for the tool-authorization gate: the Node RedisPublisher must emit a
 * `tool_authorization_required` event (mirror of ConversationRedisStreamingCallback)
 * when a gated tool result carries `toolAuthorizationRequired` metadata, so the chat
 * paints the authorization card on the bridge (claude-code/codex) path.
 *
 * Run with: node --test mcp/bridge/test/redisPublisherToolAuthorization.test.mjs
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RedisPublisher } from '../redis-publisher.mjs';

/** In-memory redis stub capturing publish() payloads; everything else is a no-op. */
function makeRedis() {
  const published = [];
  return {
    published,
    async publish(channel, msg) { published.push({ channel, msg }); return 1; },
    async rpush() { return 1; },
    async set() { return 'OK'; },
    async expire() { return 1; },
    async exists() { return 0; },
    async get() { return null; },
    async del() { return 1; },
  };
}

function parseEvents(redis) {
  return redis.published.map((p) => {
    try { return JSON.parse(p.msg); } catch { return {}; }
  });
}

test('emits tool_authorization_required when metadata.toolAuthorizationRequired is set', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-1', 'application', true, 100, '{}', {
    toolAuthorizationRequired: true,
    rule: 'application:acquire',
    toolName: 'application',
    action: 'acquire',
    toolCallId: 'call-1',
    argsSummary: '{"action":"acquire"}',
  });

  const authEvent = parseEvents(redis).find((e) => e.toolAuthorization);
  assert.ok(authEvent, 'a tool_authorization event must be published');
  assert.equal(authEvent.toolAuthorization.rule, 'application:acquire');
  assert.equal(authEvent.toolAuthorization.toolName, 'application');
  assert.equal(authEvent.toolAuthorization.action, 'acquire');
  assert.equal(authEvent.toolAuthorization.toolCallId, 'call-1');
});

test('does NOT emit tool_authorization for a normal (non-gated) tool result', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-2', 'files', true, 50, '{}', { iconSlug: 'files' });

  assert.ok(
    !parseEvents(redis).some((e) => e.toolAuthorization),
    'no tool_authorization event for a normal result',
  );
});

test('forwards applicationId so application:acquire can open the install modal', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-3', 'application', true, 100, '{}', {
    toolAuthorizationRequired: true,
    rule: 'application:acquire',
    toolName: 'application',
    action: 'acquire',
    toolCallId: 'call-3',
    applicationId: 'pub-123',
  });

  const authEvent = parseEvents(redis).find((e) => e.toolAuthorization);
  assert.ok(authEvent, 'a tool_authorization event must be published');
  assert.equal(authEvent.toolAuthorization.applicationId, 'pub-123');
});

test('emits a distinct card per gated call within a turn (parallel, async - no pause)', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  // The bridge never pauses the agent, so two gated actions in the same turn each raise a card.
  await publisher.publishToolResult('call-a', 'application', true, 100, '{}', {
    toolAuthorizationRequired: true, rule: 'application:acquire', toolName: 'application',
  });
  await publisher.publishToolResult('call-b', 'application', true, 100, '{}', {
    toolAuthorizationRequired: true, rule: 'application:execute', toolName: 'application',
  });

  // Each event is published on both the SSE and WS channels - read one channel to avoid dupes.
  const rules = redis.published
    .filter((p) => p.channel === 'stream:events:stream-1')
    .map((p) => { try { return JSON.parse(p.msg); } catch { return {}; } })
    .filter((e) => e.toolAuthorization)
    .map((e) => e.toolAuthorization.rule);
  assert.deepEqual(rules, ['application:acquire', 'application:execute']);
});

test('emits distinct credential approval events within a bridge turn', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('cred-gmail', 'request_credential', true, 100, '{}', {
    serviceApprovalRequested: true,
    services: [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' }],
    reason: 'Connect Gmail',
  });
  await publisher.publishToolResult('cred-slack', 'request_credential', true, 100, '{}', {
    serviceApprovalRequested: true,
    services: [{ serviceType: 'slack', serviceName: 'Slack', iconSlug: 'slack' }],
    reason: 'Connect Slack',
  });
  await publisher.publishToolResult('cred-notion', 'request_credential', true, 100, '{}', {
    serviceApprovalRequested: true,
    services: [{ serviceType: 'notion', serviceName: 'Notion', iconSlug: 'notion' }],
    reason: 'Reconnect Notion',
    needsAttention: true,
  });

  const approvalEvents = redis.published
    .filter((p) => p.channel === 'stream:events:stream-1')
    .map((p) => { try { return JSON.parse(p.msg); } catch { return {}; } })
    .filter((e) => e.services);

  assert.deepEqual(
    approvalEvents.map((e) => e.services[0].serviceType),
    ['gmail', 'slack', 'notion'],
  );
  assert.deepEqual(
    approvalEvents.map((e) => e.needsAttention),
    [false, false, true],
  );
});
