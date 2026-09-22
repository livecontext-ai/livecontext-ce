import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The footer's Resources column is the ONLY internal entry point to `/videos`.
 *
 * <p>The header nav does not carry it (five entries already overflow the bar at
 * the width where it first shows), so without this link the section is reachable
 * from nowhere on the site: a crawler would find it through the sitemap alone,
 * and a reader not at all. Deleting the line passed every other test.
 *
 * <p>Source-level, like `landingFooterProductLinks.test.ts` and
 * `landingFooterSocialLinks.test.ts`: the footer renders on public pages that
 * have no intl context, so it is not mounted in a test.
 */
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

/** Just the Resources column, so a match cannot come from another column. */
const resourcesColumn = (() => {
  const start = shellSrc.indexOf('>{labels.resources}</p>');
  expect(start).toBeGreaterThan(-1);
  const end = shellSrc.indexOf('</ul>', start);
  expect(end).toBeGreaterThan(start);
  return shellSrc.slice(start, end);
})();

describe('landing footer Resources column', () => {
  it('links to the video library', () => {
    expect(resourcesColumn).toContain("withBase(siteBaseUrl, '/videos')");
    expect(resourcesColumn).toContain('>{labels.videos}</Link>');
  });

  it('routes it through withBase, so it resolves from the docs host too', () => {
    // A literal href="/videos" would 404 when the chrome renders on
    // docs.livecontext.ai, where the clean path is the one that resolves.
    expect(resourcesColumn).not.toMatch(/href="\/videos"/);
  });

  it('keeps the neighbouring Resources entries', () => {
    // The column is the site map for anyone who scrolls: losing an entry here is
    // invisible on the page and costs an internal link from every public page.
    // Changelog stays a literal: it is a proper noun the chrome does not translate.
    for (const entry of ['{labels.videos}', 'Changelog', '{labels.status}', '{labels.docs}', '{labels.selfHosted}']) {
      expect(resourcesColumn).toContain(entry);
    }
  });
});
