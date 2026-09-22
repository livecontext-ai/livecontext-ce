import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { isPersonaKey, PERSONA_KEYS, personaAlternates, personaHref } from '../personas';

const state = vi.hoisted(() => ({ ce: false }));
vi.mock('@/lib/edition', () => ({ get IS_CE() { return state.ce; } }));
vi.mock('next/navigation', () => ({
  notFound: () => { throw new Error('NOT_FOUND'); },
  redirect: (path: string) => { throw new Error(`REDIRECT:${path}`); },
}));
vi.mock('next-intl/server', () => ({
  getTranslations: async ({ locale, namespace }: { locale: string; namespace: string }) => (key: string) => `${locale}:${namespace}.${key}`,
  setRequestLocale: vi.fn(),
}));
vi.mock('../PersonaLanding', () => ({ default: () => null }));
import PersonaPage, { generateMetadata, generateStaticParams } from '@/app/[locale]/for/[persona]/page';

describe('persona public routes', () => {
  beforeEach(() => { state.ce = false; });

  it('accepts only the six hero personas, in the order they are ranked', () => {
    // The order is the ranking: it drives the pill nav and the sitemap, so a reshuffle
    // here is a product decision, not a detail.
    expect(PERSONA_KEYS).toEqual(['ops', 'creator', 'support', 'sales', 'marketing', 'recruiting']);
    expect(isPersonaKey('creator')).toBe(true);
    expect(isPersonaKey('constructor')).toBe(false);
    expect(isPersonaKey('Creator')).toBe(false);
  });

  it('pre-renders every persona in every supported locale', () => {
    expect(generateStaticParams()).toHaveLength(36);
    expect(generateStaticParams()).toContainEqual({ locale: 'fr', persona: 'creator' });
    expect(generateStaticParams()).toContainEqual({ locale: 'zh', persona: 'recruiting' });
  });

  it('uses canonical default-language paths and complete locale alternates', () => {
    expect(personaHref('creator')).toBe('/for/creator');
    expect(personaHref('sales', 'fr')).toBe('/fr/for/sales');
    expect(personaAlternates('creator', 'https://livecontext.ai/')).toEqual({
      en: 'https://livecontext.ai/for/creator', fr: 'https://livecontext.ai/fr/for/creator',
      es: 'https://livecontext.ai/es/for/creator', de: 'https://livecontext.ai/de/for/creator',
      pt: 'https://livecontext.ai/pt/for/creator', zh: 'https://livecontext.ai/zh/for/creator',
      'x-default': 'https://livecontext.ai/for/creator',
    });
  });

  it('rejects unknown personas before loading a page or producing metadata', async () => {
    const props = { params: Promise.resolve({ locale: 'en', persona: 'invalid' }) };
    await expect(PersonaPage(props)).rejects.toThrow('NOT_FOUND');
    await expect(generateMetadata(props)).rejects.toThrow('NOT_FOUND');
  });

  it('rejects unsupported locales', async () => {
    await expect(PersonaPage({ params: Promise.resolve({ locale: 'xx', persona: 'creator' }) })).rejects.toThrow('NOT_FOUND');
  });

  it('generates persona-specific translated metadata with matching social previews', async () => {
    const metadata = await generateMetadata({ params: Promise.resolve({ locale: 'fr', persona: 'creator' }) });
    expect(metadata.title).toEqual({ absolute: 'fr:PersonaLanding.personas.creator.metaTitle' });
    expect(metadata.description).toBe('fr:PersonaLanding.personas.creator.metaDescription');
    expect(metadata.alternates?.canonical).toBe('https://livecontext.ai/fr/for/creator');
    expect(metadata.openGraph?.title).toBe('fr:PersonaLanding.personas.creator.metaTitle');
    expect(metadata.twitter?.title).toBe(metadata.openGraph?.title);
    expect(metadata.robots).toEqual({ index: true, follow: true });
  });

  /** The graph, looked up by type rather than by position, which is what a reader of it does. */
  const graphOf = async (locale: string, persona: string) => {
    const page = await PersonaPage({ params: Promise.resolve({ locale, persona }) });
    const fragment = page as ReactElement<{ children: [ReactElement<{ data: { '@graph': Array<Record<string, unknown>> } }>, ReactElement] }>;
    const graph = fragment.props.children[0].props.data['@graph'];
    return (type: string) => graph.find((node) => node['@type'] === type)!;
  };

  it('adds localized WebPage and Service structured data', async () => {
    const node = await graphOf('fr', 'sales');
    expect(node('WebPage')).toMatchObject({
      '@type': 'WebPage',
      url: 'https://livecontext.ai/fr/for/sales',
      inLanguage: 'fr',
      name: 'fr:PersonaLanding.personas.sales.metaTitle',
      breadcrumb: { '@id': 'https://livecontext.ai/fr/for/sales#breadcrumb' },
    });
    expect(node('Service')).toMatchObject({ '@type': 'Service', name: 'fr:PersonaLanding.personas.sales.title' });
  });

  it('gives the page a two-step trail that starts at the home of its own language', async () => {
    const node = await graphOf('fr', 'sales');
    // Two steps because there is no /for index: a middle crumb would name a 404. The home
    // crumb must carry the LOCALE home, or the trail sends a French reader to the English
    // page it is not the parent of.
    expect(node('BreadcrumbList')).toMatchObject({
      '@id': 'https://livecontext.ai/fr/for/sales#breadcrumb',
      itemListElement: [
        { '@type': 'ListItem', position: 1, name: 'LiveContext', item: 'https://livecontext.ai/fr' },
        { '@type': 'ListItem', position: 2, name: 'fr:PersonaLanding.personas.sales.name', item: 'https://livecontext.ai/fr/for/sales' },
      ],
    });
    const english = await graphOf('en', 'ops');
    expect((english('BreadcrumbList') as { itemListElement: { item: string }[] }).itemListElement[0].item).toBe('https://livecontext.ai/');
  });

  it('redirects self-hosted visitors to their app and disables indexing', async () => {
    state.ce = true;
    const props = { params: Promise.resolve({ locale: 'de', persona: 'support' }) };
    await expect(PersonaPage(props)).rejects.toThrow('REDIRECT:/de/app/chat');
    expect((await generateMetadata(props)).robots).toEqual({ index: false, follow: false });
  });
});
