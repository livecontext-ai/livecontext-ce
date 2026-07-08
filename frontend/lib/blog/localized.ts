// Resolves localized posts + UI for the `/<locale>/blog` routes by merging the
// shared, locale-independent post metadata (date, authors, tags, cover, from
// posts.ts) with the translated title/excerpt/coverAlt/body from the per-locale
// bundles. English is served separately from `posts.ts` at `/blog`.

import { getAllPosts, getPostBySlug } from './posts';
import type { BlogPost } from './postUtils';
import type { BlogLocale, BlogTranslation, BlogUi } from './i18n';
import { BLOG_LOCALES } from './i18n';
import { frBlog } from './translations/fr';
import { esBlog } from './translations/es';
import { deBlog } from './translations/de';
import { ptBlog } from './translations/pt';
import { zhBlog } from './translations/zh';

const BLOG_TRANSLATIONS: Record<BlogLocale, BlogTranslation> = {
  fr: frBlog,
  es: esBlog,
  de: deBlog,
  pt: ptBlog,
  zh: zhBlog,
};

/** True when `value` is one of the translated blog locales. */
export function isBlogLocale(value: string): value is BlogLocale {
  return (BLOG_LOCALES as string[]).includes(value);
}

export function getLocalizedUi(locale: BlogLocale): BlogUi {
  return BLOG_TRANSLATIONS[locale].ui;
}

// Overlay the translated fields onto a shared post; fall back to the English
// fields if a translation is somehow missing (keeps the page rendering).
function localize(post: BlogPost, locale: BlogLocale): BlogPost {
  const t = BLOG_TRANSLATIONS[locale].posts[post.slug];
  if (!t) return post;
  return { ...post, title: t.title, excerpt: t.excerpt, coverAlt: t.coverAlt, content: t.content };
}

export function getLocalizedPosts(locale: BlogLocale): BlogPost[] {
  return getAllPosts().map((p) => localize(p, locale));
}

export function getLocalizedPost(locale: BlogLocale, slug: string): BlogPost | undefined {
  const post = getPostBySlug(slug);
  return post ? localize(post, locale) : undefined;
}

/**
 * hreflang alternates for a blog URL: English (canonical, and x-default) at
 * `/blog{slugPath}` plus every translated locale at `/<locale>/blog{slugPath}`.
 * `slugPath` is '' for the index or '/<slug>' for an article.
 */
export function blogHreflang(siteUrl: string, slugPath: string): Record<string, string> {
  const languages: Record<string, string> = {
    'x-default': `${siteUrl}/blog${slugPath}`,
    en: `${siteUrl}/blog${slugPath}`,
  };
  for (const locale of BLOG_LOCALES) {
    languages[locale] = `${siteUrl}/${locale}/blog${slugPath}`;
  }
  return languages;
}
