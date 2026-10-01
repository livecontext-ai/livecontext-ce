/**
 * Cache-aware budget guards (2026-09-30).
 *
 * The bridge tenant guard priced every prompt token at the model's INPUT rate. A Claude
 * Code turn is mostly cache reads (Anthropic bills them at 0.1x input), so a free
 * account's chat turn was projected at ~5x its real debit and killed after three or four
 * model calls: prod log "tenant balance 733.5726 would be exceeded (consumed=536.7652 +
 * next=310.4319 ...)" for a turn the ledger debited 112.4374.
 *
 * These tests replay that turn with the prod snapshot rates (claude-code /
 * claude-sonnet-5-5: input 2.0, output 10.0, cache read 0.2, cache write 2.5 per 1M,
 * times the 4/3 cloud multiplier).
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { PricingCache, inputBreakdown, promptShapeOf, PROMPT_SHAPE } from '../lib/pricing.js';
import { AgentBudgetGuard, TenantBudgetGuard, sumCacheCounters } from '../lib/budgetGuards.js';

const __dirname = dirname(fileURLToPath(import.meta.url));

const SONNET_ROW = {
  provider: 'claude-code', model: 'claude-sonnet-5-5',
  inputRate: 2.666667, outputRate: 13.333333, fixedCost: 0,
  cacheReadRate: 0.266667, cacheWriteRate: 3.333333,
};
const LUNA_ROW = {
  provider: 'codex', model: 'gpt-6-luna',
  inputRate: 0.133333, outputRate: 0.666667, fixedCost: 0,
  cacheReadRate: 0.013333, cacheWriteRate: 0.133333,
};

function pricingWith(...rows) {
  const p = new PricingCache({ fetcher: async () => ({ rates: [] }) });
  p.primeFromRates(rows);
  return p;
}

/**
 * The four model calls of the killed turn, as the claude adapter records them
 * (promptTokens = plain + cache write + cache read). Their sums are the ledger's:
 * 8 plain, 18,971 cache writes, 182,073 cache reads, 47 output tokens.
 */
const KILLED_TURN_CALLS = [
  { promptTokens: 40643, completionTokens: 2, cacheCreationInputTokens: 12000, cacheReadInputTokens: 28641 },
  { promptTokens: 45104, completionTokens: 8, cacheCreationInputTokens: 4461, cacheReadInputTokens: 40641 },
  { promptTokens: 57124, completionTokens: 32, cacheCreationInputTokens: 1453, cacheReadInputTokens: 55669 },
  { promptTokens: 58181, completionTokens: 5, cacheCreationInputTokens: 1057, cacheReadInputTokens: 57122 },
];

/** Cumulative usage after `n` calls, as server.mjs hands it to the guard. */
function usageAfter(n, { withCache }) {
  const calls = KILLED_TURN_CALLS.slice(0, n);
  const usage = {
    promptTokens: calls.reduce((s, c) => s + c.promptTokens, 0),
    completionTokens: calls.reduce((s, c) => s + c.completionTokens, 0),
    iterations: n * 2,
    provider: 'claude-code',
    model: 'claude-sonnet-5-5',
  };
  return withCache ? { ...usage, ...sumCacheCounters(calls) } : usage;
}

// ── inputBreakdown: the JS twin of TokenUsageConventions.inputBreakdown ─────────

test('claude-code prompt totals contain the cache, so plain input is what is left', () => {
  const b = inputBreakdown('claude-code', { promptTokens: 201052, cacheCreationTokens: 18971, cacheReadTokens: 182073 });
  assert.deepEqual(b, { plainInput: 8, cacheWrite: 18971, cacheRead: 182073 });
});

test('anthropic API prompt excludes the cache, so the cache counters are added beside it', () => {
  const b = inputBreakdown('anthropic', { promptTokens: 8, cacheCreationTokens: 18971, cacheReadTokens: 182073 });
  assert.deepEqual(b, { plainInput: 8, cacheWrite: 18971, cacheRead: 182073 });
});

