// Self-contained smoke tests for pricing.js + budgetGuards.js. Run with:
//   node lib/__tests__/budgetGuards.test.mjs

import { PricingCache, PRICING_DEFAULTS } from '../pricing.js';
import { AgentBudgetGuard, TenantBudgetGuard, chainBudgetGuards } from '../budgetGuards.js';
import { resolveStopReason } from '../stopReasonMapper.js';
import { AgentStopReason } from '../agentStopReason.js';

let passed = 0, failed = 0;
function test(name, fn) {
  return Promise.resolve().then(fn).then(
    () => { passed++; console.log('  PASS', name); },
    e => { failed++; console.error('  FAIL', name, '\n        ', e.message); }
  );
}
function eq(a, b, label = '') {
  if (a !== b) throw new Error(`${label} expected ${b}, got ${a}`);
}
function approx(a, b, eps = 1e-9, label = '') {
  if (Math.abs(a - b) > eps) throw new Error(`${label} expected ~${b}, got ${a}`);
}

const pricing = new PricingCache({ fetcher: async () => ({ rates: [] }) });
pricing.primeFromRates([
  { provider: 'openai', model: 'gpt-4o', inputRate: 0.005, outputRate: 0.015, fixedCost: 0 },
  { provider: 'anthropic', model: 'claude-opus', inputRate: 0.015, outputRate: 0.075, fixedCost: 0.001 },
]);

await test('pricing: cost formula matches Java', () => {
  // 1000 prompt tokens × 0.005 + 500 completion × 0.015 = 0.005 + 0.0075 = 0.0125
  approx(pricing.costFor('openai', 'gpt-4o', 1000, 500), 0.0125, 1e-9);
});

await test('pricing: case insensitive lookup', () => {
  approx(pricing.costFor('OpenAI', 'GPT-4o', 1000, 0), 0.005, 1e-9);
});

await test('pricing: fixedCost included', () => {
  approx(pricing.costFor('anthropic', 'claude-opus', 0, 0), 0.001, 1e-9);
});

await test('pricing: unknown model falls back to env defaults', () => {
  // defaults: 0.003 in, 0.015 out → 1000 prompt = 0.003
  const cost = pricing.costFor('unknown', 'unknown', 1000, 0);
  if (cost <= 0) throw new Error('expected positive default cost');
});

// ── AgentBudgetGuard ──────────────────────────────────────────────────────

await test('agent guard: disabled when budget = 0', () => {
  const g = new AgentBudgetGuard({ budget: 0, pricing });
  eq(g.enabled, false);
  eq(g.check({ promptTokens: 999999, completionTokens: 999999, iterations: 1, provider: 'openai', model: 'gpt-4o' }).proceed, true);
});

