import { describe, it, expect, vi, beforeEach } from 'vitest';

describe('robots - cloud edition', () => {
  beforeEach(() => vi.resetModules());

  it('disallows every private surface under EVERY locale prefix, not just en/fr', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    const { default: robots } = await import('../robots');
    const { routing } = await import('@/i18n/routing');

    const rules = robots().rules;
    const rule = Array.isArray(rules) ? rules[0] : rules;
    const disallow = rule?.disallow as string[];

    // Bare (default-locale) form and one form per locale, for each private path.
    for (const path of ['/app/', '/onboarding', '/ce-setup', '/login', '/register', '/auth/']) {
      expect(disallow).toContain(path);
      for (const locale of routing.locales) {
        expect(disallow).toContain(`/${locale}${path}`);
      }
    }
  });

  it('keeps the public marketing surface crawlable and advertises the sitemap', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    const { default: robots } = await import('../robots');

    const result = robots();
    const rules = result.rules;
    const rule = Array.isArray(rules) ? rules[0] : rules;
    const disallow = rule?.disallow as string[];

    expect(rule?.allow).toBe('/');
    expect(result.sitemap).toBe('https://livecontext.ai/sitemap.xml');
    // Nothing may accidentally shadow the SEO pages.
    for (const publicPath of ['/compare', '/about', '/changelog', '/llms.txt']) {
      expect(disallow.some((d) => publicPath.startsWith(d))).toBe(false);
    }
  });
});

describe('robots - community edition', () => {
  beforeEach(() => vi.resetModules());

  it('disallows everything on a self-hosted edition, with no sitemap or host hint', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: true }));
    const { default: robots } = await import('../robots');
    const result = robots();
    const rules = result.rules;
    const rule = Array.isArray(rules) ? rules[0] : rules;
    expect(rule?.disallow).toBe('/');
    // The build cannot know the deployer's domain: advertising the cloud
    // sitemap/host from a self-hosted install would be wrong.
    expect(result.sitemap).toBeUndefined();
    expect(result.host).toBeUndefined();
  });
});
