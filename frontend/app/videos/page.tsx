import type { Metadata } from 'next';
import Link from 'next/link';
import { Play } from 'lucide-react';
import JsonLd from '@/components/seo/JsonLd';
import { LandingShell } from '@/components/landing/LandingShell';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import { IS_CE } from '@/lib/edition';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import {
  formatTimecode,
  isoDuration,
  videoPath,
  youtubeCanonicalEmbedUrl,
} from './_lib/videos';
import { fetchPublishedVideosOrEmpty } from './_lib/publicVideos';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

const TITLE = 'Videos: watch a real automation get built, end to end';
const DESCRIPTION =
  'Full-length films of the actual product: a job that eats your week, and the automation that '
  + 'takes it over. Every step on screen, the whole transcript on the page.';

/**
 * Rendered per request, for the same reason as the film page beside it: a
 * build-time prerender would bake an empty library into every replica.
 */
export const dynamic = 'force-dynamic';

export const metadata: Metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: '/videos' },
  // Both blocks spelled out in full: Next merges metadata shallowly per
  // top-level field, so a partial override would DROP the root layout's values.
  openGraph: {
    siteName: 'LiveContext',
    title: `${TITLE} - LiveContext`,
    description: DESCRIPTION,
    url: `${SITE_URL}/videos`,
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
  twitter: {
    card: 'summary_large_image',
    title: `${TITLE} - LiveContext`,
    description: DESCRIPTION,
    images: ['/og-image.jpg'],
  },
  // Self-hosted deployments never index marketing pages. `follow: false` like
  // every neighbouring one (/marketplace, /compare, /about, /changelog, /models).
  robots: IS_CE ? { index: false, follow: false } : undefined,
};

export default async function VideosPage() {
  // The index degrades to "no films yet" rather than to an error: a directory
  // that cannot be read must lose itself, not the page it sits on. The film
  // PAGE deliberately does the opposite.
  const videos = await fetchPublishedVideosOrEmpty();

  const itemListJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'ItemList',
    name: 'LiveContext product films',
    itemListElement: videos.map((video, index) => ({
      '@type': 'ListItem',
      position: index + 1,
      item: {
        '@type': 'VideoObject',
        name: video.title,
        description: video.tagline,
        // Already absolute, and host-checked by the library: prefixing it here
        // builds "https://livecontext.aihttps://...". JPEG because not every
        // scraper that reads this block reads WebP.
        thumbnailUrl: [video.shareImage],
        uploadDate: video.publishedAt,
        duration: isoDuration(video.durationSeconds),
        embedUrl: youtubeCanonicalEmbedUrl(video.youtubeId),
        url: `${SITE_URL}${videoPath(video.slug)}`,
      },
    })),
  };

  return (
    <LandingShell>
      {!IS_CE && videos.length > 0 && <JsonLd data={itemListJsonLd} />}

      <div className="mx-auto w-full max-w-5xl px-4 py-12 sm:px-6 md:py-16">
        <header className="max-w-3xl">
          <h1 className="text-3xl font-semibold leading-tight md:text-5xl" style={{ color: 'var(--text-primary)' }}>
            Watch it build a real automation, end to end
          </h1>
          <p className="mt-5 text-base leading-relaxed md:text-lg" style={{ color: 'var(--text-secondary)' }}>
            Not a montage and not a mockup. Each film takes one job that quietly eats a working
            day, and shows the whole thing being built and run: described in chat, built by hand,
            or installed in a click. Every film comes with its chapters and its full transcript.
          </p>
        </header>

        {videos.length === 0 ? (
          <p className="mt-12 text-sm" style={{ color: 'var(--text-muted)' }}>
            The first films are being published. Check back shortly.
          </p>
        ) : (
          // Two columns only once there are two films: a lone card in a
          // two-column grid reads as a page missing half its content.
          <div className={`mt-10 grid gap-8 ${videos.length > 1 ? 'sm:grid-cols-2' : 'max-w-2xl'}`}>
            {/* ONE link per card, on the heading, stretched over the whole article
                with `after:absolute inset-0`. The obvious shapes both fail: a link
                wrapping everything puts an <h2> (flow content) inside a <span>, and
                a second link on the poster gives every card two links with the same
                name to the same page. */}
            {videos.map((video, index) => (
              <article
                key={video.slug}
                className="relative overflow-hidden rounded-2xl border transition-colors hover:bg-[var(--bg-secondary)]"
                style={{ borderColor: 'var(--border-color)' }}
              >
                <div className="relative" style={{ aspectRatio: '16 / 9' }}>
                  {/* A plain <img>: fixed local posters at a known size, already
                      served by the CDN; next/image would only add a loader. */}
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={video.poster}
                    alt={video.posterAlt}
                    width={1280}
                    height={720}
                    loading={index === 0 ? 'eager' : 'lazy'}
                    className="h-full w-full object-cover"
                  />
                  <span
                    className="absolute left-1/2 top-1/2 flex h-12 w-12 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full shadow-lg"
                    style={{ background: 'rgba(9, 9, 11, 0.78)' }}
                    aria-hidden="true"
                  >
                    <Play className="h-4 w-4 translate-x-[1px] fill-white text-white" />
                  </span>
                  <span
                    className="absolute bottom-2 right-2 rounded-full px-2 py-0.5 text-xs font-medium text-white"
                    style={{ background: 'rgba(9, 9, 11, 0.72)' }}
                  >
                    {formatTimecode(video.durationSeconds)}
                  </span>
                </div>
                <div className="p-5">
                  <h2 className="text-lg font-semibold leading-snug" style={{ color: 'var(--text-primary)' }}>
                    <Link
                      href={videoPath(video.slug)}
                      className="underline-offset-4 after:absolute after:inset-0 hover:underline"
                    >
                      {video.title}
                    </Link>
                  </h2>
                  <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                    {video.tagline}
                  </p>
                  {/* `locale: 'en'` explicitly, like /marketplace: these pages
                      live OUTSIDE the [locale] tree and are English by contract,
                      so the date must not follow the reader's browser language. */}
                  <time
                    dateTime={video.publishedAt}
                    className="mt-3 block text-xs"
                    style={{ color: 'var(--text-muted)' }}
                  >
                    {formatUtcDate(video.publishedAt, { locale: 'en' })}
                  </time>
                </div>
              </article>
            ))}
          </div>
        )}

        <section
          className="mt-16 rounded-2xl border p-6 md:p-8"
          style={{ borderColor: 'var(--border-color)', background: 'var(--bg-secondary)' }}
        >
          <h2 className="text-xl font-semibold md:text-2xl" style={{ color: 'var(--text-primary)' }}>
            Every one of these is free to run
          </h2>
          <p className="mt-3 max-w-2xl leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            The workflows in these films are published on the marketplace. Install one in a click
            and it lands in your own workspace, with its own ids and none of the publisher&apos;s keys.
          </p>
          <div className="mt-6 flex flex-wrap gap-3">
            <SignInButton
              variant="primary"
              cta="videos_index_start_free"
              className="inline-flex h-9 items-center justify-center rounded-xl px-4 text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
            >
              Start free
            </SignInButton>
            <Link
              href="/marketplace"
              className="inline-flex h-9 items-center rounded-xl border px-4 text-sm font-medium transition-colors hover:bg-[var(--bg-tertiary)]"
              style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}
            >
              Browse the marketplace
            </Link>
          </div>
        </section>
      </div>
    </LandingShell>
  );
}
