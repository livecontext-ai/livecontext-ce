/**
 * Pricing cache for the bridge - mirrors auth-service ModelPricingService.
 *
 * Fetches a snapshot from `GET /api/internal/auth/pricing/snapshot` and refreshes
 * lazily after a TTL. Falls back to env-supplied defaults when the snapshot is
 * unavailable so guards never crash a run because pricing is down.
 *
 * Cost formula (matches Java ModelCostCalculator):
 *   cost = ((inputRate * plainInput + cacheWriteRate * cacheWrite + cacheReadRate * cacheRead) / 1000)
 *        + (outputRate * completionTokens / 1000)
 *        + fixedCost
 * where the prompt is split into plain input / cache write / cache read by the
 * reporting provider's convention (`inputBreakdown`, JS twin of the Java
 * TokenUsageConventions.inputBreakdown). With no cache counters it is the plain
 * `inputRate * promptTokens` formula. cacheReadRate / cacheWriteRate come from the
 * snapshot (resolved by auth-service exactly as the ledger debits them). A row with NO
 * cache rate (a snapshot from an auth-service older than 2026-09-30) keeps the exact
 * pre-change formula instead of guessing a price, so a mixed-version rollout never moves
 * a guard. Before that change every prompt token was priced at the input rate and a
 * Claude Code turn (mostly cache reads) was projected at ~5x its real debit.
 */

import { gatewaySignedHeaders, withGatewaySignatureV2 } from './gatewayAuth.mjs';

// ─── Constants ──────────────────────────────────────────────────────────
// Centralised tunables. Source of truth for rates: auth-service DB via
// /api/internal/auth/pricing/snapshot. The fallback defaults below match
// auth-service ModelPricingService (1.0 / 4.0 per 1K tokens) so that when
// the snapshot is unavailable the budget guard errs on the expensive side
// rather than silently under-counting costs.
export const PRICING_DEFAULTS = Object.freeze({
  /** Refresh TTL for the cached pricing snapshot. */
  REFRESH_MS: 5 * 60 * 1000,
  /** Fallback input rate (USD per 1K tokens) - matches auth-service DEFAULT_INPUT_RATE. */
  INPUT_RATE_PER_1K: Number(process.env.BRIDGE_DEFAULT_INPUT_RATE_PER_1K || '1.0'),
  /** Fallback output rate (USD per 1K tokens) - matches auth-service DEFAULT_OUTPUT_RATE. */
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
              cacheReadRate: toPositiveOrNull(row.cacheReadRate),
              cacheWriteRate: toPositiveOrNull(row.cacheWriteRate),
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
    return this.costForUsage(provider, model, { promptTokens, completionTokens });
  }

  /**
   * Cache-aware cost of a usage report: each input class at its own price, split by
   * `inputBreakdown` in the reporting provider's convention. This is what the budget
   * guards use, so a cache read is projected at its cache price as the ledger debits it.
   *
   * @param {string} provider - the provider that REPORTED the counters (e.g. claude-code)
   * @param {string} model
   * @param {{promptTokens?:number, completionTokens?:number, cacheCreationTokens?:number,
   *          cacheReadTokens?:number, cachedTokens?:number}} usage
   * @returns {number}
   */
  costForUsage(provider, model, usage) {
    return this._priceUsage(provider, model, usage, false);
  }

  /**
   * What the same usage costs if it misses the cache: every token it read from the cache
   * is written again, at cacheWriteRate (1.25x input on Anthropic; the input rate for
   * providers billed on a cached subset). A guard projecting the next call from the last
   * call's cache mix alone (a read is ~0.1x input) would let one miss - a 5-minute cache
   * expiry during a long tool call - overdraw the balance by ~10x its projection.
   * Mirror of the Java ModelCostCalculator.computeCacheMissCost.
   */
  cacheMissCostForUsage(provider, model, usage) {
    return this._priceUsage(provider, model, usage, true);
  }

  /**
   * What a guard reserves for the next call missing the cache: `cacheMissCostForUsage` for
   * a provider whose prompt total carries its cached tokens (claude-code and every subset
   * reporter), 0 for the direct Anthropic API. Mirror of the Java
   * ModelCostCalculator.cacheMissReserve, which explains the split: the pre-change guards
   * already covered a miss wherever the prompt total contained the cache, and never
   * reserved anything for the Anthropic API's additive cache.
   */
  cacheMissReserveForUsage(provider, model, usage) {
    return promptTotalCarriesCachedTokens(provider)
      ? this.cacheMissCostForUsage(provider, model, usage)
      : 0;
  }

  _priceUsage(provider, model, usage, readsMissTheCache) {
    const u = usage || {};
    const row = this.rates.get(keyFor(provider, model));
    const inputRate = row ? row.inputRate : PRICING_DEFAULTS.INPUT_RATE_PER_1K;
    const outputRate = row ? row.outputRate : PRICING_DEFAULTS.OUTPUT_RATE_PER_1K;
    const fixed = row ? row.fixedCost : 0;
    const outputCost = round6(outputRate * (u.completionTokens || 0) / 1000);
    // Match Java ModelCostCalculator: round each subterm to 6 decimal places
    // (HALF_UP) before summing. Avoids drift vs the Java budget guards.
    if (!row || (!row.cacheReadRate && !row.cacheWriteRate)) {
      // No published cache rate: the pre-change formula, every prompt token at inputRate.
      return round6(inputRate * (u.promptTokens || 0) / 1000) + outputCost + fixed;
    }
    const cacheWriteRate = row.cacheWriteRate || inputRate;
    const cacheReadRate = readsMissTheCache ? cacheWriteRate : (row.cacheReadRate || inputRate);
    const input = inputBreakdown(provider, u);
    const inputCost = round6((inputRate * input.plainInput
      + cacheWriteRate * input.cacheWrite
      + cacheReadRate * input.cacheRead) / 1000);
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
        cacheReadRate: toPositiveOrNull(row.cacheReadRate),
        cacheWriteRate: toPositiveOrNull(row.cacheWriteRate),
      });
    }
    this.lastRefreshAt = Date.now();
  }
}

