/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import SeekButton from '../SeekButton';
import { FILM_SEEK_EVENT, type FilmSeekDetail } from '../filmSeek';

/**
 * Thirteen lines, and the whole mechanism that connects a chapter to the film.
 *
 * <p>It had no test at all: deleting the dispatch, pinning every chapter to
 * 0:00, dropping the scroll and removing the accessible name all left the suite
 * green, because `FilmPlayer.test.tsx` raises the window event by hand and so
 * only ever covered the consumer side.
 */
function listen() {
  const seen: FilmSeekDetail[] = [];
  const onSeek = (event: Event) => seen.push((event as CustomEvent<FilmSeekDetail>).detail);
  window.addEventListener(FILM_SEEK_EVENT, onSeek);
  return {
    seen,
    stop: () => window.removeEventListener(FILM_SEEK_EVENT, onSeek),
  };
}

afterEach(cleanup);

describe('SeekButton', () => {
  it('asks the film for the second it was given', () => {
    const { seen, stop } = listen();
    render(<SeekButton seconds={214.43} label="Play from 3:34: A real application">3:34</SeekButton>);

    fireEvent.click(screen.getByRole('button'));

    expect(seen).toEqual([{ seconds: 214.43 }]);
    stop();
  });

  it('carries a DIFFERENT second per chapter, not a constant', () => {
    // Pinning the payload to 0 leaves every chapter clickable and every one of
    // them playing the film from the top.
    const { seen, stop } = listen();
    render(
      <>
        <SeekButton seconds={0} label="a">0:00</SeekButton>
        <SeekButton seconds={252.57} label="b">4:12</SeekButton>
      </>,
    );

    screen.getAllByRole('button').forEach((button) => fireEvent.click(button));

    expect(seen.map((detail) => detail.seconds)).toEqual([0, 252.57]);
    stop();
  });

  it('brings the film back into view, so the click is not silent off-screen', () => {
    // The transcript runs far below the player: without this the film jumps to
    // the right moment somewhere the reader cannot see.
    const film = document.createElement('div');
    film.id = 'film';
    const scrollIntoView = vi.fn();
    film.scrollIntoView = scrollIntoView;
    document.body.appendChild(film);

    render(<SeekButton seconds={10} label="Play from 0:10">0:10</SeekButton>);
    fireEvent.click(screen.getByRole('button'));

    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    document.body.removeChild(film);
  });

  it('still moves the film when the player is not on the page', () => {
    // The scroll is a courtesy; losing the element must not swallow the seek.
    const { seen, stop } = listen();
    render(<SeekButton seconds={42} label="Play from 0:42">0:42</SeekButton>);

    expect(() => fireEvent.click(screen.getByRole('button'))).not.toThrow();

    expect(seen).toEqual([{ seconds: 42 }]);
    stop();
  });

  it('is named for the moment it plays, not for the timecode it shows', () => {
    render(
      <SeekButton seconds={214.43} label="Play from 3:34: A real application arrives">
        3:34
      </SeekButton>,
    );

    expect(screen.getByRole('button', { name: 'Play from 3:34: A real application arrives' })).toBeTruthy();
    expect(screen.getByRole('button').textContent).toBe('3:34');
  });

  it('does nothing until it is clicked', () => {
    const { seen, stop } = listen();
    render(<SeekButton seconds={10} label="Play from 0:10">0:10</SeekButton>);

    expect(seen).toEqual([]);
    stop();
  });
});
