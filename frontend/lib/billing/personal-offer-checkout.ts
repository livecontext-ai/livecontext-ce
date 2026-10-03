/**
 * Checkout refusals that mean the offer changed under the page (a new policy version, the deadline
 * passed, another reward or a review in the way): the offer is read again before anything else.
 */
export const PERSONAL_OFFER_STALE_CODES: readonly string[] = [
  'OFFER_PREVIEW_STALE', 'OFFER_EXPIRED', 'OFFER_CONFLICT', 'OFFER_REVIEW_REQUIRED', 'OFFER_ALREADY_USED', 'CHECKOUT_IN_PROGRESS',
];

/** Only Stripe sessions that acknowledge the chosen offer may redirect with that offer. */
export function hasAttachedPersonalOfferCheckout(result: unknown): result is {
  url: string;
  offerStatus: 'ATTACHED';
} {
  if (!result || typeof result !== 'object') return false;
  const value = result as Record<string, unknown>;
  return value.offerStatus === 'ATTACHED' &&
    typeof value.url === 'string' && value.url.trim().length > 0;
}

/** An issued offer in a blocked state needs an explicit decision before any new checkout. */
export function personalOfferCheckoutBlock(status: string | null | undefined):
  'errors.expired' | 'errors.unavailable' | 'errors.conflict' | 'errors.checkoutActive' | 'errors.reviewRequired' | null {
  if (!status || ['NONE', 'AVAILABLE', 'CHECKOUT_OPEN', 'GRANTED', 'NO_BONUS', 'CLAWED_BACK', 'ALREADY_USED'].includes(status)) return null;
  if (status === 'EXPIRED') return 'errors.expired';
  if (status === 'CONFLICT') return 'errors.conflict';
  if (status === 'PENDING_PAYMENT' || status === 'PROCESSING' || status === 'CHECKOUT_CREATING') return 'errors.checkoutActive';
  if (status === 'REVIEW_REQUIRED') return 'errors.reviewRequired';
  return 'errors.unavailable';
}

/**
 * A preview the server refused for a reason the client can act on, said in words; null when only
 * "try again" fits (the read itself failed). Every surface that checks the offer before a checkout
 * asks this before falling back to "could not verify".
 */
export function personalOfferPreviewError(code: string | null | undefined):
  'errors.conflict' | 'errors.expired' | 'errors.alreadyUsed' | 'reviewRequired' | 'errors.notEligible' | null {
  if (code === 'OFFER_CONFLICT') return 'errors.conflict';
  if (code === 'OFFER_EXPIRED') return 'errors.expired';
  if (code === 'OFFER_ALREADY_USED') return 'errors.alreadyUsed';
  if (code === 'OFFER_REVIEW_REQUIRED') return 'reviewRequired';
  if (code === 'OFFER_UNAVAILABLE') return 'errors.notEligible';
  return null;
}

export function personalOfferCheckoutApiError(code: string | null | undefined):
  'errors.firstPaymentRequired' | 'errors.paymentPreviewUnavailable' | 'errors.reviewRequired'
  | 'errors.alreadyUsed' | 'errors.checkoutActive' | 'errors.conflict' | null {
  if (code === 'OFFER_FIRST_PAYMENT_REQUIRED') return 'errors.firstPaymentRequired';
  if (code === 'OFFER_CONFLICT') return 'errors.conflict';
  if (code === 'OFFER_ALREADY_USED') return 'errors.alreadyUsed';
  if (code === 'CHECKOUT_IN_PROGRESS') return 'errors.checkoutActive';
  if (code === 'OFFER_PAYMENT_PREVIEW_UNAVAILABLE') return 'errors.paymentPreviewUnavailable';
  if (code === 'OFFER_REVIEW_REQUIRED') return 'errors.reviewRequired';
  return null;
}
