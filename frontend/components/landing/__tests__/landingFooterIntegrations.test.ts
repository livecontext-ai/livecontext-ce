import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

// Source-level, like landingFooterProductLinks.test.ts: the footer renders on
// public pages that have no intl context, so it is not mounted in a test. What
// is checked here is the wiring that has no other guard - a column that silently
// stops rendering, or hrefs that would 404 on the docs sub-host.
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

describe('landing footer Integrations column', () => {
  it('is rendered by the footer', () => {
    expect(shellSrc).toContain('<FooterIntegrations siteBaseUrl={siteBaseUrl}');
    // Its heading and its "all" link are copy, so they travel with the rest of the chrome's
    // labels. They were the last English words left in the footer of the five non-English
    // locales, sitting one column away from a Product entry that already said "Intégrations".
    expect(shellSrc).toContain('heading={labels.integrations}');
    expect(shellSrc).toContain('allLabel={labels.allIntegrations}');
  });

  // The column's own behaviour (links, the docs-sub-host prefix, the degradation
  // when the catalog is unreachable) is asserted by RENDERING it, in
  // FooterIntegrations.test.tsx. What is left here is the wiring that has no other
  // guard: whether the footer renders it at all, and whether the grid has room.

  it('leaves room for it in the footer grid', () => {
    // Seven columns since Models joined, and the grid has to be declared wide enough for all
    // seven or the new one lands on a row of its own. Asserted on the source because the
    // footer is never mounted (it renders on pages with no intl context), so there is no
    // rendered grid to measure.
    //
    // Seven of them start at xl rather than lg, and a second row below that is now deliberate,
    // not the layout bug this used to warn about: measured, the grid is 640px wide at a 1024px
    // viewport, so seven columns are 64px each there against 82px from 1280 up. 64px holds
    // "Gmail" and not "Datenschutzerklärung" (146px) - the German footer ran its Legal column
    // off the page and gave the whole document a 63px horizontal scroll.
    // `chromeFitsTranslations.test.ts` owns that reasoning and
    // `e2e/i18n/landing-chrome-fits.spec.ts` measures the result in a browser.
    expect(shellSrc).toContain('xl:grid-cols-7');
  });
});

describe('landing chrome and the removed blog', () => {
  it('links to no blog route, from the header or the footer', () => {
    // The blog is deleted and every /blog URL now answers 404, so a nav or
    // footer entry pointing at one would be a dead link shipped on every public
    // page. Matches any spelling: withBase(..., '/blog'), a bare '/blog', or a
    // locale-prefixed variant.
    expect(shellSrc).not.toMatch(/['"`]\/(?:[a-z]{2}\/)?blog/);
    expect(shellSrc).not.toContain('>Blog<');
  });
});

describe('landing footer Models column', () => {
  it('names the model families, from the verified list rather than inline strings', () => {
    // Inline names would be a claim about what the platform runs that nothing re-checks.
    // WELL_KNOWN_MODELS is verified against the catalogue seed by wellKnownModels.test.ts.
    expect(shellSrc).toContain('WELL_KNOWN_MODELS.map');
    expect(shellSrc).toContain('>{labels.models}</p>');
  });

  it('deep-links each family into /models, filtered on its own provider', () => {
    // This assertion used to demand the DOCS page, for a stated reason: no public page
    // listed the models, so eight per-family URLs would have been eight soft 404s. That
    // reason expired when /models shipped, and the URLs are no longer soft: ?provider=
    // is a real filtered view, so "Grok" lands on Grok instead of on 91 models the
    // visitor then has to search through. modelsQuery.test.ts checks the other half,
    // that all eight provider keys actually have rows.
    expect(shellSrc).toContain('providerHref(model.provider)');
    expect(shellSrc).toContain("import { providerHref } from '@/app/models/_components/modelsQuery';");
  });

  it('does not also list Models under Resources, which would be the same page twice', () => {
    // The Models column is the entry point. A second "Models" row in the neighbouring
    // Resources column pointed at the identical URL: two links, one destination, two
    // columns apart. Resources keeps the pages that have no column of their own.
    const resources = shellSrc.slice(shellSrc.indexOf('>{labels.resources}</p>'));
    expect(resources).not.toContain("<Link href={withBase(siteBaseUrl, '/models')}>{labels.models}</Link>");
  });
});

describe('landing header', () => {
  it('does not carry Integrations any more', () => {
    // Moved to the footer column, which reaches it from every public page and keeps the
    // connector tree crawlable. In the header it was the sixth entry and is what forced
    // the nav down to gap-5 to fit at 768px.
    expect(shellSrc).not.toContain("withBase(siteBaseUrl, '/integrations')");
    // And it must not come back as an in-page anchor: that would be a scroll on the
    // landing and a bounce back to the landing from everywhere else, linking to no
    // integration at all - the mistake the Marketplace entry beside it was fixed for.
    expect(shellSrc).not.toContain('targetId="integrations"');
  });
});
