import { describe, expect, it } from 'vitest';
import { createTranslator, type AbstractIntlMessages } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { locales, type Locale } from '@/i18n/routing';
import { CATALOG_INTEGRATIONS_CLAIM } from '@/lib/integrations/integrationCount';
import { LANDING_FAQ_KEYS, landingJsonLd, type Copy } from '../landingJsonLd';
import { SITE_URL } from '../siteUrl';

const messages = { en, fr, de, es, pt, zh };
const ORIGIN = SITE_URL.replace(/\/$/, '');

function build(locale: Locale) {
  const t = (namespace: string) => createTranslator({
    locale, messages: messages[locale] as AbstractIntlMessages, namespace,
    onError: (error) => { throw error; },
  }) as unknown as Copy;
  return landingJsonLd(locale, t('LandingHome.meta'), t('LandingHome.jsonLd'), t('LandingHome.faq'));
}

const nodeOf = (locale: Locale, type: string) => build(locale).find((node) => node['@type'] === type) as Record<string, unknown> | undefined;

/**
 * The structured data used to be one English module constant emitted on all six locale URLs.
 * That was defensible while they were byte-identical duplicates canonicalising to the apex,
 * and stopped being defensible the moment each locale became its own canonical: a French page
 * declared itself French through hreflang and handed the crawler an English description with
 * no `inLanguage` at all.
 */
describe('the landing structured data', () => {
  it.each(locales)('declares the page language on every node that carries prose, in %s', (locale) => {
    for (const type of ['WebSite', 'SoftwareApplication']) {
      expect(nodeOf(locale, type)?.inLanguage, `${locale} ${type}`).toBe(locale);
    }
  });

  it.each(locales)('describes the product in %s, not in English', (locale) => {
    const description = nodeOf(locale, 'SoftwareApplication')?.description as string;
    expect(description).toBe(messages[locale].LandingHome.jsonLd.softwareDescription.replace('{integrations}', CATALOG_INTEGRATIONS_CLAIM));
    if (locale !== 'en') expect(description).not.toBe(nodeOf('en', 'SoftwareApplication')?.description);
  });

  it.each(locales)('points at the locale own URL in %s, the same one the canonical names', (locale) => {
    const expected = locale === 'en' ? ORIGIN : `${ORIGIN}/${locale}`;
    expect(nodeOf(locale, 'WebSite')?.url).toBe(expected);
    expect(nodeOf(locale, 'SoftwareApplication')?.url).toBe(expected);
  });

  it('emits a FAQPage now that the section is mounted, with every question on it', () => {
    // The node was absent while `FaqSection` was defined and never mounted, because Google
    // requires FAQPage content to be VISIBLE on the page. It came back WITH the section, and
    // the pairing is the whole contract: deleting `<FaqSection />` from the page without
    // deleting this node puts the site back to advertising answers nobody can read.
    for (const locale of locales) {
      const node = nodeOf(locale, 'FAQPage');
      expect(node, locale).toBeDefined();
      const questions = (node!.mainEntity as Array<Record<string, unknown>>);
      expect(questions, locale).toHaveLength(LANDING_FAQ_KEYS.length);
      for (const question of questions) {
        expect(question['@type']).toBe('Question');
        expect(String(question.name).trim(), locale).not.toBe('');
      }
    }
  });

  it.each(locales)('says in its answers exactly what the section renders, in %s', (locale) => {
    // The other half of the same compliance rule: structured data that does not match the
    // visible text is as bad as structured data with no text behind it. The page renders
    // each answer through `t(\`${key}.answer\`, { integrations })`, so the node must apply
    // the same substitution - the placeholder shipped raw would tell a crawler
    // "{integrations} integrations" under a page showing a number.
    const faq = messages[locale].LandingHome.faq as unknown as Record<string, { answer: string }>;
    const answers = (nodeOf(locale, 'FAQPage')!.mainEntity as Array<Record<string, Record<string, string>>>)
      .map((question) => question.acceptedAnswer.text);

    answers.forEach((text, index) => {
      const key = LANDING_FAQ_KEYS[index];
      expect(text, `${locale}.${key}`).toBe(
        faq[key].answer.replace('{integrations}', CATALOG_INTEGRATIONS_CLAIM),
      );
      expect(text, `${locale}.${key}`).not.toContain('{integrations}');
    });
  });

  it('carries the FAQ copy in all six languages, which is what the rendered section reads', () => {
    // The section is on six indexable locales. A key missing from one of them falls back to
    // English (i18n/request.ts deep-merges en under the active locale), so the failure is
    // not a crash but English prose under a French page - invisible to every other check.
    for (const locale of locales) {
      const faq = messages[locale].LandingHome.faq as unknown as Record<string, { question: string; answer: string }>;
      expect(Object.keys(faq).filter((key) => !['eyebrow', 'title', 'more'].includes(key)).sort(), locale)
        .toEqual([...LANDING_FAQ_KEYS].sort());
      for (const key of LANDING_FAQ_KEYS) {
        expect(faq[key].question.trim(), `${locale}.${key}`).not.toBe('');
        expect(faq[key].answer.trim(), `${locale}.${key}`).not.toBe('');
      }
    }
  });

  it('leaves the organisation node language-free, since a company is not a language', () => {
    expect(nodeOf('fr', 'Organization')).toBeDefined();
    expect(nodeOf('fr', 'Organization')).not.toHaveProperty('inLanguage');
  });

  it('substitutes the integration count instead of shipping the placeholder', () => {
    for (const locale of locales) {
      expect(String(nodeOf(locale, 'SoftwareApplication')?.description), locale).toContain(CATALOG_INTEGRATIONS_CLAIM);
      expect(String(nodeOf(locale, 'SoftwareApplication')?.description), locale).not.toContain('{integrations}');
    }
  });

  it('would notice the two translators being swapped', () => {
    // The reason the call site casts to `Copy` and not to `never`: `never` is assignable to
    // everything, so a swap would type-check and the site description would become the offer
    // blurb. This pins the two apart.
    const site = nodeOf('en', 'WebSite')?.description;
    const offer = (nodeOf('en', 'SoftwareApplication')?.offers as Record<string, unknown>)?.description;
    expect(site).toBe(en.LandingHome.meta.description);
    expect(offer).toBe(en.LandingHome.jsonLd.offerDescription);
    expect(site).not.toBe(offer);
  });
});
