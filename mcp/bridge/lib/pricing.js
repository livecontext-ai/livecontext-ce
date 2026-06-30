/**
 * Pricing cache for the bridge - mirrors auth-service ModelPricingService.
 *
 * Fetches a snapshot from `GET /api/internal/auth/pricing/snapshot` and refreshes
 * lazily after a TTL. Falls back to env-supplied defaults when the snapshot is
 * unavailable so guards never crash a run because pricing is down.
 *
 * Cost formula (matches Java ModelCostCalculator):
 *   cost = (inputRate  * promptTokens     / 1000)
 *        + (outputRate * completionTokens / 1000)
 *        + fixedCost
 */

// ─── Constants ──────────────────────────────────────────────────────────
// Centralised tunables. Source of truth for rates: auth-service DB via
// /api/internal/auth/pricing/snapshot. The fallback defaults below match
// auth-service ModelPricingService (0.1 / 0.3 per 1K tokens) so that when
// the snapshot is unavailable the budget guard errs on the expensive side
// rather than silently under-counting costs.
export const PRICING_DEFAULTS = Object.freeze({
  /** Refresh TTL for the cached pricing snapshot. */
  REFRESH_MS: 5 * 60 * 1000,
  /** Fallback input rate (USD per 1M tokens) - matches auth-service DEFAULT_INPUT_RATE. */
  INPUT_RATE_PER_1K: Number(process.env.BRIDGE_DEFAULT_INPUT_RATE_PER_1K || '1.0'),
  /** Fallback output rate (USD per 1M tokens) - matches auth-service DEFAULT_OUTPUT_RATE. */
  OUTPUT_RATE_PER_1K: Number(process.env.BRIDGE_DEFAULT_OUTPUT_RATE_PER_1K || '4.0'),
  /** Decimal precision used by both Java and JS cost rounding. */
  ROUND_DECIMALS: 6,
});

export class PricingCache {
  /**
   * @param {object} opts
   * @param {string} [opts.snapshotUrl] - Full URL to the auth-service snapshot endpoint.
   * @param {number} [opts.refreshMs]   - Refresh TTL in milliseconds.
   * @param {(url: string) => Promise<any>} [opts.fetcher] - Override for tests.
   */
  constructor(opts = {}) {
    this.snapshotUrl = opts.snapshotUrl
      || process.env.BRIDGE_PRICING_SNAPSHOT_URL
      || 'http://localhost:8083/api/internal/auth/pricing/snapshot';
    this.refreshMs = opts.refreshMs || PRICING_DEFAULTS.REFRESH_MS;
    this.fetcher = opts.fetcher || defaultFetcher;
    /** @type {Map<string, {inputRate:number,outputRate:number,fixedCost:number}>} */
    this.rates = new Map();
    this.lastRefreshAt = 0;
    this.version = null;
    /** Health flag - false after a failed refresh, true after a successful one. */
    this.healthy = true;
    /** Last error message - observable from /health for diagnostics. */
    this.lastError = null;
    /** In-flight refresh promise - dedupes concurrent calls. */
    this._refreshPromise = null;
  }

  /**
   * Refresh from auth-service if the TTL has elapsed. Best-effort, swallows errors.
   * Concurrent callers share a single in-flight HTTP request.
   */
  async refreshIfStale() {
    const now = Date.now();
    if (now - this.lastRefreshAt < this.refreshMs && this.lastRefreshAt > 0) {
      return;
    }
    if (this._refreshPromise) {
      return this._refreshPromise;
    }
    this._refreshPromise = (async () => {
      try {
        const snapshot = await this.fetcher(this.snapshotUrl);
        // Only consider the refresh successful if we got at least one rate row.
        // Empty/missing arrays would otherwise mark the cache "fresh" while leaving
        // it on env-default fallbacks for the entire refreshMs window.
        if (snapshot && Array.isArray(snapshot.rates) && snapshot.rates.length > 0) {
          const next = new Map();
          for (const row of snapshot.rates) {
            if (!row.provider || !row.model) continue;
            next.set(keyFor(row.provider, row.model), {
              inputRate: Number(row.inputRate) || 0,
              outputRate: Number(row.outputRate) || 0,
              fixedCost: Number(row.fixedCost) || 0,
              // V162: contextWindow / maxOutputTokens drive worstCaseSingleIter in
              // budgetGuards.TenantBudgetGuard. Preserve null/undefined distinctly
              // from 0 so the guard can detect "unknown ctx" and fail-closed under
              // BUDGET_GUARD_REQUIRE_CTX_WINDOW (Phase 1C).
              contextWindow: toIntOrNull(row.contextWindow),
              maxOutputTokens: toIntOrNull(row.maxOutputTokens),
            });
          }
          this.rates = next;
          this.version = snapshot.version || null;
          this.lastRefreshAt = Date.now();
          this.healthy = true;
          this.lastError = null;
        } else {
          // Treat as a soft failure: don't bump lastRefreshAt so the next caller retries.
          this.healthy = false;
          this.lastError = 'snapshot returned no rates';
          process.stderr.write(`[BRIDGE:pricing] snapshot returned no rates - keeping previous rates, will retry\n`);
        }
      } catch (e) {
        // Don't update lastRefreshAt → next call will retry.
        this.healthy = false;
        this.lastError = e.message;
        process.stderr.write(`[BRIDGE:pricing] snapshot refresh failed: ${e.message}\n`);
      } finally {
        this._refreshPromise = null;
      }
    })();
    return this._refreshPromise;
  }

