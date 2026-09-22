/**
 * Last chance to learn what a killed CLI run actually cost.
 *
 * A CLI that reports usage only at the END of a turn (codex: `turn.completed`) reports
 * NOTHING when its child is killed mid-turn - Stop, inactivity, budget, or a crash -
 * however many model calls it already paid for. The run then reaches billing with zero
 * tokens, indistinguishable from a run where no model call ever happened, and is billed
 * nothing. Verified in prod: every codex chat a user ever stopped billed 0 credits,
 * including one that had made 21 tool calls, while the same chat allowed to finish billed
 * its real tokens.
 *
 * Adapters that can still find those numbers after the fact expose `recoverUsage(tmpDir)`,
 * reading the CLI's own on-disk accounting. Extracted here, away from the 1,200-line run
 * closure in server.mjs, so the rules below are exercised by tests rather than asserted by
 * reading the source.
 */

/**
 * @param {object} adapter - the CLI adapter for this run; may expose `recoverUsage`
 * @param {string} tmpDir - this run's temp dir, which is the CLI's HOME for it
 * @param {Array<object>} perCallUsages - what the CLI reported during the run
 * @returns {{promptTokens:number, completionTokens:number, cacheCreationInputTokens:number,
 *            cacheReadInputTokens:number, cachedTokens:number, reasoningTokens:number} | null}
 *          a usage entry to record, or null when there is nothing to recover
 */
export function recoverUnreportedUsage(adapter, tmpDir, perCallUsages) {
  // A value the CLI itself sent always wins. Deliberately NOT applied when something was
  // reported already, even though the on-disk total may be larger (a run killed in its
  // SECOND turn still loses that turn's share): mixing two accounting sources is how an
  // over-bill happens, and the case this closes - zero reported, which is every stop
  // before the first turn ends - is the one that was silently free.
  if (!Array.isArray(perCallUsages) || perCallUsages.length > 0) return null;
  if (!adapter || typeof adapter.recoverUsage !== 'function') return null;

  let recovered;
  try {
    recovered = adapter.recoverUsage(tmpDir);
  } catch (e) {
    // Best-effort by construction: the child is already dead and the run's outcome is
    // already decided, so a failure here must never change what the caller gets back.
    console.warn(`[BRIDGE] usage recovery failed (run will bill zero): ${e.message}`);
    return null;
  }
  if (!recovered) return null;

  const entry = {
    promptTokens: recovered.promptTokens || 0,
    completionTokens: recovered.completionTokens || 0,
    cacheCreationInputTokens: recovered.cacheCreationInputTokens || 0,
    cacheReadInputTokens: recovered.cacheReadInputTokens || 0,
    cachedTokens: recovered.cachedTokens || 0,
    reasoningTokens: recovered.reasoningTokens || 0,
  };
  // An adapter that answers with zeros is answering "nothing was spent", which is not a
  // recovery: recording it would claim knowledge we do not have.
  if (entry.promptTokens <= 0 && entry.completionTokens <= 0) return null;
  return entry;
}

/**
 * Apply a recovered entry to the run's accumulators, and report what the cumulative usage
 * becomes. Returns null when there was nothing to recover, so the caller logs only when
 * something actually happened.
 *
 * Lives here rather than in the run closure because the two mutations are what decide the
 * BILLED amount, not just whether a number was found: the response's cumulative totals are
 * read from `usage`, while the cache and reasoning breakdown is summed from
 * `perCallUsages`. Dropping the second one silently bills a cached codex turn at the full
 * input rate instead of the cached one - a real over-bill that no assertion about the
 * function's position in a file could catch.
 *
 * @param {object} adapter
 * @param {string} tmpDir
 * @param {Array<object>} perCallUsages - mutated in place, as the run closure owns it
 * @returns {{usage: {promptTokens:number, completionTokens:number}, entry: object} | null}
 */
export function applyRecoveredUsage(adapter, tmpDir, perCallUsages) {
  const entry = recoverUnreportedUsage(adapter, tmpDir, perCallUsages);
  if (!entry) return null;
  perCallUsages.push(entry);
  return {
    entry,
    usage: { promptTokens: entry.promptTokens, completionTokens: entry.completionTokens },
  };
}
