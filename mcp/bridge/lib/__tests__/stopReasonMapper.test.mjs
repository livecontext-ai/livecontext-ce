// Standalone smoke tests for stopReasonMapper. The bridge has no test runner
// configured today, so this is a self-contained Node script that can be invoked
// with `node lib/__tests__/stopReasonMapper.test.mjs` and exits non-zero on
// failure. When the bridge gains a vitest setup (P4 task #16) these cases
// should be migrated to vitest format.

import { resolveStopReason } from '../stopReasonMapper.js';
import { AgentStopReason } from '../agentStopReason.js';

let passed = 0, failed = 0;

function test(name, fn) {
  try {
    fn();
    passed++;
    console.log('  PASS', name);
  } catch (e) {
    failed++;
    console.error('  FAIL', name, '\n        ', e.message);
  }
}

function eq(actual, expected, label = '') {
  if (actual !== expected) {
    throw new Error(`${label} expected ${expected}, got ${actual}`);
  }
}

const ctx = (state = {}) => ({ state });

// ── CLI subtype mapping ────────────────────────────────────────────────────

test('claude success → COMPLETED', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctx());
  eq(r.reason, AgentStopReason.COMPLETED);
  eq(r.success, true);
});

test('claude error_max_turns → MAX_ITERATIONS truncated', () => {
  const r = resolveStopReason('claude', { subtype: 'error_max_turns' }, ctx());
  eq(r.reason, AgentStopReason.MAX_ITERATIONS);
  eq(r.success, true);
  eq(r.truncated, true);
});

test('claude error_during_execution → ERROR', () => {
  const r = resolveStopReason('claude', { subtype: 'error_during_execution' }, ctx());
  eq(r.reason, AgentStopReason.ERROR);
  eq(r.success, false);
});

test('claude error_max_tokens → MAX_ITERATIONS truncated', () => {
  const r = resolveStopReason('claude', { subtype: 'error_max_tokens' }, ctx());
  eq(r.reason, AgentStopReason.MAX_ITERATIONS);
});

test('claude unknown subtype + error field → ERROR', () => {
  const r = resolveStopReason('claude', { subtype: 'wat', error: 'boom' }, ctx());
  eq(r.reason, AgentStopReason.ERROR);
});

test('gemini success → COMPLETED', () => {
  const r = resolveStopReason('gemini', { subtype: 'success' }, ctx());
  eq(r.reason, AgentStopReason.COMPLETED);
});

test('gemini error → ERROR', () => {
  const r = resolveStopReason('gemini', { subtype: 'error', error: 'oops' }, ctx());
  eq(r.reason, AgentStopReason.ERROR);
});

test('mistral max_iterations → MAX_ITERATIONS', () => {
  const r = resolveStopReason('mistral', { subtype: 'max_iterations' }, ctx());
  eq(r.reason, AgentStopReason.MAX_ITERATIONS);
});

test('codex completed → COMPLETED', () => {
  const r = resolveStopReason('codex', { subtype: 'completed' }, ctx());
  eq(r.reason, AgentStopReason.COMPLETED);
});

test('unknown provider falls back to claude table', () => {
  const r = resolveStopReason('xyz', { subtype: 'success' }, ctx());
  eq(r.reason, AgentStopReason.COMPLETED);
});

test('no subtype, no error → COMPLETED', () => {
  const r = resolveStopReason('claude', {}, ctx());
  eq(r.reason, AgentStopReason.COMPLETED);
});

// ── Sentinel precedence (kill causes always win over CLI report) ───────────

test('stoppedByUser wins over success', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctx({ stoppedByUser: true }));
  eq(r.reason, AgentStopReason.STOPPED_BY_USER);
});

test('cancelledBySystem wins over success', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctx({ cancelledBySystem: true }));
  eq(r.reason, AgentStopReason.CANCELLED);
});

test('budgetExhausted wins, marks truncated', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctx({ budgetExhausted: true }));
  eq(r.reason, AgentStopReason.BUDGET_EXHAUSTED);
  eq(r.truncated, true);
});

test('loopDetected wins, marks truncated', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctx({ loopDetected: true }));
  eq(r.reason, AgentStopReason.LOOP_DETECTED);
  eq(r.truncated, true);
});

test('timedOut wins', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctx({ timedOut: true }));
  eq(r.reason, AgentStopReason.TIMEOUT);
});

// ── Sentinel ordering: stoppedByUser dominates cancelledBySystem ───────────

test('stoppedByUser dominates cancelledBySystem when both set', () => {
  const r = resolveStopReason('claude', { subtype: 'success' },
    ctx({ stoppedByUser: true, cancelledBySystem: true }));
  eq(r.reason, AgentStopReason.STOPPED_BY_USER);
});

console.log(`\n${passed}/${passed + failed} passed`);
process.exit(failed > 0 ? 1 : 0);
