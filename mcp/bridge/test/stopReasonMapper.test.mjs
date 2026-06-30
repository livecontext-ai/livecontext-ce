/**
 * Tests for stopReasonMapper - the cross-language contract that mirrors the
 * Java AgentStopReason enum on the bridge side. Sentinel precedence and
 * subtype resolution must match the Java behavior exactly; these tests are
 * the contract.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveStopReason, applyResultMapping } from '../lib/stopReasonMapper.js';
import { AgentStopReason } from '../lib/agentStopReason.js';

function ctxWithState(state = {}) {
  let captured = null;
  return {
    state,
    updateState(u) { captured = u; },
    get _captured() { return captured; },
  };
}

// ─── Sentinel precedence (must beat any CLI-reported subtype) ─────────────

test('stoppedByUser sentinel beats success subtype', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctxWithState({ stoppedByUser: true }));
  assert.equal(r.reason, AgentStopReason.STOPPED_BY_USER);
  assert.equal(r.success, false);
});

test('cancelledBySystem sentinel beats success subtype', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctxWithState({ cancelledBySystem: true }));
  assert.equal(r.reason, AgentStopReason.CANCELLED);
});

test('budgetExhausted sentinel marks truncated success', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctxWithState({ budgetExhausted: true }));
  assert.equal(r.reason, AgentStopReason.BUDGET_EXHAUSTED);
  assert.equal(r.success, true);
  assert.equal(r.truncated, true);
});

test('loopDetected sentinel marks truncated success', () => {
  const r = resolveStopReason('gemini', {}, ctxWithState({ loopDetected: true }));
  assert.equal(r.reason, AgentStopReason.LOOP_DETECTED);
  assert.equal(r.truncated, true);
});

test('timedOut sentinel marks failure', () => {
  const r = resolveStopReason('mistral', {}, ctxWithState({ timedOut: true }));
  assert.equal(r.reason, AgentStopReason.TIMEOUT);
  assert.equal(r.success, false);
});

test('inactivityTimedOut sentinel maps to INACTIVITY_TIMEOUT failure', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctxWithState({ inactivityTimedOut: true }));
  assert.equal(r.reason, AgentStopReason.INACTIVITY_TIMEOUT);
  assert.equal(r.success, false);
});

test('sentinel order: inactivityTimedOut (stalled CLI) wins over the total timedOut', () => {
  const r = resolveStopReason('claude', {}, ctxWithState({ inactivityTimedOut: true, timedOut: true }));
  assert.equal(r.reason, AgentStopReason.INACTIVITY_TIMEOUT);
});

test('sentinel order: stoppedByUser wins over budgetExhausted', () => {
  const r = resolveStopReason('claude', {}, ctxWithState({
    stoppedByUser: true,
    budgetExhausted: true,
    timedOut: true,
  }));
  assert.equal(r.reason, AgentStopReason.STOPPED_BY_USER);
});

// ─── Subtype tables ───────────────────────────────────────────────────────

test('claude success → COMPLETED', () => {
  const r = resolveStopReason('claude', { subtype: 'success' }, ctxWithState());
  assert.equal(r.reason, AgentStopReason.COMPLETED);
  assert.equal(r.success, true);
});

test('claude error_max_turns → MAX_ITERATIONS truncated', () => {
  const r = resolveStopReason('claude', { subtype: 'error_max_turns' }, ctxWithState());
  assert.equal(r.reason, AgentStopReason.MAX_ITERATIONS);
  assert.equal(r.truncated, true);
  assert.equal(r.success, true);
});

test('claude error_during_execution → ERROR', () => {
  const r = resolveStopReason('claude', { subtype: 'error_during_execution' }, ctxWithState());
  assert.equal(r.reason, AgentStopReason.ERROR);
  assert.equal(r.success, false);
});

test('gemini max_iterations → MAX_ITERATIONS', () => {
  const r = resolveStopReason('gemini', { subtype: 'max_iterations' }, ctxWithState());
  assert.equal(r.reason, AgentStopReason.MAX_ITERATIONS);
});

test('mistral completed → COMPLETED', () => {
  const r = resolveStopReason('mistral', { subtype: 'completed' }, ctxWithState());
  assert.equal(r.reason, AgentStopReason.COMPLETED);
});

test('codex error → ERROR', () => {
  const r = resolveStopReason('codex', { subtype: 'error' }, ctxWithState());
  assert.equal(r.reason, AgentStopReason.ERROR);
});

// ─── Fallback paths ───────────────────────────────────────────────────────

test('unknown subtype with explicit error message → ERROR with message', () => {
  const r = resolveStopReason('claude', { subtype: 'weird_unknown', error: 'kaboom' }, ctxWithState());
  assert.equal(r.reason, AgentStopReason.ERROR);
  assert.equal(r.error, 'kaboom');
});

test('no subtype, no error → COMPLETED (CLI thinks it is done)', () => {
  const r = resolveStopReason('codex', {}, ctxWithState());
  assert.equal(r.reason, AgentStopReason.COMPLETED);
});

test('unknown provider falls back to claude table', () => {
  const r = resolveStopReason('weird_provider', { subtype: 'success' }, ctxWithState());
  assert.equal(r.reason, AgentStopReason.COMPLETED);
});

// ─── applyResultMapping side-effects on ctx.updateState ───────────────────

test('applyResultMapping writes success/stopReason via updateState', () => {
  const ctx = ctxWithState();
  applyResultMapping('claude', { subtype: 'success' }, ctx);
  assert.equal(ctx._captured.success, true);
  assert.equal(ctx._captured.stopReason, AgentStopReason.COMPLETED);
});

test('applyResultMapping carries error string when present', () => {
  const ctx = ctxWithState();
  applyResultMapping('claude', { subtype: 'error', error: 'oops' }, ctx);
  assert.equal(ctx._captured.error, 'oops');
});

test('applyResultMapping sets truncated=true for max-iterations branch', () => {
  const ctx = ctxWithState();
  applyResultMapping('gemini', { subtype: 'max_iterations' }, ctx);
  assert.equal(ctx._captured.truncated, true);
});
