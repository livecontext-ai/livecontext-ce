import { describe, it, expect } from 'vitest';
import { PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * Strict parity guard for the pricing plan-card i18n subtree (pricing.planCards).
 * The loose e2e i18n spec does not catch missing keys, and a card feature that falls
 * back to English would silently show the wrong language. These tests fail the moment
 * a locale drifts from en.json or a feature key used by the cards has no translation.
 */

const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };

function flatten(obj: any, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? flatten(v, `${prefix}${k}.`)
      : [`${prefix}${k}`]
  );
}

const planCardsOf = (locale: any) => locale?.pricing?.planCards ?? {};

describe('pricing.planCards i18n parity', () => {
  const refKeys = flatten(planCardsOf(en)).sort();

  it('en.json defines a non-empty planCards subtree', () => {
    expect(refKeys.length).toBeGreaterThan(0);
  });

  for (const [locale, messages] of Object.entries(LOCALES)) {
    it(`${locale}.json has exactly the same planCards keys as en.json (0 missing / 0 extra)`, () => {
      const keys = flatten(planCardsOf(messages)).sort();
      const missing = refKeys.filter((k) => !keys.includes(k));
      const extra = keys.filter((k) => !refKeys.includes(k));
      expect(missing, `${locale} missing: ${missing.join(', ')}`).toEqual([]);
      expect(extra, `${locale} extra: ${extra.join(', ')}`).toEqual([]);
    });
  }

  // Every feature key referenced by the cards must resolve in every locale.
  // 'creditsDynamic' is a runtime sentinel rendered via features.creditsPerMonth,
  // so it is not itself a translation key.
  const usedFeatureKeys = Array.from(
    new Set(Object.values(PLAN_FEATURE_KEYS).flat())
  ).filter((k) => k !== 'creditsDynamic');

  for (const [locale, messages] of Object.entries(LOCALES)) {
    it(`${locale}.json provides a translation for every feature key used by the cards`, () => {
      const features = planCardsOf(messages).features ?? {};
      const missing = usedFeatureKeys.filter(
        (k) => typeof features[k] !== 'string' || features[k].length === 0
      );
      expect(missing, `${locale} missing feature translations: ${missing.join(', ')}`).toEqual([]);
    });
  }

  it('the dynamic credits sentinel maps to an interpolated key present in every locale', () => {
    for (const [locale, messages] of Object.entries(LOCALES)) {
      const tmpl = planCardsOf(messages).features?.creditsPerMonth;
      expect(typeof tmpl === 'string' && tmpl.includes('{credits}'), `${locale} creditsPerMonth must contain {credits}`).toBe(true);
    }
  });
});
