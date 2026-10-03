import { describe, expect, it } from 'vitest';
import { hasAttachedPersonalOfferCheckout, PERSONAL_OFFER_STALE_CODES, personalOfferCheckoutApiError, personalOfferCheckoutBlock, personalOfferPreviewError } from '../personal-offer-checkout';

describe('personal offer checkout response', () => {
  it('accepts only a checkout URL with an explicit offer attachment acknowledgement', () => {
    expect(hasAttachedPersonalOfferCheckout({ url: 'https://checkout.stripe.test/session', offerStatus: 'ATTACHED' })).toBe(true);
    expect(hasAttachedPersonalOfferCheckout({ url: 'https://checkout.stripe.test/session' })).toBe(false);
    expect(hasAttachedPersonalOfferCheckout({ url: '', offerStatus: 'ATTACHED' })).toBe(false);
    expect(hasAttachedPersonalOfferCheckout('SWAP_IMMEDIAT')).toBe(false);
  });
  it('blocks expired and pending offers before another checkout can ignore or reuse them', () => {
    expect(personalOfferCheckoutBlock('EXPIRED')).toBe('errors.expired');
    expect(personalOfferCheckoutBlock('PENDING_PAYMENT')).toBe('errors.checkoutActive');
    expect(personalOfferCheckoutBlock('DISABLED')).toBe('errors.unavailable');
    expect(personalOfferCheckoutBlock('AVAILABLE')).toBeNull();
    expect(personalOfferCheckoutBlock('NO_BONUS')).toBeNull();
    expect(personalOfferCheckoutBlock('ALREADY_USED')).toBeNull();
    expect(personalOfferCheckoutBlock('REVIEW_REQUIRED')).toBe('errors.reviewRequired');
    // A checkout being created is one in progress, not another benefit.
    expect(personalOfferCheckoutBlock('CHECKOUT_CREATING')).toBe('errors.checkoutActive');
  });
  it('maps payment verification failures to actionable copy before redirect', () => {
    expect(personalOfferCheckoutApiError('OFFER_FIRST_PAYMENT_REQUIRED')).toBe('errors.firstPaymentRequired');
    expect(personalOfferCheckoutApiError('OFFER_PAYMENT_PREVIEW_UNAVAILABLE')).toBe('errors.paymentPreviewUnavailable');
    expect(personalOfferCheckoutApiError('OFFER_REVIEW_REQUIRED')).toBe('errors.reviewRequired');
    // Said in words rather than "the payment page could not be opened".
    expect(personalOfferCheckoutApiError('OFFER_ALREADY_USED')).toBe('errors.alreadyUsed');
    expect(personalOfferCheckoutApiError('CHECKOUT_IN_PROGRESS')).toBe('errors.checkoutActive');
    // Another benefit in the way: said, not "try again" for ever.
    expect(personalOfferCheckoutApiError('OFFER_CONFLICT')).toBe('errors.conflict');
    expect(personalOfferCheckoutApiError('SOMETHING_ELSE')).toBeNull();
  });
  it('a preview refusal the server named is said in words; a failed read is not', () => {
    expect(personalOfferPreviewError('OFFER_CONFLICT')).toBe('errors.conflict');
    expect(personalOfferPreviewError('OFFER_EXPIRED')).toBe('errors.expired');
    expect(personalOfferPreviewError('OFFER_ALREADY_USED')).toBe('errors.alreadyUsed');
    expect(personalOfferPreviewError('OFFER_REVIEW_REQUIRED')).toBe('reviewRequired');
    expect(personalOfferPreviewError('OFFER_UNAVAILABLE')).toBe('errors.notEligible');
    expect(personalOfferPreviewError('HTTP_503')).toBeNull();
    expect(personalOfferPreviewError(null)).toBeNull();
  });
  it('re-reads the offer on every refusal that means it changed under the page', () => {
    for (const code of ['OFFER_PREVIEW_STALE', 'OFFER_EXPIRED', 'OFFER_CONFLICT', 'OFFER_REVIEW_REQUIRED', 'OFFER_ALREADY_USED', 'CHECKOUT_IN_PROGRESS']) {
      expect(PERSONAL_OFFER_STALE_CODES).toContain(code);
    }
    expect(PERSONAL_OFFER_STALE_CODES).not.toContain('OFFER_FIRST_PAYMENT_REQUIRED');
  });
});
