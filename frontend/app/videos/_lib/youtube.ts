/**
 * Every YouTube URL the film pages build, in a module that imports NOTHING.
 *
 * <p>That is the point of it. `FilmPlayer` is a client component and needs the
 * embed URL; when that function lived beside the film registry, the player
 * imported the registry, and the registry imports every film's transcript. The
 * narration stayed out of the JS bundle only because the bundler tree-shook it,
 * which is a property of the build rather than of the code. With the URLs here,
 * no client component imports the registry at all and there is nothing to shake.
 */

/**
 * The canonical embed URL, the one structured data and share tags advertise.
 *
 * <p>Not `youtube-nocookie`: this string is read by crawlers and scrapers rather
 * than loaded by the page, and the canonical host is what they match against the
 * video they already know. The PLAYER uses {@link youtubeEmbedUrl}, which is
 * where the privacy host belongs.
 */
export function youtubeCanonicalEmbedUrl(youtubeId: string): string {
  return `https://www.youtube.com/embed/${youtubeId}`;
}

export function youtubeWatchUrl(youtubeId: string, startSeconds?: number): string {
  const base = `https://www.youtube.com/watch?v=${youtubeId}`;
  return startSeconds && startSeconds > 0 ? `${base}&t=${Math.floor(startSeconds)}` : base;
}

/**
 * The URL the player actually loads.
 *
 * <p>`youtube-nocookie.com` plus the click-to-load facade means a visitor who
 * never presses play never touches YouTube at all: no request, no storage, no
 * consent question, and none of the player's payload on a page whose job is to
 * be read.
 */
export function youtubeEmbedUrl(
  youtubeId: string,
  startSeconds?: number,
  options?: { autoplay?: boolean },
): string {
  // `modestbranding` is deliberately absent: YouTube stopped honouring it in
  // 2023, so passing it only suggests a control the player does not have.
  const params = new URLSearchParams({
    autoplay: options?.autoplay === false ? '0' : '1',
    rel: '0',
    // Without it, iOS Safari takes the film fullscreen the moment it starts,
    // which throws the reader out of the page the film is illustrating.
    playsinline: '1',
  });
  if (startSeconds && startSeconds > 0) params.set('start', String(Math.floor(startSeconds)));
  return `https://www.youtube-nocookie.com/embed/${youtubeId}?${params.toString()}`;
}
