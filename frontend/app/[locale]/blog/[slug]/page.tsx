import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from '@/app/docs/_components/docsStyles';
import { blogStyles } from '@/app/blog/_components/blogStyles';
import { BlogArticleView } from '@/app/blog/_components/BlogArticleView';
import { DocsThemeToggle } from '@/app/docs/_components/DocsThemeToggle';
import JsonLd from '@/components/seo/JsonLd';
import { getAllPosts } from '@/lib/blog/posts';
import { personaName } from '@/lib/blog/personas';
import { BLOG_LOCALES } from '@/lib/blog/i18n';
import { getLocalizedPost, getLocalizedUi, isBlogLocale, blogHreflang } from '@/lib/blog/localized';
import { IS_CE } from '@/lib/edition';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

interface LocalizedArticleParams {
  params: Promise<{ locale: string; slug: string }>;
}

export const dynamicParams = false;

// One page per (translated locale, slug).
export function generateStaticParams() {
  const slugs = getAllPosts().map((post) => post.slug);
  return BLOG_LOCALES.flatMap((locale) => slugs.map((slug) => ({ locale, slug })));
}

export async function generateMetadata({ params }: LocalizedArticleParams): Promise<Metadata> {
  const { locale, slug } = await params;
  if (!isBlogLocale(locale)) return {};
  const post = getLocalizedPost(locale, slug);
  if (!post) return { title: { absolute: 'Post not found - LiveContext' } };

  const url = `${SITE_URL}/${locale}/blog/${post.slug}`;
  return {
    title: { absolute: `${post.title} - LiveContext` },
    description: post.excerpt,
    alternates: { canonical: url, languages: blogHreflang(SITE_URL, `/${post.slug}`) },
    openGraph: {
      siteName: 'LiveContext',
      title: `${post.title} - LiveContext`,
      description: post.excerpt,
      url,
      type: 'article',
      publishedTime: post.date,
      authors: post.authors.map(personaName),
      tags: post.tags,
      images: [{ url: `${SITE_URL}${post.cover}`, width: 2000, height: 1125, alt: post.coverAlt }],
    },
    robots: IS_CE ? { index: false, follow: false } : undefined,
  };
}

export default async function LocalizedBlogArticlePage({ params }: LocalizedArticleParams) {
  const { locale, slug } = await params;
  if (!isBlogLocale(locale)) notFound();
  const post = getLocalizedPost(locale, slug);
  if (!post) notFound();

  const ui = getLocalizedUi(locale);
  const url = `${SITE_URL}/${locale}/blog/${post.slug}`;

  const articleJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BlogPosting',
    headline: post.title,
    description: post.excerpt,
    image: `${SITE_URL}${post.cover}`,
    datePublished: post.date,
    dateModified: post.date,
    inLanguage: locale,
    author: post.authors.map((name) => ({ '@type': 'Person', name: personaName(name) })),
    publisher: {
      '@type': 'Organization',
      name: 'LiveContext',
      logo: { '@type': 'ImageObject', url: `${SITE_URL}/og-image.jpg` },
    },
    mainEntityOfPage: { '@type': 'WebPage', '@id': url },
    keywords: post.tags.join(', '),
  };

  const breadcrumbJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: `${SITE_URL}/${locale}` },
      { '@type': 'ListItem', position: 2, name: ui.blogTitle, item: `${SITE_URL}/${locale}/blog` },
      { '@type': 'ListItem', position: 3, name: post.title, item: url },
    ],
  };

  return (
    <LandingShell
      extraStyles={docsStyles + blogStyles}
      headerExtra={<DocsThemeToggle />}
      themeStorageKey="blog-theme"
      themeRespectStored
    >
      {!IS_CE && <JsonLd data={articleJsonLd} />}
      {!IS_CE && <JsonLd data={breadcrumbJsonLd} />}
      <BlogArticleView post={post} ui={ui} locale={locale} backHref={`/${locale}/blog`} />
    </LandingShell>
  );
}
