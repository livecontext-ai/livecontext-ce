/**
 * A partner / creator code carried across sign-up (cloud only).
 *
 * <p>A creator shares a link such as `https://livecontext.ai/?lc_ref=TECHDOX` (any page) or
 * `/redeem?code=TECHDOX`. The parameter is deliberately NOT the conventional `?ref=`: Product
 * Hunt and most directories tag their outbound links `?ref=<site>`, which would store the
 * directory's name as a code and, first code winning, block the real partner link after it.
 *
 * <p>The visitor is usually signed out, and the redeem call needs a session, so the code is
 * remembered here and applied once they are signed in
 * ({@link PendingRewardCodeRedeemer}). First code wins, like the server's attribution: a later
 * link never replaces a code still waiting. A code older than {@link MAX_AGE_MS} is dropped.
 *
 * <p>This is functional storage (it delivers the benefit the visitor followed a link for), not
 * tracking, so it does not wait for the analytics consent. Every storage access is guarded: a
 * blocked store must never break a page.
 */

export const PENDING_REWARD_CODE_KEY = 'lc_pending_code_v1';

/** A code waiting longer than this is forgotten. */
export const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

/** Same alphabet the server accepts for an admin-chosen code, plus the lower case it upper-cases. */
const CODE_RE = /^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$/;

interface Stored {
  code: string;
  savedAt: number;
}

/** The partner-link query parameter (see the header for why it is not `ref`). */
export const PARTNER_LINK_PARAM = 'lc_ref';

/** The code a URL carries: `?lc_ref=` on any page, or `?code=` on the /redeem landing. */
export function codeFromUrl(url: URL): string | null {
  const ref = url.searchParams.get(PARTNER_LINK_PARAM);
  const fromRedeem = /\/redeem\/?$/.test(url.pathname) ? url.searchParams.get('code') : null;
  const candidate = (ref ?? fromRedeem ?? '').trim();
  return CODE_RE.test(candidate) ? candidate.toUpperCase() : null;
}

function read(storage: Storage): Stored | null {
  try {
    const raw = storage.getItem(PENDING_REWARD_CODE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<Stored>;
    if (typeof parsed.code !== 'string' || typeof parsed.savedAt !== 'number') return null;
    return { code: parsed.code, savedAt: parsed.savedAt };
  } catch {
    return null;
  }
}

/**
 * Remember the code in the current URL, unless a fresh one is already waiting. An explicit visit
 * to /redeem?code= is a deliberate choice and replaces a waiting code; a passing ?lc_ref= never does.
 */
export function capturePendingRewardCode(win: Window, now: number = Date.now()): string | null {
  let url: URL;
  try {
    url = new URL(win.location.href);
  } catch {
    return null;
  }
  const code = codeFromUrl(url);
  if (!code) return null;
  return keep(win, code, now, /\/redeem\/?$/.test(url.pathname));
}

/**
 * Remember a partner code met outside a URL parameter (a partner's offer page, whose short link
 * carries a token rather than the code): handled like a passing `?lc_ref=` link, so a fresh code
 * already waiting is kept (first code wins). Returns the code that will apply.
 */
export function rememberPendingRewardCode(win: Window, rawCode: string, now: number = Date.now()): string | null {
  const candidate = (rawCode ?? '').trim();
  if (!CODE_RE.test(candidate)) return null;
  return keep(win, candidate.toUpperCase(), now, false);
}

/**
 * Store a code, unless (when it does not replace) a fresh one is already waiting. Returns the code
 * that will apply. A blocked store keeps nothing: the code can still be typed (pre-filled on
 * /redeem), and an offer page applies the one it remembered.
 */
function keep(win: Window, code: string, now: number, replace: boolean): string {
  try {
    const existing = read(win.localStorage);
    if (!replace && existing && now - existing.savedAt < MAX_AGE_MS) return existing.code;
    win.localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code, savedAt: now }));
  } catch {
    // Blocked store: see above.
  }
  return code;
}

/** The waiting code, or null when none (or it expired, in which case it is also forgotten). */
export function readPendingRewardCode(win: Window, now: number = Date.now()): string | null {
  try {
    const stored = read(win.localStorage);
    if (!stored) return null;
    if (now - stored.savedAt >= MAX_AGE_MS) {
      win.localStorage.removeItem(PENDING_REWARD_CODE_KEY);
      return null;
    }
    return stored.code;
  } catch {
    return null;
  }
}

export function clearPendingRewardCode(win: Window): void {
  try {
    win.localStorage.removeItem(PENDING_REWARD_CODE_KEY);
  } catch {
    // Nothing to clear in.
  }
}
