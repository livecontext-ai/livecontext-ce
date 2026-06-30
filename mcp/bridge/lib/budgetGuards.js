/**
 * Bridge-side budget guards - JS twins of the Java AgentBudgetGuard / TenantBudgetGuard.
 *
 * Both expose a single `check(usage)` method that returns:
 *   { proceed: true }                       - keep streaming
 *   { proceed: false, scope, reason }       - trip the budgetExhausted sentinel
 *
 * The `usage` object is the running total observed from CLI usage events:
 *   { promptTokens, completionTokens, iterations, elapsedMs, provider, model }
 *
 * The bridge polls these between iterations (or on every usage event) and SIGTERMs
 * the child process when one denies, then sets ctx.state.budgetExhausted = true so
 * stopReasonMapper resolves to BUDGET_EXHAUSTED.
 *
 * V162 / -11305 incident - defense-in-depth (mirror of Java guards):
 *   • Layer A (this file)             - perimeter ceiling on the bridge.
 *   • Layer B (Java TenantBudgetGuard) - early reject in agent-service.
 * Projection here uses
 *   max(growthProj, lastDeltaProj × safety, worstCaseSingleIter)
 * - capturing both gradual ramp-up and step-function bursts that pure-average
 * projection misses (the actual failure mode that drove -11305). Java/JS parity
 * is locked via {@code shared/contracts/budget-guard-fixtures.json}.
 *
 * An atomic per-turn reservation (Layer C) was prototyped on conversation-service
 * then reverted: A+B alone close the incident on the bridge path, and a small
 * overshoot (≤ 1 iteration) is acceptable in exchange for a simpler call path.
 */

const LAST_DELTA_SAFETY_FACTOR = 2.0;

/**
 * Per-agent budget guard. Projects the next iteration's cost from the running average
 * and denies if `consumedSoFar + projectedNext > budget`.
 */
export class AgentBudgetGuard {
  /**
   * @param {object} opts
   * @param {number} opts.budget         - Max credits the agent may spend (>0 enables guard).
   * @param {number} [opts.consumedSoFar=0] - Credits already consumed before this run.
   * @param {import('./pricing.js').PricingCache} opts.pricing
   */
  constructor({ budget, consumedSoFar, pricing }) {
    this.budget = Number(budget) || 0;
    /** Credits consumed in prior runs within the current budget window. */
    this.consumedSoFar = Number(consumedSoFar) || 0;
    this.pricing = pricing;
    this._lastPromptTokens = 0;
    this._lastCompletionTokens = 0;
  }

  /** True when configured with a positive budget. */
  get enabled() { return this.budget > 0; }

