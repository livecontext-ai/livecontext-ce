/**
 * Pre-flight "what will this model cost me?" for the model pickers.
 *
 * <p><b>Why the numbers are not computed here.</b> The coefficients come from
 * {@code GET /api/credits/estimate-basis}. The margin lever has exactly one home
 * (auth-service `billing.llm.cloud-multiplier`), so a picker that restated it
 * would quote a price the ledger does not charge the day it moves. The server
 * folds the multiplier and the profile's token workload into ONE coefficient per
 * rate, which also keeps a field named "multiplier" off the wire. This module
 * only does the multiply-add the backend authorises.
 *
 * <p><b>Why the arithmetic is safe to do client-side.</b> The billing formula is
 * a sum over four DISJOINT token classes, each at its own published rate, so it
 * is a multiply-add over four coefficients and four rates. It used to be two,
 * because the profiles carried no cache tokens; that under-stated a chat by about
 * half, since a cache WRITE on Anthropic costs 1.25x the input rate and a real
 * message writes tens of thousands of tokens into the cache. The backend pins the
 * equivalence against the real billing code (`LlmCostProfileFormulaTest`,
 * `LlmCostEstimateServiceTest`); it is the contract this file relies on.
 *
 * <p><b>Resolving the two cache rates.</b> A model is priced by its OWN published
 * cache price wherever the catalogue has one. Where it has none, the rate comes
 * from `cacheFallback`, the per-provider weights the ledger itself falls back to
 * and which the same response publishes. They are a cost basis, set to the
 * providers' own published ratios, and they do not carry the platform's margin,
 * which stays folded into the coefficients; they are still operator levers, so an
 * install that moved one would be publishing that choice. Inventing a
 * fallback here instead (the input rate) over-stated a conversation's largest term
 * by up to ten times, on the 32% of the catalogue that publishes no cache price.
 *
 * <p><b>And the model that does not cache at all.</b> Its prompt is re-sent in full
 * every turn, so every token costs the input rate and no cache rate of any kind
 * applies. The FORM of its estimate is therefore the two-coefficient one this file
 * computed before it knew about caching - but not the figure: the profile now
 * describes a conversation of 177,810 prompt tokens where the cache-free one
 * described 15,000, so a non-caching model's quote rose by about twelve times. It
 * is the class that moved most, not the class that did not move, and that is the
 * real cost of running a whole agent context on a model with no cache.
 */

/**
 * One profile as the backend publishes it: the factor each of the model's rates
 * (USD per 1M tokens) is multiplied by to reach credits.
 *
 * The two cache coefficients arrived on 2026-09-16. A profile that declares no
 * cache work publishes them as zero, so the extra terms vanish and the result is
 * identical to the two-coefficient contract that came before.
 */
export interface LlmCostProfile {
  inputCoefficient: number;
  outputCoefficient: number;
  cacheWriteCoefficient?: number;
  cacheReadCoefficient?: number;
}

/**
 * How to price a cache class the catalogue has no price for, for one billing
 * provider. Mirrors `ModelPricingService.CacheRateFallback`.
 *
 * The weights multiply the model's INPUT rate. `modelCacheWritePriceApplies` is the
 * part that is not a fallback at all: only the Anthropic family is billed off a
 * cache-write counter, so everywhere else a first send is plain prompt input and a
 * model's published write price is never charged - quoting it would name a price
 * nobody pays.
 */
export interface CacheRateFallback {
  cacheWriteWeight: number;
  cacheReadWeight: number;
  modelCacheWritePriceApplies: boolean;
}

/** Response of `GET /api/credits/estimate-basis`. */
export interface ModelCostBasis {
  /** False where the install does not meter credits (CE): show nothing. */
  enabled: boolean;
  profiles: Record<string, LlmCostProfile>;
  /** Keyed by billing provider, with `'*'` for one this install does not know. */
  cacheFallback?: Record<string, CacheRateFallback>;
  /**
   * What the caller's OWN provider keys change about the price of a turn. Present only when
   * they hold a key that would actually serve AND their plan lets it serve: the server
   * applies the same plan gate the run applies, so this block appearing IS the statement
   * that the next turn takes that route.
   */
  ownKey?: OwnKeyBasis;

  /**
   * The own-key flat fee per tier as a PRICE LIST, published to every caller.
   *
   * Not the same question as {@link ownKey}, which says whether the NEXT call takes that
   * route and is therefore absent for anyone who has not saved a key yet - exactly the
   * reader deciding whether to save one. A settings panel advertising the four numbers
   * needs them before the first key exists; a model row quoting one needs to know the
   * route, so it keeps reading `ownKey`. Both come from one server-side ladder, so the
   * advertised price and the quoted one cannot drift.
   */
  ownKeyFeeByTier?: Record<string, number>;

