import type { useTranslations } from 'next-intl';
import type { PlatformCredentialPublicInfo } from '@/lib/api/orchestrator';
import { priceUnitLabel } from '@/lib/credentials/priceUnits';
import { paramLabel, type LabelTranslator } from '@/lib/generation/labels';
import { formatCost, isCeMode } from '@/lib/format-cost';
import { getClientLocale } from '@/lib/utils/locale';

/**
 * A price unit in the reader's language.
 *
 * <p>`priceUnit` and `billed_unit` are wire tokens from the platform's own
 * enum, always English. Dropping one into a translated sentence produced
 * "60 credits per second" inside a French page and an English word inside a
 * Chinese one. The same placeholder is already rendered correctly by the
 * workflow inspector through `priceUnitLabel`, so this reuses that helper and
 * its dictionary rather than adding a second one.
 */
export function localizedUnit(unit: string | undefined, tUnits: ReturnType<typeof useTranslations>): string {
  return unit ? priceUnitLabel(unit, tUnits) : '';
}

/**
 * A credit amount as the reader's app locale groups it.
 *
 * <p>These are numbers on screen, so they follow the repo rule for numbers:
 * the APP locale (the URL segment, else the NEXT_LOCALE cookie), never the
 * browser's and never a hardcoded one. A bare String() printed 600000 to a
 * French reader whose every other figure on the page reads 600 000, and it
 * did so on two surfaces at once once this arithmetic was shared.
 *
 * <p>Trailing zeros are dropped rather than padded: a rate is quoted as it
 * was published, and 60.00 credits per second states a precision the
 * catalogue does not have.
 *
 * <p>Exported because a price is also stated AFTER the fact, on the card of a
 * generation that has already been charged. Quoting and reporting are two
 * sentences about one number, and a second copy of this rule is how the same
 * amount ends up grouped one way before the spend and another way after it.
 */
export function formatCredits(value: number): string {
  // A non-finite amount is not a number to show: markupCredits is only
  // null-checked upstream, so a malformed one would otherwise render the
  // literal "NaN" into a price sentence. Empty, and the caller falls back to
  // the unpriced note.
  if (!Number.isFinite(value)) return '';
  return value.toLocaleString(getClientLocale(), { maximumFractionDigits: 6 });
}

/**
 * What this model costs, read from the PUBLISHED price rather than the seed.
 *
 * <p>The model listing carries a `price` too, but it is the list rate shipped
 * with the catalog seed, and the amount actually charged comes from the pricing
 * version an administrator published, which they can and do change. Quoting the
 * seed here would have one screen state one number while the invoice states
 * another. So the components come from the same quote endpoint every surface
 * uses: one price, reached by one arithmetic, wherever it is shown.
 *
 * <p>Shared by the studio composer and the workflow inspector's Generate node.
 * Both state a price before the spend is committed, so they have to phrase the
 * same quote the same way; two copies of this arithmetic is how the two
 * surfaces end up quoting one model differently. (It was written for the
 * generation dialog, which the studio replaced.)
 *
 * <p>The floor and ceiling are included because a rate on its own understates a
 * model that carries a minimum: "4 credits per second" for a model whose floor
 * is 8 describes a price no short call can actually cost.
 *
 * <p>Returns an empty string when nothing is published. That is not "free": a
 * generation with no published price is REFUSED on the platform key, so the
 * caller shows the unpriced note instead of an amount.
 *
 * @param t the `generation` namespace, which owns the `price.*` wording.
 * @param tUnits the `credentials` namespace, which owns the unit names.
 */
