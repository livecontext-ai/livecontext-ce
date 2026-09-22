// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createTranslator, type AbstractIntlMessages } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { locales, type Locale } from '@/i18n/routing';
import { CATALOG_INTEGRATIONS_CLAIM, CATALOG_OPERATIONS_CLAIM } from '@/lib/integrations/integrationCount';

const messages = { en, fr, de, es, pt, zh };

vi.mock('next-intl/server', () => ({
  getTranslations: async ({ locale, namespace }: { locale: Locale; namespace: string }) => createTranslator({
    locale, messages: messages[locale] as AbstractIntlMessages, namespace,
    onError: (error) => { throw error; },
  }),
}));
vi.mock('@/components/landing/personas/PersonaRoleCards', () => ({ default: () => <div data-testid="role-cards" /> }));
vi.mock('@/components/integrations/BrandMark', () => ({ BrandMark: () => <i /> }));

import { BuildableSection, HomeIntegrationsStrip, RolesSection, showsTestimonials } from '../homeSections';

afterEach(cleanup);

/** Every English literal that was on this page before the translation pass. */
const ENGLISH_LEFTOVERS = [
  /Connects to the tools your team already uses/,
  /Browse all/, /ready-to-call operations/,
  /Describe the job\. Get the automation\./,
  /What you can build/,
  /Made for your job/,
];

const RAW_KEY = /LandingHome\.|PersonaLanding\./;

describe('the sections that hand another component translated props', () => {
  it.each(locales)('renders the roles section in %s with no English leftover and no raw key', async (locale) => {
    cleanup();
    render(await RolesSection({ locale }));
    expect(screen.getByTestId('role-cards')).toBeInTheDocument();
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(RAW_KEY);
    if (locale !== 'en') for (const leftover of ENGLISH_LEFTOVERS) expect(text, `${locale}`).not.toMatch(leftover);
  });

  it.each(locales)('hands the integrations strip its labels in %s', async (locale) => {
    // The strip prints English defaults when given none, which is exactly how this band
    // stayed English on five locales while everything around it was translated.
    cleanup();
    render(await HomeIntegrationsStrip({ locale }));
    const text = document.body.textContent ?? '';
    expect(text).toContain(messages[locale].LandingHome.integrations.heading);
    expect(text).toContain(CATALOG_INTEGRATIONS_CLAIM);
    expect(text).toContain(CATALOG_OPERATIONS_CLAIM);
    expect(text).not.toMatch(RAW_KEY);
    if (locale !== 'en') for (const leftover of ENGLISH_LEFTOVERS) expect(text, `${locale}`).not.toMatch(leftover);
  });

  it.each(locales)('renders the buildable band in %s from the shared keys', async (locale) => {
    cleanup();
    render(await BuildableSection({ locale }));
    const text = document.body.textContent ?? '';
    expect(text).toContain(messages[locale].PersonaLanding.common.buildEyebrow);
    expect(text).toContain(messages[locale].PersonaLanding.buildable.title);
    // One translated card, to prove the cards themselves came through and not just the heading.
    expect(text).toContain(messages[locale].PersonaLanding.buildable.examples.support.role);
    expect(text).not.toMatch(RAW_KEY);
  });

  it('gives each scrolling row a translated accessible name', async () => {
    // The rows are focusable scrollers, so their labels ARE user-facing text. They were
    // English literals on all six locales, inside the component the pass was rebuilding.
    render(await BuildableSection({ locale: 'fr' }));
    for (const n of [1, 2]) {
      expect(screen.getByRole('group', { name: `Exemples d’automatisations, rangée ${n}` })).toBeInTheDocument();
    }
    expect(screen.queryByRole('group', { name: /first row|second row/ })).toBeNull();
  });
});

/**
 * The heading and the cards are chosen by ONE function, because a section that announces
 * customer stories over cards attributed to nobody is the single thing this band must never
 * do. It is exported for that reason: the rule is the part that can be wrong.
 */
describe('which wall the buildable band shows', () => {
  it('shows real quotes only on the page whose language they were spoken in', () => {
    expect(showsTestimonials('en', true)).toBe(true);
    for (const locale of locales.filter((value) => value !== 'en')) {
      expect(showsTestimonials(locale, true), locale).toBe(false);
    }
  });

  it('shows capability cards everywhere while there are no authorized quotes', () => {
    for (const locale of locales) expect(showsTestimonials(locale, false), locale).toBe(false);
  });

  it('is what the section reads, so the heading cannot disagree with the cards', async () => {
    // With no authorized quotes (the shipped state of socialProof.ts) the English page shows
    // the capability heading, not the customer-stories one.
    render(await BuildableSection({ locale: 'en' }));
    expect(document.body.textContent).toContain(en.PersonaLanding.common.buildEyebrow);
    expect(document.body.textContent).not.toContain(en.LandingHome.build.testimonialsEyebrow);
  });

  it('names the rows for what they hold once quotes are authorized', async () => {
    // The branch is unreachable on the shipped data, which is exactly why it is driven here
    // by injecting the flag: the day marketing fills socialProof.ts, two scrollers of
    // customer stories must not announce themselves as "Example automations".
    vi.resetModules();
    vi.doMock('../socialProof', async () => {
      const actual = await vi.importActual<typeof import('../socialProof')>('../socialProof');
      return { ...actual, testimonialsReady: () => true };
    });
    const { BuildableSection: WithQuotes } = await import('../homeSections');
    cleanup();
    render(await WithQuotes({ locale: 'en' }));
    for (const n of [1, 2]) {
      expect(screen.getByRole('group', { name: `Customer stories, row ${n}` })).toBeInTheDocument();
    }
    expect(screen.queryByRole('group', { name: /Example automations/ })).toBeNull();
    expect(document.body.textContent).toContain(en.LandingHome.build.testimonialsEyebrow);
    vi.doUnmock('../socialProof');
    vi.resetModules();
  });
});
