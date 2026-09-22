import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'fs';
import path from 'path';
import { isPublicMarketingPath } from '../publicMarketingPath';

// Regression pin for the landing-SEO fix: paths returning true here are NEVER
// replaced by the blocking auth spinner / SessionGate in smart-providers, so
// their real content reaches the server-rendered HTML (crawlers that do not
// execute JavaScript see the page). Paths returning false keep the blocking
// auth UI exactly as before.
describe('isPublicMarketingPath', () => {
  it('server-renders all persona landings without waiting for authentication', () => {
    for (const locale of ['', '/en', '/fr', '/de', '/es', '/pt', '/zh']) {
      for (const persona of ['support', 'creator', 'sales', 'marketing', 'recruiting']) {
        expect(isPublicMarketingPath(`${locale}/for/${persona}`)).toBe(true);
      }
    }
    expect(isPublicMarketingPath('/foreign/creator')).toBe(false);
  });
  it('covers the landing page, bare and under every locale prefix', () => {
    expect(isPublicMarketingPath('/')).toBe(true);
    for (const locale of ['en', 'fr', 'es', 'de', 'pt', 'zh']) {
      expect(isPublicMarketingPath(`/${locale}`)).toBe(true);
    }
  });

  it('covers the public marketing and docs surfaces', () => {
    for (const path of [
      '/compare',
      '/compare/n8n-alternative',
      '/about',
      '/contact',
      '/legal/privacy',
      '/changelog',
      '/docs',
      '/docs/agents',
    ]) {
      expect(isPublicMarketingPath(path), path).toBe(true);
    }
  });

  it('never gates /status behind the auth spinner', () => {
    // The page exists for the case where signing in is what is broken: behind
    // the blocking auth UI it would answer an outage with a spinner, and it
    // would server-render as spinner-only HTML.
    for (const path of ['/status', '/en/status', '/fr/status']) {
      expect(isPublicMarketingPath(path), path).toBe(true);
    }
  });

  it('keeps the blocking auth UI on app, auth and share surfaces', () => {
    for (const path of [
      '/app/chat',
      '/en/app/chat',
      '/fr/app/settings/pricing',
      '/en/onboarding',
      '/en/ce-setup',
      '/en/login',
      '/workflows/builder',
      '/billing/success',
      '/f/token123',
      '/s/token123',
      '/w/embed/token123',
      null,
    ]) {
      expect(isPublicMarketingPath(path), String(path)).toBe(false);
    }
  });

  it('no longer covers the deleted blog', () => {
    // The blog routes, their content and their assets were removed, so /blog
    // 404s. Leaving it listed here would only skip the auth UI on a 404.
    for (const path of ['/blog', '/blog/the-niche-data-advantage', '/fr/blog']) {
      expect(isPublicMarketingPath(path), path).toBe(false);
    }
  });

  it('does not treat lookalike prefixes as public', () => {
    expect(isPublicMarketingPath('/aboutus')).toBe(false);
    expect(isPublicMarketingPath('/comparetool')).toBe(false);
    expect(isPublicMarketingPath('/modelsomething')).toBe(false);
    expect(isPublicMarketingPath('/integrationshub')).toBe(false);
  });

  it('covers the two public catalogues, which shipped uncrawlable without it', () => {
    // Both /integrations and /models served spinner-only HTML on a production
    // build, 49 KB and 67 KB with not one integration or model in the markup,
    // because neither was listed. They render fine as soon as JavaScript runs,
    // so nothing reported it.
    expect(isPublicMarketingPath('/integrations')).toBe(true);
    expect(isPublicMarketingPath('/integrations/stripe')).toBe(true);
    expect(isPublicMarketingPath('/models')).toBe(true);
    expect(isPublicMarketingPath('/fr/models')).toBe(true);
  });
});

/**
 * The structural guard, and the reason this bug can stop recurring.
 *
 * A page that renders LandingShell is by definition a public page: that is the
 * public chrome. Every one of them must be a public marketing path, or the auth
 * gate replaces its whole body with a spinner during SSR and only a crawler ever
 * notices. Listing them by hand is what failed twice, so this reads the routes off
 * the filesystem instead.
 */
describe('publicMarketingPathCoverage', () => {
  const APP_DIR = path.resolve(__dirname, '../../../app');

  /** Every app-router page whose source mounts the public chrome. */
  function publicChromePages(dir: string, segments: string[] = []): string[] {
    const found: string[] = [];
    for (const entry of readdirSync(dir)) {
      const full = path.join(dir, entry);
      if (statSync(full).isDirectory()) {
        found.push(...publicChromePages(full, [...segments, entry]));
      } else if (entry === 'page.tsx' && readFileSync(full, 'utf8').includes('LandingShell')) {
        // `[locale]` is stripped by isPublicMarketingPath itself; any other dynamic
        // segment stands in for a real value, which is what a visitor requests.
        const route = segments
          .filter((s) => s !== '[locale]')
          .map((s) => (s.startsWith('[') ? 'sample' : s))
          .join('/');
        found.push(`/${route}`);
      }
    }
    return found;
  }

  it('finds the public pages, so an empty sweep cannot pass as success', () => {
    // Without this, a broken scan reports "0 pages, all fine".
    expect(publicChromePages(APP_DIR).length).toBeGreaterThan(5);
  });

  it('every page using the public chrome is a public marketing path', () => {
    for (const route of publicChromePages(APP_DIR)) {
      expect(
        isPublicMarketingPath(route),
        `${route} renders the public chrome but is not a public marketing path: `
          + 'its body will be replaced by the auth spinner in the server-rendered HTML. '
          + 'Add its prefix to PUBLIC_MARKETING_PREFIXES.',
      ).toBe(true);
    }
  });
});
