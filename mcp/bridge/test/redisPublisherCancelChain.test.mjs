/**
 * P0-E - verifies the bridge's RedisPublisher cancel chain.
 *
 * Pre-fix, isCancelled() only checked the bridge's OWN streamId. A user STOP
 * on the parent conversation set agent:cancel:{parentStreamId}, and the
 * orchestrator's workflow STOP set workflow:cancel:{workflowRunId}, but the
 * bridge sub-agent never saw either - it ran to completion (up to 65min)
 * burning credits while the parent was already gone.
 *
 * Post-fix: getCancelStatus() walks
 *   1) agent:cancel:{ownStreamId}
 *   2) agent:cancel:{parentStreamId} (resolved via stream:conv:{parentConv})
 *   3) workflow:cancel:{workflowRunId}
 * First hit wins, with cause=user|system parsed from the JSON payload.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RedisPublisher } from '../redis-publisher.mjs';

/** Tiny in-memory Redis stub matching the methods getCancelStatus uses. */
function makeRedis(initial = {}) {
  const store = new Map(Object.entries(initial));
  return {
    store,
    async get(k) { return store.has(k) ? store.get(k) : null; },
    async exists(k) { return store.has(k) ? 1 : 0; },
    async set(k, v) { store.set(k, v); return 'OK'; },
    async del(k) { return store.delete(k) ? 1 : 0; },
  };
}

function newPublisher(redis, parent = {}) {
  return new RedisPublisher(redis, 'sub-stream-1', 'sub-conv-1', redis, parent);
}

test('isCancelled returns false when no key is set anywhere', async () => {
  const r = makeRedis();
  const p = newPublisher(r, {
    parentConversationId: 'parent-conv',
    workflowRunId: 'wr-1',
  });
  assert.equal(await p.isCancelled(), false);
});

test('own streamId cancelled → isCancelled=true (legacy: empty value)', async () => {
  const r = makeRedis({ 'agent:cancel:sub-stream-1': '' });
  const p = newPublisher(r);
  const status = await p.getCancelStatus();
  assert.equal(status.cancelled, true);
  assert.equal(status.cause, 'user');
});

test('own streamId cancelled with system cause → cause=system', async () => {
  const r = makeRedis({ 'agent:cancel:sub-stream-1': '{"cause":"system"}' });
  const p = newPublisher(r);
  const status = await p.getCancelStatus();
  assert.equal(status.cancelled, true);
  assert.equal(status.cause, 'system');
});

test('parent stream cancelled → bridge sub honors it (P0-E core regression)', async () => {
  const r = makeRedis({
    'stream:conv:parent-conv': 'parent-stream-9',
    'agent:cancel:parent-stream-9': '{"cause":"user"}',
  });
  const p = newPublisher(r, { parentConversationId: 'parent-conv' });
  const status = await p.getCancelStatus();
  assert.equal(status.cancelled, true, 'parent cancel must propagate to sub');
  assert.equal(status.cause, 'user');
});

test('workflow run cancelled → bridge sub honors it (orchestrator-driven STOP)', async () => {
  const r = makeRedis({ 'workflow:cancel:wr-1': 'cancelled' });
  const p = newPublisher(r, { workflowRunId: 'wr-1' });
  const status = await p.getCancelStatus();
  assert.equal(status.cancelled, true);
  assert.equal(status.cause, 'user');
});

test('own NOT cancelled, parent not cancelled, no workflow → false', async () => {
  const r = makeRedis({ 'stream:conv:parent-conv': 'parent-stream-9' });
  const p = newPublisher(r, { parentConversationId: 'parent-conv' });
  assert.equal(await p.isCancelled(), false);
});

test('Redis throws → fail-open (do NOT block the run)', async () => {
  const broken = {
    async get() { throw new Error('redis-down'); },
    async exists() { throw new Error('redis-down'); },
  };
  const p = newPublisher(broken, {
    parentConversationId: 'parent-conv',
    workflowRunId: 'wr-1',
  });
  // Each branch must swallow and return false; the loop continues to next.
  const status = await p.getCancelStatus();
  assert.equal(status.cancelled, false);
});

test('own cancel takes precedence over parent (short-circuit)', async () => {
  const r = makeRedis({
    'agent:cancel:sub-stream-1': '{"cause":"system"}',
    'stream:conv:parent-conv': 'parent-stream-9',
    'agent:cancel:parent-stream-9': '{"cause":"user"}',
  });
  const p = newPublisher(r, { parentConversationId: 'parent-conv' });
  const status = await p.getCancelStatus();
  assert.equal(status.cause, 'system', 'own key wins over parent key');
});

test('parent index missing → fall through to workflow check', async () => {
  // No stream:conv:* index, but workflow cancel set
  const r = makeRedis({ 'workflow:cancel:wr-2': 'cancelled' });
  const p = newPublisher(r, { parentConversationId: 'phantom', workflowRunId: 'wr-2' });
  assert.equal(await p.isCancelled(), true);
});