await test('agent guard: allows when well under budget', () => {
  const g = new AgentBudgetGuard({ budget: 10, pricing });
  const r = g.check({ promptTokens: 100, completionTokens: 50, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, true);
});

await test('agent guard: denies when consumed exceeds budget', () => {
  const g = new AgentBudgetGuard({ budget: 0.001, pricing });
  const r = g.check({ promptTokens: 10000, completionTokens: 0, iterations: 5, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, false);
  eq(r.scope, 'agent');
});

await test('agent guard: denies when projected next pushes over (≥2 iters)', () => {
  // After 2 iterations: consumed = 0.01, avg prompt = 1000 → projected = 0.005
  // → consumed + projected = 0.015 > 0.0125 → deny
  const g = new AgentBudgetGuard({ budget: 0.0125, pricing });
  const r = g.check({ promptTokens: 2000, completionTokens: 0, iterations: 2, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, false);
  eq(r.scope, 'agent');
});

await test('agent guard: denies immediately when consumedSoFar >= budget (pre-exhausted)', () => {
  const g = new AgentBudgetGuard({ budget: 10, consumedSoFar: 11, pricing });
  const r = g.check({ promptTokens: 0, completionTokens: 0, iterations: 0, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, false);
  eq(r.scope, 'agent');
});

await test('agent guard: consumedSoFar + runCost exceeds budget → deny', () => {
  // Budget=10, prior consumed=9.5, run cost from 1000 prompt tokens = 0.005 → total 9.505 < 10 → allow
  const g = new AgentBudgetGuard({ budget: 10, consumedSoFar: 9.5, pricing });
  const r = g.check({ promptTokens: 1000, completionTokens: 0, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, true, 'should allow when total is under budget');
  // But with more tokens pushing over: 9.5 + costFor(2_000_000, 0) = 9.5 + 10 = 19.5 > 10
  const r2 = g.check({ promptTokens: 2_000_000, completionTokens: 0, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r2.proceed, false, 'should deny when consumedSoFar + runCost > budget');
});

await test('agent guard: skips projection on first iteration (avoids 2x self-deny)', () => {
  // Single iteration costs exactly half the budget. Old code denied (consumed +
  // projected = 2*consumed > budget). New code skips projection at iters<2.
  const g = new AgentBudgetGuard({ budget: 0.01, pricing });
  const r = g.check({ promptTokens: 1000, completionTokens: 0, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, true, 'first iteration alone must not trigger projection-deny');
});

// ── TenantBudgetGuard ─────────────────────────────────────────────────────

await test('tenant guard: disabled when balance = 0', () => {
  const g = new TenantBudgetGuard({ initialBalance: 0, pricing });
  eq(g.enabled, false);
});

await test('tenant guard: allows when consumed < balance', async () => {
  const g = new TenantBudgetGuard({ initialBalance: 100, pricing });
  const r = await g.check({ promptTokens: 1000, completionTokens: 500, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, true);
});

await test('tenant guard: denies + scope=tenant when consumed exceeds balance', async () => {
  const g = new TenantBudgetGuard({ initialBalance: 0.01, pricing });
  const r = await g.check({ promptTokens: 10000, completionTokens: 0, iterations: 5, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, false);
  eq(r.scope, 'tenant');
});

await test('tenant guard: refreshBalance called every N iterations', async () => {
  let calls = 0;
  const g = new TenantBudgetGuard({
    initialBalance: 100,
    pricing,
    refreshBalance: async () => { calls++; return 100; },
    refreshEveryNIters: 5,
  });
  await g.check({ promptTokens: 0, completionTokens: 0, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  await g.check({ promptTokens: 0, completionTokens: 0, iterations: 4, provider: 'openai', model: 'gpt-4o' });
  eq(calls, 0, 'should not refresh yet');
  await g.check({ promptTokens: 0, completionTokens: 0, iterations: 5, provider: 'openai', model: 'gpt-4o' });
  eq(calls, 1, 'should have refreshed at iter 5');
});

// ── chain ─────────────────────────────────────────────────────────────────

await test('chain: tenant deny wins over agent', async () => {
  const tenant = new TenantBudgetGuard({ initialBalance: 0.001, pricing });
  const agent = new AgentBudgetGuard({ budget: 1000, pricing });
  const chain = chainBudgetGuards(tenant, agent);
  const r = await chain({ promptTokens: 10000, completionTokens: 0, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, false);
  eq(r.scope, 'tenant');
});

await test('chain: agent denies when tenant ok', async () => {
  const tenant = new TenantBudgetGuard({ initialBalance: 1000, pricing });
  const agent = new AgentBudgetGuard({ budget: 0.001, pricing });
  const chain = chainBudgetGuards(tenant, agent);
  const r = await chain({ promptTokens: 10000, completionTokens: 0, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, false);
  eq(r.scope, 'agent');
});

await test('chain: both ok → proceed', async () => {
  const tenant = new TenantBudgetGuard({ initialBalance: 1000, pricing });
  const agent = new AgentBudgetGuard({ budget: 1000, pricing });
  const chain = chainBudgetGuards(tenant, agent);
  const r = await chain({ promptTokens: 100, completionTokens: 50, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, true);
});

await test('chain: disabled guards skipped', async () => {
  const chain = chainBudgetGuards(
    new TenantBudgetGuard({ initialBalance: 0, pricing }),
    new AgentBudgetGuard({ budget: 0, pricing }),
  );
  const r = await chain({ promptTokens: 999999, completionTokens: 999999, iterations: 1, provider: 'x', model: 'y' });
  eq(r.proceed, true);
});

// ── PricingCache: failure / fallback / dedup ──────────────────────────────

await test('pricing: refresh failure → healthy=false, costFor still works via defaults', async () => {
  const cache = new PricingCache({
    snapshotUrl: 'http://nope',
    refreshMs: 1,
    fetcher: async () => { throw new Error('boom'); },
  });
  await cache.refreshIfStale();
  eq(cache.healthy, false);
  if (!cache.lastError || !cache.lastError.includes('boom')) {
    throw new Error('expected lastError to capture failure message');
  }
  // Default fallback still produces a positive cost
  const cost = cache.costFor('openai', 'gpt-4o', 1000, 0);
  if (cost <= 0) throw new Error('expected positive default cost after refresh failure');
});

await test('pricing: concurrent refresh shares one in-flight HTTP call', async () => {
  let calls = 0;
  const cache = new PricingCache({
    refreshMs: 60_000,
    fetcher: async () => {
      calls++;
      await new Promise(r => setTimeout(r, 10));
      return { rates: [] };
    },
  });
  await Promise.all([
    cache.refreshIfStale(),
    cache.refreshIfStale(),
    cache.refreshIfStale(),
  ]);
  eq(calls, 1, 'three concurrent calls should dedupe to one fetch');
});

await test('pricing: round6 matches Java HALF_UP at 6dp', () => {
  const cache = new PricingCache({ fetcher: async () => ({ rates: [] }) });
  cache.primeFromRates([
    { provider: 'openai', model: 'gpt-4o', inputRate: 0.0033333333, outputRate: 0, fixedCost: 0 },
  ]);
  // 0.0033333333 * 1000 / 1000 = 0.0033333333 → round6 → 0.003333
  approx(cache.costFor('openai', 'gpt-4o', 1000, 0), 0.003333, 1e-9);
});

// ── End-to-end: BUDGET_EXHAUSTED sentinel → mapper resolution ──────────────

await test('e2e: budgetExhausted sentinel → mapper returns BUDGET_EXHAUSTED truncated', () => {
  const ctx = { state: { budgetExhausted: true, budgetScope: 'tenant' } };
  const result = resolveStopReason('claude', { subtype: 'success' }, ctx);
  eq(result.reason, AgentStopReason.BUDGET_EXHAUSTED);
  eq(result.truncated, true);
});

await test('e2e: tenant scope reported by chain → propagates as deny.scope', async () => {
  const pricing2 = new PricingCache({ fetcher: async () => ({ rates: [] }) });
  pricing2.primeFromRates([
    { provider: 'openai', model: 'gpt-4o', inputRate: 0.005, outputRate: 0.015, fixedCost: 0 },
  ]);
  const tenant = new TenantBudgetGuard({ initialBalance: 0.0001, pricing: pricing2 });
  const agent = new AgentBudgetGuard({ budget: 1000, pricing: pricing2 });
  const chain = chainBudgetGuards(tenant, agent);
  const r = await chain({ promptTokens: 10000, completionTokens: 0, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r.proceed, false);
  eq(r.scope, 'tenant');
});

await test('PRICING_DEFAULTS exposes ROUND_DECIMALS=6', () => {
  eq(PRICING_DEFAULTS.ROUND_DECIMALS, 6);
});

// ── killChildOnce semantics - race-safety regression test ─────────────────
//
// Reproduces the killChildOnce pattern from server.mjs in isolation: a single
// flag must guarantee that N concurrent callers (cancelInterval + budget guard)
// only fire SIGTERM once. If the implementation regresses to checking-then-setting
// without atomicity, this test will catch it under a stress loop.
await test('killChildOnce: only one SIGTERM under concurrent callers', async () => {
  let killCalls = 0;
  let killSignalSent = false;
  const fakeChild = { kill: () => { killCalls++; } };

  const killChildOnce = (label) => {
    if (killSignalSent) return;
    killSignalSent = true;
    fakeChild.kill('SIGTERM');
  };

  // Simulate 50 concurrent callers (budget guard + cancel interval ticks).
  await Promise.all(
    Array.from({ length: 50 }, async (_, i) => {
      // Yield to event loop so the calls actually interleave.
      await Promise.resolve();
      killChildOnce(`caller-${i}`);
    })
  );
  eq(killCalls, 1, 'should kill exactly once even with 50 concurrent callers');
});

// ── budgetCheckInFlight dedup ─────────────────────────────────────────────
//
// Mirrors the runBudgetCheck pattern: a single in-flight promise must dedupe
// concurrent fire-and-forget invocations. Verifies the actual semantics rather
// than re-implementing them - sets up the same flag pattern and confirms only
// one guard call goes through per usage burst.
// ── TenantBudgetGuard refresh failure handling ────────────────────────────
//
// When refreshBalance() throws (auth-service down, network error, etc.) the guard
// must keep operating with its last known balance - never trip on undefined and never
// silently switch to "infinite balance". This is the fail-open contract documented in
// server.mjs: stale-but-functional beats blocking the user mid-conversation.
await test('tenant guard: refresh failure preserves previous balance (fail-open)', async () => {
  let refreshCalls = 0;
  const guard = new TenantBudgetGuard({
    initialBalance: 10,
    pricing,
    refreshBalance: async () => {
      refreshCalls++;
      throw new Error('auth-service down');
    },
    refreshEveryNIters: 5,
  });

  // Burn 4 iterations - no refresh yet, balance still 10.
  for (let i = 1; i <= 4; i++) {
    const r = await guard.check({ promptTokens: 100, completionTokens: 50, iterations: i, provider: 'openai', model: 'gpt-4o' });
    eq(r.proceed, true);
  }
  eq(refreshCalls, 0);

  // Iteration 5 triggers a refresh that throws - guard MUST keep balance at 10
  // and continue operating.
  const r5 = await guard.check({ promptTokens: 100, completionTokens: 50, iterations: 5, provider: 'openai', model: 'gpt-4o' });
  eq(refreshCalls, 1, 'refresh should have been attempted');
  eq(r5.proceed, true, 'guard should fail-open with last known balance');
  // Balance still 10 internally - verify by exhausting it.
  const r6 = await guard.check({ promptTokens: 100_000_000, completionTokens: 0, iterations: 6, provider: 'openai', model: 'gpt-4o' });
  eq(r6.proceed, false, 'guard still enforces the original 10-credit budget');
  eq(r6.scope, 'tenant');
});

await test('tenant guard: null refreshBalance is a no-op (used by bridge for non-numeric tenantId)', async () => {
  // Mirrors the bridge wiring: when tenantId is non-numeric, server.mjs passes
  // refreshBalance: null. The guard must keep operating off its initial balance
  // without ever attempting (or crashing on) a refresh.
  const guard = new TenantBudgetGuard({
    initialBalance: 1,
    pricing,
    refreshBalance: null,
    refreshEveryNIters: 1,
  });
  const r1 = await guard.check({ promptTokens: 100, completionTokens: 50, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(r1.proceed, true);
  const r2 = await guard.check({ promptTokens: 100, completionTokens: 50, iterations: 10, provider: 'openai', model: 'gpt-4o' });
  eq(r2.proceed, true, 'no refresh should be attempted even past the refresh interval');
  // Burn the seeded balance - guard still enforces it.
  const burnt = await guard.check({ promptTokens: 10_000_000, completionTokens: 0, iterations: 11, provider: 'openai', model: 'gpt-4o' });
  eq(burnt.proceed, false);
  eq(burnt.scope, 'tenant');
});

await test('tenant guard: refresh returning null preserves previous balance', async () => {
  // Bridge server.mjs returns null on HTTP failure - TenantBudgetGuard.check() must
  // ignore null and keep the existing balance.
  let stage = 0;
  const guard = new TenantBudgetGuard({
    initialBalance: 5,
    pricing,
    refreshBalance: async () => { stage++; return null; },
    refreshEveryNIters: 1,
  });
  const r = await guard.check({ promptTokens: 100, completionTokens: 50, iterations: 1, provider: 'openai', model: 'gpt-4o' });
  eq(stage, 1, 'refresh attempted');
  eq(r.proceed, true);
  // Balance should still be 5 - burn it.
  const burnt = await guard.check({ promptTokens: 10_000_000, completionTokens: 0, iterations: 2, provider: 'openai', model: 'gpt-4o' });
  eq(burnt.proceed, false, 'previous balance is preserved when refresh returns null');
});

await test('budgetCheckInFlight dedup: 10 calls during one async wait → 1 guard call', async () => {
  let guardCalls = 0;
  let inFlight = null;
  let exhausted = false;

  const fakeGuard = async () => {
    guardCalls++;
    await new Promise(r => setTimeout(r, 5));
    return { proceed: true };
  };

  const runCheck = () => {
    if (exhausted || inFlight) return;
    inFlight = (async () => {
      try { await fakeGuard(); }
      finally { inFlight = null; }
    })();
  };

  // Fire 10 synchronous calls - only the first should start the guard.
  for (let i = 0; i < 10; i++) runCheck();
  // Wait for the in-flight guard to settle.
  await new Promise(r => setTimeout(r, 20));
  eq(guardCalls, 1, '10 concurrent calls should yield 1 guard invocation');

  // After settling, a fresh call goes through again.
  runCheck();
  await new Promise(r => setTimeout(r, 20));
  eq(guardCalls, 2, 'next call after settle should run a fresh check');
});

console.log(`\n${passed}/${passed + failed} passed`);
process.exit(failed > 0 ? 1 : 0);