/**
 * How a provider lays its input tokens out - JS twin of the Java
 * `TokenUsageConventions.shapeOf` (same provider names, same three shapes). Driven by
 * the provider NAME, never by the numbers: a direct Anthropic call with a large plain
 * input and a small cache read is indistinguishable from an inclusive bridge total.
 *   - ADDITIVE_INCLUSIVE (claude-code): prompt = plain + cache write + cache read
 *   - ADDITIVE_EXCLUSIVE (anthropic, claude): prompt = plain, cache counted beside it
 *   - SUBSET (everyone else): the cached part is a subset of the prompt
 */
export const PROMPT_SHAPE = Object.freeze({
  ADDITIVE_EXCLUSIVE: 'ADDITIVE_EXCLUSIVE',
  ADDITIVE_INCLUSIVE: 'ADDITIVE_INCLUSIVE',
  SUBSET: 'SUBSET',
});

/** True when the provider's prompt total CONTAINS its cached tokens (every shape but the Anthropic API). */
export function promptTotalCarriesCachedTokens(provider) {
  return promptShapeOf(provider) !== PROMPT_SHAPE.ADDITIVE_EXCLUSIVE;
}

export function promptShapeOf(provider) {
  const p = String(provider || '').trim().toLowerCase();
  if (p === 'claude-code') return PROMPT_SHAPE.ADDITIVE_INCLUSIVE;
  if (p === 'anthropic' || p === 'claude') return PROMPT_SHAPE.ADDITIVE_EXCLUSIVE;
  return PROMPT_SHAPE.SUBSET;
}

/**
 * Split a usage report's input side into plain input / cache write / cache read, in the
 * reporting provider's convention. Mirror of the Java `TokenUsageConventions.decompose`;
 * `shared/contracts/budget-guard-fixtures.json` runs both on the same cases.
 *
 * @returns {{plainInput:number, cacheWrite:number, cacheRead:number}}
 */
export function inputBreakdown(provider, usage) {
  const u = usage || {};
  const prompt = Math.max(0, u.promptTokens || 0);
  const write = Math.max(0, u.cacheCreationTokens || 0);
  // The cached portion travels under either name depending on who reported it, and
  // never under both, so the larger is the one that was filled.
  const read = Math.max(Math.max(0, u.cacheReadTokens || 0), Math.max(0, u.cachedTokens || 0));
  switch (promptShapeOf(provider)) {
    case PROMPT_SHAPE.ADDITIVE_EXCLUSIVE:
      return { plainInput: prompt, cacheWrite: write, cacheRead: read };
    case PROMPT_SHAPE.ADDITIVE_INCLUSIVE:
      return { plainInput: Math.max(0, prompt - write - read), cacheWrite: write, cacheRead: read };
    default: {
      // Subset: whatever is not cached is plain input; the cached part is clamped to the
      // prompt so a provider that over-reports cannot drive plain input negative.
      const cached = Math.min(read, prompt);
      return { plainInput: prompt - cached, cacheWrite: write, cacheRead: cached };
    }
  }
}

/**
 * Parse an optional rate. `null` for missing, non-numeric and non-positive values: a
 * zero cache price would make cached input free, so it is read as "unknown" and the
 * caller falls back to the input rate.
 */
function toPositiveOrNull(v) {
  if (v === null || v === undefined) return null;
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : null;
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

/** Provider id the snapshot read is signed with (Java twin: PricingSnapshotClient). */
export const PRICING_SNAPSHOT_PROVIDER_ID = 'internal-pricing-snapshot-client';

/**
 * Snapshot fetcher that signs the request with the shared gateway HMAC (v1 headers plus the
 * v2 signature over method and URL). auth-service can require that signature on its whole
 * `/api/internal/auth/` prefix (`AUTH_INTERNAL_HMAC_REQUIRED_PATH`), and the bridge reads the
 * snapshot directly, without the gateway. A blank secret sends the request unsigned, as the
 * balance refresh does.
 *
 * @param {string} secretKey  `GATEWAY_SECRET_KEY`
 * @param {typeof fetch} [fetchImpl]  override for tests
 */
export function signedSnapshotFetcher(secretKey, fetchImpl = (...args) => fetch(...args)) {
  return async (url) => {
    const headers = withGatewaySignatureV2(
      { 'Accept': 'application/json',
        ...gatewaySignedHeaders({ secretKey, providerId: PRICING_SNAPSHOT_PROVIDER_ID }) },
      { secretKey, method: 'GET', url });
    const res = await fetchImpl(url, { headers });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  };
}

/** Singleton - most callers want the shared cache. */
export const sharedPricingCache = new PricingCache();
