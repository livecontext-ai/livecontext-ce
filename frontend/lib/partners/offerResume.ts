/**
 * "Carry on to payment" on a partner's offer page, after a round trip the page itself started.
 *
 * <p>The page sends a visitor to sign in (or a fresh account to onboarding) with `continue=1` in its
 * return URL, and on the way back opens the checkout on its own. That query alone must not be
 * enough: anyone can write it into a link, and a signed-in person who opens it would land on a
 * checkout with no click of theirs. So the page also leaves this marker when it starts the round
 * trip, and resumes only when the marker is there for that offer, consuming it.
 *
 * <p>Local storage, not session storage: the email check of a fresh account may continue in
 * another tab. A day at most, one use. A blocked store means no automatic resume: the visitor clicks.
 */

export const OFFER_RESUME_KEY = 'lc_offer_resume_v1';
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

/** Leave the marker for this offer, just before the page leaves for sign-in or onboarding. */
export function markOfferResume(win: Window, token: string, now: number = Date.now()): void {
  try {
    win.localStorage.setItem(OFFER_RESUME_KEY, JSON.stringify({ token, savedAt: now }));
  } catch {
    // Blocked store: the visitor will click to carry on.
  }
}

/** Whether the page started a round trip for this offer (recently): read once, then forgotten. */
export function takeOfferResume(win: Window, token: string, now: number = Date.now()): boolean {
  try {
    const raw = win.localStorage.getItem(OFFER_RESUME_KEY);
    if (!raw) return false;
    const parsed = JSON.parse(raw) as { token?: unknown; savedAt?: unknown };
    // Another offer's marker is left for that offer.
    if (parsed.token !== token) return false;
    win.localStorage.removeItem(OFFER_RESUME_KEY);
    return typeof parsed.savedAt === 'number' && parsed.savedAt <= now && now - parsed.savedAt <= MAX_AGE_MS;
  } catch {
    return false;
  }
}
