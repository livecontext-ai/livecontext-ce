import { describe, it, expect } from 'vitest';
import { buildRobotsTxt, privatePaths, DOCS_ORIGIN } from '@/lib/seo/robotsTxt';
import { ANSWER_ENGINE_CRAWLERS, TRAINING_CRAWLERS } from '@/lib/seo/crawlers';
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
  it('lets the answer engines in and keeps the training crawlers out', () => {
    const text = cloud();

    // The decision, stated on the two crawlers that make it concrete: OpenAI
    // and Anthropic each run a search crawler and a training crawler.
    expect(groupFor(text, 'OAI-SearchBot')).toContain('Allow: /');
    expect(groupFor(text, 'Claude-SearchBot')).toContain('Allow: /');
    expect(groupFor(text, 'GPTBot')).toEqual(['Disallow: /']);
    expect(groupFor(text, 'ClaudeBot')).toEqual(['Disallow: /']);

    for (const agent of ANSWER_ENGINE_CRAWLERS) {
      expect(groupFor(text, agent), `${agent} should be allowed`).toContain('Allow: /');
    }
    for (const agent of TRAINING_CRAWLERS) {
      expect(groupFor(text, agent), `${agent} should be refused`).toEqual(['Disallow: /']);
    }
  });

  it('no crawler is in both lists, so no group can contradict the other', () => {
    const both = ANSWER_ENGINE_CRAWLERS.filter((a) => (TRAINING_CRAWLERS as readonly string[]).includes(a));
    expect(both).toEqual([]);
  });

  it('repeats the private paths inside the answer-engine group, because groups do NOT inherit', () => {
    // Naming a crawler in its own group frees it from every rule under `*`.
    // Without this repetition, opening the site to answer engines would also
    // have opened /app/, /billing/ and the token URLs to them.
    const answers = groupFor(cloud(), 'OAI-SearchBot');
    const star = groupFor(cloud(), '*');

    for (const path of privatePaths(LOCALES)) {
      expect(answers, `${path} must be disallowed for answer engines too`).toContain(`Disallow: ${path}`);
    }
    expect(answers.filter((d) => d.startsWith('Disallow: ')))
      .toEqual(star.filter((d) => d.startsWith('Disallow: ')));
  });

  it('states the content signals in BOTH groups, since a crawler obeys only one', () => {
    // The answer engines are the only crawlers allowed to fetch. Emitting the
    // signal for `*` alone meant they were the only ones never told ai-train=no,
    // which is what carries the reservation of rights.
    const signal = 'Content-Signal: search=yes,ai-input=yes,ai-train=no,use=reference';
    expect(groupFor(cloud(), '*')).toContain(signal);
    expect(groupFor(cloud(), 'OAI-SearchBot')).toContain(signal);
    expect(groupFor(cloud(), 'Applebot')).toContain(signal);
    // Not to the ones refused outright: they are told nothing but no.
    expect(groupFor(cloud(), 'GPTBot')).toEqual(['Disallow: /']);
  });
});

describe('robots.txt - the shape of the file itself', () => {
  // `groupFor` above is a model of a parser, written by this same change, so it
  // can agree with a file a real crawler would read differently. These assert
  // the literal text: the order of the groups, the blank line that separates
  // them, and the trailing newline. None of them is visible to a test that only
  // asks the model questions.
  it('orders the groups: the wildcard, then the answer engines, then the training crawlers', () => {
    const text = cloud();
    const star = text.indexOf('User-agent: *');
    const answers = text.indexOf('User-agent: OAI-SearchBot');
    const training = text.indexOf('User-agent: GPTBot');
    const global = text.indexOf('Host: ');

    expect(star).toBeGreaterThan(-1);
    expect(answers).toBeGreaterThan(star);
    expect(training).toBeGreaterThan(answers);
    // `Sitemap` is a non-group record valid anywhere, but putting it last is
    // what keeps it from reading as part of the training group.
    expect(global).toBeGreaterThan(training);
    expect(text.indexOf('Sitemap: ')).toBeGreaterThan(training);
  });

  it('separates every group with a blank line and ends with exactly one newline', () => {
    const text = cloud();
    // A `User-agent` line directly after a rule line opens a new group, but
    // reading the file is a human job too, and a missing separator is the first
    // sign that two groups were accidentally concatenated into one.
    for (const agent of ['User-agent: OAI-SearchBot', 'User-agent: GPTBot']) {
      const before = text.slice(0, text.indexOf(agent));
      expect(before.endsWith('\n\n') || /\n#[^\n]*\n$/.test(before), agent).toBe(true);
    }
    expect(text.endsWith('\n')).toBe(true);
    expect(text.endsWith('\n\n')).toBe(false);
  });

  it('puts no directive between the last group and the global records', () => {
    const tail = cloud().slice(cloud().indexOf('User-agent: GPTBot'));
    const lines = tail.split('\n').filter((line) => line.trim() !== '');
    // user-agents, one Disallow, then Host and the sitemaps. Nothing else.
    const kinds = [...new Set(lines.map((line) => line.split(':')[0]))];
    expect(kinds).toEqual(['User-agent', 'Disallow', 'Host', 'Sitemap']);
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
