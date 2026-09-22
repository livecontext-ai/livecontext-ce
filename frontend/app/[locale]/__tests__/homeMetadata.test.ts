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
    locale, messages: messages[locale] as AbstractIntlMessages, namespace,
    onError: (error) => { throw error; },
  }),
  setRequestLocale: vi.fn(),
}));
vi.mock('next/navigation', () => ({ redirect: (path: string) => { throw new Error(`REDIRECT:${path}`); } }));
// The page pulls in the marketplace preview, whose router comes from next-intl's navigation
// helper; that module does not resolve under vitest (see MarketplacePreview.test).
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }), usePathname: () => '/', Link: () => null, redirect: vi.fn(), getPathname: () => '/' }));

import { generateMetadata } from '../page';

const ORIGIN = (process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai').replace(/\/$/, '');
const metaFor = (locale: string) => generateMetadata({ params: Promise.resolve({ locale }) });

/**
 * The landing used to be hardcoded English on every locale URL, so all six canonicalised to
 * the apex on purpose: six URLs with the same text are duplicates. They are six translated
 * pages now, and leaving that line in place would tell Google the five translations do not
 * exist, which is the opposite of why they were written.
 */
describe('home page metadata, once the landing is translated', () => {
  it('takes its title and description from the page language', async () => {
    expect((await metaFor('fr')).title).toEqual({ absolute: fr.LandingHome.meta.title });
    expect((await metaFor('de')).description).toBe(de.LandingHome.meta.description);
    expect((await metaFor('en')).description).toBe(en.LandingHome.meta.description);
  });

  it('makes each locale its own canonical', async () => {
    // English is the bare apex, the spelling the sitemap advertises and the one Next emits
    // after normalising a trailing slash away. A mismatch here is a crawler instruction
    // arguing with itself.
    expect((await metaFor('en')).alternates?.canonical).toBe(ORIGIN);
    expect((await metaFor('fr')).alternates?.canonical).toBe(`${ORIGIN}/fr`);
    expect((await metaFor('zh')).alternates?.canonical).toBe(`${ORIGIN}/zh`);
  });

  it('declares the whole cluster, plus an x-default pointing at the unprefixed page', async () => {
    const languages = (await metaFor('pt')).alternates?.languages as Record<string, string>;
    expect(Object.keys(languages).sort()).toEqual([...locales, 'x-default'].sort());
    expect(languages.en).toBe(ORIGIN);
    expect(languages.es).toBe(`${ORIGIN}/es`);
    expect(languages['x-default']).toBe(ORIGIN);
  });

  it('hands the page and the sitemap the SAME cluster', async () => {
    const { homeAlternates } = await import('@/lib/seo/siteUrl');
    expect((await metaFor('de')).alternates?.languages).toEqual(homeAlternates(ORIGIN));
  });
});

/**
 * The root layout declares ONE English Open Graph card for the whole site, with the apex as
 * its URL. That was right while this page was English on all six URLs; once translated it
 * meant sharing /fr posted an English card pointing at the English page.
 */
describe('the card each locale shares', () => {
  it.each(locales)('shares %s in its own language', async (locale) => {
    const meta = await metaFor(locale);
    const og = meta.openGraph as Record<string, unknown>;
    expect(og.title).toBe(messages[locale].LandingHome.meta.title);
    expect(og.description).toBe(messages[locale].LandingHome.meta.description);
    expect((meta.twitter as Record<string, unknown>).title).toBe(messages[locale].LandingHome.meta.title);
  });

  it.each(locales)('points %s at itself, not at the apex', async (locale) => {
    const og = (await metaFor(locale)).openGraph as Record<string, unknown>;
    expect(og.url).toBe(locale === 'en' ? ORIGIN : `${ORIGIN}/${locale}`);
    // The same URL the canonical names, or the share and the crawler disagree.
    expect(og.url).toBe((await metaFor(locale)).alternates?.canonical);
  });

  it.each(locales)('declares %s as a language_TERRITORY tag, with the others as alternates', async (locale) => {
    const og = (await metaFor(locale)).openGraph as Record<string, unknown>;
    expect(og.locale).toMatch(/^[a-z]{2}_[A-Z]{2}$/);
    expect(og.alternateLocale).toHaveLength(locales.length - 1);
    expect(og.alternateLocale).not.toContain(og.locale);
  });

  it('gives every locale a distinct tag, so two languages cannot claim the same one', async () => {
    const tags = await Promise.all(locales.map(async (locale) => ((await metaFor(locale)).openGraph as Record<string, unknown>).locale));
    expect(new Set(tags).size).toBe(locales.length);
  });
});