test('subset providers (codex, openai, deepseek...) carve the cached part out of the prompt', () => {
  const b = inputBreakdown('codex', { promptTokens: 115668, cachedTokens: 87808 });
  assert.deepEqual(b, { plainInput: 27860, cacheWrite: 0, cacheRead: 87808 });
});

test('a subset provider over-reporting its cache cannot drive plain input negative', () => {
  const b = inputBreakdown('openai', { promptTokens: 100, cachedTokens: 250 });
  assert.deepEqual(b, { plainInput: 0, cacheWrite: 0, cacheRead: 100 });
});

test('an inclusive total smaller than its cache counters clamps plain input at 0', () => {
  const b = inputBreakdown('claude-code', { promptTokens: 100, cacheCreationTokens: 80, cacheReadTokens: 80 });
  assert.equal(b.plainInput, 0);
});

test('shape comes from the provider name, case-insensitively, unknown = subset', () => {
  assert.equal(promptShapeOf('Claude-Code'), PROMPT_SHAPE.ADDITIVE_INCLUSIVE);
  assert.equal(promptShapeOf('claude'), PROMPT_SHAPE.ADDITIVE_EXCLUSIVE);
  assert.equal(promptShapeOf('gemini-cli'), PROMPT_SHAPE.SUBSET);
  assert.equal(promptShapeOf(undefined), PROMPT_SHAPE.SUBSET);
});

// ── costForUsage: the guard's cost equals the ledger's debit ────────────────────

test('the killed claude-code turn costs what the ledger debited (112.44), not 536.77', () => {
  const pricing = pricingWith(SONNET_ROW);
  const cost = pricing.costForUsage('claude-code', 'claude-sonnet-5-5', usageAfter(4, { withCache: true }));
  assert.ok(Math.abs(cost - 112.4374) < 0.001, `expected ~112.4374 (ledger), got ${cost}`);
});

test('the GPT Luna turn costs what the ledger debited (5.1094)', () => {
  const pricing = pricingWith(LUNA_ROW);
  const cost = pricing.costForUsage('codex', 'gpt-6-luna',
    { promptTokens: 115668, completionTokens: 336, cachedTokens: 87808 });
  assert.ok(Math.abs(cost - 5.1094) < 0.001, `expected ~5.1094 (ledger), got ${cost}`);
});

test('a row without cache rates keeps the pre-change formula (older auth-service snapshot)', () => {
  const { cacheReadRate, cacheWriteRate, ...legacy } = SONNET_ROW;
  const pricing = pricingWith(legacy, { ...legacy, provider: 'anthropic' });
  const usage = usageAfter(4, { withCache: true });
  const allAtInput = pricing.costFor('claude-code', 'claude-sonnet-5-5', usage.promptTokens, usage.completionTokens);
  assert.equal(pricing.costForUsage('claude-code', 'claude-sonnet-5-5', usage), allAtInput);
  assert.equal(pricing.cacheMissCostForUsage('claude-code', 'claude-sonnet-5-5', usage), allAtInput);
  // Direct Anthropic keeps ignoring its additive cache, as before: pricing it at the input
  // rate would project the reads 10x too high for as long as a rollout lasts.
  const anthropic = { promptTokens: 8, completionTokens: 47, cacheCreationTokens: 18971, cacheReadTokens: 182073 };
  assert.equal(pricing.costForUsage('anthropic', 'claude-sonnet-5-5', anthropic),
    pricing.costFor('anthropic', 'claude-sonnet-5-5', 8, 47));
});

test('a cache miss prices the last call with everything it read written again (194.00, not 18.8)', () => {
  const pricing = pricingWith(SONNET_ROW, LUNA_ROW);
  const lastCall = { promptTokens: 58181, completionTokens: 5, cacheCreationTokens: 1057, cacheReadTokens: 57122 };
  const miss = pricing.cacheMissCostForUsage('claude-code', 'claude-sonnet-5-5', lastCall);
  const hit = pricing.costForUsage('claude-code', 'claude-sonnet-5-5', lastCall);
  assert.ok(Math.abs(miss - 194.002) < 0.01, `expected ~194.00, got ${miss}`);
  assert.ok(Math.abs(hit - 18.83) < 0.01, `expected ~18.83, got ${hit}`);
  // A subset provider has no write premium: a miss is the whole prompt at the input rate.
  const luna = { promptTokens: 115668, completionTokens: 336, cachedTokens: 87808 };
  assert.equal(pricing.cacheMissCostForUsage('codex', 'gpt-6-luna', luna),
    pricing.costFor('codex', 'gpt-6-luna', 115668, 336));
});

