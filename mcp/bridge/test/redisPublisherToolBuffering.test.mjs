/**
 * The Node RedisPublisher must BUFFER tool events (tool_call + tool_result) into
 * stream:{streamId}:tools - the same way publishContent buffers text into
 * stream:{streamId}:content and the Java ConversationRedisStreamingCallback buffers via
 * appendToolEvent. Without this, a conversation page that opens/refreshes mid-run replays the
 * streamed text but renders NO in-progress tool cards (the snapshot endpoint reads the tools
 * list). This is the bridge-side half of "scheduled/bridge sync runs stream into the
 * conversation like an interactive chat".
 *
 * Run with: node --test mcp/bridge/test/redisPublisherToolBuffering.test.mjs
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RedisPublisher } from '../redis-publisher.mjs';

function makeRedis() {
  const published = [];
  const rpushed = [];
  return {
    published,
    rpushed,
    async publish(channel, msg) { published.push({ channel, msg }); return 1; },
    async rpush(key, val) { rpushed.push({ key, val }); return rpushed.length; },
    async set() { return 'OK'; },
    async exists() { return 0; },
    async get() { return null; },
  };
}

const toolsBuffered = (redis) => redis.rpushed
  .filter((r) => r.key === 'stream:stream-1:tools')
  .map((r) => { try { return JSON.parse(r.val); } catch { return {}; } });

test('publishToolCall buffers the tool_call event into stream:{id}:tools for replay', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);
  await publisher.publishToolCall('files', 'call-1', { path: 'a.txt' });

  const buffered = toolsBuffered(redis);
  assert.equal(buffered.length, 1, 'exactly one tool event must be buffered for the tool_call');
  assert.equal(buffered[0].toolName, 'files');
  assert.equal(buffered[0].toolId, 'call-1');
  assert.equal(buffered[0].streamId, 'stream-1');
  // The buffered shape MUST equal the live-published event so reconnect replay and live render are identical.
  const liveCall = redis.published
    .filter((p) => p.channel === 'stream:events:stream-1')
    .map((p) => JSON.parse(p.msg))
    .find((e) => e.toolName === 'files' && e.toolId === 'call-1' && e.arguments);
  assert.deepEqual(buffered[0], liveCall, 'buffered tool_call must be byte-identical to the live event');
});

test('publishToolResult buffers the tool_result event into stream:{id}:tools for replay', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);
  await publisher.publishToolResult('call-1', 'files', true, 12, '{"ok":true}', { iconSlug: 'files' });

  const buffered = toolsBuffered(redis);
  const result = buffered.find((e) => e.success !== undefined && e.toolId === 'call-1');
  assert.ok(result, 'a tool_result event must be buffered');
  assert.equal(result.toolName, 'files');
  assert.equal(result.success, true);
  assert.equal(result.durationMs, 12);
  assert.equal(result.iconSlug, 'files', 'render metadata must survive into the buffered event');
});

test('a full tool_call → tool_result pair buffers two events in order', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);
  await publisher.publishToolCall('files', 'call-1', { path: 'a.txt' });
  await publisher.publishToolResult('call-1', 'files', true, 5, 'done', null);

  const buffered = toolsBuffered(redis);
  assert.equal(buffered.length, 2, 'both the call and the result must be buffered');
  // First buffered event is the tool_call (carries the exact arguments), second is the result.
  assert.deepEqual(buffered[0].arguments, { path: 'a.txt' });
  assert.equal(buffered[0].toolId, 'call-1');
  assert.equal(buffered[1].success, true);
  assert.equal(buffered[1].toolId, 'call-1');
});

test('tool buffering does not disturb content buffering (regression)', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);
  await publisher.publishContent('hello ');
  await publisher.publishToolCall('files', 'call-1', {});

  const contentPushes = redis.rpushed.filter((r) => r.key === 'stream:stream-1:content');
  const toolPushes = redis.rpushed.filter((r) => r.key === 'stream:stream-1:tools');
  assert.equal(contentPushes.length, 1, 'content is still buffered to the content list');
  assert.equal(contentPushes[0].val, 'hello ');
  assert.equal(toolPushes.length, 1, 'tool events go to the tools list, not the content list');
});

test('a Redis rpush failure never breaks the live tool publish (best-effort)', async () => {
  const redis = makeRedis();
  redis.rpush = async () => { throw new Error('redis down'); };
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  // Must not throw even though buffering fails.
  await publisher.publishToolCall('files', 'call-1', {});
  await publisher.publishToolResult('call-1', 'files', true, 1, 'ok', null);

  const liveToolEvents = redis.published
    .filter((p) => p.channel === 'stream:events:stream-1')
    .map((p) => JSON.parse(p.msg))
    .filter((e) => e.toolName === 'files');
  assert.ok(liveToolEvents.length >= 2, 'live tool_call + tool_result are still published despite buffer failure');
});
