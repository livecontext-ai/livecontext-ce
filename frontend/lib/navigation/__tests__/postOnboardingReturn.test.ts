// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import {
  forgetPostOnboardingReturnOfOthers,
  POST_ONBOARDING_RETURN_KEY,
  rememberPostOnboardingReturn,
  takePostOnboardingReturn,
} from '../postOnboardingReturn';

const PRICING = '/fr/app/settings/pricing?planCode=PRO&creditTierIndex=5&lc_ref=NORTHWIND&lc_rec=pro.5.monthly';

describe('the page onboarding hands back to', () => {
  afterEach(() => window.localStorage.clear());

  it('remembers a pricing page with its query and gives it back once', () => {
    rememberPostOnboardingReturn(window, PRICING, 'user-a');

    expect(takePostOnboardingReturn(window)).toBe(PRICING);
    expect(takePostOnboardingReturn(window)).toBeNull();
  });

  it('accepts the pricing page without a locale prefix', () => {
    rememberPostOnboardingReturn(window, '/app/settings/pricing', 'user-a');

    expect(takePostOnboardingReturn(window)).toBe('/app/settings/pricing');
  });

  it("remembers a partner's offer page with the visitor's choice and gives it back once", () => {
    const offer = '/offer/Abc23XyZ9k?plan=team&tier=3&cycle=yearly&continue=1';
    rememberPostOnboardingReturn(window, offer, 'user-a');

    expect(takePostOnboardingReturn(window)).toBe(offer);
    expect(takePostOnboardingReturn(window)).toBeNull();
  });

  it('never stores, nor gives back, anything but a page to pay from: a stored target must not send people elsewhere', () => {
    rememberPostOnboardingReturn(window, '/fr/app/chat', 'user-a');
    rememberPostOnboardingReturn(window, 'https://evil.example/app/settings/pricing', 'user-a');
    rememberPostOnboardingReturn(window, '/fr/app/settings/pricing/sub', 'user-a');
    rememberPostOnboardingReturn(window, '/offer/Abc23XyZ9k/more', 'user-a');
    rememberPostOnboardingReturn(window, 'https://evil.example/offer/Abc23XyZ9k', 'user-a');
    expect(window.localStorage.getItem(POST_ONBOARDING_RETURN_KEY)).toBeNull();

    // A value written by something else is checked again on the way out.
    for (const path of [
      '//evil.example/app/settings/pricing', '/fr/app/settings/pricing#x', '/fr/app/workflows', 'javascript:alert(1)',
      '//evil.example/offer/Abc23XyZ9k', '/offer/abc', '/offer/Abc23XyZ9k#x', '/offer/../app/chat',
    ]) {
      window.localStorage.setItem(POST_ONBOARDING_RETURN_KEY, JSON.stringify({ path, owner: 'user-a', savedAt: Date.now() }));
      expect(takePostOnboardingReturn(window)).toBeNull();
    }
  });

  it('a return written for no account is neither stored nor given back', () => {
    rememberPostOnboardingReturn(window, PRICING, '');
    expect(window.localStorage.getItem(POST_ONBOARDING_RETURN_KEY)).toBeNull();

    window.localStorage.setItem(POST_ONBOARDING_RETURN_KEY, JSON.stringify({ path: PRICING, savedAt: Date.now() }));
    expect(takePostOnboardingReturn(window)).toBeNull();
  });

  it('forgets a return older than a day, or dated in the future', () => {
    const now = Date.now();
    rememberPostOnboardingReturn(window, PRICING, 'user-a', now - 25 * 60 * 60 * 1000);
    expect(takePostOnboardingReturn(window, now)).toBeNull();

    rememberPostOnboardingReturn(window, PRICING, 'user-a', now + 60_000);
    expect(takePostOnboardingReturn(window, now)).toBeNull();
  });

  it('a corrupted value is dropped, not thrown', () => {
    window.localStorage.setItem(POST_ONBOARDING_RETURN_KEY, '{not json');

    expect(takePostOnboardingReturn(window)).toBeNull();
    expect(window.localStorage.getItem(POST_ONBOARDING_RETURN_KEY)).toBeNull();
  });
});

describe('one account\'s return is never handed to another', () => {
  afterEach(() => window.localStorage.clear());

  it('another account signing in on the browser drops it', () => {
    rememberPostOnboardingReturn(window, PRICING, 'user-a');

    forgetPostOnboardingReturnOfOthers(window, 'user-b');

    expect(takePostOnboardingReturn(window)).toBeNull();
  });

  it('the same account keeps it', () => {
    rememberPostOnboardingReturn(window, PRICING, 'user-a');

    forgetPostOnboardingReturnOfOthers(window, 'user-a');

    expect(takePostOnboardingReturn(window)).toBe(PRICING);
  });

  it('an unreadable or ownerless value is dropped too', () => {
    window.localStorage.setItem(POST_ONBOARDING_RETURN_KEY, '{not json');
    forgetPostOnboardingReturnOfOthers(window, 'user-a');
    expect(window.localStorage.getItem(POST_ONBOARDING_RETURN_KEY)).toBeNull();

    window.localStorage.setItem(POST_ONBOARDING_RETURN_KEY, JSON.stringify({ path: PRICING, savedAt: Date.now() }));
    forgetPostOnboardingReturnOfOthers(window, 'user-a');
    expect(window.localStorage.getItem(POST_ONBOARDING_RETURN_KEY)).toBeNull();
  });
});
