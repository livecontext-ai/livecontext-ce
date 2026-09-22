import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { ArrowLeft, ArrowRight, ExternalLink, Play } from 'lucide-react';
import JsonLd from '@/components/seo/JsonLd';
import { LandingShell } from '@/components/landing/LandingShell';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import FilmPlayer from '@/components/videos/FilmPlayer';
import SeekButton from '@/components/videos/SeekButton';
import { IS_CE } from '@/lib/edition';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import type { ProductVideo, TranscriptLine, VideoChapter } from '../_lib/types';
import {
  formatTimecode,
  isoDuration,
  linesToProse,
  transcriptText,
  videoPath,
  youtubeCanonicalEmbedUrl,
  youtubeWatchUrl,
} from '../_lib/videos';
import { fetchPublishedVideos, fetchVideo } from '../_lib/publicVideos';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/**
 * Rendered per request, NOT prerendered at build time.
 *
 * <p>The same reasoning as `/marketplace` and `/integrations`, and it has been
 * observed in production on both: the gateway is unreachable from the CI
 * builder, so a prerender bakes an EMPTY library, and every frontend replica
 * then serves that copy until it individually revalidates. A film put live
 * would be invisible until something happened to evict it.
 *
 * <p>The upstream read keeps its own hourly window, so this costs one gateway
 * read per hour per replica, not one per page view.
 */
export const dynamic = 'force-dynamic';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  // Metadata must never be the thing that fails a page: an unreadable library
  // costs the share tags, and the render below decides the status code.
  let video: Awaited<ReturnType<typeof fetchVideo>> = null;
  try {
    video = await fetchVideo(slug);
  } catch {
    return {};
  }
  if (!video) return {};

  const url = `${SITE_URL}${videoPath(slug)}`;
  const title = `${video.title} - LiveContext`;
  // Absolute already: the library validates the host before serving it, so
  // prefixing it here builds "https://livecontext.aihttps://...".
  const shareImage = video.shareImage;

  return {
    // `absolute`: the string already names the brand, and the root layout's
    // template would otherwise render "... - LiveContext - LiveContext".
    title: { absolute: title },
    description: video.tagline,
    alternates: { canonical: url },
    // Both share blocks spelled out in full: Next merges metadata shallowly per
    // top-level field, so a partial override drops the root layout's values.
    openGraph: {
      siteName: 'LiveContext',
      title,
      description: video.tagline,
      url,
      type: 'video.other',
      images: [{ url: shareImage, width: 1280, height: 720, alt: video.posterAlt }],
      // `video.other` without an `og:video` is a type no scraper can act on, so
      // the embed is declared alongside it and some platforms play it inline.
      videos: [
        {
          url: youtubeCanonicalEmbedUrl(video.youtubeId),
          width: 1280,
          height: 720,
          type: 'text/html',
        },
      ],
    },
    twitter: {
      // `summary_large_image`, not `player`: a player card needs a `twitter:player`
      // iframe URL and its dimensions, which are not emitted here, and an
      // incomplete player card renders as NO card at all.
      card: 'summary_large_image',
      title,
      description: video.tagline,
      images: [shareImage],
    },
    // Self-hosted deployments never index marketing pages. `follow: false` like
    // /marketplace, /compare, /about, /changelog, /models and /status; robots.ts
    // already disallows everything on CE, so this is the belt for those braces.
    robots: IS_CE ? { index: false, follow: false } : undefined,
  };
}