  /**
   * @param {{promptTokens:number, completionTokens:number, iterations:number, provider:string, model:string}} usage
   * @returns {{proceed:boolean, scope?:string, reason?:string}}
   */
  check(usage) {
    if (!this.enabled) return { proceed: true };
    const runCost = this.pricing.costFor(
      usage.provider, usage.model, usage.promptTokens, usage.completionTokens);
    const totalConsumed = this.consumedSoFar + runCost;
    if (totalConsumed > this.budget) {
      return {
        proceed: false,
        scope: 'agent',
        reason: `agent budget exhausted (prior=${this.consumedSoFar.toFixed(4)} + run=${runCost.toFixed(4)} = ${totalConsumed.toFixed(4)} / ${this.budget})`,
      };
    }
    // V162: max(growth, lastDelta × safety, worstCaseSingleIter). Skip projection
    // on the first iteration: the running average == runCost itself, which would
    // deny any single iteration above half remaining budget - even when the next
    // call could be smaller. Wait until ≥ 2 samples before trusting projection.
    const iters = Math.max(1, usage.iterations || 1);
    const lastDeltaPrompt = Math.max(0, (usage.promptTokens || 0) - this._lastPromptTokens);
    const lastDeltaCompletion = Math.max(0, (usage.completionTokens || 0) - this._lastCompletionTokens);
    this._lastPromptTokens = usage.promptTokens || 0;
    this._lastCompletionTokens = usage.completionTokens || 0;

    if (iters >= 2) {
      const avgPrompt = (usage.promptTokens || 0) / iters;
      const avgCompletion = (usage.completionTokens || 0) / iters;
      const growthProj = this.pricing.costFor(
        usage.provider, usage.model, avgPrompt, avgCompletion);
      const lastDeltaProj = this.pricing.costFor(
        usage.provider, usage.model, lastDeltaPrompt, lastDeltaCompletion) * LAST_DELTA_SAFETY_FACTOR;
      let projectedNext = Math.max(growthProj, lastDeltaProj);
      const worstCase = this.pricing.worstCaseSingleIter(usage.provider, usage.model);
      if (Number.isFinite(worstCase)) {
        projectedNext = Math.max(projectedNext, worstCase);
      }
      if (totalConsumed + projectedNext > this.budget) {
        return {
          proceed: false,
          scope: 'agent',
          reason: `agent budget would be exceeded by next iteration (${(totalConsumed + projectedNext).toFixed(4)} / ${this.budget}, growth=${growthProj.toFixed(2)}, lastDelta=${lastDeltaProj.toFixed(2)}, worstCase=${Number.isFinite(worstCase) ? worstCase.toFixed(2) : 'unknown'})`,
        };
      }
    }
    return { proceed: true };
  }
}

/**
 * Tenant-level budget guard. Tracks the credits consumed by THIS run against the
 * tenant's remaining balance fetched from auth-service. The balance is refreshed
 * lazily (every N iterations) to avoid hammering the credit endpoint.
 */
export class TenantBudgetGuard {
  /**
   * @param {object} opts
   * @param {number}   opts.initialBalance     - Tenant balance at the start of the run.
   * @param {import('./pricing.js').PricingCache} opts.pricing
   * @param {() => Promise<number>} [opts.refreshBalance] - Optional refresh hook.
   * @param {number}   [opts.refreshEveryNIters=5]
   * @param {boolean}  [opts.requireCtxWindow=false] - P1.3 fail-closed flag mirror
   *                   of BUDGET_GUARD_REQUIRE_CTX_WINDOW. When true, deny iterations
   *                   on models without contextWindow/maxOutputTokens metadata.
   *                   Default false during migration window so legacy snapshots
   *                   don't self-DoS the chat path.
   */
  constructor({ initialBalance, pricing, refreshBalance, refreshEveryNIters = 5, requireCtxWindow = false }) {
    this.balance = Number(initialBalance) || 0;
    this.pricing = pricing;
    this.refreshBalance = refreshBalance || null;
    this.refreshEveryNIters = refreshEveryNIters;
    this.requireCtxWindow = requireCtxWindow;
    this._lastRefreshIter = 0;
    this._lastPromptTokens = 0;
    this._lastCompletionTokens = 0;
  }

  /** True when configured with a positive balance and pricing cache. */
  get enabled() { return this.balance > 0 && !!this.pricing; }

