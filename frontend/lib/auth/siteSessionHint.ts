import { SITE_URL } from '@/lib/seo/siteUrl';

/**
 * Whether this browser is signed in to the app, for the pages of the site that cannot read the
 * app's session: the docs subdomain (docs.livecontext.ai) is another origin, and the session
 * lives in the app origin's storage. The app writes this cookie on the site's registrable domain
 * while an account is signed in, and clears it when it is not; the docs header reads it to show
 * the account instead of "Sign in" / "Get started".
 *
 * <p>A hint, not a credential: it holds the account's initials and nothing else (no token, no
 * name, no id), readable by script on purpose. A stale one (a session that expired while the
 * app was not open) shows "Open the app", which then asks to sign in.
 */
export const SITE_SESSION_HINT_COOKIE = 'lc_account';

/** Thirty days, renewed each time the app loads signed in. */
const MAX_AGE_SECONDS = 30 * 24 * 3600;

/** What the cookie may hold: one or two letters or digits of any script (the initials), or "?". */
const VALUE = /^[\p{L}\p{N}?]{1,2}$/u;

/**
 * The domain to set the cookie on: the site's host with a leading dot on the site itself and on
 * its docs subdomain, the two that share it; null on any other host (localhost, a staging host
 * under the same domain), which keeps a host-only cookie and so never writes or clears the
 * production one.
 */
export function siteCookieDomain(hostname: string, siteUrl: string = SITE_URL): string | null {
  let site: string;
  try {
    site = new URL(siteUrl).hostname;
  } catch {
    return null;
  }
  return hostname === site || hostname === `docs.${site}` ? `.${site}` : null;
}

/** The initials the cookie string holds, or null when there is none (or it is not one we wrote). */
export function readSiteSessionHint(cookie: string): string | null {
  const match = cookie.match(new RegExp(`(?:^|;\\s*)${SITE_SESSION_HINT_COOKIE}=([^;]*)`));
  if (!match) return null;
  let value: string;
  try {
    value = decodeURIComponent(match[1]);
  } catch {
    return null;
  }
  return VALUE.test(value) ? value : null;
}

function cookieAttributes(location: Pick<Location, 'hostname' | 'protocol'>): string {
  const domain = siteCookieDomain(location.hostname);
  const secure = location.protocol === 'https:' ? '; Secure' : '';
  return `; Path=/${domain ? `; Domain=${domain}` : ''}; SameSite=Lax${secure}`;
}

/** Records that this browser is signed in, with the account's initials. */
export function writeSiteSessionHint(initials: string, doc: Document = document): void {
  const value = initials.toUpperCase();
  if (!VALUE.test(value)) return;
  doc.cookie = `${SITE_SESSION_HINT_COOKIE}=${encodeURIComponent(value)}; Max-Age=${MAX_AGE_SECONDS}${cookieAttributes(doc.location)}`;
}

/** Forgets it: on sign-out, and whenever the app finds no signed-in account. */
export function clearSiteSessionHint(doc: Document = document): void {
  if (!readSiteSessionHint(doc.cookie)) return;
  doc.cookie = `${SITE_SESSION_HINT_COOKIE}=; Max-Age=0${cookieAttributes(doc.location)}`;
}

/** The name an OIDC profile carries (the auth context's `user` IS the profile), or null. */
export function accountDisplayName(profile: unknown): string | null {
  const p = profile as Record<string, unknown> | null | undefined;
  const name = p?.name ?? p?.preferred_username ?? p?.email;
  return typeof name === 'string' && name.trim() ? name.trim() : null;
}

/** Scripts written without spaces between words, where two characters are often a whole name. */
const NO_WORD_SPACES = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u;

/**
 * One or two letters for a name ("Lucas Martin" -> "LM", "lucas" -> "LU"), "?" without one. A
 * name in a script without word spaces gives ONE character ("王伟" -> "王", and "伟 王" from a
 * first and a last name -> "伟"): there two characters are often the whole name, and these
 * initials travel in a cookie.
 */
export function initials(name: string | null): string {
  if (!name) return '?';
  const words = name.replace(/@.*$/, '').split(/[\s._-]+/).filter(Boolean);
  if (!words.length) return '?';
  const chars = (word: string) => Array.from(word);
  const first = chars(words[0])[0];
  if (NO_WORD_SPACES.test(first)) return first;
  if (words.length > 1) return (first + chars(words[words.length - 1])[0]).toUpperCase();
  return chars(words[0]).slice(0, 2).join('').toUpperCase();
}
