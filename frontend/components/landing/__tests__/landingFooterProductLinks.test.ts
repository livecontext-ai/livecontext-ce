import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { DOCS_PAGES } from '@/app/docs/_nav';

// The footer's Product column lists what the product does and sends each entry to
// the docs page that explains it. Two things can silently break that: a docs page
// renamed or removed under `app/docs/`, and a stray hard-coded `/docs/...` href
// that would 404 when the chrome renders ON the docs subdomain (where the clean
// path is the one that resolves). Both are checked here.
//
// Source-level, like landingFooterSocialLinks.test.ts: the footer renders on public
// pages that have no intl context, so it is not mounted in a test.
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

/** Just the Product column, so a match cannot come from Resources or Compare. */
const productColumn = (() => {
  const start = shellSrc.indexOf('>{labels.product}</p>');
  expect(start).toBeGreaterThan(-1);
  const end = shellSrc.indexOf('</ul>', start);
  expect(end).toBeGreaterThan(start);
  return shellSrc.slice(start, end);
})();

describe('landing footer Product column', () => {
  // The anchor text is a label prop now, not a literal: the chrome renders on the localised
  // pages too, so its copy is handed in (see shellLabels.ts). What this file guards is the
  // link and its destination, and both still live in the source.
  const docsEntries = [
    { page: 'workflows', label: '{labels.workflows}' },
    { page: 'agents', label: '{labels.agents}' },
    { page: 'interfaces', label: '{labels.interfaces}' },
    { page: 'tables', label: '{labels.tables}' },
    { page: 'integrations', label: '{labels.integrations}' },
  ];

  for (const { page, label } of docsEntries) {
    it(`links "${label}" to the ${page} docs page`, () => {
      expect(productColumn).toContain(`docsHref(siteBaseUrl, '${page}')`);
      expect(productColumn).toContain(`>${label}</Link>`);
    });

    it(`the ${page} docs page it links to exists in the docs nav`, () => {
      expect(DOCS_PAGES.map((p) => p.href)).toContain(`/${page}`);
    });
  }

  it('sends Marketplace to the PUBLIC listing index, not behind a sign-in', () => {
    // There is a crawlable page behind this word (`/marketplace`, every
    // publication). Sending it to a sign-in prompt instead cost the site a
    // footer link into the whole listing tree from every public page, and gave
    // a signed-out visitor a login wall where a browsable catalogue exists.
    expect(productColumn).toContain("withBase(siteBaseUrl, '/marketplace')");
    expect(productColumn).not.toContain('returnTo="/app/marketplace"');
  });

  it('keeps Pricing behind sign-in, which has no public page', () => {
    expect(productColumn).toContain('returnTo="/app/settings/pricing"');
  });

  it('never hard-codes a docs path, so the links work on the docs host too', () => {
    // A literal href="/docs/..." or href="/workflows" would break on one of the
    // two hosts; every docs link has to go through the helper.
    expect(productColumn).not.toMatch(/href="\/docs\//);
    expect(productColumn).not.toMatch(/href="\/(workflows|agents|interfaces|tables|integrations)"/);
  });

  it('routes the header and Resources docs links through the same helper', () => {
    // The old inline `siteBaseUrl ? '/' : '/docs'` ternaries are gone, so there is
    // one definition of where the docs live.
    expect(shellSrc).not.toContain("siteBaseUrl ? '/' : '/docs'");
    expect(shellSrc.match(/docsHref\(siteBaseUrl\)/g) ?? []).toHaveLength(2);
  });
});
