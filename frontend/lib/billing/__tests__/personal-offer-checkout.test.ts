import { describe, expect, it } from 'vitest';
import { hasAttachedPersonalOfferCheckout, personalOfferCheckoutApiError, personalOfferCheckoutBlock } from '../personal-offer-checkout';

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
  });
  it('maps payment verification failures to actionable copy before redirect', () => {
    expect(personalOfferCheckoutApiError('OFFER_FIRST_PAYMENT_REQUIRED')).toBe('errors.firstPaymentRequired');
    expect(personalOfferCheckoutApiError('OFFER_PAYMENT_PREVIEW_UNAVAILABLE')).toBe('errors.paymentPreviewUnavailable');
    expect(personalOfferCheckoutApiError('OFFER_REVIEW_REQUIRED')).toBe('errors.reviewRequired');
  });
});
