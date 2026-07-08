import type { Metadata } from 'next';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from '@/app/docs/_components/docsStyles';
import { blogStyles } from './_components/blogStyles';
import { BlogIndexView } from './_components/BlogIndexView';
import { DocsThemeToggle } from '@/app/docs/_components/DocsThemeToggle';
import JsonLd from '@/components/seo/JsonLd';
import { getAllPosts } from '@/lib/blog/posts';
import { personaName } from '@/lib/blog/personas';
import { EN_BLOG_UI } from '@/lib/blog/i18n';
import { blogHreflang } from '@/lib/blog/localized';
import { IS_CE } from '@/lib/edition';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/**
 * Public blog index (English, canonical at `/blog`). Localized versions live at
 * `/<locale>/blog` (see `app/[locale]/blog`). Like every page outside the
 * `[locale]` tree this stays intl-context-free: hardcoded English strings come
 * from `EN_BLOG_UI`, the same shape the localized routes feed the shared view.
 */
export const metadata: Metadata = {
  title: { absolute: EN_BLOG_UI.metaTitle },
  description: EN_BLOG_UI.metaDescription,
  alternates: { canonical: `${SITE_URL}/blog`, languages: blogHreflang(SITE_URL, '') },
  openGraph: {
    siteName: 'LiveContext',
    title: 'LiveContext Blog: niche data and the automations built on it',
    description: EN_BLOG_UI.metaDescription,
    url: `${SITE_URL}/blog`,
    type: 'website',
    images: [
      { url: '/og-image.jpg', width: 1200, height: 630, alt: 'LiveContext: one message in, a working automation out.' },
    ],
  },
  robots: IS_CE ? { index: false, follow: false } : undefined,
};

export default function BlogIndexPage() {
  const posts = getAllPosts();

  const blogJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'Blog',
    name: 'LiveContext Blog',
    description: EN_BLOG_UI.metaDescription,
    url: `${SITE_URL}/blog`,
    blogPost: posts.map((post) => ({
      '@type': 'BlogPosting',
      headline: post.title,
      description: post.excerpt,
      datePublished: post.date,
      image: `${SITE_URL}${post.cover}`,
      author: post.authors.map((name) => ({ '@type': 'Person', name: personaName(name) })),
      url: `${SITE_URL}/blog/${post.slug}`,
    })),
  };

  return (
    <LandingShell
      extraStyles={docsStyles + blogStyles}
      headerExtra={<DocsThemeToggle />}
      themeStorageKey="blog-theme"
      themeRespectStored
    >
      {!IS_CE && <JsonLd data={blogJsonLd} />}
      <BlogIndexView posts={posts} ui={EN_BLOG_UI} locale="en" hrefFor={(slug) => `/blog/${slug}`} />
    </LandingShell>
  );
}
