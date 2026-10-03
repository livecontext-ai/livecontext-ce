/**
 * The ONE validator for every "where to go after this" value the frontend receives from the
 * outside world: the `returnTo` query parameter of the login and register pages, the
 * `appState.returnTo` handed to the sign-in redirect, and return paths read back from browser
 * storage (ASVS 5.1.5, open redirect).
 *
 * Only a same-origin RELATIVE path is accepted: one leading `/`, not `//` or `/\` (both are
 * protocol-relative forms a browser resolves to ANOTHER host) and no `//` anywhere else in the
 * path either (a locale-strip redirect can bring it to the front), no scheme (`javascript:`,
 * `data:`, `https://evil.example`), no backslash, no control character (a tab or newline is
 * silently dropped by the URL parser, so `/\t/evil.example` would become `//evil.example`).
 * The same checks run on the percent-decoded value, so an encoded `%2F%2Fevil.example` cannot
 * slip through a consumer that decodes once more. Mirrors the backend `OAuth2ReturnPath`.
 * These shape checks apply to the PATH part only: the query and fragment cannot change the
 * origin and may carry encoded newlines, backslashes or colons (`?q=a%0Ab`). A raw (unencoded)
 * control character is refused anywhere in the value.
 *
 * An absolute URL on the CURRENT origin is tolerated and reduced to its path: the CE embedded
 * sign-in hands the login page `${origin}/app/...`, which is same-origin by construction.
 *
 * A same-origin path that is NOT a page is refused too (defence in depth): the ingress or a
 * `next.config.mjs` rewrite sends `/api`, `/mcp`, `/webhook*`, `/ws`, `/approval-callback`,
 * `/chat`, `/form`, `/c`, `/share`, `/app/public/` and the widget endpoints to backend
 * services, and `/_next` and `/.well-known` are framework or protocol files, so a signed-in user
 * must never land on one of them, directly or through the middleware's locale-strip redirect
 * (`/en/api/x` -> `/api/x`). See {@link isNonPageTarget} for how the path is normalized.
 */

import { locales } from '@/i18n/routing';

/** Upper bound so a hostile value cannot bloat a URL or a storage entry. */
const MAX_LENGTH = 2048;

/** C0 controls, DEL and the C1 range: never part of a legitimate return path. */
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f-\u009f]/;

/** RFC 3986 scheme prefix (`javascript:`, `https:`, ...). */
const SCHEME_PREFIX = /^[a-zA-Z][a-zA-Z0-9+.-]*:/;

/** A fixed base used only to prove the path cannot leave its origin once resolved. */
const PROBE_BASE = 'http://return-path.invalid';

/**
 * Shape of the PATH part (no query, no fragment): where a browser could be sent off-origin. No
 * `//` ANYWHERE, not only at the start: the middleware strips a leading locale with a redirect, so
 * `/en//2130706433` would become the protocol-relative `//2130706433` (another host) if anything
 * on the way did not normalise it first.
 * KEEP IN SYNC with `hasSafeShape` in the backend `OAuth2ReturnPath`.
 */
function hasSafeShape(path: string): boolean {
  return path.startsWith('/')
    && !path.includes('//')
    && !path.includes('\\')
    && !CONTROL_CHARS.test(path);
}

/**
 * The path part of a relative reference: everything before the first `?` or `#`. The query and
 * the fragment cannot move the target to another origin, and they legitimately carry encoded
 * newlines, backslashes or colons (`?q=a%0Ab`, `?path=C%3A%5C`), so the shape checks (raw and
 * decoded) run on the path alone. A RAW control character is still refused anywhere (below):
 * a URL a browser or the app produced never carries one unencoded.
 */
