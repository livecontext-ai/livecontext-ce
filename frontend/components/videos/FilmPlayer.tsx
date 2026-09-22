'use client';

import { useCallback, useEffect, useState } from 'react';
import { Play } from 'lucide-react';
import { youtubeEmbedUrl } from '@/app/videos/_lib/youtube';
import { FILM_SEEK_EVENT, type FilmSeekDetail } from './filmSeek';

interface FilmPlayerProps {
  youtubeId: string;
  /** The film's title, for the iframe's accessible name. */
  title: string;
  poster: string;
  posterAlt: string;
  /** e.g. "5:18". Shown on the facade so the length is known before the click. */
  durationLabel: string;
}

/** What the player is showing. `null` is the facade, with nothing loaded. */
interface MountedFilm {
  /** Seconds into the film the embed starts at. */
  at: number;
  /** Whether it should start playing by itself. */
  autoplay: boolean;
  /**
   * Meaningless except for being different every time, and it is the iframe's
   * React key.
   *
   * <p>The iframe is cross-origin, so the only control this component has over
   * it is to replace it. Keying on `at` looks equivalent and is not: asking for
   * a moment already on screen would change nothing, so a viewer who scrubbed
   * away and clicked that chapter again would sit on a player ignoring them.
   *
   * <p>The cost is honest: every seek re-downloads the player. Avoiding it means
   * `enablejsapi=1` and `postMessage` seeks (no third-party script, so the
   * facade would survive), worth doing if seeking ever becomes frequent. With
   * one button per chapter it is not.
   */
  nonce: number;
}

/**
 * A click-to-load YouTube facade.
 *
 * <p>A visitor who does not press play, and did not arrive on a timestamped
 * link, never touches YouTube at all: no request, no storage, no consent
 * question, and none of the player's payload on a page whose job is to be read.
 * The poster is a real frame of the film, so what the page shows before the
 * click is still the product.
 *
 * <p>The exception is deliberate, and it is the `?t=` case below: that URL is a
 * request for one moment of one film, so it loads the embed rather than asking
 * for a second click. It does NOT autoplay and does not move focus, so arriving
 * there is never a surprise, and `youtube-nocookie.com` means nothing is stored
 * until playback actually starts.
 */
export default function FilmPlayer({
  youtubeId,
  title,
  poster,
  posterAlt,
  durationLabel,
}: FilmPlayerProps) {
  const [film, setFilm] = useState<MountedFilm | null>(null);

  const start = useCallback((seconds: number, autoplay = true) => {
    setFilm((previous) => ({
      at: Math.max(0, Math.floor(seconds)),
      autoplay,
      nonce: (previous?.nonce ?? 0) + 1,
    }));
  }, []);

  useEffect(() => {
    const onSeek = (event: Event) => {
      const detail = (event as CustomEvent<FilmSeekDetail>).detail;
      if (detail && Number.isFinite(detail.seconds)) start(detail.seconds);
    };
    window.addEventListener(FILM_SEEK_EVENT, onSeek);
    return () => window.removeEventListener(FILM_SEEK_EVENT, onSeek);
  }, [start]);

  // `?t=214` is what the Clip markup advertises to a search engine as a key
  // moment, so arriving on one has to actually put the film at that moment.
  //
  // `>= 0`, not `> 0`: the first chapter of every film starts at zero, so its
  // advertised moment IS `?t=0`. Rejecting it made the one key moment every film
  // has in common the only one that did nothing.
  //
  // Read from `window` rather than the server's searchParams so the page itself
  // stays statically renderable.
  useEffect(() => {
    const raw = new URLSearchParams(window.location.search).get('t');
    if (raw === null) return;
    const seconds = Number.parseInt(raw, 10);
    if (Number.isFinite(seconds) && seconds >= 0) start(seconds, false);
  }, [start]);

  return (
    <div
      id="film"
      className="relative w-full overflow-hidden rounded-2xl border"
      style={{ aspectRatio: '16 / 9', borderColor: 'var(--border-color)', background: 'var(--bg-secondary)' }}
    >
      {film === null ? (
        <button
          type="button"
          onClick={() => start(0)}
          aria-label={`Play the film: ${title} (${durationLabel})`}
          className="group absolute inset-0 h-full w-full cursor-pointer"
        >
          {/* A plain <img>: the poster is a fixed local asset at a known size, and
              next/image here would only add a loader in front of a file the CDN
              already serves. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={poster}
            alt={posterAlt}
            width={1280}
            height={720}
            className="h-full w-full object-cover"
          />
          <span
            className="absolute inset-0 transition-colors"
            style={{ background: 'rgba(9, 9, 11, 0.28)' }}
            aria-hidden="true"
          />
          {/* A fixed near-black disc, not the theme accent: the poster is an
              arbitrary frame of the film, so the badge has to stay legible on any
              picture and in both themes. Same ink as the duration pill. */}
          <span
            className="absolute left-1/2 top-1/2 flex h-16 w-16 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full shadow-lg transition-transform group-hover:scale-105"
            style={{ background: 'rgba(9, 9, 11, 0.78)' }}
            aria-hidden="true"
          >
            <Play className="h-6 w-6 translate-x-[2px] fill-white text-white" />
          </span>
          <span
            className="absolute bottom-3 right-3 rounded-full px-2.5 py-1 text-xs font-medium text-white"
            style={{ background: 'rgba(9, 9, 11, 0.72)' }}
            aria-hidden="true"
          >
            {durationLabel}
          </span>
        </button>
      ) : (
        <iframe
          key={film.nonce}
          src={youtubeEmbedUrl(youtubeId, film.at, { autoplay: film.autoplay })}
          title={title}
          className="absolute inset-0 h-full w-full"
          allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
          referrerPolicy="strict-origin-when-cross-origin"
          allowFullScreen
        />
      )}
    </div>
  );
}
