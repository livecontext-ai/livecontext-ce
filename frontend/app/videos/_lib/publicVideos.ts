/**
 * Server-side reads of the product-film library, for the crawlable /videos pages.
 *
 * <p>Mirrors `lib/integrations/publicIntegrations.ts` exactly, and for the same
 * three reasons documented there: it calls the gateway DIRECTLY rather than
 * looping back through `/api/proxy`, it does NOT use `lib/api/api-client` (that
 * client is browser-bound and a `globalThis` singleton, so giving it a token on
 * the server would share that token across concurrent requests), and it reads
 * `GATEWAY_SERVICE_URL` first because `NEXT_PUBLIC_*` variables are inlined at
 * BUILD time while the non-public one is injected into the pod at runtime.
 *
 * <p>`/api/public/videos` is anonymous by design at the gateway, so no
 * credentials are ever attached here, and the backend serves only rows the
 * library marks `status = 'published'`.
 *
 * <p>This is what makes a new film ship with no deploy: the editorial copy, the
 * chapters and the transcript are a row someone edits, not a file someone
 * releases.
 */
import 'server-only';

import { gatewayBaseUrl } from '@/lib/marketplace/publicPublications';
import type { ProductVideo, VideoChapter, TranscriptLine } from './types';

/**
 * How long a public page may serve a stale copy of the library, in seconds.
 *
 * <p>An hour. A film is published a few times a month, deliberately, so this is
 * nowhere near a stream of user writes; the cost of the window is that a film
 * put live takes up to an hour to appear, which is the trade the whole design
 * was chosen for.
 */
export const PUBLIC_VIDEOS_REVALIDATE_SECONDS = 3600;

/**
 * How long a public page waits for the gateway before giving up.
 *
 * <p>A ceiling, not a target. What it bounds is the case a try/catch cannot
 * see: a gateway that accepts the connection and then never answers. Every
 * caller here is on the critical path of a public page, so an unbounded read is
 * a render that hangs rather than a page that degrades.
 */
const GATEWAY_READ_TIMEOUT_MS = 8000;

/** Distinguishes "no such film" from "we could not ask". */
export class VideoLibraryUnavailableError extends Error {}

interface GatewayRead {
  ok: boolean;
  body: unknown;
  /**
   * The HTTP status, or null when the request never completed.
   *
   * <p>Null is the load-bearing case: it tells an answered 404 apart from a
   * gateway we could not reach. One means the film is gone, the other means we
   * could not ask, and turning the second into a 404 invites search engines to
   * drop pages that still exist.
   */
  status: number | null;
}

async function getJson(path: string, revalidateSeconds: number): Promise<GatewayRead> {
  try {
    const res = await fetch(`${gatewayBaseUrl()}${path}`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: revalidateSeconds },
      signal: AbortSignal.timeout(GATEWAY_READ_TIMEOUT_MS),
    });
    if (!res.ok) return { ok: false, body: null, status: res.status };
    return { ok: true, body: await res.json(), status: res.status };
  } catch {
    return { ok: false, body: null, status: null };
  }
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function num(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

function chapters(value: unknown): VideoChapter[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((entry) => entry as Record<string, unknown>)
    .filter((entry) => entry && typeof entry.title === 'string' && entry.title.trim() !== '')
    .map((entry) => ({ start: num(entry.start), end: num(entry.end), title: str(entry.title) }));
}

function transcript(value: unknown): TranscriptLine[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((entry) => entry as Record<string, unknown>)
    .filter((entry) => entry && typeof entry.text === 'string' && entry.text.trim() !== '')
    .map((entry) => ({ t: num(entry.t), text: str(entry.text) }));
}

/**
 * One row as a film, or null when it could not make a page.
 *
 * <p>The backend already refuses a row with nothing to show, and this refuses it
 * again: these two services are deployed separately, so the page must not depend
 * on the version of the other one that happens to be running.
 */
function mapVideo(raw: unknown): ProductVideo | null {
  if (!raw || typeof raw !== 'object') return null;
  const entry = raw as Record<string, unknown>;
  const slug = str(entry.slug);
  const youtubeId = str(entry.youtubeId);
  if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(slug)) return null;
  // The id is pasted straight after `/embed/` on four surfaces. A full URL there
  // renders `.../embed/https://youtu.be/ID` on every one of them at once.
  if (!/^[\w-]{11}$/.test(youtubeId)) return null;
  if (str(entry.title).trim() === '') return null;

  return {
    slug,
    title: str(entry.title),
    tagline: str(entry.tagline),
    youtubeId,
    durationSeconds: Math.round(num(entry.durationSeconds)),
    publishedAt: str(entry.publishedAt),
    poster: str(entry.posterUrl),
    shareImage: str(entry.shareImageUrl),
    posterAlt: str(entry.posterAlt),
    problem: strings(entry.problem),
    answer: strings(entry.answer),
    highlights: strings(entry.highlights),
    chapters: chapters(entry.chapters),
    transcript: transcript(entry.transcript),
    marketplaceSlug: str(entry.marketplaceSlug),
    marketplaceTitle: str(entry.marketplaceTitle),
  };
}

