import { describe, expect, it } from 'vitest';

import { getAllPosts, getPostBySlug } from '../posts';
import { BLOG_LOCALES } from '../i18n';
import nextConfig from '../../../next.config.mjs';

/**
 * Retiring a blog slug is a three-part change: drop the post, delete its content
 * modules in every locale, and 301 the old URL. Forgetting the redirect breaks
 * every existing inbound link silently, which is exactly the kind of thing that
 * is never noticed until traffic is already lost.
 *
 * Add a slug here when you retire it, and this suite enforces the other two
 * parts: the post is really gone, and the redirect really exists, for the
 * English canonical URL and for all five translated locales.
 */
const RETIRED = [
  { slug: 'small-data-sharp-decisions', absorbedBy: 'the-niche-data-advantage' },
] as const;

type Redirect = { source: string; destination: string; permanent?: boolean };

async function redirects(): Promise<Redirect[]> {
  // next.config.mjs is wrapped by the next-intl plugin, which keeps redirects()
  // on the returned object.
  const fn = (nextConfig as { redirects?: () => Promise<Redirect[]> }).redirects;
  expect(typeof fn).toBe('function');
  return fn!();
}

describe('retired blog slugs', () => {
  it.each(RETIRED)('$slug is no longer a published post', ({ slug }) => {
    expect(getPostBySlug(slug)).toBeUndefined();
    expect(getAllPosts().map((p) => p.slug)).not.toContain(slug);
  });

  it.each(RETIRED)('$slug redirects to a post that still exists', async ({ slug, absorbedBy }) => {
    // The destination must be live, otherwise the redirect chains into a 404.
    expect(getPostBySlug(absorbedBy)).toBeDefined();

    const all = await redirects();
    const en = all.find((r) => r.source === `/blog/${slug}`);
    expect(en, `missing English redirect for /blog/${slug}`).toBeDefined();
    expect(en!.destination).toBe(`/blog/${absorbedBy}`);
    expect(en!.permanent, 'a retired slug must be a 301, not a 302').toBe(true);
  });

  it.each(RETIRED)('$slug redirects for every translated locale', async ({ slug, absorbedBy }) => {
    const all = await redirects();
    // Matched either by an explicit per-locale rule or by one parameterised rule
    // whose pattern covers all five locales.
    const localeRule = all.find(
      (r) => r.source.includes(`/blog/${slug}`) && r.source.startsWith('/:locale'),
    );

    if (localeRule) {
      expect(localeRule.destination).toBe(`/:locale/blog/${absorbedBy}`);
      expect(localeRule.permanent).toBe(true);
      for (const locale of BLOG_LOCALES) {
        expect(
          localeRule.source,
          `parameterised redirect does not cover locale "${locale}"`,
        ).toContain(locale);
      }
      return;
    }

    for (const locale of BLOG_LOCALES) {
      const rule = all.find((r) => r.source === `/${locale}/blog/${slug}`);
      expect(rule, `missing redirect for /${locale}/blog/${slug}`).toBeDefined();
      expect(rule!.destination).toBe(`/${locale}/blog/${absorbedBy}`);
      expect(rule!.permanent).toBe(true);
    }
  });
});