test('costFor without cache counters is unchanged', () => {
  const pricing = pricingWith(SONNET_ROW);
  // 1000 prompt at 2.666667 + 1000 completion at 13.333333 = 16.0
  assert.ok(Math.abs(pricing.costFor('claude-code', 'claude-sonnet-5-5', 1000, 1000) - 16.0) < 1e-6);
});

test('snapshot refresh reads cacheReadRate / cacheWriteRate, and a 0 rate means unknown', async () => {
  const pricing = new PricingCache({
    fetcher: async () => ({ rates: [
      { ...SONNET_ROW },
      { provider: 'x', model: 'free-cache', inputRate: 1, outputRate: 1, fixedCost: 0, cacheReadRate: 0, cacheWriteRate: '0' },
    ] }),
  });
  await pricing.refreshIfStale();
  const cost = pricing.costForUsage('claude-code', 'claude-sonnet-5-5', { promptTokens: 1000, cacheReadTokens: 1000 });
  assert.ok(Math.abs(cost - 0.266667) < 1e-6, `cache read must use cacheReadRate, got ${cost}`);
  // 0 must never make cached input free: it falls back to the input rate.
  const zero = pricing.costForUsage('x', 'free-cache', { promptTokens: 1000, cachedTokens: 1000 });
  assert.ok(Math.abs(zero - 1.0) < 1e-6, `a 0 cache rate must fall back to inputRate, got ${zero}`);
});

// ── sumCacheCounters ────────────────────────────────────────────────────────────

test('sumCacheCounters adds the per-call cache counters under the guard key names', () => {
  assert.deepEqual(sumCacheCounters([
    { cacheCreationInputTokens: 10, cacheReadInputTokens: 20, cachedTokens: 0 },
    { cacheCreationInputTokens: 1, cacheReadInputTokens: 2, cachedTokens: 3 },
    {},
  ]), { cacheCreationTokens: 11, cacheReadTokens: 22, cachedTokens: 3 });
  assert.deepEqual(sumCacheCounters(undefined), { cacheCreationTokens: 0, cacheReadTokens: 0, cachedTokens: 0 });
});

// ── the guards replay the killed turn ───────────────────────────────────────────

test('tenant guard lets the killed turn run through all four calls once it sees the cache', async () => {
  const guard = new TenantBudgetGuard({ initialBalance: 733.5726, pricing: pricingWith(SONNET_ROW) });
  for (let n = 1; n <= KILLED_TURN_CALLS.length; n++) {
    const r = await guard.check(usageAfter(n, { withCache: true }));
    assert.equal(r.proceed, true, `call ${n} must proceed, got: ${r.reason}`);
  }
});

test('the same replay without cache counters is the prod kill (536.77 + 310.43 > 733.57)', async () => {
  const guard = new TenantBudgetGuard({ initialBalance: 733.5726, pricing: pricingWith(SONNET_ROW) });
  let last;
  for (let n = 1; n <= KILLED_TURN_CALLS.length; n++) {
    last = await guard.check(usageAfter(n, { withCache: false }));
  }
  assert.equal(last.proceed, false);
  assert.match(last.reason, /consumed=536\.765\d/);
  assert.match(last.reason, /lastDelta=310\.43/);
});

test('tenant guard keeps room for one cache miss: 250 credits do not cover 112.44 + a 194 rewrite', async () => {
  const guard = new TenantBudgetGuard({ initialBalance: 250, pricing: pricingWith(SONNET_ROW) });
  for (let n = 1; n <= 3; n++) await guard.check(usageAfter(n, { withCache: true }));
  const r = await guard.check(usageAfter(4, { withCache: true }));
  assert.equal(r.proceed, false);
  assert.match(r.reason, /cacheMiss=194\.00/);
  // The cache-mix branches alone (112.44 + 2 x 18.8 = 150) would have let it through.
  assert.match(r.reason, /lastDelta=37\.6\d/);
});

