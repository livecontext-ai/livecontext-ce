import type { GenerationModel, GenerationPriceModifier } from '@/lib/api/orchestrator/generation.service';

/**
 * What the CHOICES in a call do to the rate its model is published at.
 *
 * <p><b>Why this arithmetic exists on the client at all.</b> A published price
 * scales on one dimension, the size of the call, and the quote endpoint prices
 * that. Everything else the reader picks - a higher resolution, a reference
 * image the provider charges to read - is a factor the server derives from the
 * parameters at billing time, and the server cannot derive it for a form that
 * has not been submitted. So the surface computes it from the SAME declared
 * table the biller reads (`price.modifiers`, shipped on the model row) and
 * sends it with the quote.
 *
 * <p>What travels is the rule, not a number the server trusts: the amount
 * actually charged is resolved again from the real parameters, so a surface
 * that got this wrong would only misquote a price to itself. Keeping the two
 * implementations on one declared table is what stops that happening at all.
 *
 * <p>Mirrors `GenerationSpec.Price.factorFor` in catalog-service, rule for
 * rule. The two are pinned by their own tests rather than by a shared runtime,
 * because one is Java on the billing path and the other is TypeScript in a
 * browser; what they share is the table.
 */

/** A call at the model's published rate. */
export const BASE_RATE = 1;

/**
 * The form a value is looked up under, so the seed's key and whatever is in
 * the form agree however each was written.
 *
 * <p>Numbers compare as numbers: a table keyed `5` and a field holding `5.0`
 * are one choice, and a miss here would quote the reference tier for a call
 * the server bills at the expensive one - the two numbers on screen and on the
 * invoice would then differ with nothing to explain why.
 */
function normalizeKey(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  const raw = String(value).trim();
  if (raw === '') return null;
  const asNumber = Number(raw);
  if (Number.isFinite(asNumber) && raw !== '') {
    // Render without an exponent or trailing zeros, the way the Java side's
    // BigDecimal.stripTrailingZeros().toPlainString() does.
    return stripNumber(raw, asNumber);
  }
  return raw.toLowerCase();
}

/**
 * `5.0` -> `5`, `1e3` -> `1000`, keeping the precision the input actually had.
 *
 * <p>This mirrors Java's {@code BigDecimal.stripTrailingZeros().toPlainString()}
 * for every value a price table can hold. It diverges above ~1e21, where
 * JavaScript renders an exponent and Java does not; a modifier key is a
 * resolution, a quality tier or a small count, and the parser caps every factor
 * at 100, so nothing that far out can reach here. Said rather than left implied,
 * because "mirrors it exactly" would be the stronger claim and it is not true.
 */
function stripNumber(raw: string, parsed: number): string {
  if (Number.isInteger(parsed)) return String(parsed);
  // toString drops trailing zeros already; the raw string is only used when
  // it carries more precision than a double can hold, which no price does.
  return String(parsed);
}

/** How many files a slot was given. One file is one; nothing is none. */
function countFiles(value: unknown): number {
  if (value === null || value === undefined) return 0;
  if (Array.isArray(value)) {
    return value.filter((item) => item !== null && item !== undefined && String(item).trim() !== '').length;
  }
  if (typeof value === 'object') return 1;
  return String(value).trim() === '' ? 0 : 1;
}

