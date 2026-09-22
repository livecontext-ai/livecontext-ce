// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  WELCOME_GIFT_DONE_EVENT,
  WELCOME_GIFT_FLAG,
  armWelcomeGift,
  isWelcomeGiftPending,
  notifyWelcomeGiftDone,
} from '../welcomeGiftHandoff';

/**
 * The hand-off onboarding uses to greet a new account with what its plan grants.
 *
 * Two things are being pinned, and the second is the one that bites: the flag
 * carries the request, AND it sequences the two modals onboarding arms. A flag
 * cleared at the wrong moment does not fail loudly - it stacks two overlays, or
 * leaves the second one armed forever.
 */
describe('welcomeGiftHandoff', () => {
  beforeEach(() => sessionStorage.clear());

  it('is not pending until something arms it', () => {
    expect(isWelcomeGiftPending()).toBe(false);
  });

  it('survives the navigation out of onboarding, which is the whole point', () => {
    armWelcomeGift();

    // Written where the app tree can read it after the redirect to chat. A
    // value held in memory would not survive that navigation at all.
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBe('1');
    expect(isWelcomeGiftPending()).toBe(true);
  });

  it('stays pending when read, so the modal waiting behind it keeps waiting', () => {
    // The read is NOT a consume. The suggested-applications modal reads this to
    // decide whether to wait; if reading cleared it, whichever component mounted
    // first would silently decide for the other one.
    armWelcomeGift();

    expect(isWelcomeGiftPending()).toBe(true);
    expect(isWelcomeGiftPending()).toBe(true);
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBe('1');
  });

  it('clears the flag and releases the waiter together', () => {
    armWelcomeGift();
    const released = vi.fn();
    window.addEventListener(WELCOME_GIFT_DONE_EVENT, released);

    notifyWelcomeGiftDone();

    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBeNull();
    expect(released).toHaveBeenCalledTimes(1);
    expect(isWelcomeGiftPending()).toBe(false);
    window.removeEventListener(WELCOME_GIFT_DONE_EVENT, released);
  });

  it('still releases the waiter when nothing was ever armed', () => {
    // The owner calls this on every path where it decides not to open: a
    // self-hosted install, a paid account, an answer that never arrives. All
    // must be harmless, not a special case the caller has to remember.
    const released = vi.fn();
    window.addEventListener(WELCOME_GIFT_DONE_EVENT, released);

    expect(() => notifyWelcomeGiftDone()).not.toThrow();

    expect(released).toHaveBeenCalledTimes(1);
    window.removeEventListener(WELCOME_GIFT_DONE_EVENT, released);
  });

  it('does not throw when the tab denies storage', () => {
    // A private window with site data blocked throws on setItem. The gift is a
    // nicety; it must not be able to break the completion of onboarding.
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });

    expect(() => armWelcomeGift()).not.toThrow();
    expect(isWelcomeGiftPending()).toBe(false);

    setItem.mockRestore();
    getItem.mockRestore();
  });

  it('releases the waiter even when clearing the flag throws', () => {
    // Same denied-storage tab, on the other side: a waiter that armed before
    // the denial is still listening, and a throw here would strand it.
    armWelcomeGift();
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('denied');
    });
    const released = vi.fn();
    window.addEventListener(WELCOME_GIFT_DONE_EVENT, released);

    expect(() => notifyWelcomeGiftDone()).not.toThrow();

    expect(released).toHaveBeenCalledTimes(1);
    window.removeEventListener(WELCOME_GIFT_DONE_EVENT, released);
    removeItem.mockRestore();
  });
});
