import { describe, it, expect } from 'vitest';
import { getAllPosts } from '../posts';
import { isBlogLocale, blogHreflang, getLocalizedPost, getLocalizedPosts } from '../localized';

const SITE = 'https://example.test';

describe('isBlogLocale', () => {
  it('accepts the five translated locales', () => {
    for (const locale of ['fr', 'es', 'de', 'pt', 'zh']) {
      expect(isBlogLocale(locale)).toBe(true);
    }
  });

  it('rejects English (canonical, served separately) and unknown codes', () => {
    expect(isBlogLocale('en')).toBe(false);
    expect(isBlogLocale('xx')).toBe(false);
    expect(isBlogLocale('')).toBe(false);
  });
});

describe('blogHreflang', () => {
  it('builds a reciprocal cluster for the index (x-default + en + 5 locales)', () => {
    const langs = blogHreflang(SITE, '');
    expect(Object.keys(langs).sort()).toEqual(['de', 'en', 'es', 'fr', 'pt', 'x-default', 'zh']);
    expect(langs.en).toBe(`${SITE}/blog`);
    expect(langs['x-default']).toBe(`${SITE}/blog`);
    expect(langs.fr).toBe(`${SITE}/fr/blog`);
    expect(langs.zh).toBe(`${SITE}/zh/blog`);
  });

  it('appends the slug path for an article', () => {
    const langs = blogHreflang(SITE, '/my-post');
    expect(langs.en).toBe(`${SITE}/blog/my-post`);
    expect(langs.de).toBe(`${SITE}/de/blog/my-post`);
    expect(langs['x-default']).toBe(`${SITE}/blog/my-post`);
  });
});

describe('getLocalizedPosts / getLocalizedPost', () => {
  it('returns every post in the same order, with translated title + body but shared metadata', () => {
    const en = getAllPosts();
    const fr = getLocalizedPosts('fr');

    expect(fr.map((p) => p.slug)).toEqual(en.map((p) => p.slug));

    for (let i = 0; i < en.length; i++) {
      // Translated fields actually differ from English.
      expect(fr[i].title).not.toBe(en[i].title);
      expect(fr[i].content).not.toBe(en[i].content);
      expect(fr[i].title.length).toBeGreaterThan(0);
      // Locale-independent metadata is preserved from the shared registry.
      expect(fr[i].date).toBe(en[i].date);
      expect(fr[i].authors).toEqual(en[i].authors);
      expect(fr[i].cover).toBe(en[i].cover);
      expect(fr[i].tags).toEqual(en[i].tags);
    }
  });

  it('returns a single localized post by slug', () => {
    const slug = getAllPosts()[0].slug;
    const post = getLocalizedPost('de', slug);
    expect(post?.slug).toBe(slug);
    expect(post?.title).toBeTruthy();
    expect(post?.content).toBeTruthy();
  });

  it('returns undefined for an unknown slug', () => {
    expect(getLocalizedPost('fr', 'does-not-exist')).toBeUndefined();
  });
});
