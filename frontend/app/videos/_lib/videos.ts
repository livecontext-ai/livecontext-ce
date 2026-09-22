import type { ProductVideo, TranscriptLine } from './types';

export type { ProductVideo, TranscriptLine, VideoChapter } from './types';
// Re-exported so callers have ONE import for the film helpers, while the
// definitions stay in a module no client component drags the registry through.
export { youtubeCanonicalEmbedUrl, youtubeEmbedUrl, youtubeWatchUrl } from './youtube';

/** Site-relative path of a film's page. */
export function videoPath(slug: string): string {
  return `/videos/${slug}`;
}

/** `318` -> `"5:18"`. Used in badges and next to chapter titles. */
export function formatTimecode(totalSeconds: number): string {
  const whole = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(whole / 60);
  const seconds = whole % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/**
 * `318` -> `"PT5M18S"`, the ISO 8601 duration schema.org asks for.
 *
 * <p>Google drops a VideoObject whose `duration` is malformed, which costs the
 * page its video rich result while the page itself still renders perfectly, so
 * this is deliberately not hand-written per film.
 */
export function isoDuration(totalSeconds: number): string {
  const whole = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(whole / 60);
  const seconds = whole % 60;
  return `PT${minutes}M${seconds}S`;
}

/**
 * Caption lines as one block of readable prose.
 *
 * <p>ONE definition, used by the VideoObject `transcript` and by the page's
 * per-chapter paragraphs. A second copy of this two-line join is exactly how the
 * missing separator came back after it was first fixed: joined on nothing, the
 * word count stays above any threshold while the text reads "...describe
 * it.Method one...".
 */
export function linesToProse(lines: readonly TranscriptLine[]): string {
  return lines.map((line) => line.text).join(' ');
}

/** The narration as one block of prose, for the VideoObject `transcript`. */
export function transcriptText(video: ProductVideo): string {
  return linesToProse(video.transcript);
}