export function describeQuotedPrice(
  quote: PlatformCredentialPublicInfo | undefined,
  t: ReturnType<typeof useTranslations>,
  tUnits: ReturnType<typeof useTranslations>,
): string {
  // Covers the version-default case too, and is the only check that can: the
  // server never emits a price alongside `versionDefaultOnly`, on either leg
  // (the local quote computes `hasPricing` as "positive AND not the
  // credential-wide default", and the CE cloud relay resolves no markup at all
  // for one). A second client-side copy of that rule looked like a belt to the
  // server's braces and was simply unreachable, so it certified nothing while
  // reading as though it protected the spend button.
  if (!quote?.hasPricing) return '';
  const rate = Number(quote.unitCredits);
  const base = Number(quote.baseCredits);
  const parts: string[] = [];

  if (Number.isFinite(rate) && rate > 0 && quote.priceUnit && quote.priceUnit !== 'call') {
    parts.push(t('price.perUnit', { rate: formatCredits(rate), unit: localizedUnit(quote.priceUnit, tUnits) }));
    if (Number.isFinite(base) && base > 0) {
      parts.push(t('price.plusBase', { base: formatCredits(base) }));
    }
    // The total for THIS request, when the quote knew its size. It is the
    // number that will be charged, so it leads rather than being implied.
    // Finite, not merely present. formatCredits answers '' for a value it cannot
    // render, and here `parts` already holds the per-unit rate, so the sentence
    // would come back TRUTHY with an empty amount in it ("Total:  credits") and
    // the caller's unpriced fallback would never fire.
    if (quote.quantity != null && Number.isFinite(Number(quote.markupCredits))) {
      parts.unshift(t('price.total', { credits: formatCredits(Number(quote.markupCredits)) }));
    }
  } else {
    const flat = Number.isFinite(Number(quote.markupCredits)) && Number(quote.markupCredits) > 0
      ? Number(quote.markupCredits)
      : (Number.isFinite(base) && base > 0 ? base : rate);
    if (!Number.isFinite(flat) || flat <= 0) return '';
    parts.push(t('price.flat', { credits: formatCredits(flat) }));
  }

  const min = quote.minCredits == null ? null : Number(quote.minCredits);
  const max = quote.maxCredits == null ? null : Number(quote.maxCredits);
  if (min != null && Number.isFinite(min) && min > 0) {
    parts.push(t('price.min', { credits: formatCredits(min) }));
  }
  if (max != null && Number.isFinite(max) && max > 0) {
    parts.push(t('price.max', { credits: formatCredits(max) }));
  }
  return parts.join(', ');
}

/**
 * Why this call is not priced at its model's published rate.
 *
 * <p>The amount beside the button already INCLUDES the factor: the quote
 * applied it and the biller applies the same one. What is missing without this
 * is the reason, and an amount with no visible reason reads as a mistake - the
 * reader's only way to test it is to spend.
 *
 * <p>Returns an empty string when nothing moved the price, which is every model
 * that declares no modifiers and every call that picked the reference tier.
 *
 * @param factors what `priceFactorReasons` computed for the current form
 * @param t the `generation` namespace, which owns both the wording and the
 *          `params.*` dictionary the parameter names are read from
 */
export function describePriceFactors(
  factors: Array<{ param: string; factor: number }>,
  t: ReturnType<typeof useTranslations>,
): string {
  if (factors.length === 0) return '';
  const labels = factors.map((entry) => t('price.factor', {
    param: paramLabel(entry.param, t as unknown as LabelTranslator),
    factor: formatCredits(entry.factor),
  }));
  return t('price.factors', { list: joinInAppLocale(labels) });
}

/**
 * The same sentence, for a factor that has already been CHARGED.
 *
 * <p>A finished turn carries the reasons the server gave, as the server wrote
 * them: `param + " x" + factor`, e.g. `resolution x2`. Printed verbatim that is
 * the raw contract name in English, joined with a Latin comma, on a card whose
 * own estimate said "includes Resolution x2" translated and list-joined per
 * locale. One fact, two vocabularies, on the same screen.
 *
 * <p>So the server's lines are parsed back into the shape the estimate uses and
 * worded by the same function. Parsing rather than asking the server for
 * structure is the smaller change and the reversible one: the wire format is
 * documented and already shipped, and a line this cannot read falls through
 * UNTOUCHED rather than being dropped, so a future format reaches the reader as
 * the server's own words instead of vanishing.
 *
 * @param reasons `billed_multiplier_reasons`, exactly as the response carried them
 * @param t the `generation` namespace, as for {@link describePriceFactors}
 */
export function describeBilledFactors(
  reasons: readonly string[] | undefined,
  t: ReturnType<typeof useTranslations>,
): string {
  if (!reasons || reasons.length === 0) return '';
  const parsed: Array<{ param: string; factor: number }> = [];
  const unparsed: string[] = [];
  for (const line of reasons) {
    // `param x<factor>`: the param may contain underscores, the factor is plain decimal.
    const match = /^(.+?)\s+x([0-9]+(?:\.[0-9]+)?)$/.exec(line.trim());
    const factor = match ? Number(match[2]) : NaN;
    if (match && Number.isFinite(factor)) parsed.push({ param: match[1], factor });
    else unparsed.push(line);
  }
  const labels = [
    ...parsed.map((entry) => t('price.factor', {
      param: paramLabel(entry.param, t as unknown as LabelTranslator),
      factor: formatCredits(entry.factor),
    })),
    ...unparsed,
  ];
  return t('price.factors', { list: joinInAppLocale(labels) });
}

