import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from '@/app/docs/_components/docsStyles';
import { blogStyles } from '@/app/blog/_components/blogStyles';
import { BlogIndexView } from '@/app/blog/_components/BlogIndexView';
import { DocsThemeToggle } from '@/app/docs/_components/DocsThemeToggle';
import JsonLd from '@/components/seo/JsonLd';
import { personaName } from '@/lib/blog/personas';
import { BLOG_LOCALES } from '@/lib/blog/i18n';
import { getLocalizedPosts, getLocalizedUi, isBlogLocale, blogHreflang } from '@/lib/blog/localized';
import { IS_CE } from '@/lib/edition';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

interface LocaleParams {
  params: Promise<{ locale: string }>;
}

// Statically render the blog index for every translated locale. English is
// served from `/blog` (localePrefix 'as-needed' redirects /en/blog there).
export const dynamicParams = false;

export function generateStaticParams() {
  return BLOG_LOCALES.map((locale) => ({ locale }));
}

export async function generateMetadata({ params }: LocaleParams): Promise<Metadata> {
  const { locale } = await params;
  if (!isBlogLocale(locale)) return {};
  const ui = getLocalizedUi(locale);
  const url = `${SITE_URL}/${locale}/blog`;
  return {
    title: { absolute: ui.metaTitle },
    description: ui.metaDescription,
    alternates: { canonical: url, languages: blogHreflang(SITE_URL, '') },
    openGraph: {
      siteName: 'LiveContext',
      title: ui.metaTitle,
      description: ui.metaDescription,
      url,
      type: 'website',
      images: [
        { url: '/og-image.jpg', width: 1200, height: 630, alt: 'LiveContext: one message in, a working automation out.' },
      ],
    },
    robots: IS_CE ? { index: false, follow: false } : undefined,
  };
}

export default async function LocalizedBlogIndexPage({ params }: LocaleParams) {
  const { locale } = await params;
  if (!isBlogLocale(locale)) notFound();

  const ui = getLocalizedUi(locale);
  const posts = getLocalizedPosts(locale);

  const blogJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'Blog',
    name: 'LiveContext Blog',
    description: ui.metaDescription,
    url: `${SITE_URL}/${locale}/blog`,
    inLanguage: locale,
    blogPost: posts.map((post) => ({
      '@type': 'BlogPosting',
      headline: post.title,
      description: post.excerpt,
      datePublished: post.date,
      image: `${SITE_URL}${post.cover}`,
      author: post.authors.map((name) => ({ '@type': 'Person', name: personaName(name) })),
      url: `${SITE_URL}/${locale}/blog/${post.slug}`,
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
      <BlogIndexView posts={posts} ui={ui} locale={locale} hrefFor={(slug) => `/${locale}/blog/${slug}`} />
    </LandingShell>
  );
}
