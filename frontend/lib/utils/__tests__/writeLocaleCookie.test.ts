// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { writeLocaleCookie } from '../locale';

/** Regression (CASA DAST): the client wrote NEXT_LOCALE without `Secure` on the HTTPS site. */
describe('writeLocaleCookie', () => {
  afterEach(() => vi.restoreAllMocks());

  function captureCookieWrite(): () => string {
    let written = '';
    vi.spyOn(document, 'cookie', 'set').mockImplementation((value: string) => { written = value; });
    return () => written;
  }

  it('adds Secure on an HTTPS page and keeps SameSite=Lax, path and one-year lifetime', () => {
    const written = captureCookieWrite();
    writeLocaleCookie('fr', 'https:');
    expect(written()).toBe('NEXT_LOCALE=fr; path=/; max-age=31536000; SameSite=Lax; Secure');
  });

  it('omits Secure on a plain-HTTP page (a browser would drop the cookie)', () => {
    const written = captureCookieWrite();
    writeLocaleCookie('de', 'http:');
    expect(written()).toBe('NEXT_LOCALE=de; path=/; max-age=31536000; SameSite=Lax');
  });

  it('defaults to the current page protocol', () => {
    const written = captureCookieWrite();
    writeLocaleCookie('es');
    expect(written().endsWith('; Secure')).toBe(window.location.protocol === 'https:');
  });
});
