// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { NextIntlClientProvider, type AbstractIntlMessages } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { locales, type Locale } from '@/i18n/routing';

/**
 * The three "what you can build" sections that show the product instead of describing it.
 *
 * <p>What is worth pinning here is not the layout, which moves, but the two promises the
 * section makes and the two ways it broke while being built: every wire must land on a tile
 * that exists (a wire to a missing id draws NOTHING, silently, which is how the section
 * shipped once with no visible links), and the artefacts must be the product's own, so the
 * table is the real snapshot and the screen the real interface HTML rather than a picture
 * of either.
 */
const messages: Record<Locale, AbstractIntlMessages> = { en, fr, de, es, pt, zh } as never;

const captured = vi.hoisted(() => ({ tables: [] as Record<string, unknown>[], screens: [] as Record<string, unknown>[] }));
// Both heavy children arrive through next/dynamic, so one mock covers them; the props tell
// them apart, and capturing those props is how the "it is the REAL table" claim is checked
// without mounting the table itself.
vi.mock('next/dynamic', () => ({
  default: () => (props: Record<string, unknown>) => {
    if ('snapshotData' in props) { captured.tables.push(props); return <div data-testid="studio-table" />; }
    captured.screens.push(props);
    return <div data-testid="studio-screen" />;
  },
}));
vi.mock('@/components/landing/LandingThemeProvider', () => ({ useLandingTheme: () => ({ theme: 'light' }) }));
vi.mock('@/components/integrations/BrandMark', () => ({ BrandMark: ({ iconSlug }: { iconSlug: string }) => <i data-testid="brand-mark" data-slug={iconSlug} /> }));

import OpsBuildStudio from '../OpsBuildStudio';
import SupportBuildStudio from '../SupportBuildStudio';
import SalesBuildStudio from '../SalesBuildStudio';
import MarketingBuildStudio from '../MarketingBuildStudio';
import RecruitingBuildStudio from '../RecruitingBuildStudio';
import { BUSINESS_DESTINATIONS, HERO_EXAMPLE_KEYS } from '../personas';
import { CANDIDATE_KEYS } from '../WorkflowRecapPanel';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';

beforeEach(() => { captured.tables = []; captured.screens = []; });
afterEach(cleanup);

const mount = (node: ReactElement, locale: Locale = 'en') =>
  render(<NextIntlClientProvider locale={locale} messages={messages[locale]}>{node}</NextIntlClientProvider>);

/** Every wire the stages declare, and every tile id the markup offers them. */
const wiresOf = (container: HTMLElement) => [...container.querySelectorAll('[data-wires]')]
  .flatMap((stage) => JSON.parse(stage.getAttribute('data-wires')!) as { from: string; to: string }[]);
const idsOf = (container: HTMLElement) => new Set([...container.querySelectorAll('[data-node]')].map((node) => node.getAttribute('data-node')));
const mountCards = (node: ReactElement) => { mount(node); return screen.getAllByRole('article'); };
const tableCopy = (persona: string) => (en.PersonaLanding.personas as unknown as Record<string, { workflowShowcase: { studio: { cards: { table: { title: string; summary: string } } } } }>)[persona].workflowShowcase.studio.cards.table;

/** The five sections, with the number of wires each one declares. */
const STUDIOS = [
  { persona: 'ops', element: <OpsBuildStudio />, wires: 14 },
  { persona: 'support', element: <SupportBuildStudio />, wires: 6 },
  { persona: 'sales', element: <SalesBuildStudio />, wires: 7 },
  { persona: 'marketing', element: <MarketingBuildStudio />, wires: 6 },
  { persona: 'recruiting', element: <RecruitingBuildStudio />, wires: 8 },
] as const;

