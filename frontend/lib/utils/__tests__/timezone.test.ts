/**
 * @vitest-environment jsdom
 *
 * getClientTimeZone and friends: which zone the app DISPLAYS timestamps in.
 *
 * jsdom on purpose. The suite's default environment is `node`, where `window` does not exist,
 * so every branch below except the SSR fallback would be unreachable - which is exactly why the
 * pre-existing dateFormatters tests kept passing unchanged when the formatters stopped forcing
 * UTC: they only ever exercised the no-window path.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  DISPLAY_TIME_ZONE_COOKIE,
  applyDisplayTimeZone,
  clearDisplayTimeZone,
  getBrowserTimeZone,
  getClientTimeZone,
  isValidTimeZone,
  subscribeToDisplayTimeZone,
} from '../timezone';

/**
 * The browser zone, forced to something that is NOT UTC.
 *
 * <p>Every assertion of the shape `getClientTimeZone() === getBrowserTimeZone()` is vacuous on a
 * UTC machine: an implementation that ignored the browser entirely and always answered "UTC"
 * satisfies it, and CI runners are UTC. `TZ=...` is no help - on Windows it does not change what
 * Node resolves - so the zone is stubbed in-process, on the no-argument call only, which is the
 * one `getBrowserTimeZone` makes. Calls WITH arguments go to the real implementation, because
 * `isValidTimeZone` and every formatter depend on them.
 */
const DEVICE = 'America/Denver';
const RealDateTimeFormat = Intl.DateTimeFormat;

function forceDeviceZone() {
  // A plain function, NOT an arrow: callers reach this through "new Intl.DateTimeFormat(...)", and
  // an arrow is not constructible, so an arrow stub made every zone validation throw - which
  // isValidTimeZone swallows into "invalid", quietly failing 13 unrelated tests.
  vi.spyOn(Intl, 'DateTimeFormat').mockImplementation(function (
    ...args: unknown[]
  ) {
    if (args.length === 0) {
      return { resolvedOptions: () => ({ timeZone: DEVICE }) } as unknown as Intl.DateTimeFormat;
    }
    return new (RealDateTimeFormat as unknown as new (...a: unknown[]) => Intl.DateTimeFormat)(
      ...args
    );
  } as unknown as typeof Intl.DateTimeFormat);
}

/** jsdom keeps cookies for the document's lifetime, so each test starts from a clean one. */
function wipeCookies() {
  for (const pair of document.cookie.split(';')) {
    const name = pair.split('=')[0]?.trim();
    if (name) document.cookie = `${name}=; path=/; max-age=0`;
  }
}

beforeEach(() => {
  clearDisplayTimeZone();
  wipeCookies();
  forceDeviceZone();
});

afterEach(() => {
  vi.restoreAllMocks();
  clearDisplayTimeZone();
  wipeCookies();
});

describe('isValidTimeZone', () => {
  it('accepts an IANA zone id and UTC', () => {
    expect(isValidTimeZone('Europe/Paris')).toBe(true);
    expect(isValidTimeZone('UTC')).toBe(true);
  });

  it('rejects an invented zone, a blank and a nullish value', () => {
    expect(isValidTimeZone('Mars/Base')).toBe(false);
    expect(isValidTimeZone('')).toBe(false);
    expect(isValidTimeZone(null)).toBe(false);
    expect(isValidTimeZone(undefined)).toBe(false);
  });
});

describe('applyDisplayTimeZone', () => {
  it('makes the applied zone the display zone, and mirrors it into the cookie', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    expect(getClientTimeZone()).toBe('Asia/Tokyo');
    expect(document.cookie).toContain(`${DISPLAY_TIME_ZONE_COOKIE}=Asia%2FTokyo`);
  });

  it('ignores an invalid or empty zone, leaving the previous answer in place', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    applyDisplayTimeZone('Mars/Base');
    applyDisplayTimeZone('');
    applyDisplayTimeZone(null);

    expect(getClientTimeZone()).toBe('Asia/Tokyo');
  });

  it('survives a browser that refuses cookies: the zone still applies for this page load', () => {
    // A private window, or blocked site data: the setter throws. The in-memory value is the
    // one every formatter reads, so the page must still be right.
    const original = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => '',
      set: () => {
        throw new Error('blocked');
      },
    });

    expect(() => applyDisplayTimeZone('Asia/Tokyo')).not.toThrow();
    expect(getClientTimeZone()).toBe('Asia/Tokyo');

    // Drop the per-instance override so later tests see jsdom's real cookie jar again, then
    // put the prototype accessor back exactly as it was.
    delete (document as unknown as Record<string, unknown>).cookie;
    if (original) Object.defineProperty(Document.prototype, 'cookie', original);
  });
});

