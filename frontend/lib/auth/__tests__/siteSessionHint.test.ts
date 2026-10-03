/**
 * @vitest-environment jsdom
 *
 * The docs subdomain's "signed in" hint: a cookie the app writes on the site's domain while an
 * account is signed in (initials only), which the docs header reads because it cannot read the
 * app's session. These pin what it may hold, where it is shared, and that it goes away.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  SITE_SESSION_HINT_COOKIE,
  accountDisplayName,
  clearSiteSessionHint,
  initials,
  readSiteSessionHint,
  siteCookieDomain,
  writeSiteSessionHint,
} from '../siteSessionHint';

/** A document whose cookie writes are recorded as written, attributes included. */
function recordingDocument(hostname: string, protocol = 'https:', cookie = '') {
  const writes: string[] = [];
  return {
    writes,
    doc: {
      location: { hostname, protocol },
      get cookie() { return cookie; },
      set cookie(value: string) { writes.push(value); },
    } as unknown as Document,
  };
}

afterEach(() => {
  document.cookie = `${SITE_SESSION_HINT_COOKIE}=; path=/; max-age=0`;
});

describe('where the hint is shared', () => {
  it('on the site and every subdomain (the docs), on the registrable domain', () => {
    expect(siteCookieDomain('livecontext.ai', 'https://livecontext.ai')).toBe('.livecontext.ai');
    expect(siteCookieDomain('docs.livecontext.ai', 'https://livecontext.ai')).toBe('.livecontext.ai');
  });

  it("nowhere else: localhost, another host, a host that only ends with the same letters, or a staging host under the same domain (it must never write or clear production's)", () => {
    expect(siteCookieDomain('staging.livecontext.ai', 'https://livecontext.ai')).toBeNull();
    expect(siteCookieDomain('localhost', 'https://livecontext.ai')).toBeNull();
    expect(siteCookieDomain('example.com', 'https://livecontext.ai')).toBeNull();
    expect(siteCookieDomain('evil-livecontext.ai', 'https://livecontext.ai')).toBeNull();
    expect(siteCookieDomain('livecontext.ai', 'not a url')).toBeNull();
  });
});

describe('writing it', () => {
  it('holds the initials, on the shared domain, for a month, Secure on https', () => {
    const { doc, writes } = recordingDocument('livecontext.ai');

    writeSiteSessionHint('lm', doc);

    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatch(/^lc_account=LM; Max-Age=2592000; Path=\/; Domain=\.livecontext\.ai; SameSite=Lax; Secure$/);
  });

  it('host-only and not Secure on a local http host', () => {
    const { doc, writes } = recordingDocument('localhost', 'http:');

    writeSiteSessionHint('LM', doc);

    expect(writes[0]).toBe('lc_account=LM; Max-Age=2592000; Path=/; SameSite=Lax');
  });

  it('accepts initials in any script, encoded, and refuses anything that is not initials', () => {
    const { doc, writes } = recordingDocument('livecontext.ai');

    writeSiteSessionHint('ÉD', doc);
    writeSiteSessionHint('王伟', doc);
    writeSiteSessionHint('Lucas Martin', doc);
    writeSiteSessionHint('a;b', doc);
    writeSiteSessionHint('', doc);

    expect(writes.map((w) => decodeURIComponent(w.split(';')[0]))).toEqual(['lc_account=ÉD', 'lc_account=王伟']);
  });
});

describe('reading it', () => {
  it('reads the initials it wrote, decoded, among other cookies', () => {
    expect(readSiteSessionHint('theme=dark; lc_account=LM; other=1')).toBe('LM');
    expect(readSiteSessionHint(`lc_account=${encodeURIComponent('王伟')}`)).toBe('王伟');
  });

  it('reads nothing that is not a hint: absent, emptied, too long, a cookie whose name only ends the same, a broken encoding', () => {
    expect(readSiteSessionHint('theme=dark')).toBeNull();
    expect(readSiteSessionHint('lc_account=')).toBeNull();
    expect(readSiteSessionHint('lc_account=LMX')).toBeNull();
    expect(readSiteSessionHint('xlc_account=LM')).toBeNull();
    expect(readSiteSessionHint('lc_account=%E0%A4')).toBeNull();
  });

  it('round-trips through the real document cookie', () => {
    writeSiteSessionHint('LM');
    expect(readSiteSessionHint(document.cookie)).toBe('LM');
  });
});

