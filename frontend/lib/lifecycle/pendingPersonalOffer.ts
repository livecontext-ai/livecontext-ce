/** A personal offer is separate from the partner attribution slot. */
export const PERSONAL_OFFER_PARAM = 'lc_offer';
export const PENDING_PERSONAL_OFFER_KEY = 'lc_pending_personal_offer_v1';
const MAX_AGE_MS = 4 * 24 * 60 * 60 * 1000;
const CODE_RE = /^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$/;

type Pending = { code: string; savedAt: number };

export function personalOfferCodeFromUrl(url: URL): string | null {
  const code = (url.searchParams.get(PERSONAL_OFFER_PARAM) ?? '').trim();
  return CODE_RE.test(code) ? code.toUpperCase() : null;
}

export function readPendingPersonalOffer(win: Window, now = Date.now()): string | null {
  try {
    const raw = win.sessionStorage.getItem(PENDING_PERSONAL_OFFER_KEY);
    if (!raw) return null;
    const pending = JSON.parse(raw) as Pending;
    if (!CODE_RE.test(pending.code) || !Number.isFinite(pending.savedAt) ||
        pending.savedAt > now || now - pending.savedAt >= MAX_AGE_MS) {
      win.sessionStorage.removeItem(PENDING_PERSONAL_OFFER_KEY);
      return null;
    }
    return pending.code;
  } catch {
    return null;
  }
}

/** Capture only a personal-offer link. Leave lc_ref and its first-code-wins slot alone. */
export function capturePendingPersonalOffer(win: Window, now = Date.now()): string | null {
  let url: URL;
  try {
    url = new URL(win.location.href);
  } catch {
    return null;
  }
  const code = personalOfferCodeFromUrl(url);
  if (!code) return null;
  try {
    win.sessionStorage.setItem(PENDING_PERSONAL_OFFER_KEY, JSON.stringify({ code, savedAt: now }));
    // Keep the selection, Stripe return parameters and referral attribution in the URL.
    url.searchParams.delete(PERSONAL_OFFER_PARAM);
    win.history.replaceState(win.history.state, '', `${url.pathname}${url.search}${url.hash}`);
  } catch {
    // When storage is blocked, keep the code in the URL for the sign-in return.
  }
  return code;
}

export function clearPendingPersonalOffer(win: Window): void {
  try {
    win.sessionStorage.removeItem(PENDING_PERSONAL_OFFER_KEY);
  } catch {
    // A blocked store is already empty from the caller's perspective.
  }
}