function pathPart(value: string): string {
  const end = value.search(/[?#]/);
  return end === -1 ? value : value.slice(0, end);
}

/**
 * One LENIENT round of percent-decoding: every run of `%XX` escapes is decoded, a malformed `%`
 * stays literal, and a run that is not valid UTF-8 still has its ASCII escapes decoded. Strict
 * `decodeURIComponent` throws on the whole value instead, which used to skip every decoded check:
 * `/%61pi/x%zz` was then judged on its raw form only. Never throws.
 * KEEP IN SYNC with `percentDecode` in the backend `OAuth2ReturnPath`.
 */
function lenientDecode(value: string): string {
  return value.replace(/(?:%[0-9a-fA-F]{2})+/g, (run) => {
    try {
      return decodeURIComponent(run);
    } catch {
      return run.replace(/%[0-7][0-9a-fA-F]/g, (escape) =>
        String.fromCharCode(parseInt(escape.slice(1), 16)));
    }
  });
}

/** Rounds of percent-decoding a return path may need; more than this is refused outright. */
const MAX_DECODE_ROUNDS = 3;

/**
 * Up to {@link MAX_DECODE_ROUNDS} rounds of (lenient) percent-decoding, stopping at the first
 * stable value; null when the value is STILL changing after the last round (a 4x-encoded `/api`
 * would otherwise be judged without ever being seen decoded).
 */
function decodedForms(value: string): string[] | null {
  const forms: string[] = [];
  let current = value;
  for (let i = 0; i < MAX_DECODE_ROUNDS; i++) {
    const next = lenientDecode(current);
    if (next === current) return forms;
    forms.push(next);
    current = next;
  }
  return lenientDecode(current) === current ? forms : null;
}

/**
 * Paths that are never a page: what the prod ingress or a `next.config.mjs` rewrite sends to the
 * gateway, plus framework and protocol files. Each entry copies how its route really matches:
 * - STRING prefixes, because the prod ingress (Traefik) matches `pathType: Prefix` as a string
 *   (`/api;x`, `/api.json`, `/wsx` reach the gateway) and no page starts with these letters:
 *   `/api` (incl. `/api/proxy`), `/ws`, `/approval-callback`, `/webhook` (+ `/webhooks`),
 *   `/mcp`, `/form`.
 * - `/chat` likewise, bare form included: the cloud ingress takes every `/chat*` before the
 *   middleware could redirect the legacy `/chat` and `/chat/c/<id>`.
 * - `/widget/` with its slash and `/widget.js` exactly, as the ingress writes them (the bare
 *   `/widget` and `/widget-demo` stay with the frontend).
 * - whole segments: `/c` and `/share` (next.config rewrites, bare form included; `/shared` and
 *   `/compare` are untouched), `/_next`, `/.well-known`, and `/app/public/` (rewritten after the
 *   middleware lets it through; locale-prefixed `/en/app/public` is a page).
 * A leading locale does not hide them: see {@link isNonPagePath}.
 * Exported only for the backend parity test.
 * KEEP IN SYNC with `NON_PAGE_PREFIX` in the backend `OAuth2ReturnPath` (auth-service), which
 * applies the same list and normalization to the OAuth connect `return_url`
 * (`__tests__/safeReturnPath.backendParity.test.ts` compares the two).
 */
export const NON_PAGE_PREFIX =
  /^\/(?:api|ws|approval-callback|webhook|mcp|form|chat|widget\/|widget\.js$|(?:c|share|_next|\.well-known)(?:\/|$)|app\/public\/)/;

/** A leading supported locale segment (`/en`, `/fr/...`), from the i18n routing config. */
const LOCALE_SEGMENT = new RegExp(`^/(?:${locales.join('|')})(?=/|$)`);

/**
 * Locale-required area that overlaps the deny-list: `/<locale>/app/public/...` is a page.
 * Exported only for the backend parity test.
 */
export const LOCALE_RENDERED_AREA = /^\/app(?:\/|$)/;

/**
 * @return true when the resolved, lower-cased {@code path} is a non-page target, either as is or
 * once its leading locale is stripped. The middleware 308-redirects `/<locale><rest>` to `<rest>`
 * whenever `<rest>` is not a locale-required area, so `/en/api/proxy/x` lands on `/api/proxy/x`
 * and `/en/fr/api` on `/api` (one redirect per locale). A locale-required `<rest>` renders under
 * the locale instead, and of those only `/app` (`/app/public/`) overlaps the deny-list, so the
 * walk stops there.
 */
function isNonPagePath(path: string): boolean {
  if (NON_PAGE_PREFIX.test(path)) return true;
  let current = path;
  for (let locale = LOCALE_SEGMENT.exec(current); locale; locale = LOCALE_SEGMENT.exec(current)) {
    current = current.slice(locale[0].length) || '/';
    if (LOCALE_RENDERED_AREA.test(current)) return false;
    if (NON_PAGE_PREFIX.test(current)) return true;
  }
  return false;
}

/**
 * @return true when {@code path} (the path part of a relative reference) names a non-page
 * target. Checked on the raw value and on every percent-decoded form, each one resolved the
 * way a browser would (dot segments removed, so `/en/../api` and `/%2e/api` count), with
 * repeated slashes and backslashes collapsed and case ignored: `/%61pi/x`, `/API/`, `//api`
 * and `/\api` are all caught here even where an earlier shape check would not stop them.
 */
function isNonPageTarget(forms: readonly string[]): boolean {
  for (const form of forms) {
    let resolved: string;
    try {
      resolved = new URL(form.replace(/[\\/]+/g, '/'), PROBE_BASE).pathname;
    } catch {
      return true;
    }
    if (isNonPagePath(resolved.toLowerCase())) return true;
  }
  return false;
}

/** @return true when {@code value} is a same-origin relative path that is safe to navigate to. */
export function isSafeReturnPath(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_LENGTH) return false;
  if (CONTROL_CHARS.test(value)) return false;
  const path = pathPart(value);
  if (!hasSafeShape(path)) return false;
  const decoded = decodedForms(path);
  if (decoded === null || !decoded.every(hasSafeShape)) return false;
  if (isNonPageTarget([path, ...decoded])) return false;
  try {
    return new URL(value, PROBE_BASE).origin === PROBE_BASE;
  } catch {
    return false;
  }
}

function currentOrigin(): string | undefined {
  return typeof window !== 'undefined' ? window.location.origin : undefined;
}

/**
 * @param raw      the untrusted value (query parameter, appState, storage)
 * @param fallback where to go when {@code raw} is missing or unsafe (a constant of the caller)
 * @param origin   the origin an absolute value must match; defaults to the current page's
 * @return {@code raw} reduced to a safe same-origin relative path, or {@code fallback}
 */
export function safeReturnPath(
  raw: string | null | undefined,
  fallback: string,
  origin: string | undefined = currentOrigin(),
): string {
  if (typeof raw !== 'string' || raw.length === 0) return fallback;
  let candidate = raw;
  if (SCHEME_PREFIX.test(raw)) {
    if (!origin) return fallback;
    try {
      const url = new URL(raw);
      if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.origin !== origin) {
        return fallback;
      }
      candidate = `${url.pathname}${url.search}${url.hash}`;
    } catch {
      return fallback;
    }
  }
  return isSafeReturnPath(candidate) ? candidate : fallback;
}