  /**
   * Per-provider correction to the coefficients above, keyed by billing provider.
   * Absent, empty, or missing an entry all mean 1.
   *
   * The coefficients fold in the platform's GLOBAL margin lever and name no provider,
   * which is exactly what keeps the margin off the wire as a number. A provider billed
   * on a lever of its own therefore needs this factor, or the figure rendered here is
   * not the figure the ledger debits - the one property this whole module exists to
   * hold.
   *
   * No install currently publishes one: the platform bills every provider at a single
   * margin, the decision model included, so this map arrives empty and every quote is
   * the coefficient alone. It is honoured anyway because the day an operator sets
   * `billing.llm.provider-multipliers`, a picker that ignored it would quote a price
   * nobody is charged, and that failure is silent.
   */
  providerScales?: Record<string, number>;
}

export interface OwnKeyBasis {
  /** Providers whose saved key serves the next call, lower-case. */
  providers: string[];
  /** Flat credits per turn, by model tier. */
  feeByTier: Record<string, number>;
}

/**
 * The flat fee per turn this model's tier carries on the caller's own key, or null when it
 * does not run on one. A CEILING, not the charge: see {@link ownKeyChargeFor}.
 */
export function ownKeyFeeFor(
  model: { provider?: string | null; tier?: string | null },
  basis: ModelCostBasis | null,
): number | null {
  const ownKey = basis?.ownKey;
  if (!ownKey || !model.provider) return null;
  if (!ownKey.providers.includes(model.provider.toLowerCase())) return null;
  // An unpriced model carries no tier; the backend publishes the unknown-tier fee for it.
  const fee = ownKey.feeByTier[model.tier ?? 'unknown'] ?? ownKey.feeByTier.unknown;
  return typeof fee === 'number' && fee >= 0 ? fee : null;
}

/** What one turn on the caller's own key will cost, and which of the two rules set it. */
export interface OwnKeyCharge {
  credits: number;
  /**
   * True when the MEDIAN turn of this profile would cost less than the flat fee, so the cap
   * decides and the figure moves with the length of the turn. False when the fee decides,
   * which is the flat number for any turn at or above that median. Neither half is a promise
   * for one specific turn: see {@link ownKeyChargeFor}.
   */
  estimated: boolean;
}

/**
 * What the ledger will debit for one turn of `profileId` on the caller's own key, or null
 * when this model does not run on one.
 *
 * <p><b>Why this is not just the fee.</b> A flat fee prices the AVERAGE turn of its tier, and
 * a quarter of real turns on the two cheap tiers are shorter than their own fee, so the
 * backend debits `min(fee, consumption)` rather than letting your own key cost MORE than not
 * bringing it. Quoting the bare fee would therefore over-state those turns, and over-state
 * EVERY classify or guardrail call, which costs a fraction of a credit whatever the tier.
 *
 * <p><b>What it cannot promise.</b> The rule is the ledger's, but the operands differ: the
 * ledger caps against the tokens the turn ACTUALLY moved, and this caps against the profile's
 * median workload, which is all a picker can know before the turn exists. So on a tier whose
 * fee sits below that median the figure reads as the flat fee and a short turn will still be
 * billed less. It is the same direction of error as every other estimate here, and it is never
 * an under-quote: the debit can only come in at or below what this says.
 */
export function ownKeyChargeFor(
  model: { provider?: string | null; tier?: string | null; id?: string | null },
  rates: ModelRates | undefined | null,
  basis: ModelCostBasis | null,
  profileId: CostProfileId,
): OwnKeyCharge | null {
  const fee = ownKeyFeeFor(model, basis);
  if (fee === null) return null;
  // The provider and the model have to travel: the platform estimate is scaled by
  // `providerScales`, and the cap is only meaningful against the SAME number the row
  // displays. Comparing the fee to an unscaled estimate quotes a cap the ledger never
  // applies, and it does so silently, because both halves are plausible credit figures.
  const platform = estimateModelCredits(rates, basis, profileId, model.provider, model.id);
  if (platform === null || platform >= fee) return { credits: fee, estimated: false };
  return { credits: platform, estimated: true };
}

/** The key `cacheFallback` publishes for a provider with no family of its own. */
const DEFAULT_FALLBACK_KEY = '*';

