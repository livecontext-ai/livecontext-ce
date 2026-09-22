/**
 * The shape of a product film on the public video library (`/videos`).
 *
 * <p>These pages exist for search, not for the landing: each one is a long
 * narrated screen recording of the real product, wrapped in the text a search
 * engine can actually read (the problem, the answer, the chapters and the full
 * transcript). The film converts; the text ranks.
 *
 * <p>The films live in a DATA SOURCE the team edits, read through
 * `/api/public/videos`, not in this repo: putting a film online is a row, not a
 * release. `publicVideos.ts` is the only thing that builds one of these.
 */

/** One measured segment of the film, used for the chapter list and Clip markup. */
export interface VideoChapter {
  /** Seconds from the start of the film. */
  start: number;
  /** Seconds from the start of the film; the next chapter's start. */
  end: number;
  title: string;
}

/** One burned caption, at its position on the film timeline. */
export interface TranscriptLine {
  /** Seconds from the start of the film. */
  t: number;
  text: string;
}

export interface ProductVideo {
  /** URL segment. Reads as the job someone would search for, not as a film title. */
  slug: string;
  /** `<h1>` and `<title>`: the job, in the words of someone looking for it. */
  title: string;
  /** One sentence under the h1. */
  tagline: string;
  /**
   * The YouTube id of the long cut. Always 11 characters.
   *
   * <p>A row without one never reaches here: the service refuses it and
   * `mapVideo` refuses it again. A page whose only reason to exist is a film it
   * cannot play is worth neither serving nor indexing.
   */
  youtubeId: string;
  /** Exact runtime of the published cut, in seconds. */
  durationSeconds: number;
  /** ISO 8601, UTC. When the long cut went up. */
  publishedAt: string;
  /** Poster, a real frame of the film. WebP where there is one, for the page. */
  poster: string;
  /**
   * The same frame as JPEG, for share cards and structured data.
   *
   * <p>Not the WebP: several scrapers that read `og:image` (LinkedIn among them,
   * linked from the footer of every page here) do not reliably render WebP, and
   * a share card that fails is invisible rather than wrong.
   */
  shareImage: string;
  /** Alt text for the poster. */
  posterAlt: string;
  /** The series this film belongs to. Empty when it belongs to none. */
  series?: string;
  /** The problem the film opens on, in the reader's words. One paragraph per entry. */
  problem: string[];
  /** How LiveContext answers it. One paragraph per entry. */
  answer: string[];
  /** What the film shows, as short scannable claims. */
  highlights: string[];
  chapters: VideoChapter[];
  transcript: TranscriptLine[];
  /** The marketplace listing of the app the film builds, for the cross-link. */
  marketplaceSlug: string;
  marketplaceTitle: string;
}
