/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import FilmPlayer from '../FilmPlayer';
import { FILM_SEEK_EVENT, type FilmSeekDetail } from '../filmSeek';

const PROPS = {
  youtubeId: 'lG-wfKo2NOo',
  title: 'Screen every application, not just the first nine',
  poster: '/videos/automate-candidate-screening.webp',
  posterAlt: 'The candidates table in LiveContext',
  durationLabel: '5:18',
};

/** The iframe, or null while the facade is still up. */
function frame(): HTMLIFrameElement | null {
  return document.querySelector('iframe');
}

function setSearch(search: string) {
  window.history.replaceState({}, '', `/videos/automate-candidate-screening${search}`);
}

describe('FilmPlayer facade', () => {
  beforeEach(() => setSearch(''));
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('loads nothing from YouTube until the visitor asks for the film', () => {
    // The whole point of the facade: a page whose job is to be read must not
    // pull a third-party player, its payload or its storage on arrival.
    render(<FilmPlayer {...PROPS} />);

    expect(frame()).toBeNull();
    const poster = screen.getByRole('img', { name: PROPS.posterAlt });
    expect(poster.getAttribute('src')).toBe(PROPS.poster);
  });

  it('carries the id the chapter buttons scroll back to', () => {
    // SeekButton finds the player by `#film`. Renaming it leaves every seek
    // working and invisible, somewhere off-screen.
    const { container } = render(<FilmPlayer {...PROPS} />);
    expect(container.querySelector('#film')).not.toBeNull();
  });

  it('plays inline, so a phone does not take the film fullscreen on its own', async () => {
    render(<FilmPlayer {...PROPS} />);
    fireEvent.click(screen.getByRole('button'));

    await waitFor(() => expect(frame()).not.toBeNull());
    expect(frame()!.getAttribute('src') ?? '').toContain('playsinline=1');
  });

  it('names the play control with the film and its length', () => {
    render(<FilmPlayer {...PROPS} />);
    expect(screen.getByRole('button', { name: `Play the film: ${PROPS.title} (5:18)` })).toBeTruthy();
  });

  it('mounts the no-cookie embed on click, from the start', async () => {
    render(<FilmPlayer {...PROPS} />);

    fireEvent.click(screen.getByRole('button'));

    await waitFor(() => expect(frame()).not.toBeNull());
    const src = frame()!.getAttribute('src') ?? '';
    expect(src).toContain('https://www.youtube-nocookie.com/embed/lG-wfKo2NOo');
    expect(src).toContain('autoplay=1');
    expect(src).not.toContain('start=');
  });

  it('starts at the moment a chapter asks for', async () => {
    render(<FilmPlayer {...PROPS} />);

    window.dispatchEvent(
      new CustomEvent<FilmSeekDetail>(FILM_SEEK_EVENT, { detail: { seconds: 214.43 } }),
    );

    await waitFor(() => expect(frame()?.getAttribute('src') ?? '').toContain('start=214'));
  });

  it('moves to a second moment once the film is already playing', async () => {
    // The iframe is cross-origin, so a seek can only be a remount.
    render(<FilmPlayer {...PROPS} />);

    window.dispatchEvent(new CustomEvent<FilmSeekDetail>(FILM_SEEK_EVENT, { detail: { seconds: 60 } }));
    await waitFor(() => expect(frame()?.getAttribute('src') ?? '').toContain('start=60'));

    window.dispatchEvent(new CustomEvent<FilmSeekDetail>(FILM_SEEK_EVENT, { detail: { seconds: 279.4 } }));
    await waitFor(() => expect(frame()?.getAttribute('src') ?? '').toContain('start=279'));
  });

  it('restarts the film when the SAME moment is asked for twice', async () => {
    // Keying the iframe on the OFFSET cannot cover this: asking for a moment
    // already in the key changes nothing, and a viewer who scrubbed away and
    // clicked that chapter again would sit on a player that ignores them. This
    // is what the separate `nonce` key exists for.
    render(<FilmPlayer {...PROPS} />);

    await act(async () => {
      window.dispatchEvent(new CustomEvent<FilmSeekDetail>(FILM_SEEK_EVENT, { detail: { seconds: 60 } }));
    });
    const first = frame();
    expect(first?.getAttribute('src') ?? '').toContain('start=60');

    await act(async () => {
      window.dispatchEvent(new CustomEvent<FilmSeekDetail>(FILM_SEEK_EVENT, { detail: { seconds: 60 } }));
    });

    // A NEW element, not the one that was already playing.
    expect(frame()).not.toBe(first);
    expect(frame()?.getAttribute('src') ?? '').toContain('start=60');
  });

  it('ignores a seek carrying no usable time', async () => {
    // `act` so React has committed before the assertion: without it this test
    // passed on an implementation with no guard at all, which is worse than no
    // test. Proven by mutation.
    render(<FilmPlayer {...PROPS} />);

    await act(async () => {
      window.dispatchEvent(new CustomEvent(FILM_SEEK_EVENT));
      window.dispatchEvent(
        new CustomEvent<FilmSeekDetail>(FILM_SEEK_EVENT, { detail: { seconds: Number.NaN } }),
      );
    });

    expect(frame()).toBeNull();
  });

  it('honours ?t= on arrival, which is what the key-moment markup promises', async () => {
    // Every Clip in the page's VideoObject points at this page with `?t=`. If the
    // player ignored it, a moment offered in a search result would land on a page
    // showing the poster, at zero.
    setSearch('?t=252');
    render(<FilmPlayer {...PROPS} />);

    await waitFor(() => expect(frame()?.getAttribute('src') ?? '').toContain('start=252'));
  });

  it('honours ?t=0, the key moment every film has', async () => {
    // Every film's first chapter starts at zero, so `?t=0` is an advertised key
    // moment. A `> 0` guard made it the only one that did nothing, and the page
    // still promised it in its Clip markup.
    setSearch('?t=0');
    render(<FilmPlayer {...PROPS} />);

    await waitFor(() => expect(frame()).not.toBeNull());
    expect(frame()!.getAttribute('src') ?? '').toContain('/embed/lG-wfKo2NOo');
  });

  it('does not autoplay, nor steal focus, when a timestamped link opens it', async () => {
    // Arriving on a key moment is not the same as pressing play: the embed is
    // mounted at the right moment and waits. Autoplaying, or pulling focus into
    // a cross-origin iframe on load, is a surprise the page never asked for.
    setSearch('?t=252');
    render(<FilmPlayer {...PROPS} />);

    await waitFor(() => expect(frame()).not.toBeNull());
    expect(frame()!.getAttribute('src') ?? '').toContain('autoplay=0');
    expect(document.activeElement).not.toBe(frame());
  });

  it('autoplays when the visitor presses play', async () => {
    render(<FilmPlayer {...PROPS} />);
    fireEvent.click(screen.getByRole('button'));

    await waitFor(() => expect(frame()).not.toBeNull());
    expect(frame()!.getAttribute('src') ?? '').toContain('autoplay=1');
  });

  it('keeps the facade up for a ?t= that is not a time', async () => {
    setSearch('?t=soon');
    render(<FilmPlayer {...PROPS} />);

    await act(async () => {});
    expect(frame()).toBeNull();
  });

  it('stops listening for seeks once it is gone', async () => {
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = render(<FilmPlayer {...PROPS} />);

    unmount();

    expect(remove.mock.calls.some(([event]) => event === FILM_SEEK_EVENT)).toBe(true);
  });
});