/**
 * Every published film, newest first.
 *
 * @throws VideoLibraryUnavailableError when the library could not be read at all
 */
export async function fetchPublishedVideos({
  revalidateSeconds = PUBLIC_VIDEOS_REVALIDATE_SECONDS,
}: { revalidateSeconds?: number } = {}): Promise<ProductVideo[]> {
  const result = await getJson('/api/public/videos', revalidateSeconds);
  if (!result.ok) {
    throw new VideoLibraryUnavailableError(
      `The video library could not be read (status ${result.status ?? 'none'})`,
    );
  }
  const body = result.body as Record<string, unknown>;
  const rows = Array.isArray(body?.videos) ? body.videos : [];
  return rows.map(mapVideo).filter((video): video is ProductVideo => video !== null);
}

/**
 * Every published film, or an empty list when the library could not be read.
 *
 * <p>For surfaces that must lose themselves rather than the page they sit on:
 * the library index renders "no films yet" and the sitemap advertises none,
 * which are both survivable. The film PAGE must not use this: see
 * `fetchPublishedVideos`.
 */
export async function fetchPublishedVideosOrEmpty(
  options: { revalidateSeconds?: number } = {},
): Promise<ProductVideo[]> {
  try {
    return await fetchPublishedVideos(options);
  } catch {
    // Never let a partial library look like a complete one.
    console.warn('[videos] the film library could not be read; serving none');
    return [];
  }
}

/**
 * One published film.
 *
 * <p>Returns null ONLY for "no such published film", and throws when the library
 * could not be read, so a gateway blip renders the error boundary (a 500 a
 * crawler retries) instead of a 404 that invites it to drop a page that exists.
 *
 * @throws VideoLibraryUnavailableError when the library could not be read at all
 */
export async function fetchVideo(
  slug: string,
  options: { revalidateSeconds?: number } = {},
): Promise<ProductVideo | null> {
  const videos = await fetchPublishedVideos(options);
  return videos.find((video) => video.slug === slug) ?? null;
}

/**
 * The film that demonstrates a marketplace listing, if one exists.
 *
 * <p>Best-effort by design: this feeds a section of someone else's page, so an
 * unreadable library costs that section and nothing more. It is also what makes
 * the link go both ways from ONE source: the film names its listing, and
 * nothing on the publication side has to know about films.
 */
export async function fetchVideoForMarketplaceSlug(
  marketplaceSlug: string,
  options: { revalidateSeconds?: number } = {},
): Promise<ProductVideo | null> {
  if (!marketplaceSlug) return null;
  const videos = await fetchPublishedVideosOrEmpty(options);
  return videos.find((video) => video.marketplaceSlug === marketplaceSlug) ?? null;
}
