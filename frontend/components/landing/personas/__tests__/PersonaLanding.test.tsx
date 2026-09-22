// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import type { ReactNode } from 'react';
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
vi.mock('@/components/landing/LandingShell', () => ({ LandingHeader: () => null, LandingFooter: () => null, GithubMark: () => <svg />, landingChromeStyles: '' }));
vi.mock('@/components/landing/landingStyles', () => ({ landingStyles: '' }));
vi.mock('@/app/[locale]/_landing/SignInButton', () => ({ default: ({ children, returnTo, className }: { children: ReactNode; returnTo: string; className: string }) => <a href={returnTo} className={className}>{children}</a> }));
// The hero demo and the pill nav are client components that read the intl CONTEXT,
// which this file does not provide (it renders the server component directly).
vi.mock('../HeroWorkflowShowcase', () => ({ default: ({ persona }: { persona: string }) => <div data-testid="hero-workflow-demo" data-persona={persona} /> }));
vi.mock('../PersonaHeroNav', () => ({ default: ({ current }: { current?: string }) => <nav data-testid="persona-hero-nav" data-current={current} /> }));
// An async server component of its own: covered by PersonaBuildGrid.test.tsx, which can await it.
vi.mock('../PersonaBuildGrid', () => ({ default: ({ persona }: { persona: string }) => <div data-testid="persona-build-grid" data-persona={persona} /> }));
vi.mock('../PersonaBuildableBand', () => ({ default: ({ persona }: { persona: string }) => <div data-testid="persona-buildable-band" data-persona={persona} /> }));
vi.mock('@/app/[locale]/_landing/AgentsShowcase', () => ({ default: ({ team, persona, locale }: { team: { name: string; description: string }[]; persona: string; locale: string }) => <div data-testid="agents-demo" data-persona={persona} data-locale={locale}>{team.map((agent) => <article key={agent.name}><h3>{agent.name}</h3><p>{agent.description}</p></article>)}</div> }));
vi.mock('@/app/[locale]/_landing/AgendaShowcase', () => ({ default: ({ persona, locale }: { persona: string; locale: string }) => <div data-testid="agenda-demo" data-persona={persona} data-locale={locale} /> }));
vi.mock('@/app/[locale]/_landing/MarketplacePreview', () => ({ default: ({ persona }: { persona?: string }) => <div data-testid="marketplace-preview" data-persona={persona} /> }));
vi.mock('@/app/[locale]/_landing/PricingSection', () => ({ default: () => <div data-testid="pricing-plans" /> }));
import PersonaLanding from '../PersonaLanding';
import { PERSONA_KEYS } from '../personas';
import { personaStyles } from '../personaStyles';

afterEach(() => { cleanup(); localStorage.clear(); });

