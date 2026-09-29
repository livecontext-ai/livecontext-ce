import { describe, expect, it } from 'vitest';
import de from '@/messages/de.json';
import en from '@/messages/en.json';
import es from '@/messages/es.json';
import fr from '@/messages/fr.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { CATALOG_INTEGRATIONS_CLAIM } from '@/lib/integrations/integrationCount';

/**
 * Meta descriptions that fit a search result.
 *
 * Google cuts a snippet at roughly 155 to 160 characters. The landing's was 234
 * characters in English and 285 in French, so every result ended mid-sentence.
 */
const MAX = 160;
const locales = { en, fr, de, es, pt, zh } as const;

describe('meta description length', () => {
  it('the site-wide default description, used by every page without its own, fits too', () => {
    const layout = readFileSync(path.resolve(__dirname, '../../../app/layout.tsx'), 'utf8');
    const template = layout.match(/const SITE_DESCRIPTION = `([^`]+)`;/);
    expect(template).not.toBeNull();
    const description = template![1].replace('${CATALOG_INTEGRATIONS_CLAIM}', CATALOG_INTEGRATIONS_CLAIM);
    expect(description).not.toContain('${');
    expect(description.length).toBeLessThanOrEqual(MAX);
  });

  it.each(Object.keys(locales) as (keyof typeof locales)[])('%s landing description fits a search snippet', (locale) => {
    const description = locales[locale].LandingHome.meta.description;
    expect(description.length).toBeGreaterThan(50);
    expect(description.length).toBeLessThanOrEqual(MAX);
  });

  it.each(Object.keys(locales) as (keyof typeof locales)[])('%s persona descriptions fit a search snippet', (locale) => {
    for (const [persona, copy] of Object.entries(locales[locale].PersonaLanding.personas)) {
      expect((copy as { metaDescription: string }).metaDescription.length, persona).toBeLessThanOrEqual(MAX);
    }
  });
});
