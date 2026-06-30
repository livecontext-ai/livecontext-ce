/**
 * Tests for AgentBudgetGuard / TenantBudgetGuard / chainBudgetGuards.
 * These are the JS twins of the Java PreIterationGuard chain - must stay
 * in lockstep with the Java enforcement so cross-language behavior is uniform.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AgentBudgetGuard, TenantBudgetGuard, chainBudgetGuards } from '../lib/budgetGuards.js';

// Minimal pricing stub: $1 per 1k prompt + $2 per 1k completion (synthetic).
const fakePricing = {
  costFor: (_provider, _model, prompt, completion) => (prompt / 1000) * 1 + (completion / 1000) * 2,
  contextWindowFor: () => 128000,
  maxOutputTokensFor: () => 4096,
  worstCaseSingleIter: () => Number.NaN,
};

// ─── AgentBudgetGuard ─────────────────────────────────────────────────────

test('AgentBudgetGuard disabled when budget <= 0', () => {
  const g = new AgentBudgetGuard({ budget: 0, pricing: fakePricing });
  assert.equal(g.enabled, false);
  assert.equal(g.check({ promptTokens: 999999, completionTokens: 999999, iterations: 1, provider: 'p', model: 'm' }).proceed, true);
});

test('AgentBudgetGuard denies when consumed > budget', () => {
  const g = new AgentBudgetGuard({ budget: 0.5, pricing: fakePricing });
  // 1k+1k tokens → 1*1 + 1*2 = 3 > 0.5
  const r = g.check({ promptTokens: 1000, completionTokens: 1000, iterations: 1, provider: 'p', model: 'm' });
  assert.equal(r.proceed, false);
  assert.equal(r.scope, 'agent');
  assert.match(r.reason, /agent budget exhausted/);
});

test('AgentBudgetGuard allows first iteration even when projected next would deny (no average yet)', () => {
  // The "first iteration" carve-out documented in the source comments.
  const g = new AgentBudgetGuard({ budget: 5, pricing: fakePricing });
  // Single iteration, consumed=3, projection on iters<2 is skipped → must proceed.
  const r = g.check({ promptTokens: 1000, completionTokens: 1000, iterations: 1, provider: 'p', model: 'm' });
  assert.equal(r.proceed, true);
});

test('AgentBudgetGuard denies after iter 2 when projection exceeds budget', () => {
  const g = new AgentBudgetGuard({ budget: 5, pricing: fakePricing });
  // 2 iters, total 2k+2k tokens → consumed=6 > budget=5 → denies on absolute first
  const r = g.check({ promptTokens: 2000, completionTokens: 2000, iterations: 2, provider: 'p', model: 'm' });
  assert.equal(r.proceed, false);
});

test('AgentBudgetGuard projection: 2 iters, average puts next over budget', () => {
  const g = new AgentBudgetGuard({ budget: 7, pricing: fakePricing });
  // 2 iters total: 2k prompt + 2k completion = consumed 6
  // average per iter = 1k+1k = projected 3
  // 6+3 = 9 > 7 → denies via projection
  const r = g.check({ promptTokens: 2000, completionTokens: 2000, iterations: 2, provider: 'p', model: 'm' });
  assert.equal(r.proceed, false);
  assert.match(r.reason, /would be exceeded by next iteration/);
});

// ─── TenantBudgetGuard ────────────────────────────────────────────────────

test('TenantBudgetGuard disabled when balance <= 0', async () => {
  const g = new TenantBudgetGuard({ initialBalance: 0, pricing: fakePricing });
  assert.equal(g.enabled, false);
  const r = await g.check({ promptTokens: 9999, completionTokens: 9999, iterations: 1, provider: 'p', model: 'm' });
  assert.equal(r.proceed, true);
});

test('TenantBudgetGuard denies when consumed > balance', async () => {
  const g = new TenantBudgetGuard({ initialBalance: 1, pricing: fakePricing });
  const r = await g.check({ promptTokens: 1000, completionTokens: 1000, iterations: 1, provider: 'p', model: 'm' });
  assert.equal(r.proceed, false);
  assert.equal(r.scope, 'tenant');
});

test('TenantBudgetGuard refreshBalance hook fires when iters - lastRefreshIter >= refreshEveryNIters', async () => {
  let calls = 0;
  const g = new TenantBudgetGuard({
    initialBalance: 100,
    pricing: fakePricing,
    refreshBalance: async () => { calls++; return 100; },
    refreshEveryNIters: 3,
  });
  // iters=1: delta 1 < 3 → no refresh
  await g.check({ promptTokens: 1, completionTokens: 1, iterations: 1, provider: 'p', model: 'm' });
  assert.equal(calls, 0);
  // iters=2: delta 2 < 3 → no refresh
  await g.check({ promptTokens: 1, completionTokens: 1, iterations: 2, provider: 'p', model: 'm' });
  assert.equal(calls, 0);
  // iters=4: delta 4 >= 3 → refresh, _lastRefreshIter becomes 4
  await g.check({ promptTokens: 1, completionTokens: 1, iterations: 4, provider: 'p', model: 'm' });
  assert.equal(calls, 1);
  // iters=6: delta 2 < 3 → no refresh
  await g.check({ promptTokens: 1, completionTokens: 1, iterations: 6, provider: 'p', model: 'm' });
  assert.equal(calls, 1);
  // iters=7: delta 3 >= 3 → refresh
  await g.check({ promptTokens: 1, completionTokens: 1, iterations: 7, provider: 'p', model: 'm' });
  assert.equal(calls, 2);
});

test('TenantBudgetGuard swallows refresh errors but keeps checking', async () => {
  const g = new TenantBudgetGuard({
    initialBalance: 100,
    pricing: fakePricing,
    refreshBalance: async () => { throw new Error('upstream down'); },
    refreshEveryNIters: 1,
  });
  const origStderrWrite = process.stderr.write.bind(process.stderr);
  process.stderr.write = () => true;
  try {
    const r = await g.check({ promptTokens: 1, completionTokens: 1, iterations: 1, provider: 'p', model: 'm' });
    assert.equal(r.proceed, true, 'must keep going after a refresh failure');
  } finally {
    process.stderr.write = origStderrWrite;
  }
});

// ─── chainBudgetGuards ────────────────────────────────────────────────────

test('chainBudgetGuards: tenant deny short-circuits agent', async () => {
  const tenant = new TenantBudgetGuard({ initialBalance: 0.001, pricing: fakePricing });
  const agent = new AgentBudgetGuard({ budget: 1000000, pricing: fakePricing });
  const chain = chainBudgetGuards(tenant, agent);
  const r = await chain({ promptTokens: 10000, completionTokens: 10000, iterations: 1, provider: 'p', model: 'm' });
  assert.equal(r.proceed, false);
  assert.equal(r.scope, 'tenant');
});

test('chainBudgetGuards: agent deny when tenant allows', async () => {
  const tenant = new TenantBudgetGuard({ initialBalance: 1000, pricing: fakePricing });
  const agent = new AgentBudgetGuard({ budget: 0.0001, pricing: fakePricing });
  const chain = chainBudgetGuards(tenant, agent);
  const r = await chain({ promptTokens: 1000, completionTokens: 1000, iterations: 1, provider: 'p', model: 'm' });
  assert.equal(r.proceed, false);
  assert.equal(r.scope, 'agent');
});

test('chainBudgetGuards: both proceed when within limits', async () => {
  const tenant = new TenantBudgetGuard({ initialBalance: 1000, pricing: fakePricing });
  const agent = new AgentBudgetGuard({ budget: 1000, pricing: fakePricing });
  const chain = chainBudgetGuards(tenant, agent);
  const r = await chain({ promptTokens: 100, completionTokens: 100, iterations: 1, provider: 'p', model: 'm' });
  assert.equal(r.proceed, true);
});

test('chainBudgetGuards: null/disabled guards are skipped', async () => {
  const chain = chainBudgetGuards(null, null);
  const r = await chain({ promptTokens: 9999999, completionTokens: 9999999, iterations: 1, provider: 'p', model: 'm' });
  assert.equal(r.proceed, true);
});
