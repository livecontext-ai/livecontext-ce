// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import { locales } from '@/i18n/routing';
import PersonaHeroNav from '../PersonaHeroNav';
import { PERSONA_KEYS, personaHref } from '../personas';

const messages = { en, fr } as const;

afterEach(cleanup);

/**
 * These pills were drawn inside the hero iframe until the hero became the live
 * workflow demo. They are the ONLY in-page links to the /for/<persona> pages, so a
 * regression here silently strips those five pages of every inbound link.
 */
describe('persona hero navigation', () => {
  it.each(['en', 'fr'] as const)('links every persona page from the %s hero', (locale) => {
    render(
      <NextIntlClientProvider locale={locale} messages={messages[locale]} onError={(error) => { throw error; }}>
        <PersonaHeroNav />
      </NextIntlClientProvider>,
    );
    const nav = screen.getByRole('navigation', { name: messages[locale].PersonaLanding.common.personaNavigation });
    const links = within(nav).getAllByRole('link');
    expect(links).toHaveLength(PERSONA_KEYS.length);
    expect(links.map((link) => link.getAttribute('href'))).toEqual(PERSONA_KEYS.map((persona) => personaHref(persona, locale)));
    expect(links.map((link) => link.textContent)).toEqual(PERSONA_KEYS.map((persona) => messages[locale].PersonaLanding.personas[persona].name));
    expect(links.every((link) => link.querySelector('svg'))).toBe(true);
    expect(nav.querySelector('[aria-current]')).toBeNull();
  });

  it('marks the page being read and leaves the others navigable', () => {
    render(
      <NextIntlClientProvider locale="en" messages={en}>
        <PersonaHeroNav current="sales" onPage />
      </NextIntlClientProvider>,
    );
    expect(screen.getByRole('link', { name: en.PersonaLanding.personas.sales.name })).toHaveAttribute('aria-current', 'page');
    for (const persona of PERSONA_KEYS.filter((key) => key !== 'sales')) {
      expect(screen.getByRole('link', { name: en.PersonaLanding.personas[persona].name })).not.toHaveAttribute('aria-current');
    }
  });

  it('marks the persona the hero is PLAYING as the current item, not as the current page', () => {
    // The home page shows operations and links away to it, so "page" would be a lie to a
    // screen reader while "true" is exactly what it means: the current item of a set.
    render(
      <NextIntlClientProvider locale="en" messages={en}>
        <PersonaHeroNav current="ops" />
      </NextIntlClientProvider>,
    );
    expect(screen.getByRole('link', { name: en.PersonaLanding.personas.ops.name })).toHaveAttribute('aria-current', 'true');
    expect(screen.getAllByRole('link').filter((link) => link.hasAttribute('aria-current'))).toHaveLength(1);
  });

  it('names each link even where the label is hidden, since the icon is all a phone shows', () => {
    render(
      <NextIntlClientProvider locale="en" messages={en}>
        <PersonaHeroNav />
      </NextIntlClientProvider>,
    );
    for (const persona of PERSONA_KEYS) {
      const link = screen.getByRole('link', { name: en.PersonaLanding.personas[persona].name });
      expect(link).toHaveAttribute('aria-label', en.PersonaLanding.personas[persona].name);
      expect(link.querySelector('.persona-hero-nav-label')).toHaveTextContent(en.PersonaLanding.personas[persona].name);
    }
  });

  it.each(locales)('keeps every href inside the %s URL tree', (locale) => {
    render(
      <NextIntlClientProvider locale={locale} messages={en}>
        <PersonaHeroNav />
      </NextIntlClientProvider>,
    );
    const expected = locale === 'en' ? '/for/' : `/${locale}/for/`;
    for (const link of screen.getAllByRole('link')) expect(link.getAttribute('href')).toContain(expected);
  });
});