describe('clearing it', () => {
  it('expires it with the same attributes it was written with', () => {
    const { doc, writes } = recordingDocument('docs.livecontext.ai', 'https:', 'lc_account=LM');

    clearSiteSessionHint(doc);

    expect(writes).toEqual(['lc_account=; Max-Age=0; Path=/; Domain=.livecontext.ai; SameSite=Lax; Secure']);
  });

  it('writes nothing when there is nothing to clear', () => {
    const { doc, writes } = recordingDocument('livecontext.ai', 'https:', 'theme=dark');

    clearSiteSessionHint(doc);

    expect(writes).toEqual([]);
  });

  it('removes it from the real document cookie', () => {
    writeSiteSessionHint('LM');
    clearSiteSessionHint();
    expect(readSiteSessionHint(document.cookie)).toBeNull();
  });
});

describe('the account\'s initials', () => {
  it('reads the name from the profile itself (the auth context\'s user IS the profile), then the username, then the email', () => {
    expect(accountDisplayName({ name: ' Lucas Martin ', email: 'l@acme.io' })).toBe('Lucas Martin');
    expect(accountDisplayName({ preferred_username: 'lucas', email: 'l@acme.io' })).toBe('lucas');
    expect(accountDisplayName({ email: 'jane.doe@acme.io' })).toBe('jane.doe@acme.io');
    expect(accountDisplayName({ profile: { name: 'Nested' } })).toBeNull();
    expect(accountDisplayName(null)).toBeNull();
  });

  it('two letters for a full name, the start of a one-word name or an email, "?" without one', () => {
    expect(initials('Lucas Martin')).toBe('LM');
    expect(initials('lucas')).toBe('LU');
    expect(initials('jane.doe@acme.io')).toBe('JD');
    expect(initials(null)).toBe('?');
  });

  it('regression: a one-word name in a script without word spaces gives one character, not the whole name (it travels in a cookie)', () => {
    expect(initials('王伟')).toBe('王');
    expect(initials('たなか')).toBe('た');
    expect(initials('김민준')).toBe('김');
    // regression: a first and a last name joined with a space (what sign-up stores) is still
    // one character in those scripts: "伟 王" is the whole name.
    expect(initials('伟 王')).toBe('伟');
    expect(initials('山田 太郎')).toBe('山');
    // Two words in a script with word spaces still give two initials.
    expect(initials('Élodie Dupont')).toBe('ÉD');
  });
});

describe('sign-out forgets it', () => {
  // The same source-level shape as smart-providers.logoutClearsZone.test.ts: the wiring is two
  // lines in a provider no test renders, before a redirect that ends the page.
  const source = readFileSync(join(process.cwd(), 'lib', 'providers', 'smart-providers.tsx'), 'utf8');

  it('inside logout, before the sign-out redirect leaves the page', () => {
    const logout = source.slice(source.indexOf('const logout = useCallback'));
    const end = logout.indexOf('}, [oidc]);');
    expect(end, 'the end of logout must still be findable').toBeGreaterThan(-1);
    const body = logout.slice(0, end);

    expect(body).toContain('clearSiteSessionHint()');
    expect(body.indexOf('clearSiteSessionHint()')).toBeLessThan(body.indexOf('signoutRedirect'));
  });

  it('when a session ends without anyone signing out', () => {
    const marker = source.indexOf('const markSessionExpired = useCallback');
    expect(marker, 'markSessionExpired must still exist').toBeGreaterThan(-1);
    const end = source.indexOf('}, []);', marker);
    expect(end, 'the end of markSessionExpired must still be findable').toBeGreaterThan(marker);

    expect(source.slice(marker, end)).toContain('clearSiteSessionHint()');
  });
});
