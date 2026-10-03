// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import {
  buildPersonalOfferSignInReturn,
  clearPersonalOfferJourney,
  readPersonalOfferJourney,
  savePersonalOfferJourney,
} from '../personalOfferJourney';
import { capturePendingPersonalOffer } from '../pendingPersonalOffer';

beforeEach(() => {
  sessionStorage.clear();
  history.replaceState(null, '', '/en/app/settings/pricing');
});

describe('personal offer journey', () => {
  it('keeps plan, pack, cadence, and work return through an auth or Stripe round trip', () => {
    savePersonalOfferJourney(window, {
      planCode: 'PRO', creditTierIndex: 2, billingCycle: 'monthly',
      returnToWork: '/en/app/chat', userKey: 'account-a',
    });

    expect(readPersonalOfferJourney(window, 'account-a')).toMatchObject({
      planCode: 'PRO', creditTierIndex: 2, billingCycle: 'monthly',
      returnToWork: '/en/app/chat',
    });
    expect(readPersonalOfferJourney(window, 'account-b')).toBeNull();
  });

  it('adds the candidate offer to a local sign-in return and removes Stripe tokens', () => {
    history.replaceState(null, '', '/fr/app/settings/pricing?lc_offer=CODE123&checkout=cancelled&session_id=secret');
    capturePendingPersonalOffer(window);

    const path = buildPersonalOfferSignInReturn(window, {
      planCode: 'TEAM', creditTierIndex: 3, billingCycle: 'yearly',
    });
    const url = new URL(path, window.location.origin);

    expect(url.pathname).toBe('/fr/app/settings/pricing');
    expect(url.searchParams.get('lc_offer')).toBe('CODE123');
    expect(url.searchParams.get('planCode')).toBe('TEAM');
    expect(url.searchParams.get('creditTierIndex')).toBe('3');
    expect(url.searchParams.get('billingCycle')).toBe('yearly');
    expect(url.searchParams.has('session_id')).toBe(false);
    expect(url.searchParams.has('checkout')).toBe(false);
  });

  it.each(['/\\evil.example', '//evil.example', 'javascript:alert(1)', '/%2F%2Fevil.example'])(
    'drops a stored work return that would leave the origin (%s)',
    (hostile) => {
      // Regression (ASVS 5.1.5): the old ad-hoc check only refused `//`, so `/\evil.example`
      // (which browsers resolve to another host) reached the "Return to work" link.
      savePersonalOfferJourney(window, {
        creditTierIndex: 1, billingCycle: 'monthly', returnToWork: hostile,
      });
      expect(readPersonalOfferJourney(window)).toBeNull();
    },
  );

  it('clears an obsolete selection after its offer is no longer active', () => {
    savePersonalOfferJourney(window, {
      creditTierIndex: 1, billingCycle: 'monthly', returnToWork: '/en/app/chat',
    });
    clearPersonalOfferJourney(window);
    expect(readPersonalOfferJourney(window)).toBeNull();
  });
});
