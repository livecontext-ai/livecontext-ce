import { BASE_RATE } from '@/lib/generation/priceModifiers';

/**
 * The cache key every surface asks a generation's price under.
 *
 * <p><b>Why one function instead of four literals.</b> Four surfaces quote the same endpoint - the
 * studio composer, the payer pane inside its model picker, the workflow inspector's Generate form
 * and the chat's generation dialog - and two of them are on screen at once. React Query dedupes on
 * the key, so an identical key means one request and ONE amount; a key that differs by a single
 * element means two requests for one generation and two numbers that can disagree in front of the
 * reader. Three of those call sites carried a comment promising they matched, and nothing enforced
 * it: adding the price factor to three of the four broke the promise for every model, including the
 * ones with no factor at all, because `1` is still an extra element.
 *
 * <p>So the promise is a function. A field added here reaches every caller, and a caller that wants
 * a field nobody else sends has to come here and say so.
 *
 * <p>Every element is normalised, because `undefined` and `null` are the same statement to a quote
 * ("this surface cannot say") and a different key to React Query.
 */
export interface GenerationQuoteQuestion {
  /** Platform credential the price hangs off. Lower-cased: the server is case-insensitive here. */
  integrationName?: string | null;
  /** Catalog endpoint. Two endpoints of one API can be priced differently. */
  apiToolId?: string | null;
  /** Generation model, or null for the endpoint-wide price. */
  modelId?: string | null;
  /** PLATFORM measurement of the call, in the unit below. */
  quantity?: number | null;
  /** Whether the endpoint resells a generated asset: it changes the ANSWER, not just the amount. */
  generation?: boolean;
  /** What the quantity is COUNTED in, which can turn a quote off entirely. */
  quantityUnit?: string | null;
  /** What the call's own choices do to the rate. 1, or absent, means the published rate. */
  priceMultiplier?: number | null;
}

export function generationQuoteKey(question: GenerationQuoteQuestion): readonly unknown[] {
  return [
    'platform-credential-public-info',
    question.integrationName?.toLowerCase() ?? '',
    question.apiToolId ?? null,
    question.modelId ?? null,
    question.quantity ?? null,
    question.generation ?? false,
    question.quantityUnit ?? null,
    question.priceMultiplier ?? BASE_RATE,
  ] as const;
}
