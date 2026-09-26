/**
 * The hand-off that greets a brand-new account with what its plan already gives it.
 *
 * <p>Onboarding used to spend this moment on the five-column plan comparison,
 * opened on its credits row. That is the wrong screen for the moment: a reader
 * who has just signed up is not choosing a plan, they are finding out what the
 * one they already have grants. So the app now opens a small welcome-gift
 * modal instead, which states the Free plan's monthly credits and what they pay
 * for, and nothing else.
 *
 * <p><b>Why a sessionStorage flag and not a query param.</b> Onboarding finishes
 * by navigating into the app, and the app tree is where the modal is mounted.
 * A flag survives that navigation, is per-tab, and dies with the tab, so a
 * reader who comes back tomorrow is not greeted by it again. It is the same
 * mechanism the suggested-applications modal already uses.
 *
 * <p><b>The flag also SEQUENCES the two modals.</b> Onboarding arms both this
 * and the suggested applications; two overlays opening on the same paint would
 * stack. So this one goes first and the other waits for
 * {@link WELCOME_GIFT_DONE_EVENT}. The waiter only ever waits while the flag is
 * present, and the owner ({@code WelcomeGiftModal}) resolves the flag on every
 * path it can take - including the ones where it decides not to open at all,
 * and including a bounded timeout - so "armed forever" has no path to exist.
 */

/** Set by onboarding, read by the app tree. Per tab, cleared once acted on. */
export const WELCOME_GIFT_FLAG = 'lc_show_welcome_gift';

/**
 * The second thing onboarding arms: the suggested-applications modal.
 *
 * <p>It lives here, beside the gift's flag, because three unrelated files need
 * the same string - the page that writes it, the modal that consumes it, and the
 * changelog panel that stays quiet while either is pending - and each of them
 * spelled it out. One of those literals HAS already gone stale: the gift's own
 * flag was hardcoded in the changelog panel and kept guarding a modal nobody
 * could arm any more, in silence. The module that owns the hand-off owns both
 * names.
 */
export const APP_SUGGESTIONS_FLAG = 'lc_show_app_suggestions';

/** Dispatched on `window` once the gift is done with, opened or not. */
export const WELCOME_GIFT_DONE_EVENT = 'lc:welcome-gift-done';

/** Ask for the welcome gift on the next app screen. No-op on the server. */
export function armWelcomeGift(): void {
  if (typeof window === 'undefined') return;
  try {
    sessionStorage.setItem(WELCOME_GIFT_FLAG, '1');
  } catch {
    // A tab with storage denied simply does not get the gift. Nothing
    // downstream waits on a flag that was never written, so this is a missing
    // nicety and not a broken hand-off.
  }
}

/**
 * Whether a gift is still owed.
 *
 * <p>Deliberately does NOT clear the flag: it means "a gift is still owed", and
 * what waits on it needs that to stay true until the gift has actually been
 * shown and dismissed. {@link notifyWelcomeGiftDone} is what clears it.
 */
export function isWelcomeGiftPending(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return sessionStorage.getItem(WELCOME_GIFT_FLAG) === '1';
  } catch {
    return false;
  }
}

/**
 * The gift is done with: clear the flag and release whoever is waiting.
 *
 * <p>Must be called on EVERY path the owner can take, including deciding not to
 * open (self-hosted, a paid account, an answer that never arrives): a waiter
 * released by nothing would never open either, turning one skipped modal into
 * two.
 */
export function notifyWelcomeGiftDone(): void {
  if (typeof window === 'undefined') return;
  try {
    sessionStorage.removeItem(WELCOME_GIFT_FLAG);
  } catch {
    // Storage denied: there was no flag to clear. The event still goes out,
    // because a waiter armed before the denial is still waiting.
  }
  window.dispatchEvent(new Event(WELCOME_GIFT_DONE_EVENT));
}
