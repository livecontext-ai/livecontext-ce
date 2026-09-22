import { describe, expect, it, vi } from 'vitest';
import { createTranslator, type AbstractIntlMessages } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { locales, type Locale } from '@/i18n/routing';

const messages = { en, fr, de, es, pt, zh };

vi.mock('next-intl/server', () => ({
  getTranslations: async ({ locale, namespace }: { locale: Locale; namespace: string }) => createTranslator({
    locale,
    messages: messages[locale] as AbstractIntlMessages,
    namespace,
    // A missing shell key renders the raw path in the header of every page of that language,
    // which is exactly the class of bug this helper exists to close. Make it a failure.
    onError: (error) => { throw error; },
  }),
}));

import { shellLabels } from '../shellLabels';
import { DEFAULT_SHELL_LABELS } from '../LandingShell';
import { PERSONA_KEYS, personaHref } from '../personas/personas';

describe('the header and footer copy handed to the shell', () => {
  it('fills every field the shell can render, in all six locales', async () => {
    // The defaults ARE the shell's complete surface, so iterating them is what proves no field
    // was added to the chrome and left out of the messages.
    for (const locale of locales) {
      const labels = await shellLabels(locale);
      for (const key of Object.keys(DEFAULT_SHELL_LABELS) as (keyof typeof DEFAULT_SHELL_LABELS)[]) {
        const value = labels[key];
        if (typeof value === 'string') expect(value, `${locale}.${key}`).not.toBe('');
      }
    }
  });

  it('names all six personas in the footer, so none of the use-case links loses its label', async () => {
    for (const locale of locales) {
      const { personas } = await shellLabels(locale);
      expect(Object.keys(personas).sort()).toEqual([...PERSONA_KEYS].sort());
      for (const persona of PERSONA_KEYS) expect(personas[persona], `${locale}.${persona}`).not.toBe('');
    }
  });

  it('puts the brand where each language puts it, rather than always suffixing it', async () => {
    // "Zapier alternative" inverts in French, Spanish and Portuguese, which is the whole
    // reason this is a placeholder and not a concatenation. Asserting only that the brand
    // appears would pass on a locale that copied the English word order verbatim, which is
    // exactly the failure the placeholder exists to prevent, so the Romance locales are
    // pinned to NOT lead with it.
    for (const locale of locales) {
      const { alternativeTo } = await shellLabels(locale);
      expect(alternativeTo('Zapier'), locale).toContain('Zapier');
    }
    expect((await shellLabels('en')).alternativeTo('Zapier')).toBe('Zapier alternative');
    for (const locale of ['fr', 'es', 'pt'] as const) {
      expect((await shellLabels(locale)).alternativeTo('Zapier'), locale).not.toMatch(/^Zapier/);
    }
    // German and Chinese legitimately lead with the brand ("Zapier-Alternative", "Zapier 的替代
    // 方案"), so the word-order rule cannot cover them. They still must not be the English
    // string: because alternativeTo is a FUNCTION, the identical-to-English sweep above skips
    // it, and a locale that never translated this key would otherwise pass every test here.
    for (const locale of locales) {
      if (locale === 'en') continue;
      expect((await shellLabels(locale)).alternativeTo('Zapier'), locale).not.toBe('Zapier alternative');
    }
  });

  it('actually translates, in every locale and not only in French', async () => {
    // The bug was a French page inside an English header and footer. Echoing en.json into the
    // other locales would satisfy key parity and reproduce it exactly, so compare the copy.
    //
    // Stated as an EXEMPTION list rather than an inclusion list, because an inclusion list is
    // only ever as complete as the day it was written: the first version of this test named 18
    // keys and left 15 unguarded, among them the two theme-toggle strings that this whole round
    // of work exists to translate. Inverted, a key added tomorrow is covered by default and has
    // to be argued out.
    //
    // COGNATES holds the words a language genuinely spells the English way; an identical string
    // there is the right translation, not a missing one, and forcing a different word in would
    // make the footer worse. `marketplace` is exempt in fr/es/pt on a harder criterion than
    // taste: those are the words the SIGNED-IN app uses for the same destination
    // (`sidebar.nav.marketplace`), which is also why de and zh are NOT exempt, since the app
    // says "Marktplatz" and "市场" there. The test below pins that agreement directly.
    //
    // Kept to exactly the keys that are identical TODAY, with no spares: an exemption for a key
    // that is in fact translated is a standing licence for it to regress to English unnoticed.
    // Five of these were in that state (de already says "Doku" and "Agenten"; es and pt already
    // translate agents and contact), which is how the list is meant to be pruned, not grown.
    const COGNATES: Record<string, readonly string[]> = {
      fr: ['docs', 'marketplace', 'workflows', 'agents', 'contact'],
      de: ['workflows', 'videos', 'status'],
      es: ['docs', 'marketplace', 'legal', 'workflows'],
      pt: ['docs', 'marketplace', 'legal', 'workflows'],
      zh: [],
    };
    const english = await shellLabels('en');
    for (const locale of locales) {
      if (locale === 'en') continue;
      const translated = await shellLabels(locale);
      const exempt = new Set(COGNATES[locale] ?? []);
      for (const key of Object.keys(english) as (keyof typeof english)[]) {
        const value = translated[key];
        if (typeof value !== 'string' || exempt.has(key as string)) continue;
        expect(value, `${locale}.${key} is still the English string`).not.toBe(english[key]);
      }
      // The persona names travel in their own record and would otherwise escape the sweep.
      for (const persona of PERSONA_KEYS) {
        expect(translated.personas[persona], `${locale}.personas.${persona}`)
          .not.toBe(english.personas[persona]);
      }
    }
  });

  it('calls the marketplace what the signed-in app calls it', async () => {
    // The chrome used to hardcode "Marketplace" as a proper noun. The product disagrees: the
    // sidebar says "Marktplatz" in German and "市场" in Chinese, so a visitor met one word on
    // the public site and another the moment they signed in, for the same destination.
    for (const locale of locales) {
      const { marketplace } = await shellLabels(locale);
      const inApp = (messages[locale] as { sidebar: { nav: { marketplace: string } } }).sidebar.nav.marketplace;
      expect(marketplace, `${locale}: chrome and sidebar must agree`).toBe(inApp);
    }
  });

  it('carries the locale, so a French anchor does not point at the English page', async () => {
    // The persona row is the one chrome destination with a localised sibling. Its links used
    // to be built with no prefix, so /fr showed "Équipes opérations" pointing at /for/ops: a
    // reader is rescued by the NEXT_LOCALE cookie and the middleware, a crawler is not, and
    // follows a French page's French link straight out of the French tree.
    for (const locale of locales) {
      const { locale: carried } = await shellLabels(locale);
      expect(carried, locale).toBe(locale);
      expect(personaHref('ops', carried), locale)
        .toBe(locale === 'en' ? '/for/ops' : `/${locale}/for/ops`);
    }
    // The pages outside the [locale] tree hand over nothing, and their persona links are
    // correctly the unprefixed English ones.
    expect(DEFAULT_SHELL_LABELS.locale).toBeUndefined();
    expect(personaHref('ops', DEFAULT_SHELL_LABELS.locale)).toBe('/for/ops');
  });

  it('gives the legal COLUMN a different word from the legal PAGE it links to', async () => {
    // French said "Mentions légales" for both, so the footer showed a column headed
    // "Mentions légales" whose third entry was also "Mentions légales". One is a category,
    // the other is a document.
    for (const locale of locales) {
      const { legal, notice } = await shellLabels(locale);
      expect(legal, locale).not.toBe(notice);
    }
  });

  it('keeps the English defaults in step with the messages, so the two chromes read alike', async () => {
    // The pages outside the [locale] tree get DEFAULT_SHELL_LABELS and cannot be translated.
    // If en.json drifts from them, the same site says two different things in English.
    const english = await shellLabels('en');
    for (const key of Object.keys(DEFAULT_SHELL_LABELS) as (keyof typeof DEFAULT_SHELL_LABELS)[]) {
      const fallback = DEFAULT_SHELL_LABELS[key];
      if (typeof fallback === 'string') expect(english[key], key).toBe(fallback);
    }
    expect(english.personas).toEqual(DEFAULT_SHELL_LABELS.personas);
  });
});