  /**
   * @param {{promptTokens:number, completionTokens:number, iterations:number, provider:string, model:string}} usage
   * @returns {Promise<{proceed:boolean, scope?:string, reason?:string}>}
   */
  async check(usage) {
    if (!this.enabled) return { proceed: true };

    const consumed = this.pricing.costFor(
      usage.provider, usage.model, usage.promptTokens, usage.completionTokens);

    // Adaptive refresh: when burn rate > 70% of balance, refresh every iter so a
    // stale snapshot can't hide a near-empty wallet across 5 iters of bursts.
    const burnRate = this.balance > 0 ? consumed / this.balance : 1;
    const refreshN = burnRate > 0.7 ? 1 : this.refreshEveryNIters;
    const iters = usage.iterations || 0;
    if (this.refreshBalance && iters - this._lastRefreshIter >= refreshN) {
      try {
        const fresh = await this.refreshBalance();
        if (typeof fresh === 'number' && Number.isFinite(fresh)) {
          this.balance = fresh;
        }
      } catch (e) {
        process.stderr.write(`[BRIDGE:tenantGuard] refresh failed: ${e.message}\n`);
      }
      this._lastRefreshIter = iters;
    }

    if (consumed >= this.balance) {
      return {
        proceed: false,
        scope: 'tenant',
        reason: `tenant balance exhausted (${consumed.toFixed(4)} / ${this.balance})`,
      };
    }

    // V162 fail-closed (P1.3): when BUDGET_GUARD_REQUIRE_CTX_WINDOW is on AND the
    // model has no contextWindow/maxOutputTokens, deny rather than silently fall
    // through to growth-only projection (which would let unknown models bypass the
    // worstCase ceiling). Mirror of Java TenantBudgetGuard's flag-on path.
    const ctxWindow = this.pricing.contextWindowFor(usage.provider, usage.model);
    const maxOutput = this.pricing.maxOutputTokensFor(usage.provider, usage.model);
    if (this.requireCtxWindow && (!Number.isFinite(ctxWindow) || !Number.isFinite(maxOutput))) {
      return {
        proceed: false,
        scope: 'tenant',
        reason: `missing_ctx_window for ${usage.provider}/${usage.model} - fail-closed under BUDGET_GUARD_REQUIRE_CTX_WINDOW`,
      };
    }

    // Track last-iteration delta for projection. Mirror of Java
    // TenantBudgetGuard / IterationContext.lastIterationPromptTokens.
    const lastDeltaPrompt = Math.max(0, (usage.promptTokens || 0) - this._lastPromptTokens);
    const lastDeltaCompletion = Math.max(0, (usage.completionTokens || 0) - this._lastCompletionTokens);
    this._lastPromptTokens = usage.promptTokens || 0;
    this._lastCompletionTokens = usage.completionTokens || 0;

    // Projection: max(growth, lastDelta × safety, worstCaseSingleIter). See class doc.
    const safeIters = Math.max(1, iters);
    const avgPrompt = (usage.promptTokens || 0) / safeIters;
    const avgCompletion = (usage.completionTokens || 0) / safeIters;
    const growthProj = this.pricing.costFor(
      usage.provider, usage.model, avgPrompt, avgCompletion);
    const lastDeltaProj = this.pricing.costFor(
      usage.provider, usage.model, lastDeltaPrompt, lastDeltaCompletion) * LAST_DELTA_SAFETY_FACTOR;
    let projectedNext = Math.max(growthProj, lastDeltaProj);
    const worstCase = this.pricing.worstCaseSingleIter(usage.provider, usage.model);
    if (Number.isFinite(worstCase)) {
      projectedNext = Math.max(projectedNext, worstCase);
    }
    if (consumed + projectedNext > this.balance) {
      return {
        proceed: false,
        scope: 'tenant',
        reason: `tenant balance ${this.balance} would be exceeded (consumed=${consumed.toFixed(4)} + next=${projectedNext.toFixed(4)} [growth=${growthProj.toFixed(2)}, lastDelta=${lastDeltaProj.toFixed(2)}, worstCase=${Number.isFinite(worstCase) ? worstCase.toFixed(2) : 'unknown'}])`,
      };
    }
    return { proceed: true };
  }
}

/**
 * Compose tenant + agent guards. Tenant runs first (always wins). Returns the first
 * deny encountered, otherwise `{proceed:true}`. Mirrors PreIterationGuard.chain.
 */
export function chainBudgetGuards(tenantGuard, agentGuard) {
  return async function check(usage) {
    if (tenantGuard && tenantGuard.enabled) {
      const r = await tenantGuard.check(usage);
      if (!r.proceed) return r;
    }
    if (agentGuard && agentGuard.enabled) {
      const r = agentGuard.check(usage);
      if (!r.proceed) return r;
    }
    return { proceed: true };
  };
}
