/**
 * The time zone every absolute timestamp in the app is DISPLAYED in.
 *
 * The server stores and serves UTC; this module only decides how it is shown. One resolution
 * order, used by `dateFormatters` and by anything that needs the zone itself (the default of a
 * new schedule trigger, a recurrence):
 *
 *   1. the zone applied from the signed-in person's preference (`auth.users.time_zone`,
 *      pushed here by `useDisplayPreferences` as soon as the profile loads);
 *   2. the `LC_TZ` cookie, which mirrors (1) so the FIRST paint - and every route outside the
 *      /app shell, such as the workflow builder - is already right instead of flashing another
 *      zone for one render;
 *   3. the browser's own zone, which is also what the app reports to the backend when the
 *      person has never picked one (an "auto" default, the same rule as Notion or Linear);
 *   4. `UTC`, the only sane answer with no browser (SSR) and no cookie.
 *
 * Never read `Intl...resolvedOptions().timeZone` directly for DISPLAY: that is the browser's
 * zone, not the zone the person chose, so someone who picked Tokyo while travelling in Paris
 * would read Paris times. Go through `getClientTimeZone()`.
 *
 * <p>ON THE SERVER this answers UTC, because neither the cookie nor `Intl` is reachable from a
 * plain module: a date rendered by a SERVER component is in UTC and is not personalized. It is
 * only reachable on the few public, unauthenticated pages that render a date at all (marketplace,
 * videos); everything behind sign-in renders its dates from data fetched on the client, where the
 * preference is known. A server component that ever needs the real zone must read the cookie
 * through `next/headers` and pass it as `timeZone`.
 *
 * <p>This used to be self-describing, and is no longer. The formatters appended the zone to every
 * instant, so a server-rendered date read "... UTC" and no reader could be misled. They now label
 * a zone only when a caller PINNED one (see `dateFormatters`), and UTC-by-absence is not a pin, so
 * those public dates carry no attribution. Still correct - UTC is what the server knows - but a
 * public page showing an absolute date should name the zone itself, the way `lib/status/format.ts`
 * does for the status page.
 */

/** Mirrors the applied preference so a first paint and the non-/app routes get it too. */
export const DISPLAY_TIME_ZONE_COOKIE = 'LC_TZ';

const UTC = 'UTC';

/** Applied preference for this page load. Null until the profile arrives (or on the server). */
let appliedTimeZone: string | null = null;

/** True when `Intl` accepts `tz` as a zone id. Cheap, and it never throws on odd input. */
const validated = new Map<string, boolean>();

export function isValidTimeZone(tz: string | null | undefined): boolean {
  if (!tz) return false;
  const known = validated.get(tz);
  if (known !== undefined) return known;
  // Memoized: the answer for a given id cannot change, and this runs on the path where a caller
  // pins a zone - i.e. once per ROW of a list. Constructing an Intl.DateTimeFormat to answer it
  // measured ~292 microseconds a call, which is the same cost the formatter cache exists to
  // remove, reintroduced one layer down.
  let valid: boolean;
  try {
    new Intl.DateTimeFormat('en', { timeZone: tz });
    valid = true;
  } catch {
    valid = false;
  }
  validated.set(tz, valid);
  return valid;
}

/** The browser's own zone, or undefined when `Intl` cannot report one. */
export function getBrowserTimeZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
  } catch {
    return undefined;
  }
}

const COOKIE_RE = new RegExp(`(?:^|;\\s*)${DISPLAY_TIME_ZONE_COOKIE}=([^;]+)`);

function readCookie(): string | undefined {
  try {
    const match = document.cookie.match(COOKIE_RE);
    return match ? decodeURIComponent(match[1]) : undefined;
  } catch {
    return undefined;
  }
}

/**
 * The resolved answer for this page load, so the fallback path costs one lookup instead of one
 * per formatted date.
 *
 * <p>It matters because the fallback path is the COMMON one outside the `/app` shell (the workflow
 * builder, which renders long lists of timestamps, mounts no preferences gate). Without this,
 * every row re-read `document.cookie` and validated the result by constructing up to two
 * `Intl.DateTimeFormat`s - the most expensive way there is to answer a question whose answer
 * cannot change until something calls {@link applyDisplayTimeZone} or
 * {@link clearDisplayTimeZone}, both of which reset it.
 *
 * <p>The cost of the cache: a zone changed in ANOTHER TAB is not picked up here until this one
 * reloads, because nothing re-reads the cookie in between. Accepted deliberately - the
 * alternative is re-reading and re-validating per formatted date, on a page that renders
 * hundreds - and harmless, since each tab is internally consistent and the next navigation
 * agrees with the account.
 */