test('tenant guard lastDelta prices the latest call only, at its cache mix', async () => {
  const guard = new TenantBudgetGuard({ initialBalance: 150, pricing: pricingWith(SONNET_ROW) });
  for (let n = 1; n <= 3; n++) await guard.check(usageAfter(n, { withCache: true }));
  // After call 4: consumed 112.44; call 4 alone is ~18.8 so next = 2 x 18.8 = ~37.7.
  // 112.44 + 37.7 = ~150.1 > 150 -> deny, and the message shows the cache-aware figures.
  const r = await guard.check(usageAfter(4, { withCache: true }));
  assert.equal(r.proceed, false);
  assert.match(r.reason, /consumed=112\.43\d+/);
  assert.match(r.reason, /lastDelta=37\.6\d/);
});

test('agent guard prices the cache too (budget 350: proceeds with cache, exhausted without)', () => {
  // 350 covers 112.44 spent + a 194.00 cache miss; the input-rate price (536.77) does not.
  const withCache = new AgentBudgetGuard({ budget: 350, pricing: pricingWith(SONNET_ROW) });
  const withoutCache = new AgentBudgetGuard({ budget: 350, pricing: pricingWith(SONNET_ROW) });
  let a, b;
  for (let n = 1; n <= KILLED_TURN_CALLS.length; n++) {
    a = withCache.check(usageAfter(n, { withCache: true }));
    b = withoutCache.check(usageAfter(n, { withCache: false }));
  }
  assert.equal(a.proceed, true, a.reason);
  assert.equal(b.proceed, false);
  assert.match(b.reason, /agent budget exhausted/);
});

// ── server.mjs wiring ───────────────────────────────────────────────────────────