/**
 * Whether the platform's margin multiplier applies to this pair at all.
 *
 * Mirrors `ModelPricingService.usesCloudLlmBillingMultiplier`: these are billed per unit
 * against a platform credential rather than per token, so the ledger charges them with no
 * multiplier of any kind. Kept in step by hand, which is why it is written out rather than
 * folded into a condition: a divergence here quotes a price nobody is charged.
 */
function usesBillingMultiplier(provider: string, model?: string): boolean {
  const p = provider.toLowerCase();
  if (p === 'websearch' || p === 'stability-ai') return false;
  const m = (model ?? '').toLowerCase();
  return !m.includes('image') && !m.startsWith('dall-e');
}

/**
 * What an install that has not published a fallback yet gets: no discount on either
 * class, which is the input rate for both and therefore the pre-cache behaviour.
 * Never a guessed weight - a wrong discount is worse than no discount.
 */
const NO_CACHE_DISCOUNT: CacheRateFallback = {
  cacheWriteWeight: 1,
  cacheReadWeight: 1,
  modelCacheWritePriceApplies: false,
};

/**
 * Which shape of work a picker is pricing. Mirrors `LlmCostProfile` in
 * auth-service; the string values ARE the keys of the published `profiles` map.
 */
export type CostProfileId = 'chatConversation' | 'guardrailCheck' | 'classifyStep';

/**
 * A model's list rates, as the catalogue serves them (USD per 1M tokens).
 *
 * `cacheRead` / `cacheWrite` are optional because most of the catalogue's 813
 * active models publish a cache read price and far fewer publish a write price.
 * A missing one is resolved through `provider` and the published `cacheFallback`,
 * never by assuming the input rate - see `resolveCacheRates`.
 */
export interface ModelRates {
  input?: number;
  output?: number;
  cacheRead?: number;
  cacheWrite?: number;
  /**
   * The billing provider, which decides HOW a cache rate is resolved (see
   * `cacheFallback`): the same missing price costs a tenth of input on Anthropic
   * and half of it on OpenAI. Absent, the model is priced with no cache discount.
   */
  provider?: string;
  /**
   * Whether the model caches prompts at all. One that does not re-sends its whole
   * prompt every turn at the input rate, so no cache rate applies to it - and a
   * catalogue row publishing a REAL (positive) cache price is taken as caching
   * whatever this flag says, because a price for a thing is better evidence than a
   * flag about it. A stored 0 is not a price, so there the flag is all there is.
   */
  supportsPromptCaching?: boolean;
}

/**
 * A published cache price, or null when the catalogue has none.
 *
 * <p>Zero counts as NONE, which is the one place this differs from an ordinary
 * "is it a number" test and is not a style choice: the ledger treats a
 * non-positive stored cache rate as unknown too (`ModelPricingService.rateFor`
 * tests `signum() > 0`), and the mirror that fills these columns refuses to store
 * a 0 for the same reason - it would make cached input free. Reading 0 as a real
 * price here would quote 138,200 cache-read tokens at nothing while the ledger
 * charged the family rate for them.
 */
function publishedRate(value: number | undefined): number | null {
  return typeof value === 'number' && value > 0 ? value : null;
}

/**
 * The two cache rates for this model, resolved exactly as the ledger resolves
 * them: the model's own price where the catalogue has one, the provider's
 * published fallback weight where it does not, and the plain input rate for a
 * model that does not cache at all.
 */
function resolveCacheRates(
  rates: ModelRates,
  inputRate: number,
  basis: ModelCostBasis
): { cacheWriteRate: number; cacheReadRate: number } {
  const publishedRead = publishedRate(rates.cacheRead);
  const publishedWrite = publishedRate(rates.cacheWrite);

  // A row carrying a cache price of EITHER class caches, whatever the capability
  // flag says: the price is the stronger evidence, and a catalogue row that has one
  // and no flag is the common shape. With neither, the model is priced as if it
  // re-sent its whole prompt, which is the conservative answer.
  const caches = rates.supportsPromptCaching === true
    || publishedRead !== null
    || publishedWrite !== null;
  if (!caches) {
    return { cacheWriteRate: inputRate, cacheReadRate: inputRate };
  }

  // Lower-cased because the server keys its families that way and the catalogue
  // does not promise a case: an unmatched provider would silently fall to the
  // no-discount entry and quote a cached token at full price.
  const fallback = basis.cacheFallback?.[rates.provider?.toLowerCase() ?? '']
    ?? basis.cacheFallback?.[DEFAULT_FALLBACK_KEY]
    ?? NO_CACHE_DISCOUNT;

  return {
    cacheWriteRate: fallback.modelCacheWritePriceApplies && publishedWrite !== null
      ? publishedWrite
      : inputRate * fallback.cacheWriteWeight,
    cacheReadRate: publishedRead !== null
      ? publishedRead
      : inputRate * fallback.cacheReadWeight,
  };
}

