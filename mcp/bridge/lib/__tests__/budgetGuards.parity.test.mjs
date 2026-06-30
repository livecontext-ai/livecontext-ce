// Cross-language parity test for the V162 budget guard formula.
//
// Reads `shared/contracts/budget-guard-fixtures.json` - the same fixture consumed
// by the Java twin `BudgetGuardParityTest`. Runs the JS TenantBudgetGuard against
// each fixture case and asserts the expected proceed / reason_contains outcome.
//
// Pattern mirrors `agent-stop-reason.json`: when the formula changes, update the
// fixture once and both runners (Java + JS) re-validate. Prevents silent drift
// between the two budget-guard implementations.
//
// Run with: node mcp/bridge/lib/__tests__/budgetGuards.parity.test.mjs

import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PricingCache } from '../pricing.js';
import { TenantBudgetGuard } from '../budgetGuards.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Walk up to find shared/contracts/budget-guard-fixtures.json.
function locateFixture() {
  let here = __dirname;
  for (let i = 0; i < 6; i++) {
    const candidate = resolve(here, 'shared/contracts/budget-guard-fixtures.json');
    if (existsSync(candidate)) return candidate;
    const parent = dirname(here);
    if (parent === here) break;
    here = parent;
  }
  throw new Error(`budget-guard-fixtures.json not found from ${__dirname}`);
}

const fixturePath = locateFixture();
const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));

let passed = 0, failed = 0;
const failures = [];

async function runCase(c) {
  const i = c.input;
  // Build a pricing cache primed with this case's rate row.
  const pricing = new PricingCache({ fetcher: async () => ({ rates: [] }) });
  pricing.primeFromRates([{
    provider: i.provider,
    model: i.model,
    inputRate: i.inputRate,
    outputRate: i.outputRate,
    fixedCost: i.fixedCost,
    contextWindow: i.contextWindow,
    maxOutputTokens: i.maxOutputTokens,
  }]);

  // Mock refreshBalance to return the fixture's balance once. Iterations >=
  // refreshEveryNIters trigger a refresh; setting initialBalance and never
  // returning a new value keeps the fixture-controlled balance for the entire run.
  const guard = new TenantBudgetGuard({
    initialBalance: i.balance,
    pricing,
    refreshBalance: async () => i.balance,
    refreshEveryNIters: 5,
    requireCtxWindow: !!i.requireCtxWindow,
  });

  // Seed the guard's internal lastTokens so that lastDelta computed by check()
  // equals the fixture's lastPromptTokens / lastCompletionTokens semantics
  // (delta of the most recently completed iteration). Java passes the delta
  // directly via IterationContext; JS tracks cumulative state and computes
  // delta = current - prior. Pre-seeding ensures the same effective lastDelta
  // value drives the projection in both runners.
  guard._lastPromptTokens = (i.promptTokens || 0) - (i.lastPromptTokens || 0);
  guard._lastCompletionTokens = (i.completionTokens || 0) - (i.lastCompletionTokens || 0);

  const result = await guard.check({
    promptTokens: i.promptTokens,
    completionTokens: i.completionTokens,
    iterations: i.iterations,
    provider: i.provider,
    model: i.model,
  });

  if (result.proceed !== c.expected.proceed) {
    throw new Error(
      `expected proceed=${c.expected.proceed} got proceed=${result.proceed} (reason=${result.reason})`);
  }
  if (c.expected.reason_contains) {
    const reason = result.reason || '';
    if (!reason.toLowerCase().includes(c.expected.reason_contains.toLowerCase())) {
      throw new Error(
        `expected reason to contain '${c.expected.reason_contains}', got '${reason}'`);
    }
  }
}

(async () => {
  console.log(`Running ${fixture.cases.length} parity cases (formula=${fixture.formulaVersion})`);
  for (const c of fixture.cases) {
    try {
      await runCase(c);
      passed++;
      console.log(`  PASS ${c.name}`);
    } catch (e) {
      failed++;
      failures.push({ name: c.name, error: e.message });
      console.error(`  FAIL ${c.name}: ${e.message}`);
    }
  }
  console.log(`\n${passed} passed, ${failed} failed.`);
  if (failed > 0) {
    console.error('\nFailures:');
    failures.forEach(f => console.error(`  - ${f.name}: ${f.error}`));
    process.exit(1);
  }
})();
