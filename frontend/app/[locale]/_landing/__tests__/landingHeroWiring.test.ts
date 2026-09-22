import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The homepage hero visual, at the wiring level.
 *
 * <p>The hero used to embed `public/hero-flow.html`, a hand-drawn replica of the app.
 * It now runs the live demo the persona pages run: the same request typed in chat and
 * the same self-building workflow, drawn with the product's real nodes.
 *
 * <p>Source-level, like `landingMarketplaceWiring.test.ts`: the landing is a
 * 3,000-line server component whose module pulls in the entire marketing tree, and
 * rendering it in jsdom would test Framer motion rather than this wiring.
 *
 * <p>The pill nav is asserted here because it carries the only in-page links to the
 * /for/<persona> pages; it lived inside the iframe, and dropping it with the iframe
 * would have left those five pages linked from the sitemap alone.
 */
const landingSource = readFileSync(path.resolve(__dirname, '../../page.tsx'), 'utf8');

describe('landing demo panels', () => {
  it('puts the agents and agenda showcases on the hero backdrop, each bleeding off its own sides', () => {
    // The backdrop is one shared class, and each showcase names the sides it runs off:
    // agents keeps a band on the left and top, the agenda keeps left, right and top and
    // is cut at roughly 70% of the calendar.
    expect(landingSource).toContain('<div className="landing-demo-panel" data-bleed="right bottom">');
    expect(landingSource).toContain('data-bleed="bottom" data-crop="agenda"');
  });
});

describe('landing hero showcase', () => {
  it('runs the live workflow demo, not the old iframe replica', () => {
    expect(landingSource).toContain('<HeroWorkflowShowcase />');
    expect(landingSource).not.toContain('HeroFlowShowcase');
    expect(landingSource).not.toContain('hero-flow.html');
  });

  it('keeps the persona pills in the hero, the only in-page links to the persona pages', () => {
    // The home page opens on operations, and the pill says so: a nav with no marked pill
    // was the "default page" state this replaced, where the hero played a persona and
    // nothing on screen said which.
    expect(landingSource).toContain('<PersonaHeroNav current="ops" />');
    expect(landingSource.indexOf('<PersonaHeroNav current="ops" />')).toBeLessThan(landingSource.indexOf('<HeroWorkflowShowcase'));
    expect(landingSource).toContain('persona-hero-stage');
  });
});
