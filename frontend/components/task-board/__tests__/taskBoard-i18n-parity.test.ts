import { describe, it, expect } from 'vitest';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * Strict parity guard for the taskBoard i18n subtree. The loose e2e i18n spec runs
 * English-only and the component unit tests mock next-intl to an identity map, so a
 * key that drifts or falls back to English would otherwise ship silently. These tests
 * fail the moment a locale diverges from en.json, a newly-added key is missing in any
 * locale, a non-English locale copies the English string verbatim, or an em/en-dash
 * (banned project-wide) sneaks into a taskBoard value.
 */

const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };

function flatten(obj: any, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? flatten(v, `${prefix}${k}.`)
      : [`${prefix}${k}`]
  );
}

function value(locale: any, dotted: string): unknown {
  return dotted.split('.').reduce<any>((acc, part) => (acc == null ? acc : acc[part]), locale);
}

const taskBoardOf = (locale: any) => locale?.taskBoard ?? {};

// Keys added by the detail-rail rework + Filters dropdown - their translations must
// exist (not fall back to English) in every locale.
const NEW_KEYS = [
  'taskBoard.detail.addOrCreateLabel',
  'taskBoard.detail.createLabel',
  'taskBoard.detail.noBlockerOptions',
  'taskBoard.detail.advanced',
  'taskBoard.filters.filters',
];

// Keys added by the i18n-fallback cleanup (actor-name fallbacks 'Agent' / 'System').
// Presence is asserted in every locale, but the "not an English copy" check is
// deliberately skipped: the genuine French/German translations of these words
// coincide with the English string ("Agent", "System"), so the copy check would
// false-fail on correct translations.
const NEW_KEYS_IDENTICAL_OK = [
  'taskBoard.detail.agent',
  'taskBoard.detail.system',
];

describe('taskBoard i18n parity', () => {
  const refKeys = flatten(taskBoardOf(en)).sort();

  it('en.json defines a non-empty taskBoard subtree', () => {
    expect(refKeys.length).toBeGreaterThan(0);
  });

  for (const [locale, messages] of Object.entries(LOCALES)) {
    it(`${locale}.json has exactly the same taskBoard keys as en.json (0 missing / 0 extra)`, () => {
      const keys = flatten(taskBoardOf(messages)).sort();
      const missing = refKeys.filter((k) => !keys.includes(k));
      const extra = keys.filter((k) => !refKeys.includes(k));
      expect(missing, `${locale} missing: ${missing.join(', ')}`).toEqual([]);
      expect(extra, `${locale} extra: ${extra.join(', ')}`).toEqual([]);
    });
  }

  for (const key of NEW_KEYS) {
    it(`every locale provides a real translation for ${key}`, () => {
      for (const [locale, messages] of Object.entries(LOCALES)) {
        const v = value(messages, key);
        expect(typeof v === 'string' && v.length > 0, `${locale} missing ${key}`).toBe(true);
        if (locale !== 'en') {
          // Not an English-string-copied placeholder.
          expect(v, `${locale} ${key} is an untranslated English copy`).not.toBe(value(en, key));
        }
      }
    });
  }

  for (const key of NEW_KEYS_IDENTICAL_OK) {
    it(`every locale provides ${key} (identical-to-English allowed)`, () => {
      for (const [locale, messages] of Object.entries(LOCALES)) {
        const v = value(messages, key);
        expect(typeof v === 'string' && v.length > 0, `${locale} missing ${key}`).toBe(true);
      }
    });
  }

  for (const [locale, messages] of Object.entries(LOCALES)) {
    it(`${locale}.json taskBoard values contain no em-dash / en-dash (banned)`, () => {
      const offenders = flatten(taskBoardOf(messages))
        .map((k) => [k, value(messages, `taskBoard.${k}`)] as const)
        .filter(([, v]) => typeof v === 'string' && /[--]/.test(v as string))
        .map(([k]) => k);
      expect(offenders, `${locale} taskBoard values with em/en-dash: ${offenders.join(', ')}`).toEqual([]);
    });
  }
});