/**
 * Credits one unit of `profileId` would cost on a model billed at `rates`.
 * Returns null when anything needed is missing (unpriced model, CE, a profile
 * the backend did not publish) so the caller can simply render nothing.
 */
export function estimateModelCredits(
  rates: ModelRates | undefined | null,
  basis: ModelCostBasis | undefined | null,
  profileId: CostProfileId,
  provider?: string | null,
  model?: string | null
): number | null {
  if (!basis?.enabled) return null;
  const profile = basis.profiles?.[profileId];
  if (!profile
      || typeof profile.inputCoefficient !== 'number'
      || typeof profile.outputCoefficient !== 'number') {
    return null;
  }
  if (!rates) return null;
  const inputRate = rates.input;
  const outputRate = rates.output;
  if (typeof inputRate !== 'number' || typeof outputRate !== 'number') return null;
  // A zero-rate row is a real answer (a free model), a negative one is catalogue
  // garbage (the openrouter/auto router's "-1" sentinel) and must not be shown.
  if (inputRate < 0 || outputRate < 0) return null;

  const { cacheWriteRate, cacheReadRate } = resolveCacheRates(rates, inputRate, basis);

  const credits = inputRate * profile.inputCoefficient
    + cacheWriteRate * (profile.cacheWriteCoefficient ?? 0)
    + cacheReadRate * (profile.cacheReadCoefficient ?? 0)
    + outputRate * profile.outputCoefficient;

  return credits * providerScale(basis, rates, provider, model);
}

/**
 * The factor this provider's bill differs by, or 1 when it is billed on the global
 * lever like every other.
 *
 * A non-finite or non-positive published factor is ignored rather than applied: it
 * could only come from a malformed payload, and multiplying a price by it would show a
 * free or negative estimate, which reads as a promise.
 */
function providerScale(
  basis: ModelCostBasis,
  rates: ModelRates,
  provider?: string | null,
  model?: string | null,
): number {
  // Falls back to the provider the RATES already carry, so a call site that forgets the
  // argument is still correct: the alternative is a silent under-quote that looks fine.
  const resolved = provider ?? rates.provider;
  if (!resolved) return 1;
  // The ledger asks this FIRST and skips the multiplier entirely for these, so applying a
  // factor to them here would quote a price it never charges. Not reachable for a decision
  // model, which is none of them; it is the trap the SECOND overridden provider walks into.
  if (!usesBillingMultiplier(resolved, model ?? undefined)) return 1;
  const scale = basis.providerScales?.[resolved.toLowerCase()];
  if (typeof scale !== 'number' || !Number.isFinite(scale) || scale <= 0) return 1;
  return scale;
}

/**
 * Round an estimate to something a reader can hold in their head. It is an
 * estimate of a median, so precision past the leading digits would be false
 * confidence: under 10 credits keeps one decimal (a classify step at 3.4 is
 * meaningfully different from 0.4), 10 and above rounds to a whole credit and
 * 1,000 and above to the nearest ten.
 */
export function roundCreditEstimate(credits: number): number {
  if (credits < 10) return Math.round(credits * 10) / 10;
  if (credits < 1000) return Math.round(credits);
  return Math.round(credits / 10) * 10;
}

/**
 * Formatted estimate for display, in the app locale, or null when there is
 * nothing to show. Never returns "0": a model too cheap to round to a tenth of
 * a credit is shown as "<0.1" by the caller's message, not as free.
 */
export function formatCreditEstimate(
  rates: ModelRates | undefined | null,
  basis: ModelCostBasis | undefined | null,
  profileId: CostProfileId,
  locale: string,
  provider?: string | null,
  model?: string | null
): string | null {
  const credits = estimateModelCredits(rates, basis, profileId, provider, model);
  if (credits === null) return null;
  return formatCreditAmount(credits, locale);
}

/**
 * A credit figure as a reader should see it: rounded per {@link roundCreditEstimate} and
 * grouped in the APP locale. Shared with the own-key charge, which is the same number in the
 * same units and must not round differently.
 */
export function formatCreditAmount(credits: number, locale: string): string {
  const rounded = roundCreditEstimate(credits);
  if (rounded <= 0) return credits > 0 ? '<0.1' : '0';
  return rounded.toLocaleString(locale);
}
