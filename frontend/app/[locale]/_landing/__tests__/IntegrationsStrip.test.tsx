/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import IntegrationsStrip from '../IntegrationsStrip';

/**
 * The band under the hero, and the two failures that removed its two predecessors.
 *
 * <p>The cases below are not a render smoke test. Each one pins a property that, when it was
 * absent, is what got this band deleted from the landing: it must not fetch (7131fa534: the
 * gateway read could not run at build time and froze an empty render into production for an
 * hour on 2026-09-06), it must stay a strip rather than becoming a directory (same commit:
 * a 24-card grid with a search box pushed the whole page down), and it must link only where
 * the verified list can reach (7337ea9c6 replaced hand-written slugs nobody had resolved).
 */

describe('landing integrations strip', () => {
  it('prioritizes persona tools without dropping, duplicating or changing any verified link', () => {
    const originalOrder = WELL_KNOWN_INTEGRATIONS.map((integration) => integration.slug);
    render(<IntegrationsStrip prioritySlugs={['reddit', 'linkedin', 'reddit', 'not-a-real-integration', 'tiktok']} />);
    const links = Array.from(document.querySelectorAll('.integration-chips a'));
    const slugs = links.map((link) => link.getAttribute('href')!.replace('/integrations/', ''));
    expect(slugs.slice(0, 3)).toEqual(['reddit', 'linkedin', 'tiktok']);
    expect(slugs).toHaveLength(WELL_KNOWN_INTEGRATIONS.length);
    expect(slugs).toHaveLength(new Set(slugs).size);
    expect([...slugs].sort()).toEqual([...originalOrder].sort());
    expect(slugs.slice(3)).toEqual(originalOrder.filter((slug) => !['reddit', 'linkedin', 'tiktok'].includes(slug)));
    expect(WELL_KNOWN_INTEGRATIONS.map((integration) => integration.slug)).toEqual(originalOrder);
  });

  it('uses supplied translated labels with the exact homepage chip geometry', () => {
    const labels = { heading: 'Connectez vos outils', browseAll: 'Toutes les intégrations', details: 'Des opérations prêtes à utiliser' };
    render(<IntegrationsStrip labels={labels} className="persona-integrations" />);
    expect(screen.getByText(labels.heading)).not.toBeNull();
    expect(screen.getByText(labels.details)).not.toBeNull();
    expect(screen.getByRole('link', { name: labels.browseAll }).getAttribute('href')).toBe('/integrations');
    expect(screen.queryByText('Connects to the tools your team already uses')).toBeNull();
    expect(document.querySelector('#integrations')?.classList.contains('persona-integrations')).toBe(true);
    for (const link of Array.from(document.querySelectorAll('.integration-chip'))) {
      for (const token of ['h-9', 'px-3', 'rounded-xl', 'text-sm']) expect(link.classList.contains(token)).toBe(true);
    }
  });

  it('renders every verified integration as a link to its own page', () => {
    render(<IntegrationsStrip />);

    // Every entry, not a slice: the footer takes the first eight, this band is what the rest
    // of the list exists for, and a silent slice here would leave verified entries nowhere.
    for (const { name, slug } of WELL_KNOWN_INTEGRATIONS) {
      expect(screen.getByRole('link', { name }).getAttribute('href')).toBe(`/integrations/${slug}`);
    }
  });

  it('links nowhere outside the verified list and the directory', () => {
    render(<IntegrationsStrip />);

    // What makes a hand-written list safe here is wellKnownIntegrations.test.ts resolving
    // each slug against the seed corpus. This pins that the band renders nothing outside it,
    // so that verification actually covers what ships.
    const allowed = new Set([
      '/integrations',
      ...WELL_KNOWN_INTEGRATIONS.map((i) => `/integrations/${i.slug}`),
    ]);
    const hrefs = screen.getAllByRole('link').map((a) => a.getAttribute('href') ?? '');
    expect(hrefs.filter((href) => !allowed.has(href))).toHaveLength(0);
  });

  it('sends the visitor to the full catalogue under the advertised figure', () => {
    render(<IntegrationsStrip />);

    // The band is a shortcut, not the catalogue. Losing this link is what would make it a
    // dead end, and the figure is interpolated so it cannot drift from the constant.
    expect(screen.getByRole('link', { name: /Browse all 1000\+ integrations/ }).getAttribute('href')).toBe(
      '/integrations',
    );
  });

  it('stays a strip: no search field and no per-integration prose', () => {
    render(<IntegrationsStrip />);

    // The version removed in 7131fa534 was a 24-card grid with a search box over the whole
    // catalogue, sitting between the hero and everything the landing exists to say. The
    // catalogue page still does all of that, one click away.
    expect(screen.queryByRole('searchbox')).toBeNull();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(document.querySelectorAll('article')).toHaveLength(0);
  });

  it('keeps every link in the markup while capping what a phone shows', () => {
    render(<IntegrationsStrip />);

    // The cap is CSS, not a slice, so a crawler still sees every integration link. Asserting
    // it here is what stops someone "fixing" the mobile height by slicing the array instead,
    // which would quietly drop two dozen crawlable links off the page.
    const chips = document.querySelectorAll('.integration-chips > li');
    expect(chips).toHaveLength(WELL_KNOWN_INTEGRATIONS.length);

    const landingSource = readFileSync(join(process.cwd(), 'components', 'landing', 'landingStyles.ts'), 'utf8');
    expect(landingSource).toMatch(
      /@media \(max-width: 767px\)[\s\S]{0,300}integration-chips[\s\S]{0,150}display: none/,
    );
  });

  it('renders without reaching the gateway at all', () => {
    // The failure that deleted the previous band. It read the catalogue on render, which
    // cannot work at build time (the CI builder has no gateway) and timed out mid-rollout,
    // caching an empty band for every visitor for over an hour. A component that never
    // fetches cannot repeat it.
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    try {
      render(<IntegrationsStrip />);
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('draws every mark from the icon key rather than the slug', () => {
    render(<IntegrationsStrip />);

    // google-sheets is drawn by googlesheets: deriving the icon path from the slug is the
    // obvious shortcut and it produces a blank square in the first band under the hero.
    const sources = Array.from(document.querySelectorAll('img')).map((img) => img.getAttribute('src'));
    for (const { iconSlug } of WELL_KNOWN_INTEGRATIONS) {
      expect(sources).toContain(`/icons/services/${iconSlug}.svg`);
    }
  });

  it('leaves the marks out of the accessibility tree', () => {
    render(<IntegrationsStrip />);

    // Each mark sits inside a link that already carries the integration's name, so a real
    // alt would make a screen reader announce every name twice.
    for (const img of Array.from(document.querySelectorAll('img'))) {
      expect(img.getAttribute('alt')).toBe('');
    }
  });
});
