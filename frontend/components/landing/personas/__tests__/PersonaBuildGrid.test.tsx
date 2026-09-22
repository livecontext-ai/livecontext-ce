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

const messages = { en, fr, de, es, pt, zh };

vi.mock('next-intl/server', () => ({
  getTranslations: async ({ locale, namespace }: { locale: Locale; namespace: string }) => createTranslator({
    locale,
    messages: messages[locale] as AbstractIntlMessages,
    namespace,
    onError: (error) => { throw error; },
  }),
}));
vi.mock('@/components/integrations/BrandMark', () => ({ BrandMark: ({ iconSlug }: { iconSlug: string }) => <i data-testid="brand-mark" data-slug={iconSlug} /> }));
vi.mock('../OpsBuildStudio', () => ({ default: () => <div data-testid="ops-studio" /> }));
vi.mock('../SupportBuildStudio', () => ({ default: () => <div data-testid="support-studio" /> }));
vi.mock('../SalesBuildStudio', () => ({ default: () => <div data-testid="sales-studio" /> }));
vi.mock('../MarketingBuildStudio', () => ({ default: () => <div data-testid="marketing-studio" /> }));
vi.mock('../RecruitingBuildStudio', () => ({ default: () => <div data-testid="recruiting-studio" /> }));

import PersonaBuildGrid from '../PersonaBuildGrid';
import type { PersonaKey } from '../personas';

afterEach(cleanup);

/** The fields a card reads. The namespace also holds a `navigation` string, which is why
 *  this is a narrowing cast rather than an index signature over the whole object. */
type ExampleCopy = {
  label: string; title: string; summary: string;
  triggerLabel: string; agentLabel: string; approvalLabel: string; format: string;
  description: string; hashtags: string; subtitleFirst: string;
  table: { name: string };
};
const copyOf = (persona: PersonaKey) =>
  en.PersonaLanding.personas[persona].workflowShowcase.examples as unknown as Record<string, ExampleCopy>;

describe('what this persona can build', () => {
  /**
   * The grid of text cards this section used to be is gone: every persona now shows the
   * product instead of describing it, so the only thing left to pin here is that each one
   * reaches the section built for it.
   */
  it.each(['ops', 'support', 'sales', 'marketing', 'recruiting'] as const)('sends %s to the section built for it, and to no other', async (persona) => {
    render(await PersonaBuildGrid({ persona, locale: 'en' }));
    expect(screen.getByTestId(`${persona}-studio`)).toBeInTheDocument();
    // A missing branch would fall through to whichever section the routing ends on, which
    // is a persona reading another persona's examples rather than an error.
    expect(screen.getAllByTestId(/-studio$/)).toHaveLength(1);
  });

  /**
   * Creator gets the photoroom.com treatment: four cards, each a workflow somebody runs,
   * and the visual is the run's own output. These assertions are about that promise being
   * kept, not about layout.
   */
  it('gives creator four workflow cards rather than a gallery', async () => {
    const studio = en.PersonaLanding.personas.creator.workflowShowcase.studio.cards;
    render(await PersonaBuildGrid({ persona: 'creator', locale: 'en' }));
    const cards = screen.getAllByRole('article');
    expect(cards).toHaveLength(4);
    for (const card of [studio.cutout, studio.batch, studio.formats, studio.subtitles]) {
      expect(screen.getByText(card.title)).toBeInTheDocument();
      expect(screen.getByText(card.summary)).toBeInTheDocument();
    }
  });

  it('wires every output back to the input it came from', async () => {
    const { container } = render(await PersonaBuildGrid({ persona: 'creator', locale: 'en' }));
    // The wires are what stop this reading as a moodboard, so each stage declares them and
    // every endpoint must exist in the markup: a wire to a missing tile draws nothing.
    const stages = [...container.querySelectorAll('[data-wires]')];
    expect(stages.length).toBeGreaterThanOrEqual(4);
    const ids = new Set([...container.querySelectorAll('[data-node]')].map((node) => node.getAttribute('data-node')));
    const wires = stages.flatMap((stage) => JSON.parse(stage.getAttribute('data-wires')!));
    // 1 cut-out + 3 exports, 1 pile to pile, 5 crops, 1 subtitled frame.
    expect(wires.length).toBe(11);
    for (const wire of wires) {
      expect(ids.has(wire.from)).toBe(true);
      expect(ids.has(wire.to)).toBe(true);
    }
  });

  it('exports the CUT-OUT to each marketplace, not the photograph it came from', async () => {
    const { container } = render(await PersonaBuildGrid({ persona: 'creator', locale: 'en' }));
    const srcOf = (node: string) => container.querySelector(`[data-node="${node}"] img`)?.getAttribute('src');
    expect(srcOf('raw')).toContain('supplier-raw');
    expect(srcOf('cut')).toContain('supplier-cutout');
    // An export pads the cut-out onto the marketplace's canvas. Showing the lifestyle crop
    // here would claim a step the run never took, which is the mistake this pins.
    for (const key of ['square', 'feed', 'link']) {
      expect(srcOf(`market-${key}`)).toContain(`listing-${key}`);
      expect(srcOf(`market-${key}`)).not.toContain('format-');
    }
  });

  it('cuts ONE photograph into the five sizes the networks ask for', async () => {
    const { container } = render(await PersonaBuildGrid({ persona: 'creator', locale: 'en' }));
    const names = en.PersonaLanding.personas.creator.workflowShowcase.studio.cards.formatNames;
    for (const [key, label] of Object.entries(names)) {
      expect(screen.getByText(label)).toBeInTheDocument();
      expect(container.querySelector(`[data-node="format-${key}"] img`)?.getAttribute('src')).toContain(`format-${key}`);
    }
    // Five crops, one source: the card says nothing is shot twice.
    expect(container.querySelector('[data-node="shoot"] img')?.getAttribute('src')).toContain('shoot');
    for (const size of ['1280 x 720', '1200 x 628', '1080 x 1080', '1080 x 1350', '1080 x 1920']) {
      expect(screen.getAllByText(size).length).toBeGreaterThanOrEqual(1);
    }
  });

  it('draws the subtitle in the page rather than baking it into the picture', async () => {
    const copy = copyOf('creator');
    const { container } = render(await PersonaBuildGrid({ persona: 'creator', locale: 'en' }));
    const clip = container.querySelector('[data-node="clip"]')!;
    const subtitled = container.querySelector('[data-node="subtitled"]')!;
    // Same frame on both sides; only one carries the band, and it is TEXT, so it stays
    // translated in the six locales instead of being burned into a French-only file.
    expect(clip.querySelector('img')?.getAttribute('src')).toBe(subtitled.querySelector('img')?.getAttribute('src'));
    expect(clip.textContent).not.toContain(copy.reel.subtitleFirst);
    expect(subtitled.textContent).toContain(copy.reel.subtitleFirst);
  });

  it.each(locales)('resolves every creator card in %s, with no key falling through', async (locale) => {
    // getTranslations throws on a missing message here, so copy short of a key in one
    // locale fails this rather than shipping an English card into a translated page. The
    // other personas render client studios, covered by their own files.
    render(await PersonaBuildGrid({ persona: 'creator', locale }));
    const cards = screen.getAllByRole('article');
    expect(cards).toHaveLength(4);
    expect(cards.every((card) => (card.textContent ?? '').trim().length > 20)).toBe(true);
  });
});
