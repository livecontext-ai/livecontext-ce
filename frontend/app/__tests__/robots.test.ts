import { describe, it, expect } from 'vitest';
import { buildRobotsTxt, DOCS_ORIGIN } from '@/lib/seo/robotsTxt';
import { sitemapUrls } from '@/lib/seo/sitemaps';
import { routing } from '@/i18n/routing';

const SITE = 'https://livecontext.ai';
const LOCALES = routing.locales;

function cloud(host = 'livecontext.ai'): string {
  return buildRobotsTxt({ isCe: false, host, siteUrl: SITE, locales: LOCALES });
}

/**
 * The file parsed the way a crawler reads it: consecutive `User-agent` lines
 * open one group, and the directives under them belong to all of those agents.
 */
function groupFor(text: string, userAgent: string): string[] {
  const lines = text.split('\n').map((line) => line.trim()).filter((line) => line !== '' && !line.startsWith('#'));

  let agents: string[] = [];
  let directives: string[] = [];
  let inDirectives = false;

  /** Close the group being read; return its rules when it is the one we want. */
  const closed = (): string[] | null => (agents.includes(userAgent) ? directives : null);

  for (const line of lines) {
    // `Host:` and `Sitemap:` are global directives, never part of a group.
    const isGlobal = /^(host|sitemap):/i.test(line);
    const isAgent = line.toLowerCase().startsWith('user-agent:');

    if ((isAgent && inDirectives) || isGlobal) {
      const found = closed();
      if (found) return found;
      agents = [];
      directives = [];
      inDirectives = false;
      if (isGlobal) continue;
    }

    if (isAgent) {
      agents.push(line.slice('user-agent:'.length).trim());
    } else {
      inDirectives = true;
      directives.push(line);
    }
  }

  return closed() ?? [];
}

describe('robots.txt - community edition', () => {
  it('disallows everything on a self-hosted edition, with no sitemap or host hint', () => {
    const text = buildRobotsTxt({ isCe: true, host: 'example.internal', siteUrl: SITE, locales: LOCALES });

    expect(text).toBe('User-agent: *\nDisallow: /\n');
    // The build cannot know the deployer's domain: advertising the cloud
    // sitemap/host from a self-hosted install would be wrong.
    expect(text).not.toContain('Sitemap:');
    expect(text).not.toContain('Host:');
    expect(text).not.toContain('Content-Signal');
  });
});

describe('robots.txt - the private surface', () => {
  it('disallows every private surface under EVERY locale prefix, not just en/fr', () => {
    const directives = groupFor(cloud(), '*');

    for (const path of ['/app/', '/onboarding', '/ce-setup', '/login', '/register', '/auth/']) {
      expect(directives).toContain(`Disallow: ${path}`);
      for (const locale of LOCALES) {
        expect(directives).toContain(`Disallow: /${locale}${path}`);
      }
    }
  });

  it('keeps the public marketing surface crawlable and advertises every sitemap', () => {
    const text = cloud();
    const directives = groupFor(text, '*');
    const disallowed = directives
      .filter((d) => d.startsWith('Disallow: '))
      .map((d) => d.slice('Disallow: '.length));

    expect(directives).toContain('Allow: /');
    for (const url of sitemapUrls(SITE)) {
      expect(text).toContain(`Sitemap: ${url}`);
    }
    // Nothing may accidentally shadow the SEO pages.
    for (const publicPath of ['/compare', '/about', '/changelog', '/llms.txt', '/videos', '/marketplace', '/integrations']) {
      expect(disallowed.some((d) => publicPath.startsWith(d))).toBe(false);
    }
  });
});

describe('robots.txt - crawl policy', () => {
  it('opens the public site to every crawler, AI agents included, through one wildcard group', () => {
    const text = cloud();

    // One group only: a crawler named in a group of its own would be freed from
    // every private-path rule under `*`, because groups do not inherit.
    expect(text.match(/^User-agent:/gm)).toEqual(['User-agent:']);
    expect(text).toContain('User-agent: *');
    // The training crawlers that used to be refused now fall under `*` like
    // every other agent, so none of them is named anywhere.
    for (const agent of ['GPTBot', 'ClaudeBot', 'CCBot', 'Google-Extended', 'Bytespider']) {
      expect(text).not.toContain(agent);
    }
    expect(text).not.toMatch(/^Disallow: \/$/m);
  });

  it('emits no Content-Signal line, which validators report as an unknown directive', () => {
    expect(cloud()).not.toContain('Content-Signal');
    expect(cloud('docs.livecontext.ai')).not.toContain('Content-Signal');
  });

  it('uses only standard directives, so a validator finds nothing to flag', () => {
    const directives = cloud()
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line !== '' && !line.startsWith('#'))
      .map((line) => line.split(':')[0].toLowerCase());

    for (const directive of new Set(directives)) {
      expect(['user-agent', 'allow', 'disallow', 'host', 'sitemap']).toContain(directive);
    }
  });
});

describe('robots.txt - the shape of the file itself', () => {
  // `groupFor` above is a model of a parser written alongside this file, so it
  // can agree with a file a real crawler would read differently. These assert
  // the literal text.
  it('puts the wildcard group first and the global records after it', () => {
    const text = cloud();
    const star = text.indexOf('User-agent: *');

    expect(star).toBeGreaterThan(-1);
    expect(text.indexOf('Host: ')).toBeGreaterThan(star);
    expect(text.indexOf('Sitemap: ')).toBeGreaterThan(star);
  });

  it('separates the group from the global records with a blank line and ends with exactly one newline', () => {
    const text = cloud();
    const before = text.slice(0, text.indexOf('Host: '));

    expect(before.endsWith('\n\n')).toBe(true);
    expect(text.endsWith('\n')).toBe(true);
    expect(text.endsWith('\n\n')).toBe(false);
  });

  it('puts nothing but Host and the sitemaps after the group', () => {
    const tail = cloud().slice(cloud().indexOf('Host: '));
    const kinds = [...new Set(tail.split('\n').filter((line) => line.trim() !== '').map((line) => line.split(':')[0]))];
    expect(kinds).toEqual(['Host', 'Sitemap']);
  });
});

describe('robots.txt - host hint', () => {
  it('names the apex as its own host', () => {
    expect(cloud('livecontext.ai')).toContain(`Host: ${SITE}`);
  });

  it('names the docs subdomain as ITS host, not the apex', () => {
    // The metadata route this replaced could not see the request, so it told
    // docs.livecontext.ai that its canonical host was livecontext.ai.
    expect(cloud('docs.livecontext.ai')).toContain(`Host: ${DOCS_ORIGIN}`);
    expect(cloud('DOCS.livecontext.ai:443')).toContain(`Host: ${DOCS_ORIGIN}`);
  });

  it('still declares the main sitemaps from the docs host, which is what lets them carry docs URLs', () => {
    // A sitemap may list another host's URLs only when that host's own
    // robots.txt declares the sitemap. The docs pages live in the main sitemap.
    const text = cloud('docs.livecontext.ai');
    for (const url of sitemapUrls(SITE)) {
      expect(text).toContain(`Sitemap: ${url}`);
    }
  });

  it('falls back to the apex when there is no host header at all', () => {
    expect(buildRobotsTxt({ isCe: false, host: null, siteUrl: SITE, locales: LOCALES }))
      .toContain(`Host: ${SITE}`);
  });
});