/**
 * Join a list the way the reader's own language joins one.
 *
 * <p>A hard-coded ", " is a Latin-script assumption: Chinese enumerates with
 * a different mark entirely. `Intl.ListFormat` is given the APP locale, like
 * every other formatted value in this file, never the browser's.
 *
 * <p><b>`conjunction` + `narrow`, and both halves are load-bearing.</b> This
 * asked for `type: 'unit'`, which in `zh` emits NO separator at all: three
 * factors rendered as `分辨率 x2参考图 x1.1`, run together, which is worse than
 * the hard-coded ", " the comment above congratulates itself for replacing.
 * `conjunction` is the type that carries a mark in every locale, and `narrow`
 * is what keeps it an enumeration rather than a sentence: the `long`/`short`
 * styles add "and" / "&" in English, which reads as prose beside a price.
 *
 * <p>Verified per locale rather than assumed:
 * `en "a, b, c"` · `zh "a、b、c"` · `fr "a, b, c"` · `de "a, b und c"` ·
 * `es "a, b y c"` · `pt "a, b, c"`.
 *
 * <p>Falls back to ", " where the runtime has no ListFormat: a list joined
 * with the wrong mark still reads; a screen that threw would not.
 */
function joinInAppLocale(parts: string[]): string {
  try {
    return new Intl.ListFormat(getClientLocale(), { style: 'narrow', type: 'conjunction' })
      .format(parts);
  } catch {
    return parts.join(', ');
  }
}

/**
 * What a generation ALREADY charged, in the unit its own edition spends in.
 *
 * <p>A ledger amount is stored in credits, and a self-hosted install turns it into dollars
 * everywhere it shows spend, because it pays its own providers and a credit count means nothing to
 * it. Every surface that states a charge has to make that decision, and each one that makes it
 * privately is a surface that can disagree with the one beside it: the studio thread quoted "78
 * credits" ten pixels above a history card reading "$0.078", for the same asset, on the one install
 * that has both.
 *
 * <p>Distinct from {@link describeQuotedPrice}, which explains what a call WILL cost from a
 * published rate. This states what was taken.
 *
 * @param credits the amount charged. Callers filter out absent, zero and non-finite values BEFORE
 *                calling: absent is not zero, and a charge of nothing is not a price to show.
 * @param t the `generationHistory` namespace, which owns the wording and its plural.
 */
export function describeCharge(credits: number, t: ReturnType<typeof useTranslations>): string {
  return isCeMode
    ? formatCost(credits)
    : t('cost', { credits: formatCredits(credits), count: credits });
}

/**
 * A quoted amount with the reason it is not the published rate, when it is not.
 *
 * <p><b>Why this is shared rather than written at each surface.</b> Four surfaces quote a
 * generation and the amount they show already carries the factor. The studio composer explained
 * it; the workflow inspector's model list and the chat dialog's model options did not, so both
 * printed a total that is not rate x size with nothing on screen to account for the difference.
 * An unexplained total does not read as "there is a surcharge", it reads as an arithmetic error,
 * and the reader's only way to test it is to spend.
 *
 * <p><b>Gated on the SERVER's echo, never on the local calculation.</b> A server that did not
 * apply the factor answers at the published rate, and appending a reason there would explain a
 * surcharge the amount does not contain: the same lie in the other direction. The reason itself is
 * computed locally because only the surface holding the parameters knows which choices produced
 * it; the CLAIM that there was one is the server's.
 *
 * @param label the amount as {@link describeQuotedPrice} worded it, possibly empty
 * @param quote the answer the amount came from, read for its echoed `priceMultiplier`
 * @param factors what `priceFactorReasons` computed for the same call
 * @param t the `generation` namespace
 */
export function withQuotedPriceReason(
  label: string,
  quote: { priceMultiplier?: string | number | null } | undefined | null,
  factors: Array<{ param: string; factor: number }>,
  t: ReturnType<typeof useTranslations>,
): string {
  if (!label) return label;
  const echoed = Number(quote?.priceMultiplier);
  if (!Number.isFinite(echoed) || echoed <= 0 || echoed === 1) return label;
  const reason = describePriceFactors(factors, t);
  return reason ? `${label} (${reason})` : label;
}
