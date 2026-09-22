import { describe, expect, it } from 'vitest';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { locales } from '@/i18n/routing';
import { PERSONA_KEYS } from '../personas';

/**
 * What a search result actually shows of these six pages.
 *
 * <p>A description past roughly 160 characters is CUT in the result, and the clause that
 * gets cut is the last one, which is where this product's differentiator lives ("waits for
 * your approval"). Five of the six operations descriptions were 183 to 200 characters, so
 * every reader saw the generic half and none of the promise. Nothing failed: metadata is
 * valid at any length, which is exactly why this is a test and not a review note.
 *
 * <p>The caps are the SERP's, not a style rule: ~60 characters for a title, ~160 for a
 * description. Chinese is measured the same way and sits far below both, since a CJK
 * character carries more meaning per character, never less.
 */
const messages = { en, fr, de, es, pt, zh } as Record<string, typeof en>;

const metaOf = (locale: string, persona: string) => {
  const copy = (messages[locale].PersonaLanding.personas as unknown as Record<string, { metaTitle: string; metaDescription: string }>)[persona];
  return copy;
};

describe('persona page search metadata', () => {
  it.each(locales)('keeps every %s title and description inside what a result shows', (locale) => {
    for (const persona of PERSONA_KEYS) {
      const { metaTitle, metaDescription } = metaOf(locale, persona);
      expect(metaTitle.length, `${locale}/${persona} title`).toBeLessThanOrEqual(60);
      expect(metaDescription.length, `${locale}/${persona} description`).toBeLessThanOrEqual(160);
      // A floor too: an empty or one-line description wastes the result's second line.
      // 40 rather than 120 because a CJK character carries several words' worth.
      expect(metaDescription.length, `${locale}/${persona} description`).toBeGreaterThanOrEqual(40);
    }
  });

  it.each(locales)('gives each %s page its own title, all of them branded', (locale) => {
    const titles = PERSONA_KEYS.map((persona) => metaOf(locale, persona).metaTitle);
    // Two pages sharing a title compete for the same result, and the route sets the title
    // ABSOLUTE (no root template), so the brand has to be in the string itself.
    expect(new Set(titles).size).toBe(PERSONA_KEYS.length);
    for (const title of titles) expect(title).toContain('LiveContext');
    const descriptions = PERSONA_KEYS.map((persona) => metaOf(locale, persona).metaDescription);
    expect(new Set(descriptions).size).toBe(PERSONA_KEYS.length);
  });

  it('brands each title once, never twice', () => {
    // A layout title that is a plain string nulls the root template for its children, and
    // the fix for that is a manual suffix. Doing both gives "... | LiveContext | LiveContext".
    for (const locale of locales) {
      for (const persona of PERSONA_KEYS) {
        expect(metaOf(locale, persona).metaTitle.match(/LiveContext/g)).toHaveLength(1);
      }
    }
  });
});
