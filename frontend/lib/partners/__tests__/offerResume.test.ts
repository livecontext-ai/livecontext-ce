// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { markOfferResume, OFFER_RESUME_KEY, takeOfferResume } from '../offerResume';

afterEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe('the offer page resume marker', () => {
  it('lets the page carry on once for the offer that started the round trip, then is gone', () => {
    markOfferResume(window, 'Abc23XyZ9k', 1_000);

    expect(takeOfferResume(window, 'Abc23XyZ9k', 2_000)).toBe(true);
    expect(takeOfferResume(window, 'Abc23XyZ9k', 2_000)).toBe(false);
  });

  it('without a marker, nothing carries on (a "continue=1" written into a link)', () => {
    expect(takeOfferResume(window, 'Abc23XyZ9k')).toBe(false);
  });

  it('another offer marker does not count here, and is left for that offer', () => {
    markOfferResume(window, 'Other00001', 1_000);

    expect(takeOfferResume(window, 'Abc23XyZ9k', 2_000)).toBe(false);
    expect(takeOfferResume(window, 'Other00001', 2_000)).toBe(true);
  });

  it('a marker older than a day, or from the future, is dropped', () => {
    const day = 24 * 60 * 60 * 1000;
    markOfferResume(window, 'Abc23XyZ9k', 0);
    expect(takeOfferResume(window, 'Abc23XyZ9k', day + 1)).toBe(false);
    expect(window.localStorage.getItem(OFFER_RESUME_KEY)).toBeNull();

    markOfferResume(window, 'Abc23XyZ9k', 10_000);
    expect(takeOfferResume(window, 'Abc23XyZ9k', 5_000)).toBe(false);
  });

  it('an unreadable value or a blocked store never throws, and never carries on', () => {
    window.localStorage.setItem(OFFER_RESUME_KEY, '{not json');
    expect(takeOfferResume(window, 'Abc23XyZ9k')).toBe(false);

    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(() => markOfferResume(window, 'Abc23XyZ9k')).not.toThrow();
    expect(takeOfferResume(window, 'Abc23XyZ9k')).toBe(false);
  });
});