describe('getClientTimeZone resolution order', () => {
  it('reads the cookie when nothing has been applied yet, which is what the first paint uses', () => {
    document.cookie = `${DISPLAY_TIME_ZONE_COOKIE}=${encodeURIComponent('America/New_York')}; path=/`;

    expect(getClientTimeZone()).toBe('America/New_York');
  });

  it('prefers the applied zone over the cookie', () => {
    document.cookie = `${DISPLAY_TIME_ZONE_COOKIE}=${encodeURIComponent('America/New_York')}; path=/`;
    applyDisplayTimeZone('Asia/Tokyo');

    expect(getClientTimeZone()).toBe('Asia/Tokyo');
  });

  it('ignores a corrupt cookie and falls through to the browser zone', () => {
    document.cookie = `${DISPLAY_TIME_ZONE_COOKIE}=Mars%2FBase; path=/`;

    expect(getClientTimeZone()).toBe(DEVICE);
    expect(getBrowserTimeZone()).toBe(DEVICE);
  });

  it('falls back to the browser zone when nothing is stored, the "auto" default', () => {
    expect(getClientTimeZone()).toBe(DEVICE);
  });

  it('answers UTC when even the browser reports no zone', () => {
    vi.spyOn(Intl, 'DateTimeFormat').mockImplementation(
      () => ({ resolvedOptions: () => ({ timeZone: undefined }) }) as unknown as Intl.DateTimeFormat
    );

    expect(getClientTimeZone()).toBe('UTC');
  });
});

describe('clearDisplayTimeZone', () => {
  it('drops the applied zone and its cookie, so a next signed-in person starts clean', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    clearDisplayTimeZone();

    expect(document.cookie).not.toContain('Asia%2FTokyo');
    expect(getClientTimeZone()).toBe(DEVICE);
  });

  it('leaves NOTHING memoized, so a zone arriving after it is still seen', () => {
    // The assertion above passes even against a module that answered from a stale cache, because
    // the value cached at clear time happens to be the browser zone it expects. What separates
    // "cleared" from "cached the right thing by luck" is writing a zone AFTERWARDS: sign-out then
    // sign-in as someone else is exactly that sequence, and a memoized answer served them the
    // previous person's zone.
    applyDisplayTimeZone('Asia/Tokyo');
    clearDisplayTimeZone();

    document.cookie = `${DISPLAY_TIME_ZONE_COOKIE}=America%2FDenver; path=/`;

    expect(getClientTimeZone()).toBe('America/Denver');
  });
});

describe('who gets told about a change', () => {
  /** Subscribes and counts, the way DisplayPreferencesGate does. */
  function countNotifications() {
    const calls = { n: 0 };
    const stop = subscribeToDisplayTimeZone(() => { calls.n += 1; });
    return { calls, stop };
  }

  it('says nothing when the zone applied is the one already on screen via the cookie', () => {
    // The overwhelmingly common case: a returning person's first paint already used their cookie,
    // then the profile confirms the same zone. Nothing changed for them, so nothing is notified -
    // the only subscriber remounts the app shell.
    document.cookie = `${DISPLAY_TIME_ZONE_COOKIE}=Asia%2FTokyo; path=/`;
    expect(getClientTimeZone()).toBe('Asia/Tokyo');
    const { calls, stop } = countNotifications();

    applyDisplayTimeZone('Asia/Tokyo');

    expect(calls.n).toBe(0);
    stop();
  });

  it('says nothing when the stored zone is this device zone', () => {
    const { calls, stop } = countNotifications();

    applyDisplayTimeZone(DEVICE);

    expect(calls.n).toBe(0);
    stop();
  });

  it('tells subscribers when the zone genuinely differs from the one on screen', () => {
    document.cookie = `${DISPLAY_TIME_ZONE_COOKIE}=Asia%2FTokyo; path=/`;
    expect(getClientTimeZone()).toBe('Asia/Tokyo');
    const { calls, stop } = countNotifications();

    applyDisplayTimeZone('America/Denver');

    expect(calls.n).toBe(1);
    stop();
  });

  it('tells subscribers when clearing a pin hands display back to another zone', () => {
    applyDisplayTimeZone('Asia/Tokyo');
    const { calls, stop } = countNotifications();

    clearDisplayTimeZone();

    // The device zone is forced away from Tokyo above, so this is one notification, not a
    // conditional that would have accepted zero on a machine that happened to be in Tokyo.
    expect(calls.n).toBe(1);
    stop();
  });

  it('stops telling a subscriber that unsubscribed', () => {
    // A zone that DIFFERS from the one on screen, or the notification is skipped anyway and the
    // test passes against an unsubscribe that does nothing. The forced device zone is Denver,
    // which is exactly what the previous version applied.
    const { calls, stop } = countNotifications();
    applyDisplayTimeZone('Asia/Tokyo');
    expect(calls.n).toBe(1);

    stop();
    applyDisplayTimeZone('America/Denver');

    expect(calls.n).toBe(1);
  });
});
