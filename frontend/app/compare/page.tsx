import type { Metadata } from 'next';
import Link from 'next/link';
import { ArrowRight } from 'lucide-react';
import { LandingShell } from '@/components/landing/LandingShell';
import { IS_CE } from '@/lib/edition';
import { COMPARISONS } from './_lib/comparisons';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

export const metadata: Metadata = {
  title: 'Compare LiveContext vs Zapier, n8n and Make',
  description:
    'How LiveContext compares to Zapier, n8n and Make (Integromat): honest feature-by-feature tables, pricing models, self-hosting, AI agents and migration guides.',
  alternates: { canonical: `${SITE_URL}/compare` },
  openGraph: {
    siteName: 'LiveContext',
    title: 'Compare LiveContext vs Zapier, n8n and Make',
    description:
      'How LiveContext compares to Zapier, n8n and Make (Integromat): honest feature-by-feature tables, pricing models, self-hosting, AI agents and migration guides.',
    url: `${SITE_URL}/compare`,
    type: 'website',
    images: [
      {
        url: '/og-image.jpg',
        width: 1200,
        height: 630,
        alt: 'LiveContext: one message in, a working automation out.',
      },
    ],
  },
  robots: IS_CE ? { index: false, follow: false } : undefined,
};

export default function CompareIndexPage() {
  return (
    <LandingShell>
      <div className="mx-auto w-full max-w-4xl px-6 py-16 md:py-24">
        <h1
          className="text-4xl md:text-5xl font-bold tracking-tight"
          style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em', lineHeight: 1.1 }}
        >
          How LiveContext compares
        </h1>
        <p className="mt-6 text-lg leading-relaxed max-w-2xl" style={{ color: 'var(--text-secondary)' }}>
          Feature-by-feature comparisons with the automation tools teams evaluate most,
          including where each of them is the better fit. Every page is kept honest and dated.
        </p>
        <div className="mt-12 grid gap-4">
          {COMPARISONS.map((comparison) => (
            <Link
              key={comparison.slug}
              href={`/compare/${comparison.slug}`}
              className="group rounded-2xl p-6 transition-all hover:-translate-y-0.5 flex items-center justify-between gap-4"
              style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}
            >
              <div>
                <h2 className="text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>
                  LiveContext vs {comparison.competitor}
                </h2>
                <p className="mt-1 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                  {comparison.metaDescription}
                </p>
              </div>
              <ArrowRight className="w-5 h-5 shrink-0 transition-transform group-hover:translate-x-0.5" style={{ color: 'var(--text-muted)' }} />
            </Link>
          ))}
        </div>
      </div>
    </LandingShell>
  );
}