export default async function VideoPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  // `fetchVideo` returns null ONLY for "no such published film" and THROWS when
  // the library could not be read, so a gateway blip renders the error boundary
  // (a 500 a crawler retries) rather than a 404 that invites it to drop a page
  // that still exists.
  const [video, library] = await Promise.all([fetchVideo(slug), fetchPublishedVideos()]);
  if (!video) notFound();

  const url = `${SITE_URL}${videoPath(slug)}`;
  const others = library.filter((other) => other.slug !== video.slug);

  const videoJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'VideoObject',
    name: video.title,
    description: video.tagline,
    // Absolute already, and host-checked by the library read: prefixing it
    // here builds "https://livecontext.aihttps://...".
    thumbnailUrl: [video.shareImage],
    uploadDate: video.publishedAt,
    duration: isoDuration(video.durationSeconds),
    embedUrl: youtubeCanonicalEmbedUrl(video.youtubeId),
    url,
    // The narration, verbatim. It is also on the page, but a search engine that
    // reads the markup rather than the DOM gets it either way.
    transcript: transcriptText(video),
    publisher: {
      '@type': 'Organization',
      name: 'LiveContext',
      url: SITE_URL,
      logo: { '@type': 'ImageObject', url: `${SITE_URL}/og-image.jpg` },
    },
    // Key moments. Every `url` points back at THIS page with the timestamp the
    // player honours on arrival, so a moment offered in a search result lands
    // on the moment it promised.
    // A chapter under a second floors to startOffset === endOffset, which is
    // rejected outright, taking the whole VideoObject's key moments with it.
    // Such a chapter is not a moment anyone wants to jump to anyway.
    hasPart: video.chapters
      .filter((chapter) => Math.floor(chapter.end) > Math.floor(chapter.start))
      .map((chapter) => ({
        '@type': 'Clip',
        name: chapter.title,
        startOffset: Math.floor(chapter.start),
        endOffset: Math.floor(chapter.end),
        url: `${url}?t=${Math.floor(chapter.start)}`,
      })),
  };

  const breadcrumbJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: SITE_URL },
      { '@type': 'ListItem', position: 2, name: 'Videos', item: `${SITE_URL}/videos` },
      { '@type': 'ListItem', position: 3, name: video.title, item: url },
    ],
  };

  return (
    <LandingShell>
      {!IS_CE && <JsonLd data={videoJsonLd} />}
      {!IS_CE && <JsonLd data={breadcrumbJsonLd} />}

      <div className="mx-auto w-full max-w-4xl px-4 py-10 sm:px-6 md:py-14">
        <Link
          href="/videos"
          className="inline-flex items-center gap-1.5 text-sm transition-opacity hover:opacity-80"
          style={{ color: 'var(--text-muted)' }}
        >
          <ArrowLeft className="h-3.5 w-3.5" aria-hidden="true" />
          All videos
        </Link>

        <header className="mt-6">
          <h1 className="text-3xl font-semibold leading-tight md:text-4xl" style={{ color: 'var(--text-primary)' }}>
            {video.title}
          </h1>
          <p className="mt-4 text-base leading-relaxed md:text-lg" style={{ color: 'var(--text-secondary)' }}>
            {video.tagline}
          </p>
          <div className="mt-4 flex flex-wrap items-center gap-x-3 gap-y-2 text-xs" style={{ color: 'var(--text-muted)' }}>
            <span>{formatTimecode(video.durationSeconds)}</span>
            <span aria-hidden="true">·</span>
            {/* `locale: 'en'` explicitly, like /marketplace: these pages live
                OUTSIDE the [locale] tree and are English by contract, so the
                date must not follow the reader's browser language. */}
            <time dateTime={video.publishedAt}>{formatUtcDate(video.publishedAt, { locale: 'en' })}</time>
            <span aria-hidden="true">·</span>
            <span>English, subtitled</span>
          </div>
        </header>

        <div className="mt-8">
          <FilmPlayer
            youtubeId={video.youtubeId}
            title={video.title}
            poster={video.poster}
            posterAlt={video.posterAlt}
            durationLabel={formatTimecode(video.durationSeconds)}
          />
        </div>

        <div className="mt-6 flex flex-wrap gap-3">
          <SignInButton
            variant="primary"
            cta={`video_${video.slug}_start_free`}
            className="inline-flex h-9 items-center justify-center rounded-xl px-4 text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
          >
            Start free
          </SignInButton>
          <Link
            href={`/marketplace/${video.marketplaceSlug}`}
            className="inline-flex h-9 items-center gap-2 rounded-xl border px-4 text-sm font-medium transition-colors hover:bg-[var(--bg-secondary)]"
            style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}
          >
            Install {video.marketplaceTitle}
            <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
          </Link>
          <a
            href={youtubeWatchUrl(video.youtubeId)}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex h-9 items-center gap-2 rounded-xl border px-4 text-sm font-medium transition-colors hover:bg-[var(--bg-secondary)]"
            style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
          >
            Watch on YouTube
            <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
          </a>
        </div>

        <section className="mt-12">
          <SectionTitle>The problem</SectionTitle>
          {video.problem.map((paragraph) => (
            <p key={paragraph} className="mt-4 leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              {paragraph}
            </p>
          ))}
        </section>

        <section className="mt-10">
          <SectionTitle>How LiveContext solves it</SectionTitle>
          {video.answer.map((paragraph) => (
            <p key={paragraph} className="mt-4 leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              {paragraph}
            </p>
          ))}
          <ul className="mt-6 space-y-2.5">
            {video.highlights.map((highlight) => (
              <li key={highlight} className="flex gap-2.5 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                <span aria-hidden="true" style={{ color: 'var(--text-muted)' }}>
                  &bull;
                </span>
                <span>{highlight}</span>
              </li>
            ))}
          </ul>
        </section>

        <section className="mt-12">
          <SectionTitle>Chapters</SectionTitle>
          <ol className="mt-4 divide-y rounded-2xl border" style={{ borderColor: 'var(--border-color)' }}>
            {video.chapters.map((chapter) => (
              <li key={chapter.start} style={{ borderColor: 'var(--border-color)' }}>
                <SeekButton
                  seconds={chapter.start}
                  label={`Play from ${formatTimecode(chapter.start)}: ${chapter.title}`}
                  className="flex w-full cursor-pointer items-baseline gap-4 px-4 py-3 text-left transition-colors hover:bg-[var(--bg-secondary)]"
                >
                  <span className="shrink-0 font-mono text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                    {formatTimecode(chapter.start)}
                  </span>
                  <span className="text-sm" style={{ color: 'var(--text-primary)' }}>
                    {chapter.title}
                  </span>
                </SeekButton>
              </li>
            ))}
          </ol>
        </section>

        <section className="mt-12">
          <SectionTitle>Transcript</SectionTitle>
          <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
            Everything the film says, in order, grouped by chapter. Click a timecode to jump there.
          </p>
          {/* Grouped into chapter-sized PROSE rather than one row per caption.
              A row per caption made the page's core content ~90 consecutive tab
              stops, and read as a list of fragments rather than as something a
              person (or a search engine) can take in. */}
          <div className="mt-6 space-y-8">
            {groupTranscript(video).map((group) => (
              <div key={group.chapter.start}>
                {/* The seek control sits BESIDE the heading, never inside it: a
                    button's aria-label is folded into the accessible name of the
                    heading that contains it, so the chapter title was announced
                    twice, once prefixed with "Play from". Heading navigation is
                    how this transcript gets skimmed. */}
                <div className="flex items-baseline gap-3">
                  {/* Named by the timecode alone: the chapter list above already
                      has a button called "Play from 0:00: <title>", and two
                      buttons with the same name doing the same thing is an
                      ambiguity for anyone navigating by control. The heading
                      beside this one supplies the title. */}
                  <SeekButton
                    seconds={group.chapter.start}
                    label={`Play from ${formatTimecode(group.chapter.start)}`}
                    className="shrink-0 cursor-pointer rounded font-mono text-xs tabular-nums underline-offset-4 hover:underline"
                  >
                    <span style={{ color: 'var(--text-muted)' }}>{formatTimecode(group.chapter.start)}</span>
                  </SeekButton>
                  <h3 className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                    {group.chapter.title}
                  </h3>
                </div>
                <p className="mt-2 leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                  {linesToProse(group.lines)}
                </p>
              </div>
            ))}
          </div>
        </section>

        {others.length > 0 && (
          <section className="mt-14 border-t pt-10" style={{ borderColor: 'var(--border-color)' }}>
            <SectionTitle>More films</SectionTitle>
            <div className="mt-5 grid gap-4 sm:grid-cols-2">
              {others.map((other) => (
                <Link
                  key={other.slug}
                  href={videoPath(other.slug)}
                  className="group overflow-hidden rounded-2xl border transition-colors hover:bg-[var(--bg-secondary)]"
                  style={{ borderColor: 'var(--border-color)' }}
                >
                  <span className="relative block" style={{ aspectRatio: '16 / 9' }}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={other.poster}
                      alt={other.posterAlt}
                      width={1280}
                      height={720}
                      loading="lazy"
                      className="h-full w-full object-cover"
                    />
                    <span
                      className="absolute bottom-2 right-2 rounded-full px-2 py-0.5 text-xs font-medium text-white"
                      style={{ background: 'rgba(9, 9, 11, 0.72)' }}
                    >
                      {formatTimecode(other.durationSeconds)}
                    </span>
                  </span>
                  <span className="block p-4">
                    <span className="flex items-center gap-1.5 text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                      <Play className="h-3.5 w-3.5" aria-hidden="true" />
                      {other.title}
                    </span>
                  </span>
                </Link>
              ))}
            </div>
          </section>
        )}
      </div>
    </LandingShell>
  );
}

/**
 * Lay each caption under the chapter it falls in.
 *
 * <p>Chapters are contiguous and cover the whole film (a unit test pins that),
 * so the last group catches anything at or past the final boundary and no line
 * can be dropped: the transcript on the page is the whole transcript or the
 * test that counts its words fails.
 */
function groupTranscript(video: ProductVideo): { chapter: VideoChapter; lines: TranscriptLine[] }[] {
  // No chapters would leave nothing to group under, and `groups[-1]` throws
  // rather than dropping the transcript quietly.
  if (video.chapters.length === 0) return [];
  const groups = video.chapters.map((chapter) => ({ chapter, lines: [] as TranscriptLine[] }));
  for (const line of video.transcript) {
    // `<`, not `<=`: a line landing exactly on a boundary opens the NEXT
    // chapter, which is where the film cuts to.
    const index = groups.findIndex((group) => line.t < group.chapter.end);
    groups[index === -1 ? groups.length - 1 : index].lines.push(line);
  }
  return groups.filter((group) => group.lines.length > 0);
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="text-xl font-semibold md:text-2xl" style={{ color: 'var(--text-primary)' }}>
      {children}
    </h2>
  );
}
