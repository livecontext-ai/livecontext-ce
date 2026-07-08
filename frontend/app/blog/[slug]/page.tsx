import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from '@/app/docs/_components/docsStyles';
import { blogStyles } from '../_components/blogStyles';
import { BlogArticleView } from '../_components/BlogArticleView';
import { DocsThemeToggle } from '@/app/docs/_components/DocsThemeToggle';
import JsonLd from '@/components/seo/JsonLd';
import { getAllPosts, getPostBySlug } from '@/lib/blog/posts';
import { personaName } from '@/lib/blog/personas';
import { EN_BLOG_UI } from '@/lib/blog/i18n';
import { blogHreflang } from '@/lib/blog/localized';
import { IS_CE } from '@/lib/edition';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

interface BlogArticleParams {
  params: Promise<{ slug: string }>;
}

// Statically render one page per known slug; unknown slugs 404.
export const dynamicParams = false;

export function generateStaticParams() {
  return getAllPosts().map((post) => ({ slug: post.slug }));
}

export async function generateMetadata({ params }: BlogArticleParams): Promise<Metadata> {
  const { slug } = await params;
  const post = getPostBySlug(slug);
  if (!post) {
    return { title: { absolute: 'Post not found - LiveContext' } };
  }
  const url = `${SITE_URL}/blog/${post.slug}`;
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

export default async function BlogArticlePage({ params }: BlogArticleParams) {
  const { slug } = await params;
  const post = getPostBySlug(slug);
  if (!post) {
    notFound();
  }

  const url = `${SITE_URL}/blog/${post.slug}`;

  const articleJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BlogPosting',
    headline: post.title,
    description: post.excerpt,
    image: `${SITE_URL}${post.cover}`,
    datePublished: post.date,
    dateModified: post.date,
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
      { '@type': 'ListItem', position: 1, name: 'Home', item: SITE_URL },
      { '@type': 'ListItem', position: 2, name: 'Blog', item: `${SITE_URL}/blog` },
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
      <BlogArticleView post={post} ui={EN_BLOG_UI} locale="en" backHref="/blog" />
    </LandingShell>
  );
}
