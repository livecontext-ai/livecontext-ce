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
  if (status === 'PENDING_PAYMENT' || status === 'PROCESSING') return 'errors.checkoutActive';
  if (status === 'REVIEW_REQUIRED') return 'errors.reviewRequired';
  return 'errors.unavailable';
}

export function personalOfferCheckoutApiError(code: string | null | undefined):
  'errors.firstPaymentRequired' | 'errors.paymentPreviewUnavailable' | 'errors.reviewRequired' | null {
  if (code === 'OFFER_FIRST_PAYMENT_REQUIRED') return 'errors.firstPaymentRequired';
  if (code === 'OFFER_PAYMENT_PREVIEW_UNAVAILABLE') return 'errors.paymentPreviewUnavailable';
  if (code === 'OFFER_REVIEW_REQUIRED') return 'errors.reviewRequired';
  return null;
}
