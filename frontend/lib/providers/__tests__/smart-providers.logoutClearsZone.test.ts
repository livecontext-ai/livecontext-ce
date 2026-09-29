/**
 * @vitest-environment jsdom
 *
 * Signing out forgets the display time zone.
 *
 * The zone belongs to the ACCOUNT, but its `LC_TZ` mirror is a year-long cookie on the BROWSER.
 * Left behind, it hands the next person to sign in here the previous one's pinned zone for their
 * whole first session - every timestamp in the product, with nothing on screen to explain it.
 *
 * This file exists because the guard it pins lives in one line of a 1200-line provider that no
 * test rendered, and an audit found it uncovered: the SECONDARY guard (useDisplayPreferences
 * overriding once a profile lands) had a test, the primary one had none.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  DISPLAY_TIME_ZONE_COOKIE,
  applyDisplayTimeZone,
  clearDisplayTimeZone,
  getClientTimeZone,
  getBrowserTimeZone,
} from '@/lib/utils/timezone';

function wipeCookies() {
  for (const pair of document.cookie.split(';')) {
    const name = pair.split('=')[0]?.trim();
    if (name) document.cookie = `${name}=; path=/; max-age=0`;
  }
}

beforeEach(() => {
  clearDisplayTimeZone();
  wipeCookies();
});
afterEach(() => {
  clearDisplayTimeZone();
  wipeCookies();
});

describe('clearing the display zone', () => {
  it('drops both the applied value and the cookie a year long', () => {
    applyDisplayTimeZone('Asia/Tokyo');
    expect(document.cookie).toContain(DISPLAY_TIME_ZONE_COOKIE);

    clearDisplayTimeZone();

    expect(document.cookie).not.toContain('Asia%2FTokyo');
    // Back to this device, which is what a signed-out browser should show.
    expect(getClientTimeZone()).toBe(getBrowserTimeZone());
  });
});

describe('the logout path calls it', () => {
  // Rendering the whole provider would need the OIDC client, the query client, the org store and
  // a dozen contexts, and would still assert nothing more than this: that the one line is there,
  // inside logout, before the redirect that ends the page. A source assertion is the honest shape
  // for a wiring fact in a file this size.
  const source = readFileSync(
    join(process.cwd(), 'lib', 'providers', 'smart-providers.tsx'),
    'utf8'
  );

  it('imports the helper', () => {
    expect(source).toContain("import { clearDisplayTimeZone } from '../utils/timezone'");
  });

  it('calls it inside logout, before the sign-out redirect leaves the page', () => {
    const logout = source.slice(source.indexOf('const logout = useCallback'));
    const end = logout.indexOf('}, [oidc]);');
    // Asserted, not assumed: a changed dependency array makes indexOf answer -1, `slice(0, -1)`
    // then keeps almost the whole file, and both assertions below pass on text from elsewhere.
    expect(end, 'the end of logout must still be findable').toBeGreaterThan(-1);
    const body = logout.slice(0, end);

    expect(body).toContain('clearDisplayTimeZone()');
    expect(body.indexOf('clearDisplayTimeZone()')).toBeLessThan(body.indexOf('signoutRedirect'));
  });

  it('also calls it when a session ENDS without anyone signing out', () => {
    // A missing persisted user, a failed silent renew, a redirect to login: none of those go
    // through `logout`, and all of them leave the browser signed out. On a shared machine the
    // next person's first paint would be drawn from the previous person's year-long cookie.
    const marker = source.indexOf('const markSessionExpired = useCallback');
    expect(marker, 'markSessionExpired must still exist').toBeGreaterThan(-1);
    const end = source.indexOf('}, []);', marker);
    // The same guard test 2 documents, for the same reason: a changed dependency array makes
    // indexOf answer -1, the slice then keeps the rest of the file, and 'clearDisplayTimeZone()'
    // is found inside the logout callback instead - a pass on text from somewhere else entirely.
    expect(end, 'the end of markSessionExpired must still be findable').toBeGreaterThan(marker);
    const body = source.slice(marker, end);

    expect(body).toContain('clearDisplayTimeZone()');
  });
});