describe('what each persona can build', () => {
  it.each(STUDIOS)('draws every $persona wire between two tiles that exist', ({ element, wires: expected }) => {
    const { container } = mount(element);
    const wires = wiresOf(container);
    const ids = idsOf(container);
    // A wire naming an id nothing carries is not an error anywhere: the SVG is simply
    // empty, and the section reads as a set of unrelated pictures.
    expect(wires).toHaveLength(expected);
    for (const wire of wires) {
      expect(ids.has(wire.from)).toBe(true);
      expect(ids.has(wire.to)).toBe(true);
    }
  });

  it.each(STUDIOS)('lets no $persona card grow wider than the column it sits in', ({ element }) => {
    // The bug this pins gave the whole PAGE a horizontal scrollbar on a phone, and it is
    // invisible in jsdom because nothing is laid out: the history table carries
    // `min-width: 900px`, and a grid cell defaults to `min-width: auto`, so the cell grew to
    // its widest descendant instead of clipping it. Measured on a real build, a 360px screen
    // rendered a 974px card. `min-w-0` is what stops the propagation, and the containers
    // below it already clip with `overflow: hidden`.
    const { container } = mount(element);
    const cells = [...container.querySelectorAll('[class*="lg:col-span-"]')];
    expect(cells.length).toBeGreaterThan(0);
    for (const cell of cells) expect(cell.className, cell.className).toContain('min-w-0');
  });

  it.each(STUDIOS)('gives $persona four cards, each with its own footer', ({ persona, element }) => {
    const articles = mountCards(element);
    expect(articles).toHaveLength(4);
    // Every card says what it is: ops and support carry copy written for their four pain
    // points, the other three reuse the copy of the example each card shows plus the one
    // string the table card needed. Either way, a card with no title is a card nobody reads.
    for (const article of articles) {
      const footer = within(article.lastElementChild as HTMLElement);
      expect(footer.getByRole('heading').textContent?.trim().length).toBeGreaterThan(10);
    }
    // Every section ends on the product's own table, and that card is the one whose copy
    // had to be written rather than reused, so it is the one worth naming here.
    expect(screen.getByRole('heading', { name: tableCopy(persona).title })).toBeInTheDocument();
    expect(screen.getByText(tableCopy(persona).summary)).toBeInTheDocument();
  });

  it.each(locales)('resolves every card of every section in %s, with no key falling through', (locale) => {
    // next-intl renders the KEY PATH when a message is missing, so a locale short of a
    // string shows "PersonaLanding.personas.ops...." on the page rather than failing. That
    // is what this looks for, across the five sections at once.
    for (const { element } of STUDIOS) {
      const { container, unmount } = mount(element, locale);
      expect(screen.getAllByRole('article')).toHaveLength(4);
      expect(container.textContent).not.toContain('PersonaLanding.');
      unmount();
    }
  });

  it.each(STUDIOS)('gives $persona the product own table, six rows with a portrait second', ({ element }) => {
    const { container } = mount(element);
    const [table] = captured.tables;
    // The table is built from the same snapshot the hero's side panel opens on, so the two
    // cannot drift, and it is read-only because a landing card is not an editor.
    expect(table.readOnly).toBe(true);
    const snapshot = table.snapshotData as { rows: unknown[]; columns: { field: string }[] };
    expect(snapshot.rows).toHaveLength(6);
    expect(snapshot.columns[1].field).toContain('photo');
    expect(container.querySelector('[data-node="history"] [data-testid="studio-table"]')).toBeInTheDocument();
  });

  it('renders the screens from the interface HTML, not from a picture of one', () => {
    mount(<OpsBuildStudio />);
    const [screenProps] = captured.screens;
    // The persona's own translated copy reaches the screen, which is what an image of a
    // screen could never keep across six locales.
    expect(screenProps.htmlTemplate).toContain(en.PersonaLanding.personas.ops.workflowShowcase.examples.report.response);
    expect(screenProps.dropJs).toBe(true);
  });

  it.each(STUDIOS)('scales the $persona phone at its own size, inside a box that reserves what it paints', ({ persona, element }) => {
    const { container } = mount(element);
    const phone = container.querySelector('[data-node="phone"]') as HTMLElement;
    const box = phone.parentElement as HTMLElement;
    // The phone paints 286x514 whatever box it is given: a height on the SCALED element is
    // what cut the message in half, so the height belongs to the wrapper.
    expect(phone).toHaveClass(`${persona}-phone`);
    expect(box.style.width).toBe('150px');
    expect(box.style.height).toBe('270px');
    expect(container.querySelector('style')?.textContent)
      .toMatch(new RegExp(`\\.${persona}-phone\\{width:286px;height:514px;transform:scale\\([\\d.]+\\);transform-origin:top left\\}`));
  });

  it('gives support a roster agent with the tool it answers on', () => {
    const { container } = mount(<SupportBuildStudio />);
    const agent = container.querySelector('[data-node="agent"]')!;
    const label = en.PersonaLanding.personas.support.workflowShowcase.examples[HERO_EXAMPLE_KEYS.support].agentLabel;
    // The avatar is the roster's own preset, and the badge comes from the avatar tool
    // catalogue: a renamed preset or tool field returns null here rather than a face.
    expect(agent.querySelector('img')).toHaveAttribute('alt', label);
    expect(agent.querySelector('span.absolute svg')).toBeInTheDocument();
  });

  it('shows recruiting the same face beside the same name as its table', () => {
    const { container } = mount(<RecruitingBuildStudio />);
    const rows = (captured.tables[0].snapshotData as { rows: { data: Record<string, string> }[] }).rows;
    const roster = en.PersonaLanding.tablePreview.recruiting.candidates as Record<string, { name: string }>;
    CANDIDATE_KEYS.slice(0, 3).forEach((candidate, index) => {
      const portrait = container.querySelector(`[data-node="candidate-${candidate}"] img`)!;
      // The card and the table draw from one roster: a portrait that drifted would put two
      // different names on one face, on the same page.
      expect(portrait.getAttribute('src')).toBe(rows[index].data.photo);
      expect(portrait.getAttribute('alt')).toBe(roster[candidate].name);
    });
  });

  it('marks each marketing launch asset with the channel that example sends it to', () => {
    const { container } = mount(<MarketingBuildStudio />);
    // The record names a channel in prose; the slug comes from the example's destinations,
    // joined BY POSITION. A reordered list would put the wrong logo on the right message.
    for (const slug of BUSINESS_DESTINATIONS.campaign) {
      const mark = container.querySelector(`[data-node="channel-${slug}"] [data-testid="brand-mark"]`);
      expect(mark).toHaveAttribute('data-slug', WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug)!.iconSlug);
    }
  });
});
