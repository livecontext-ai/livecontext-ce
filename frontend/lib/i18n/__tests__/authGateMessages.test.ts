import { describe, expect, it } from 'vitest';

import { AUTH_GATE_STRINGS, authGateBody, getAuthGateStrings } from '../authGateMessages';
import { locales } from '@/i18n/routing';

import en from '../../../messages/en.json';
import fr from '../../../messages/fr.json';
import es from '../../../messages/es.json';
import de from '../../../messages/de.json';
import pt from '../../../messages/pt.json';
import zh from '../../../messages/zh.json';

// The gate renders above NextIntlClientProvider, so its copy is mirrored into a
// synchronous table (authGateMessages.ts) instead of read through next-intl. These
// tests make that mirror provably correct: they fail the moment the table drifts
// from the canonical messages/*.json translations.
const CANONICAL: Record<string, any> = { en, fr, es, de, pt, zh };
const MIRRORED_KEYS = ['title', 'subtitle', 'sessionExpired', 'submit'] as const;

describe('AUTH_GATE_STRINGS - sync with messages/*.json', () => {
  it('covers exactly the 6 supported locales', () => {
    expect(Object.keys(AUTH_GATE_STRINGS).sort()).toEqual([...locales].sort());
  });

  for (const locale of locales) {
    it(`mirrors auth.login.{title,subtitle,sessionExpired,submit} for "${locale}"`, () => {
      const canonical = CANONICAL[locale].auth.login;
      for (const key of MIRRORED_KEYS) {
        // Both sides must be present (no English placeholder, no missing key)...
        expect(canonical[key], `messages/${locale}.json auth.login.${key}`).toBeTruthy();
        // ...and byte-for-byte identical, so a translation edit can never silently
        // leave the gate showing stale or English copy.
        expect(AUTH_GATE_STRINGS[locale][key]).toBe(canonical[key]);
      }
    });
  }

  it('non-English locales carry real translations (not the English string copied)', () => {
    // Guards against a lazy "fill with English" mirror: every non-en value must
    // differ from English for at least one key (full parity is checked above).
    for (const locale of locales) {
      if (locale === 'en') continue;
      const differs = MIRRORED_KEYS.some(
        (key) => AUTH_GATE_STRINGS[locale][key] !== AUTH_GATE_STRINGS.en[key],
      );
      expect(differs, `"${locale}" must not be an English copy`).toBe(true);
    }
  });
});

describe('getAuthGateStrings', () => {
  it('returns the requested locale', () => {
    expect(getAuthGateStrings('fr')).toBe(AUTH_GATE_STRINGS.fr);
    expect(getAuthGateStrings('zh')).toBe(AUTH_GATE_STRINGS.zh);
  });

  it('falls back to English for an unknown locale', () => {
    expect(getAuthGateStrings('xx')).toBe(AUTH_GATE_STRINGS.en);
    expect(getAuthGateStrings('')).toBe(AUTH_GATE_STRINGS.en);
  });
});

describe('authGateBody - first-visit vs real expiry', () => {
  const s = AUTH_GATE_STRINGS.en;

  it('shows ONLY the neutral subtitle when there was no prior session', () => {
    const body = authGateBody(s, false);
    expect(body).toBe(s.subtitle);
    // The fix: a cold first connect (fresh slot / CE) must NOT claim an expiry.
    expect(body).not.toContain(s.sessionExpired);
  });

  it('prepends the "session has expired" sentence when a real session ended', () => {
    const body = authGateBody(s, true);
    expect(body).toBe(`${s.sessionExpired} ${s.subtitle}`);
    expect(body.startsWith(s.sessionExpired)).toBe(true);
    expect(body).toContain(s.subtitle);
  });

  it('localizes the body for every locale in both modes', () => {
    for (const locale of locales) {
      const ls = AUTH_GATE_STRINGS[locale];
      // Neutral mode: subtitle only, in every locale.
      expect(authGateBody(ls, false)).toBe(ls.subtitle);
      // Expiry mode: the expiry sentence then the subtitle, both present and in order.
      const body = authGateBody(ls, true);
      expect(body.startsWith(ls.sessionExpired)).toBe(true);
      expect(body.endsWith(ls.subtitle)).toBe(true);
      // Joined with a single space, or no space when the expiry sentence ends in
      // CJK full-width terminal punctuation (those scripts do not space sentences).
      const remainder = body.slice(ls.sessionExpired.length);
      expect(remainder === ` ${ls.subtitle}` || remainder === ls.subtitle).toBe(true);
    }
  });

  it('joins zh without an ASCII space (full-width punctuation) but en with a space', () => {
    const enS = AUTH_GATE_STRINGS.en;
    const zhS = AUTH_GATE_STRINGS.zh;
    expect(authGateBody(enS, true)).toBe(`${enS.sessionExpired} ${enS.subtitle}`);
    expect(authGateBody(zhS, true)).toBe(`${zhS.sessionExpired}${zhS.subtitle}`);
    expect(authGateBody(zhS, true)).not.toContain(`${zhS.sessionExpired} `);
  });
});