function toNumber(value: number | string | undefined): number | null {
  if (value === undefined || value === null) return null;
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** One modifier's contribution for one supplied value. */
function factorOf(modifier: GenerationPriceModifier, supplied: unknown): number {
  const perFile = toNumber(modifier.per_file);
  if (perFile !== null) {
    const count = countFiles(supplied);
    return count <= 0 ? BASE_RATE : BASE_RATE + perFile * count;
  }
  const key = normalizeKey(supplied);
  if (key === null || !modifier.by_value) return BASE_RATE;
  // A value with no entry bills at the reference tier. The seed gate refuses a
  // table whose parameter has no closed list of allowed values, and refuses one
  // that does not price every value in it, so the only way here is a value the
  // model itself would refuse.
  const found = Object.entries(modifier.by_value)
    .find(([value]) => normalizeKey(value) === key);
  const factor = found ? toNumber(found[1]) : null;
  return factor !== null && factor > 0 ? factor : BASE_RATE;
}

/**
 * The factor this call is charged at, from what is currently in the form.
 *
 * @param model  the selected model, or null while the catalogue loads
 * @param params the unified parameters as the composer holds them
 * @returns 1 when nothing is declared or nothing was chosen, which is the
 *          state of every model that ships without modifiers
 */
export function priceMultiplierFor(
  model: GenerationModel | null | undefined,
  params: Record<string, unknown> | null | undefined,
): number {
  const modifiers = model?.price?.modifiers;
  if (!modifiers) return BASE_RATE;
  let factor = BASE_RATE;
  for (const [param, modifier] of Object.entries(modifiers)) {
    factor *= factorOf(modifier, params?.[param]);
  }
  // Floating-point multiplication of decimal factors (1.1 x 2) lands a few
  // digits off, and the value goes into a query key: an unrounded one would
  // mint a new cache entry for a factor that did not change.
  const rounded = Number(factor.toFixed(6));
  // Above the ceiling, DROPPED - the same answer the server gives, and deliberately not a clamp.
  //
  // First written as `Math.min(rounded, MAX)`, on the premise that the charge carried the whole
  // product while the screen showed the base rate. That premise was wrong in both halves. The
  // charging path runs the same sanitiser as the quote door
  // (`CatalogToolBillingService.BillingScope.of` -> `sanitizeGenerationMultiplier`), which returns
  // NOTHING above the ceiling, so the call is charged at the base rate too. Clamping here would
  // have sent 100, which the quote door accepts, and put up to a hundred times the real amount on
  // screen - inverting the error the clamp was added to prevent.
  //
  // Returning the base rate keeps the two sides saying the same thing, which is the only property
  // that matters here. The case is in any event unreachable from the catalogue: the registry skips
  // a descriptor whose parse throws, and the parser throws on a product above the ceiling. It is
  // kept because this function is handed whatever a row happens to contain, and a silent
  // disagreement about money is worth one branch.
  return rounded > MAX_PRICE_FACTOR ? BASE_RATE : rounded;
}

/**
 * The largest factor any surface will quote with.
 *
 * <p>Mirrors `BillingContextHeaders.MAX_GENERATION_MULTIPLIER` on the server, which is one
 * constant read by the descriptor parser, both quote endpoints and the charging path. It is
 * restated here rather than imported because nothing crosses that boundary, and the divergence
 * that costs is a client quoting with a factor the server refuses to price.
 */
export const MAX_PRICE_FACTOR = 100;

/**
 * True when a priced parameter carries a template rather than a value, so no
 * estimate computed here can be right.
 *
 * <p><b>The workflow inspector, and only it.</b> Its fields accept expressions:
 * a `resolution` bound to `{{trigger:webhook.output.res}}` is a string that
 * matches no entry in the model's `by_value` table, so the local calculation
 * falls to the reference tier and quotes 1x for a step the server may bill at
 * 4x. A file slot bound to one template counts as ONE file however many it
 * resolves to, which understates a per-file surcharge the same way.
 *
 * <p>Neither can be fixed by computing harder: the value does not exist until
 * the run reaches that step. What a surface CAN do is stop presenting a number
 * it cannot stand behind, which is what this is for. The studio composer never
 * sees one (its fields hold literals), so it never pays for this check.
 *
 * <p>Deliberately not "does this string look like a template anywhere": only
 * parameters the model actually PRICES can move the factor, so a templated
 * prompt on a model with no modifiers is not a reason to hedge a price.
 */
export function priceFactorDependsOnRuntime(
  model: GenerationModel | null | undefined,
  params: Record<string, unknown> | null | undefined,
): boolean {
  const modifiers = model?.price?.modifiers;
  if (!modifiers) return false;
  const isTemplate = (value: unknown): boolean =>
    typeof value === 'string' && value.includes('{{') && value.includes('}}');
  return Object.keys(modifiers).some((param) => {
    const value = params?.[param];
    return Array.isArray(value) ? value.some(isTemplate) : isTemplate(value);
  });
}

/** One line per factor that actually moved the price, for a surface that says why. */
export function priceFactorReasons(
  model: GenerationModel | null | undefined,
  params: Record<string, unknown> | null | undefined,
): Array<{ param: string; factor: number }> {
  const modifiers = model?.price?.modifiers;
  if (!modifiers) return [];
  const out: Array<{ param: string; factor: number }> = [];
  for (const [param, modifier] of Object.entries(modifiers)) {
    const factor = Number(factorOf(modifier, params?.[param]).toFixed(6));
    // The reference tier is left out: listing it would fill the explanation
    // with the reasons the price did NOT change.
    if (factor !== BASE_RATE) out.push({ param, factor });
  }
  return out;
}