test('server.mjs hands the run cache counters (summed from perCallUsages) to the budget guard', () => {
  const src = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
  const start = src.indexOf('const runBudgetCheck = () => {');
  assert.notEqual(start, -1, 'runBudgetCheck not found');
  const body = src.slice(start, src.indexOf('killChildOnce(`budget-', start));
  assert.match(body, /sumCacheCounters\(perCallUsages\)/,
    'runBudgetCheck must sum the cache counters of every model call so far');
  assert.match(body, /await budgetGuard\(\{[\s\S]*\.\.\.cacheCounters/,
    'the cache counters must reach the guard, else it prices cache reads at the input rate');
});

test('server.mjs returns the guard scope with the run result, so the backend learns WHY it stopped', () => {
  const src = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
  const close = src.slice(src.indexOf("child.on('close'"), src.indexOf("child.on('error'"));
  assert.match(close, /resolvePromise\(\{[\s\S]*\bbudgetScope,/,
    'the close handler must hand budgetScope to the caller, else every budget stop reaches the backend with scope null');
  assert.match(src, /budgetScope: result\.budgetScope \|\| null/,
    'the /execute handler reads the scope off the run result into the response');
});

// ── round 2: the reservation, the gemini-cli shape, the real adapter chain ─────

test('the cache-miss reservation applies where the prompt total carries the cache, not to the direct Anthropic API', async () => {
  // Same spend and cache on both. claude-code (inclusive total) reserves a full miss, as the
  // pre-change 2 x input projection effectively did; the direct Anthropic API never had a
  // cache reservation, and adding one would stop existing direct runs far earlier.
  const pricing = pricingWith(SONNET_ROW, { ...SONNET_ROW, provider: 'anthropic' });
  const lastCall = { promptTokens: 58181, completionTokens: 5, cacheCreationTokens: 1057, cacheReadTokens: 57122 };
  assert.ok(pricing.cacheMissReserveForUsage('claude-code', 'claude-sonnet-5-5', lastCall) > 190);
  assert.equal(pricing.cacheMissReserveForUsage('anthropic', 'claude-sonnet-5-5',
    { ...lastCall, promptTokens: 2 }), 0);

  const direct = new TenantBudgetGuard({ initialBalance: 250, pricing });
  const r = await direct.check({
    promptTokens: 8, completionTokens: 47, cacheCreationTokens: 18971, cacheReadTokens: 182073,
    iterations: 8, provider: 'anthropic', model: 'claude-sonnet-5-5',
  });
  // 112.44 spent + 2 x the whole run as the last delta (first check) = 337 > 250: denied on
  // the cache-aware branches alone, with no miss reservation in the message.
  assert.equal(r.proceed, false);
  assert.match(r.reason, /cacheMiss=0\.00/);
});

test('gemini-cli reports its cached subset as cacheReadTokens: carved out of the prompt, like cachedTokens', () => {
  assert.deepEqual(inputBreakdown('gemini-cli', { promptTokens: 1000, cacheReadTokens: 600 }),
    { plainInput: 400, cacheWrite: 0, cacheRead: 600 });
});

test('real ClaudeAdapter -> perCallUsages -> sumCacheCounters -> guard: the killed turn runs through', async () => {
  const { ClaudeAdapter } = await import('../adapters/claude-adapter.mjs');
  const adapter = new ClaudeAdapter();
  let usage = { promptTokens: 0, completionTokens: 0 };
  const perCallUsages = [];
  const ctx = {
    publisher: { publishContent: async () => {}, publishThinking: async () => {},
      publishToolCall: async () => {}, publishToolResult: async () => {} },
    pendingToolCalls: new Map(), orderedEntries: [], toolResults: [], thinkingSections: [],
    adapterState: adapter.createRunState({ attachmentPathToName: null }),
    stripMcpPrefix: (n) => n, extractToolResultAndMetadata: () => ({}),
    getContent: () => '',
    state: {
      get usage() { return usage; }, get numTurns() { return 0; }, get fullContent() { return ''; },
      get perCallUsages() { return perCallUsages; }, get iterationTimestamps() { return []; },
      get finishReasons() { return []; },
    },
    updateState(u) { if (u.usage != null) usage = u.usage; },
  };
  const guard = new TenantBudgetGuard({ initialBalance: 733.5726, pricing: pricingWith(SONNET_ROW) });

  let n = 0;
  for (const c of KILLED_TURN_CALLS) {
    n++;
    await adapter.handleMessage({ type: 'assistant', message: {
      id: `msg_${n}`, stop_reason: 'tool_use', content: [{ type: 'text', text: 'x' }],
      usage: { input_tokens: 2, output_tokens: c.completionTokens,
        cache_creation_input_tokens: c.cacheCreationInputTokens, cache_read_input_tokens: c.cacheReadInputTokens },
    } }, ctx);
    // Exactly what server.mjs runBudgetCheck hands the guard.
    const r = await guard.check({ promptTokens: usage.promptTokens, completionTokens: usage.completionTokens,
      ...sumCacheCounters(perCallUsages), iterations: n * 2, provider: 'claude-code', model: 'claude-sonnet-5-5' });
    assert.equal(r.proceed, true, `call ${n}: ${r.reason}`);
  }
  assert.equal(usage.promptTokens, 201052, 'the adapter reports the inclusive total');
  assert.deepEqual(sumCacheCounters(perCallUsages), { cacheCreationTokens: 18971, cacheReadTokens: 182073, cachedTokens: 0 });
});

test('server.mjs puts the guard scope in metrics.budgetScope, where chat and workflow observability read it', () => {
  // CRLF-safe: a Windows checkout (core.autocrlf=true) has CRLF line endings in server.mjs.
  const src = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8').replace(/\r\n/g, '\n');
  const metrics = src.slice(src.indexOf('metrics: {\n        reasoningDurationMs'), src.indexOf('usagePerIteration,\n      iterationDurations'));
  assert.match(metrics, /budgetScope: result\.budgetScope/,
    'AgentObservabilityClient / AgentNode read metrics.budgetScope; the top-level field alone left chat stops unexplained');
});
