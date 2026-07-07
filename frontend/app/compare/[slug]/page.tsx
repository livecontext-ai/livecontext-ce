import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import JsonLd from '@/components/seo/JsonLd';
import { IS_CE } from '@/lib/edition';
import ComparePageContent from '../_components/ComparePageContent';
import { COMPARISONS, getComparison } from '../_lib/comparisons';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

// Statically render one page per comparison; unknown slugs 404.
export const dynamicParams = false;

export function generateStaticParams() {
  return COMPARISONS.map((c) => ({ slug: c.slug }));
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const comparison = getComparison(slug);
  if (!comparison) return {};

  const url = `${SITE_URL}/compare/${comparison.slug}`;
  return {
    title: comparison.metaTitle,
    description: comparison.metaDescription,
    alternates: { canonical: url },
    // Full openGraph block: Next.js metadata merging is shallow per top-level
    // field, so overriding `openGraph` here would otherwise DROP the root
    // layout's og:image and siteName on these share-critical pages.
    openGraph: {
      siteName: 'LiveContext',
      title: `${comparison.metaTitle} - LiveContext`,
      description: comparison.metaDescription,
      url,
      type: 'article',
      images: [
        {
          url: '/og-image.jpg',
          width: 1200,
          height: 630,
          alt: 'LiveContext: one message in, a working automation out.',
        },
      ],
    },
    // Self-hosted deployments must never index marketing pages (same rule as
    // the landing page and /changelog).
    robots: IS_CE ? { index: false, follow: false } : undefined,
  };
}

export default async function ComparePage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const comparison = getComparison(slug);
  if (!comparison) notFound();

  const url = `${SITE_URL}/compare/${comparison.slug}`;

  const faqJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: comparison.faq.map((item) => ({
      '@type': 'Question',
      name: item.question,
      acceptedAnswer: { '@type': 'Answer', text: item.answer },
    })),
  };

  const breadcrumbJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: SITE_URL },
      { '@type': 'ListItem', position: 2, name: 'Compare', item: `${SITE_URL}/compare` },
      { '@type': 'ListItem', position: 3, name: `LiveContext vs ${comparison.competitor}`, item: url },
    ],
  };

  return (
    <>
      {!IS_CE && <JsonLd data={faqJsonLd} />}
      {!IS_CE && <JsonLd data={breadcrumbJsonLd} />}
      <ComparePageContent comparison={comparison} />
    </>
  );
}
