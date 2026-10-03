import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The header's home link (the logo).
 *
 * Source-level, like its sibling chrome tests: the header renders on public
 * pages that have no intl context, so it is not mounted here.
 *
 * It sits in the first viewport of every public page, so Next prefetched `/` on
 * each of them: 185 KB of landing payload per page view, measured by Lighthouse
 * on /about, /compare, /integrations and the docs, for a link almost nobody
 * clicks. The locale detection even turned it into `/fr` for a French browser.
 */
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

const headerSrc = (() => {
  const start = shellSrc.indexOf('export function LandingHeader');
  expect(start).toBeGreaterThan(-1);
  const end = shellSrc.indexOf('export function LandingFooter', start);
  expect(end).toBeGreaterThan(start);
  return shellSrc.slice(start, end);
})();

describe('landing header home link', () => {
  it('does not prefetch the landing', () => {
    const homeLink = headerSrc.match(/<Link href=\{withBase\(siteBaseUrl, '\/'\)\}[^>]*>/);
    expect(homeLink).not.toBeNull();
    expect(homeLink![0]).toContain('prefetch={false}');
  });
});

describe('landing header account links', () => {
  // The same reasoning for a signed-in reader: "Open the app" and the avatar sit in the bar of
  // every public page, and a prefetch would fetch the app's own pages on each view.
  const accountSrc = readFileSync(path.resolve(__dirname, '../LandingAccount.tsx'), 'utf8');

  for (const href of ['/app/chat', '/app/settings']) {
    it(`does not prefetch ${href}`, () => {
      // `appHref` prefixes the main site's origin off the main host (the docs subdomain).
      const link = accountSrc.match(new RegExp(`<Link\\s+href=\\{appHref\\('${href.replace(/\//g, '\\/')}'\\)\\}[^>]*>`));
      expect(link, `no <Link href="${href}"> in LandingAccount`).not.toBeNull();
      expect(link![0]).toContain('prefetch={false}');
    });
  }
});
