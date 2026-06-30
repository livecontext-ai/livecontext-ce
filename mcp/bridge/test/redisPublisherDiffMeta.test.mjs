/**
 * The Node RedisPublisher must forward the source-tool render metadata - `diff`
 * (red/green unified-diff card) and `gitStatus` (status badges) - onto the
 * tool_result event so the chat paints them on the bridge (claude-code/codex) path,
 * mirroring how it already forwards iconSlug/tasksData/serviceApproval.
 *
 * Run with: node --test mcp/bridge/test/redisPublisherDiffMeta.test.mjs
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RedisPublisher } from '../redis-publisher.mjs';

function makeRedis() {
  const published = [];
  return {
    published,
    async publish(channel, msg) { published.push({ channel, msg }); return 1; },
    async rpush() { return 1; },
    async set() { return 'OK'; },
    async exists() { return 0; },
    async get() { return null; },
  };
}
const sseEvents = (redis) => redis.published
  .filter((p) => p.channel === 'stream:events:stream-1')
  .map((p) => { try { return JSON.parse(p.msg); } catch { return {}; } });

test('forwards diff metadata onto the tool_result event', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);
  await publisher.publishToolResult('c1', 'repo', true, 10, 'Edited app.js', {
    diff: { files: [{ path: 'app.js', status: 'modified', additions: 2, deletions: 1, unifiedDiff: '@@ -1 +1 @@\n-a\n+b\n' }] },
  });
  const ev = sseEvents(redis).find((e) => e.diff);
  assert.ok(ev, 'a tool_result event carrying diff must be published');
  assert.equal(ev.diff.files[0].path, 'app.js');
  assert.equal(ev.diff.files[0].additions, 2);
});

test('forwards gitStatus metadata onto the tool_result event', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);
  await publisher.publishToolResult('c2', 'repo', true, 10, 'clean', {
    gitStatus: { branch: 'dev', ahead: 0, behind: 0, files: [{ path: 'x.txt', status: '??' }] },
  });
  const ev = sseEvents(redis).find((e) => e.gitStatus);
  assert.ok(ev, 'a tool_result event carrying gitStatus must be published');
  assert.equal(ev.gitStatus.branch, 'dev');
  assert.equal(ev.gitStatus.files[0].status, '??');
});

test('a normal tool result carries neither diff nor gitStatus', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);
  await publisher.publishToolResult('c3', 'files', true, 5, '{}', { iconSlug: 'files' });
  assert.ok(!sseEvents(redis).some((e) => e.diff || e.gitStatus));
});
