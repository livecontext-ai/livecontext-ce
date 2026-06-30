// Maps the terminal `result` event from each CLI adapter to a canonical
// AgentStopReason value, mirroring the Java enum from agent-common.
//
// All four adapters (claude, gemini, mistral, codex) used to inline a 30-40 line
// switch that collapsed every non-success outcome to 'ERROR' - losing useful
// information about why the run actually ended (max iterations, budget exhausted,
// timeout, loop detected, user cancellation, etc.). This module provides a single
// table-driven mapping shared by every adapter so the bridge emits the SAME
// stopReason values as the Java backend.
//
// Usage from an adapter's `case 'result'`:
//
//   import { applyResultMapping } from '../lib/stopReasonMapper.js';
//   ...
//   case 'result':
//     applyResultMapping('claude', msg, ctx);
//     break;
//
// `ctx.state` may carry pre-set sentinel flags from the bridge runtime
// (stoppedByUser, cancelledBySystem, budgetExhausted, loopDetected, inactivityTimedOut, timedOut).
// These take precedence over the CLI's reported subtype because they describe
// what we actually did to the process (e.g. SIGTERM after a guard denial or an inactivity kill).

import { AgentStopReason } from './agentStopReason.js';

/**
 * Per-provider table mapping the CLI's terminal `subtype` (or equivalent)
 * to a canonical AgentStopReason. Anything not listed falls through to the
 * generic ERROR mapping.
 */
const SUBTYPE_TABLE = Object.freeze({
  claude: {
    success: { reason: AgentStopReason.COMPLETED, success: true },
    error_max_turns: { reason: AgentStopReason.MAX_ITERATIONS, success: true, truncated: true },
    error_during_execution: { reason: AgentStopReason.ERROR, success: false },
    error_max_tokens: { reason: AgentStopReason.MAX_ITERATIONS, success: true, truncated: true },
  },
  gemini: {
    success: { reason: AgentStopReason.COMPLETED, success: true },
    completed: { reason: AgentStopReason.COMPLETED, success: true },
    max_iterations: { reason: AgentStopReason.MAX_ITERATIONS, success: true, truncated: true },
    error: { reason: AgentStopReason.ERROR, success: false },
  },
  mistral: {
    success: { reason: AgentStopReason.COMPLETED, success: true },
    completed: { reason: AgentStopReason.COMPLETED, success: true },
    max_iterations: { reason: AgentStopReason.MAX_ITERATIONS, success: true, truncated: true },
    error: { reason: AgentStopReason.ERROR, success: false },
  },
  codex: {
    success: { reason: AgentStopReason.COMPLETED, success: true },
    completed: { reason: AgentStopReason.COMPLETED, success: true },
    max_iterations: { reason: AgentStopReason.MAX_ITERATIONS, success: true, truncated: true },
    error: { reason: AgentStopReason.ERROR, success: false },
  },
});

/**
 * Resolve a final stopReason for the run.
 *
 * Sentinel flags on `ctx.state` take precedence so we can record the *cause*
 * of an external interrupt (user cancel, budget guard, loop guard, timeout)
 * rather than reporting whatever the CLI emitted as it was being killed.
 *
 * @param {string} providerName  one of: 'claude' | 'gemini' | 'mistral' | 'codex'
 * @param {object} msg           parsed terminal NDJSON message from the CLI
 * @param {object} ctx           shared adapter context (carries `state`)
 * @returns {{ reason: string, success: boolean, truncated?: boolean, error?: string }}
 */
export function resolveStopReason(providerName, msg, ctx) {
  const state = ctx?.state || {};

  // 1. External interruption sentinels take precedence over the CLI's own report.
  if (state.stoppedByUser) {
    return { reason: AgentStopReason.STOPPED_BY_USER, success: false };
  }
  if (state.cancelledBySystem) {
    return { reason: AgentStopReason.CANCELLED, success: false };
  }
  if (state.budgetExhausted) {
    return {
      reason: AgentStopReason.BUDGET_EXHAUSTED,
      success: true,
      truncated: true,
    };
  }
  if (state.loopDetected) {
    return {
      reason: AgentStopReason.LOOP_DETECTED,
      success: true,
      truncated: true,
    };
  }
  if (state.inactivityTimedOut) {
    // The inactivity watchdog killed a stalled CLI (no output for the configured window).
    // Distinct from timedOut, which is the TOTAL wall-clock cap on a CLI that was still emitting.
    return { reason: AgentStopReason.INACTIVITY_TIMEOUT, success: false };
  }
  if (state.timedOut) {
    return { reason: AgentStopReason.TIMEOUT, success: false };
  }

  // 2. CLI-reported subtype.
  const table = SUBTYPE_TABLE[providerName] || SUBTYPE_TABLE.claude;
  const subtype = msg?.subtype;
  if (subtype && table[subtype]) {
    return { ...table[subtype] };
  }

  // 3. CLI emitted an explicit error or unknown subtype → ERROR.
  if (msg?.subtype === 'error' || msg?.error) {
    const errMsg = msg.error
      || (msg.errors && msg.errors.join('; '))
      || `Agent ended with: ${msg?.subtype || 'unknown'}`;
    return { reason: AgentStopReason.ERROR, success: false, error: errMsg };
  }

  // 4. No subtype at all but the CLI thinks it's done - assume completion.
  return { reason: AgentStopReason.COMPLETED, success: true };
}

/**
 * Convenience: resolve the stopReason and apply it to ctx via `updateState`.
 * Carries through any non-empty `error` field too.
 *
 * @param {string} providerName
 * @param {object} msg
 * @param {object} ctx must expose `updateState({ ... })`
 */
export function applyResultMapping(providerName, msg, ctx) {
  const result = resolveStopReason(providerName, msg, ctx);
  const update = {
    success: result.success,
    stopReason: result.reason,
  };
  if (result.error) {
    update.error = result.error;
  }
  if (result.truncated) {
    update.truncated = true;
  }
  ctx.updateState(update);
  return result;
}