let resolvedTimeZone: string | null = null;

/** Subscribers re-rendered when the applied zone changes (see `DisplayPreferencesGate`). */
const listeners = new Set<() => void>();

export function subscribeToDisplayTimeZone(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Apply the person's stored zone for the rest of this page load, and mirror it into the cookie.
 * Called with the value read back from the profile; an invalid or empty value is ignored, which
 * leaves the browser zone in charge rather than forcing UTC.
 *
 * @param options.notify whether subscribers are told. Default true.
 *
 *   Pass {@code false} when the CALLER is the reason the zone changed and can show the result
 *   itself. The only subscriber remounts the /app shell, which throws away every unsaved field on
 *   screen and every running stream in it, and the Settings row that owns this preference sits
 *   INSIDE that shell: notifying from there destroyed the password form beside it and dropped the
 *   person back on the Profile tab, as the price of redrawing dates they were not looking at. The
 *   row shows the new zone's current time instead, and the rest of the app is correct from the
 *   next navigation.
 */
export function applyDisplayTimeZone(
  tz: string | null | undefined,
  options?: { notify?: boolean }
): void {
  // Module state on a Next.js server is shared by every request, so a zone applied there would
  // leak one person's preference into everyone else's server-rendered HTML. There is nothing to
  // apply without a browser anyway.
  if (typeof window === 'undefined') return;
  if (!isValidTimeZone(tz)) return;

  // What is ALREADY ON SCREEN, which is what the subscribers care about - not `appliedTimeZone`,
  // which is null on every fresh page load even when the cookie has been serving that exact zone
  // since the first paint. Guarding on the latter made the common case (profile loads, confirms
  // the zone already in use) notify anyway, so the gate tore down and rebuilt the shell once per
  // page load for every signed-in person - the opposite of what it exists for.
  const onScreen = getClientTimeZone();

  appliedTimeZone = tz as string;
  resolvedTimeZone = tz as string;
  try {
    document.cookie = `${DISPLAY_TIME_ZONE_COOKIE}=${encodeURIComponent(tz as string)}; path=/; max-age=31536000; SameSite=Lax`;
  } catch {
    // No cookie store (private mode, blocked): the in-memory value still serves this load.
  }
  if (options?.notify !== false && onScreen !== tz) {
    listeners.forEach((listener) => listener());
  }
}

/** Forget the applied zone and its cookie mirror: used when signing out, and by tests. */
export function clearDisplayTimeZone(): void {
  if (typeof window === 'undefined') {
    appliedTimeZone = null;
    resolvedTimeZone = null;
    return;
  }
  // Same rule as above: what changes for a reader is the zone dates are drawn in, so compare the
  // zone in effect before against the one that takes over (the browser's, once nothing is pinned).
  const before = getClientTimeZone();
  appliedTimeZone = null;
  resolvedTimeZone = null;
  try {
    document.cookie = `${DISPLAY_TIME_ZONE_COOKIE}=; path=/; max-age=0; SameSite=Lax`;
  } catch {
    // Nothing to clear in.
  }
  // `resolve`, not `getClientTimeZone`: this must not re-populate the cache it has just emptied.
  // Doing so left the browser zone memoized behind a cookie that had only just been written, so a
  // later sign-in read the wrong zone from a module that reported itself as cleared.
  if (before !== resolve()) listeners.forEach((listener) => listener());
}

/** The zone in effect right now, computed from scratch. Callers decide whether to memoize it. */
function resolve(): string {
  if (appliedTimeZone) return appliedTimeZone;
  if (typeof window === 'undefined') return UTC;

  const fromCookie = readCookie();
  if (isValidTimeZone(fromCookie)) return fromCookie as string;
  const fromBrowser = getBrowserTimeZone();
  return isValidTimeZone(fromBrowser) ? (fromBrowser as string) : UTC;
}

/**
 * The zone to display timestamps in. Safe anywhere, including plain utils and module-level
 * helpers that cannot call a React hook.
 */
export function getClientTimeZone(): string {
  if (appliedTimeZone) return appliedTimeZone;
  if (typeof window === 'undefined') return UTC;
  if (resolvedTimeZone) return resolvedTimeZone;

  resolvedTimeZone = resolve();
  return resolvedTimeZone;
}