  /**
   * Compute the credit cost for a usage tuple.
   * Falls back to environment defaults when no row matches.
   *
   * @param {string} provider
   * @param {string} model
   * @param {number} promptTokens
   * @param {number} completionTokens
   * @returns {number}
   */
  costFor(provider, model, promptTokens, completionTokens) {
    const row = this.rates.get(keyFor(provider, model));
    const inputRate = row ? row.inputRate : PRICING_DEFAULTS.INPUT_RATE_PER_1K;
    const outputRate = row ? row.outputRate : PRICING_DEFAULTS.OUTPUT_RATE_PER_1K;
    const fixed = row ? row.fixedCost : 0;
    // Match Java ModelCostCalculator: round each subterm to 6 decimal places
    // (HALF_UP) before summing. Avoids drift vs the Java budget guards.
    const inputCost = round6(inputRate * (promptTokens || 0) / 1000);
    const outputCost = round6(outputRate * (completionTokens || 0) / 1000);
    return inputCost + outputCost + fixed;
  }

  /**
   * Look up the model's context window (V162). Returns {@code null} when the
   * model is unknown or the snapshot row predates the column. Caller decides
   * policy - see {@code TenantBudgetGuard.check()}.
   */
  contextWindowFor(provider, model) {
    const row = this.rates.get(keyFor(provider, model));
    return row ? row.contextWindow : null;
  }

  /** Look up the model's max output tokens (V162). {@code null} when unknown. */
  maxOutputTokensFor(provider, model) {
    const row = this.rates.get(keyFor(provider, model));
    return row ? row.maxOutputTokens : null;
  }

  /**
   * Worst-case cost of a single iteration (V162) - the absolute upper bound
   * used by the guard to close step-function bursts. Returns {@code null}
   * when contextWindow or maxOutputTokens is unknown.
   */
  worstCaseSingleIter(provider, model) {
    const ctxWindow = this.contextWindowFor(provider, model);
    const maxOutput = this.maxOutputTokensFor(provider, model);
    if (!Number.isFinite(ctxWindow) || !Number.isFinite(maxOutput)) return null;
    return this.costFor(provider, model, ctxWindow, maxOutput);
  }

  /** Inject rows directly (used by tests and by request-time overrides from the backend). */
  primeFromRates(rates) {
    if (!Array.isArray(rates)) return;
    for (const row of rates) {
      if (!row || !row.provider || !row.model) continue;
      this.rates.set(keyFor(row.provider, row.model), {
        inputRate: Number(row.inputRate) || 0,
        outputRate: Number(row.outputRate) || 0,
        fixedCost: Number(row.fixedCost) || 0,
        contextWindow: toIntOrNull(row.contextWindow),
        maxOutputTokens: toIntOrNull(row.maxOutputTokens),
      });
    }
    this.lastRefreshAt = Date.now();
  }
}

/**
 * Parse an integer field that may be missing, null, or non-numeric. Returns
 * {@code null} for any non-finite input - distinct from 0 so callers can
 * detect "unknown" and fail-closed.
 */
function toIntOrNull(v) {
  if (v === null || v === undefined) return null;
  const n = Number(v);
  return Number.isFinite(n) ? Math.trunc(n) : null;
}

function keyFor(provider, model) {
  return `${(provider || '').toLowerCase()}::${(model || '').toLowerCase()}`;
}

/** Round to 6 decimal places (HALF_UP) - matches Java BigDecimal divide(.., 6, HALF_UP). */
function round6(n) {
  return Math.round(n * 1e6) / 1e6;
}

async function defaultFetcher(url) {
  // Node 18+ has global fetch.
  const res = await fetch(url, { headers: { 'Accept': 'application/json' } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.json();
}

/** Singleton - most callers want the shared cache. */
export const sharedPricingCache = new PricingCache();
