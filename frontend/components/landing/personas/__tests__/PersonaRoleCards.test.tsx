// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
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
    // Throwing is the point: a card built from a key the persona does not own renders an
    // empty line in production, and a silent blank is exactly what these cards must not do.
    onError: (error) => { throw error; },
  }),
}));
// The screen is an iframe with its own document and a theme context; the card's own contract
// is the copy, the colour, the layout and the link, so the island is stubbed to the persona
// it was handed. PersonaScreenStage has its own suite.
vi.mock('../PersonaScreenStage', () => ({ default: ({ persona }: { persona: string }) => <div data-testid="screen" data-persona={persona} /> }));

import PersonaRoleCards, { ROLE_CARD_ROWS } from '../PersonaRoleCards';
import { HERO_EXAMPLE_KEYS, PERSONA_KEYS, PERSONA_TINTS, type PersonaKey } from '../personas';

afterEach(cleanup);

const cards = () => screen.getAllByRole('article');
const cardFor = (persona: PersonaKey) => cards().find((node) => node.dataset.persona === persona)!;
const heroCopy = (persona: PersonaKey) => {
  const examples = en.PersonaLanding.personas[persona].workflowShowcase.examples as Record<string, { title: string; summary: string }>;
  return examples[HERO_EXAMPLE_KEYS[persona]];
};

describe('the six role cards on the home page', () => {
  it('shows one card per persona and no other', async () => {
    render(await PersonaRoleCards({ locale: 'en' }));
    expect(cards()).toHaveLength(PERSONA_KEYS.length);
    expect(new Set(cards().map((card) => card.dataset.persona))).toEqual(new Set(PERSONA_KEYS));
  });

  it('reads in the ranked order, which is what a keyboard and a screen reader follow', async () => {
    // The rows alternate which half is wide; that must be done by CHOOSING the wide one, not
    // by reordering, or the visual rhythm and the reading order stop agreeing.
    render(await PersonaRoleCards({ locale: 'en' }));
    expect(cards().map((card) => card.dataset.persona)).toEqual([...PERSONA_KEYS]);
    expect(ROLE_CARD_ROWS.flat()).toEqual([...PERSONA_KEYS]);
  });

  it('alternates the wide slot from row to row, which is the studios own rhythm', async () => {
    render(await PersonaRoleCards({ locale: 'en' }));
    const spans = cards().map((card) => card.dataset.span);
    expect(spans).toEqual(['wide', 'narrow', 'narrow', 'wide', 'wide', 'narrow']);
    // Exactly one of each pair is wide, or the row is not a 3-and-2 at all.
    for (const [first, second] of ROLE_CARD_ROWS) {
      expect([cardFor(first).dataset.span, cardFor(second).dataset.span].sort()).toEqual(['narrow', 'wide']);
    }
  });

  it('gives every card a button to its own persona page', async () => {
    render(await PersonaRoleCards({ locale: 'en' }));
    for (const persona of PERSONA_KEYS) {
      expect(within(cardFor(persona)).getByRole('link')).toHaveAttribute('href', `/for/${persona}`);
    }
  });

  it('keeps the locale prefix on every link, so a French visitor stays on the French page', async () => {
    render(await PersonaRoleCards({ locale: 'fr' }));
    expect(screen.getAllByRole('link').map((link) => link.getAttribute('href')))
      .toEqual(PERSONA_KEYS.map((persona) => `/fr/for/${persona}`));
  });

  it('paints each card in the hue of the page it opens', async () => {
    render(await PersonaRoleCards({ locale: 'en' }));
    // Read from PERSONA_TINTS rather than repeated here: the whole point of that constant is
    // that the card and the page it links to cannot drift apart.
    for (const persona of PERSONA_KEYS) {
      expect(cardFor(persona).style.getPropertyValue('--role-tint')).toBe(PERSONA_TINTS[persona]);
    }
    expect(new Set(Object.values(PERSONA_TINTS)).size).toBe(PERSONA_KEYS.length);
  });

  it('describes each card with the example that persona page leads with', async () => {
    render(await PersonaRoleCards({ locale: 'en' }));
    for (const persona of PERSONA_KEYS) {
      const card = cardFor(persona);
      expect(within(card).getByRole('heading', { level: 3 })).toHaveTextContent(heroCopy(persona).title);
      expect(card).toHaveTextContent(heroCopy(persona).summary);
      // The screen shows the SAME example, or the picture and the caption are two different
      // automations sharing a card.
      expect(within(card).getByTestId('screen')).toHaveAttribute('data-persona', persona);
    }
  });

  it('names every card, so six landmarks are not announced as six articles', async () => {
    render(await PersonaRoleCards({ locale: 'en' }));
    for (const persona of PERSONA_KEYS) {
      const card = cardFor(persona);
      const heading = within(card).getByRole('heading', { level: 3 });
      expect(card).toHaveAttribute('aria-labelledby', heading.id);
      expect(heading.id).not.toBe('');
    }
  });

  it('names each button for its own role, so six links are told apart in a link list', async () => {
    render(await PersonaRoleCards({ locale: 'en' }));
    const names = screen.getAllByRole('link').map((link) => link.textContent?.trim());
    expect(new Set(names).size).toBe(PERSONA_KEYS.length);
    expect(names[0]).toContain(en.PersonaLanding.personas.ops.name);
  });

  it('renders in every supported locale with no key falling back to a raw path', async () => {
    // The mocked translator throws on a missing key, so this asserts translation coverage for
    // all six locales at once: the bug this section was built under was a home page whose
    // cards stayed English while the page around them was translated.
    for (const locale of locales) {
      cleanup();
      render(await PersonaRoleCards({ locale }));
      expect(cards()).toHaveLength(PERSONA_KEYS.length);
      for (const card of cards()) expect(card.textContent).not.toMatch(/PersonaLanding\.|LandingHome\./);
    }
  });
});
