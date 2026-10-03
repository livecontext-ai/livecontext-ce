/**
 * Where onboarding hands a person back, when a page sent them there in the middle of something.
 *
 * <p>The pricing page and a partner's offer page (/offer/<token>) are open before sign-up, and a
 * person who signs up from one (a partner's link, a plan they picked) lands back on it with a fresh
 * account: email unverified, profile empty. They are sent through onboarding first (the email check
 * is what lets a partner code apply before the first payment), and this brings them back to the
 * exact page they left (plan, credits, cycle, partner code and recommendation in its query)
 * instead of the chat.
 *
 * <p>Only those two pages are accepted, on the way in and on the way out: a stored value is read
 * back as a navigation target, and anything wider would be an open door to send people anywhere.
 * Local storage, not session storage: the email check may continue in another tab. One use, a day
 * at most, and one account: the return carries the account it was written for, and the onboarding
 * guard drops it as soon as another account is signed in on the browser (a shared machine would
 * otherwise hand B the pricing page, and the partner code, A left behind).
 */

import { OFFER_TOKEN_SOURCE } from '@/lib/partners/offerToken';

export const POST_ONBOARDING_RETURN_KEY = 'lc_post_onboarding_return_v1';
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

/** `/pricing` under the app, with or without a locale prefix, with an optional query. */
const PRICING_PATH = /^\/(?:[a-z]{2}\/)?app\/settings\/pricing(?:\?[^#\s]*)?$/;

/** A partner's offer page (outside the locale segment), with an optional query. */
const OFFER_PATH = new RegExp(`^/offer/${OFFER_TOKEN_SOURCE}(?:\\?[^#\\s]*)?$`);

export function isPostOnboardingReturnPath(path: string): boolean {
  return PRICING_PATH.test(path) || OFFER_PATH.test(path);
}

/**
 * Remember the page to come back to after onboarding, for this account; a path that is not a
 * pricing or offer page, or no account, is ignored.
 */
export function rememberPostOnboardingReturn(win: Window, path: string, owner: string, now: number = Date.now()): void {
  if (!owner || !isPostOnboardingReturnPath(path)) return;
  try {
    win.localStorage.setItem(POST_ONBOARDING_RETURN_KEY, JSON.stringify({ path, owner, savedAt: now }));
  } catch {
    // A blocked store: onboarding then ends in the chat, as it always did.
  }
}

/** Drop a waiting return written for any other account than this one (or for none). */
export function forgetPostOnboardingReturnOfOthers(win: Window, owner: string): void {
  try {
    const raw = win.localStorage.getItem(POST_ONBOARDING_RETURN_KEY);
    if (!raw) return;
    let stored: unknown = null;
    try { stored = (JSON.parse(raw) as { owner?: unknown }).owner; } catch { /* unreadable: dropped below */ }
    if (stored !== owner) win.localStorage.removeItem(POST_ONBOARDING_RETURN_KEY);
  } catch {
    // A blocked store holds nothing to forget.
  }
}

/** The page to go back to, once: read and forgotten. Null when none, stale or not a page to pay from. */
export function takePostOnboardingReturn(win: Window, now: number = Date.now()): string | null {
  try {
    const raw = win.localStorage.getItem(POST_ONBOARDING_RETURN_KEY);
    if (!raw) return null;
    win.localStorage.removeItem(POST_ONBOARDING_RETURN_KEY);
    const parsed = JSON.parse(raw) as { path?: unknown; owner?: unknown; savedAt?: unknown };
    if (typeof parsed.path !== 'string' || typeof parsed.owner !== 'string' || typeof parsed.savedAt !== 'number') return null;
    if (parsed.savedAt > now || now - parsed.savedAt > MAX_AGE_MS) return null;
    return isPostOnboardingReturnPath(parsed.path) ? parsed.path : null;
  } catch {
    return null;
  }
}