describe('persona landing experience', () => {
  describe.each(locales)('%s locale', (locale) => {
    it.each(PERSONA_KEYS)('renders translated %s content and localized navigation without missing messages', async (persona) => {
      const copy = messages[locale].PersonaLanding;
      const { container } = render(await PersonaLanding({ persona, locale }));
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(copy.personas[persona].title);
      expect(container.querySelector('#faq')).toBeNull();
      const mainSections = [...container.querySelector('main')!.children];
      // The hero plays ONE example, so the page still has to say what else this persona
      // can build; that section sits right after the integrations strip.
      expect(mainSections[2]).toHaveAttribute('id', 'persona-build');
      expect(mainSections[3]).toHaveAttribute('id', 'marketplace');
      // The homepage carries the wall of buildable automations just before its pricing
      // block; every persona page now does the same, with its own automations leading.
      expect(container.querySelector('#persona-buildable + section')).toHaveAttribute('id', 'pricing');
      expect(screen.getByTestId('persona-buildable-band')).toHaveAttribute('data-persona', persona);
      expect(container.querySelector('#persona-buildable h2')).toHaveTextContent(copy.buildable.title);
      expect(screen.getByTestId('persona-build-grid')).toHaveAttribute('data-persona', persona);
      expect(container.querySelector('#persona-build')).toHaveTextContent(copy.common.buildEyebrow);
      expect(container.querySelector('#persona-build h2')).toHaveTextContent(copy.personas[persona].workflowShowcase.title);
      // The workflow showcase that used to animate here is the hero now, so the agents
      // section is followed straight by the agenda one and the page tells its story once.
      expect(container.querySelector('#persona-agents + section')).toHaveAttribute('id', 'persona-agenda');
      expect(container.querySelector('#persona-specialists')).toBeNull();
      // The roster is this persona's OWN twelve, in its own language: the section used to
      // hand every persona the same generic team, which is the one thing a page titled
      // after a job must not do.
      const roster = screen.getByTestId('agents-demo');
      expect(roster).toHaveAttribute('data-persona', persona);
      const teamCopy = Object.values(copy.personas[persona].team);
      expect(teamCopy).toHaveLength(12);
      expect([...roster.querySelectorAll('article h3')].map((name) => name.textContent))
        .toEqual(teamCopy.map((member) => member.name));
      expect([...roster.querySelectorAll('article p')].map((role) => role.textContent))
        .toEqual(teamCopy.map((member) => member.description));
      expect(container.querySelector('#persona-agents h2')).toHaveTextContent(copy.personas[persona].workforce.title);
      expect(container.querySelector('#persona-agents')).toHaveTextContent(copy.personas[persona].workforce.eyebrow);
      expect(container.querySelector('#persona-agents')).toHaveTextContent(copy.personas[persona].workforce.description);
      expect(screen.getByTestId('marketplace-preview')).toBeInTheDocument();
      expect(screen.getByTestId('pricing-plans')).toBeInTheDocument();
      expect(container.querySelector('main > :last-child')).toHaveAttribute('id', 'final-cta');
      expect(container.querySelectorAll('.integration-chip').length).toBeGreaterThanOrEqual(25);
      expect(container.querySelector('.persona-other')).toBeNull();
      const ctas = screen.getAllByRole('link', { name: copy.common.cta });
      // Hero, build grid and final CTA go to the chat; the agenda section to the agenda.
      expect(ctas).toHaveLength(4);
      expect(ctas.filter((cta) => cta.getAttribute('href') === `/${locale}/app/chat`)).toHaveLength(3);
      expect(ctas.filter((cta) => cta.getAttribute('href') === `/${locale}/app/agenda`)).toHaveLength(1);
      for (const cta of ctas) expect(cta).toHaveClass('h-9', 'px-4', 'rounded-xl', 'text-sm', 'font-medium');
      const heroDemo = screen.getByTestId('hero-workflow-demo');
      expect(heroDemo).toHaveAttribute('data-persona', persona);
      expect(container.querySelector('#hero')).toContainElement(heroDemo);
      expect(screen.getByTestId('persona-hero-nav')).toHaveAttribute('data-current', persona);
      expect(screen.getByTestId('agenda-demo')).toHaveAttribute('data-persona', persona);
      expect(screen.getByTestId('agenda-demo')).toHaveAttribute('data-locale', locale);
      expect(roster).toHaveAttribute('data-locale', locale);
      expect(container.querySelector('#hero h1')).toHaveClass('hero-h1');
      expect(screen.getAllByRole('link', { name: messages[locale].pricing.deployment.selfHosted })).toHaveLength(2);
    });
  });

  it.each(['en', 'fr'] as const)('shows the complete creator journey in %s', async (locale) => {
    const copy = messages[locale].PersonaLanding.personas.creator;
    render(await PersonaLanding({ persona: 'creator', locale }));
    expect(screen.getByText(copy.description)).toBeInTheDocument();
    expect(screen.getByTestId('hero-workflow-demo')).toHaveAttribute('data-persona', 'creator');
    const renderedText = screen.getByRole('main').textContent!;
    expect(renderedText).toMatch(locale === 'en' ? /creat/i : /cré/i);
    expect(renderedText).toMatch(locale === 'en' ? /subtitles/i : /sous-titr/i);
    expect(renderedText).toMatch(locale === 'en' ? /calendar/i : /calendrier|agenda/i);
    expect(renderedText).toMatch(locale === 'en' ? /publish/i : /publi/i);
  });

  it('keeps every persona translation namespace at strict key parity', () => {
    function flatten(value: object, prefix = ''): string[] {
      return Object.entries(value).flatMap(([key, entry]) => typeof entry === 'object' && entry !== null
        ? flatten(entry, `${prefix}${key}.`)
        : [`${prefix}${key}`]).sort();
    }
    const reference = flatten(en.PersonaLanding);
    for (const locale of locales) expect(flatten(messages[locale].PersonaLanding), locale).toEqual(reference);
  });

  it('restores the landing dark theme and uses the actual creator workflow and agenda demos', async () => {
    localStorage.setItem('landing-theme', 'dark');
    const { container } = render(await PersonaLanding({ persona: 'creator', locale: 'en' }));
    expect(container.querySelector('.landing-root')).toHaveClass('dark', 'persona-creator');
    expect(screen.getByText('Instagram')).toBeInTheDocument();
    expect(screen.getByText('Reddit')).toBeInTheDocument();
    expect(screen.getByText('LinkedIn')).toBeInTheDocument();
    expect(screen.getByText('YouTube Data API')).toBeInTheDocument();
    expect(screen.getByTestId('agenda-demo')).toHaveAttribute('data-persona', 'creator');
    expect(screen.getByTestId('hero-workflow-demo')).toHaveAttribute('data-persona', 'creator');
    expect(screen.queryByRole('link', { name: en.PersonaLanding.common.back })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: en.PersonaLanding.common.secondaryCta })).not.toBeInTheDocument();
  });

  /**
   * The page alternates two grounds: a pale one and a band a shade darker, both tinted with
   * the persona's own hue. It did not before, and nothing failed: two grey sections ran into
   * each other and four white ones followed, which reads as one long block with a seam in an
   * arbitrary place. The order is declared in the markup rather than counted in CSS, because
   * `main` also carries nodes that are not sections.
   */
  it('alternates a pale ground and a darker band, section by section', async () => {
    const { container } = render(await PersonaLanding({ persona: 'ops', locale: 'en' }));
    const groundOf = (id: string) => (container.querySelector(`#${id}`) as HTMLElement).style.background;
    const rhythm = ['hero', 'persona-build', 'marketplace', 'persona-agents', 'persona-agenda', 'persona-buildable', 'pricing', 'final-cta']
      .map((id) => (groundOf(id).includes('--persona-band') ? 'band' : groundOf(id).includes('--persona-ground') ? 'ground' : `? ${groundOf(id)}`));
    // The integrations strip sits between the hero and the build section, and takes the band
    // from the stylesheet (it is shared with the home page), which is why it is not listed.
    expect(rhythm).toEqual(['ground', 'ground', 'band', 'ground', 'band', 'ground', 'band', 'ground']);
  });

  it('names two grounds the stylesheet declares, and keeps a neutral fallback on every section', async () => {
    // The rhythm above is inline `background: var(--persona-ground)`, and those two variables
    // exist only because personaStyles declares them on `.persona-page`. That split is the
    // risk: a var() whose name nothing declares resolves to NOTHING, so renaming one here
    // would leave the sections transparent over the body, with no error anywhere and a page
    // that still renders. Both halves are pinned, and each section carries the neutral token
    // as its fallback, which is what the page looked like before it had a hue at all.
    expect(personaStyles).toMatch(/\.persona-page \{[^}]*--persona-ground:/);
    expect(personaStyles).toMatch(/\.persona-page \{[^}]*--persona-band:/);
    const { container } = render(await PersonaLanding({ persona: 'ops', locale: 'en' }));
    const sections = ['hero', 'persona-build', 'marketplace', 'persona-agents', 'persona-agenda', 'final-cta'];
    for (const id of sections) {
      const background = (container.querySelector(`#${id}`) as HTMLElement).style.background;
      expect(background, id).toMatch(/var\(--persona-(ground, var\(--bg-primary\)|band, var\(--bg-secondary\))\)/);
    }
  });

  it.each(PERSONA_KEYS)('asks the marketplace preview for the %s row, not the home page one', async (persona) => {
    // The whole curated-row feature hangs on this one prop. Without it every /for/<persona>
    // page quietly serves the home page's row, the admin's persona tabs look like they do
    // nothing, and no other test in this file or in MarketplacePreview's own suite notices,
    // because they render the component directly.
    render(await PersonaLanding({ persona, locale: 'en' }));
    expect(screen.getByTestId('marketplace-preview')).toHaveAttribute('data-persona', persona);
  });

  it('gives every persona a hue of its own, and keeps dark mode quiet', () => {
    // A persona added without a tint would render color-mix() with an empty value, which is
    // an invalid declaration: the section would fall back to transparent, not to a default.
    for (const persona of PERSONA_KEYS) {
      expect(personaStyles, persona).toContain(`.persona-page.persona-${persona} { --persona-tint:`);
    }
    // Light carries the colour; dark keeps the neutral tokens, because the same strength on
    // a near-black ground reads as a cast over the screen rather than as an accent.
    expect(personaStyles).toMatch(/--persona-band: color-mix\(in srgb, rgb\(var\(--persona-tint\)\) [\d.]+%/);
    expect(personaStyles).toMatch(/\.persona-page\.dark \{[^}]*--persona-band: var\(--bg-secondary\)/);
  });

  it('tints the panel the three product shots float on, on its own layer', () => {
    // The hero window, the agents roster and the agenda share one backdrop, and a neutral
    // grey frame around a coloured page reads as a hole in it. The layer is ::after, not the
    // panel's own background: ::before holds the texture under a saturate(.1) filter, which
    // drains any colour put in it.
    expect(personaStyles).toContain('.persona-page .landing-demo-panel::after');
    expect(personaStyles).toMatch(/\.landing-demo-panel::after \{[^}]*rgba\(var\(--persona-tint\)/);
    expect(personaStyles).toMatch(/\.landing-demo-panel::after \{[^}]*z-index: 0/);
  });
});
