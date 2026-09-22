/**
 * The rules that decide whether a killed run's usage is recovered.
 *
 * These used to live inside the 1,200-line run closure in server.mjs, where the only way
 * to check them was to run regexes over the file's text. They are the difference between
 * billing what a stopped turn spent and billing nothing, so they are exercised here
 * instead: same module the server imports, called the same way.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { recoverUnreportedUsage, applyRecoveredUsage } from '../lib/usageRecovery.mjs';

// Shaped like what the only real implementer returns: codex reports a cached SUBSET of
// the prompt and no additive cache write (see the codex adapter's own test for why the
// write is deliberately not carried).
const FULL_USAGE = {
  promptTokens: 15255,
  completionTokens: 40,
  cachedTokens: 12160,
  reasoningTokens: 32,
};

/** An adapter that can recover, and records what it was asked for. */
function recoveringAdapter(usage = FULL_USAGE) {
  const calls = [];
  return {
    calls,
    recoverUsage(tmpDir) { calls.push(tmpDir); return usage; },
  };
}

test('recovers when the CLI reported nothing, and normalises every counter', () => {
  const adapter = recoveringAdapter();

  const entry = recoverUnreportedUsage(adapter, '/tmp/bridge-x', []);

  assert.deepEqual(entry, {
    promptTokens: 15255,
    completionTokens: 40,
    cacheCreationInputTokens: 0,
    cacheReadInputTokens: 0,
    cachedTokens: 12160,
    reasoningTokens: 32,
  });
  assert.deepEqual(adapter.calls, ['/tmp/bridge-x'], 'the run temp dir is what gets searched');
});

test('a value the CLI itself reported always wins - no second accounting source', () => {
  const adapter = recoveringAdapter();

  const entry = recoverUnreportedUsage(adapter, '/tmp/bridge-x', [{ promptTokens: 10, completionTokens: 2 }]);

  assert.equal(entry, null);
  assert.deepEqual(adapter.calls, [], 'the adapter is not even asked');
});

test('an adapter that cannot recover is not an error', () => {
  assert.equal(recoverUnreportedUsage({}, '/tmp/bridge-x', []), null);
  assert.equal(recoverUnreportedUsage(null, '/tmp/bridge-x', []), null);
});

test('an adapter that throws never changes the run outcome', () => {
  const adapter = { recoverUsage() { throw new Error('disk gone'); } };

  assert.equal(recoverUnreportedUsage(adapter, '/tmp/bridge-x', []), null);
});

test('zeros are not a recovery: claiming "nothing was spent" needs evidence', () => {
  const adapter = recoveringAdapter({ promptTokens: 0, completionTokens: 0 });

  assert.equal(recoverUnreportedUsage(adapter, '/tmp/bridge-x', []), null);
});

test('a partial answer is kept, with the counters it did not carry set to zero', () => {
  const adapter = recoveringAdapter({ promptTokens: 900 });

  assert.deepEqual(recoverUnreportedUsage(adapter, '/tmp/bridge-x', []), {
    promptTokens: 900,
    completionTokens: 0,
    cacheCreationInputTokens: 0,
    cacheReadInputTokens: 0,
    cachedTokens: 0,
    reasoningTokens: 0,
  });
});

test('output-only usage still counts - a killed turn that only generated is not free', () => {
  const adapter = recoveringAdapter({ promptTokens: 0, completionTokens: 77 });

  assert.equal(recoverUnreportedUsage(adapter, '/tmp/bridge-x', []).completionTokens, 77);
});

test('a missing usage list is refused, not guessed at, and never throws', () => {
  const adapter = recoveringAdapter();

  assert.equal(recoverUnreportedUsage(adapter, '/tmp/bridge-x', undefined), null);
});

test('an adapter that DOES report additive cache counters keeps them - the module is not codex-specific', () => {
  // Nothing in this module assumes codex. A future adapter for a provider that bills cache
  // writes separately (the Anthropic shape) must have those counters carried through.
  const adapter = recoveringAdapter({
    promptTokens: 500, completionTokens: 10,
    cacheCreationInputTokens: 128, cacheReadInputTokens: 900,
  });

  const entry = recoverUnreportedUsage(adapter, '/tmp/bridge-x', []);

  assert.equal(entry.cacheCreationInputTokens, 128);
  assert.equal(entry.cacheReadInputTokens, 900);
});

test('applying a recovery records the FULL breakdown, not just the two headline totals', () => {
  // The response sums the cache and reasoning counters out of perCallUsages, so an entry
  // that never lands there bills a cached turn at the full input rate. This is the line
  // that decides the amount, and it is the reason the application lives in this module
  // rather than in the run closure where only its position could be checked.
  const perCallUsages = [];

  const applied = applyRecoveredUsage(recoveringAdapter(), '/tmp/bridge-x', perCallUsages);

  assert.deepEqual(applied.usage, { promptTokens: 15255, completionTokens: 40 });
  assert.equal(perCallUsages.length, 1);
  assert.equal(perCallUsages[0].cachedTokens, 12160, 'the cached subset carries the discount');
  assert.equal(perCallUsages[0].reasoningTokens, 32);
});

test('applying nothing leaves the run untouched and says so', () => {
  const perCallUsages = [{ promptTokens: 10, completionTokens: 2 }];

  const applied = applyRecoveredUsage(recoveringAdapter(), '/tmp/bridge-x', perCallUsages);

  assert.equal(applied, null, 'the caller must be able to stay silent');
  assert.equal(perCallUsages.length, 1, 'a reported run is never touched');
});
