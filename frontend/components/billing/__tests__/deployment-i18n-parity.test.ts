import { describe, it, expect } from 'vitest';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * Strict parity guard for the pricing.deployment i18n subtree (Cloud | Self-hosted
 * toggle labels + the self-hosted card copy). Every plan card on the landing, the
 * settings pricing page and the insufficient-credits modal reads these keys; a locale
 * that drifts would silently fall back to English for that surface.
 */

const LOCALES = { en, fr, de, es, pt, zh };
const REQUIRED = ['cloud', 'selfHosted', 'or', 'availableOn', 'selfHostNote', 'selfHostCta'];

const deploymentOf = (locale: unknown): Record<string, string> =>
  ((locale as { pricing?: { deployment?: Record<string, string> } })?.pricing?.deployment) ?? {};

describe('pricing.deployment i18n parity', () => {
  const refKeys = Object.keys(deploymentOf(en)).sort();

  it('en.json defines every required deployment key', () => {
    for (const k of REQUIRED) {
      expect(typeof deploymentOf(en)[k], `en missing ${k}`).toBe('string');
    }
  });

  for (const [locale, messages] of Object.entries(LOCALES)) {
    it(`${locale}.json has exactly the same deployment keys as en.json (0 missing / 0 extra)`, () => {
      const keys = Object.keys(deploymentOf(messages)).sort();
      const missing = refKeys.filter((k) => !keys.includes(k));
      const extra = keys.filter((k) => !refKeys.includes(k));
      expect(missing, `${locale} missing: ${missing.join(', ')}`).toEqual([]);
      expect(extra, `${locale} extra: ${extra.join(', ')}`).toEqual([]);
    });

    it(`${locale}.json provides a non-empty translation for every deployment key`, () => {
      const block = deploymentOf(messages);
      const empty = REQUIRED.filter((k) => typeof block[k] !== 'string' || block[k].length === 0);
      expect(empty, `${locale} empty/missing: ${empty.join(', ')}`).toEqual([]);
    });

    it(`${locale}.json does not leave the self-hosted CTA as the English placeholder`, () => {
      // Non-English locales must actually translate (not copy en verbatim) for the
      // labels that have a real translation; CTA label "GitHub" is a proper noun, so
      // we assert on selfHosted (which every locale localises differently from EN).
      if (locale === 'en') return;
      expect(deploymentOf(messages).selfHosted).not.toBe(deploymentOf(en).selfHosted);
    });
  }
});
