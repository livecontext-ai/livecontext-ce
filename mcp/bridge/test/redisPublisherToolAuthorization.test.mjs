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

test('emits a distinct card per gated call within a turn', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  // Two gated actions in one turn each raise their own card. Neither carries
  // approvalCardEmitted, so the gate painted neither: acquire is never held (the user
  // installs the app themselves) and this execute reached the publisher ungated. When the
  // gate does paint a card, it holds the call and this publisher stays out of the way.
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

test('does NOT re-emit an authorization card the server-side gate already published', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  // Shape of a result handed back by a park that was refused or expired: the gate painted
  // the card itself before waiting, so publishing here would show the user two identical cards.
  await publisher.publishToolResult('call-4', 'workflow', true, 100, '{}', {
    toolAuthorizationRequired: true,
    rule: 'workflow:execute',
    toolName: 'workflow',
    approvalCardEmitted: true,
  });

  assert.ok(
    !parseEvents(redis).some((e) => e.toolAuthorization),
    'the card must not be published twice',
  );
});

test('does NOT re-emit a service-approval card the server-side gate already published', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-5', 'catalog', true, 100, '{}', {
    serviceApprovalRequested: true,
    services: [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' }],
    reason: 'Connect Gmail',
    approvalCardEmitted: true,
  });

  assert.ok(
    !parseEvents(redis).some((e) => e.services),
    'the card must not be published twice',
  );
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

test('relays the card SUBJECT, so a CLI-backed chat names the workflow going live', async () => {
  // Parity, not decoration: the general chat is backed by claude-code/codex as often as by
  // the direct route, and a card that reaches only one of them named is a card that asks
  // "run this action?" about a pin for half the users.
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-3', 'workflow', true, 100, '{}', {
    toolAuthorizationRequired: true,
    rule: 'workflow:pin',
    toolName: 'workflow',
    action: 'pin',
    toolCallId: 'call-3',
    subject: { kind: 'workflow', id: 'w-1', version: 12 },
  });

  const authEvent = parseEvents(redis).find((e) => e.toolAuthorization);
  assert.ok(authEvent, 'a tool_authorization event must be published');
  assert.deepEqual(authEvent.toolAuthorization.subject, { kind: 'workflow', id: 'w-1', version: 12 });
});

test('a gated rule with no subject still publishes its card', async () => {
  // Every rule that shipped before the subject existed goes through this same hop.
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-4', 'workflow', true, 100, '{}', {
    toolAuthorizationRequired: true,
    rule: 'workflow:execute',
    toolName: 'workflow',
    action: 'execute',
    toolCallId: 'call-4',
  });

  const authEvent = parseEvents(redis).find((e) => e.toolAuthorization);
  assert.ok(authEvent, 'a tool_authorization event must be published');
  assert.equal(authEvent.toolAuthorization.subject, undefined);
});
