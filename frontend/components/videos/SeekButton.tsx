'use client';

import { useCallback } from 'react';
import { FILM_SEEK_EVENT, type FilmSeekDetail } from './filmSeek';

interface SeekButtonProps {
  /** Seconds from the start of the film. */
  seconds: number;
  className?: string;
  /** Read by assistive tech in place of the visible children. */
  label: string;
  children: React.ReactNode;
}

/**
 * Moves the film to a moment and scrolls it back into view.
 *
 * <p>Deliberately tiny: the chapter titles and the transcript stay in SERVER
 * components and are passed through as `children`, so the text of the page is
 * rendered once, in the HTML, and never shipped again inside the JS bundle.
 */
export default function SeekButton({ seconds, className, label, children }: SeekButtonProps) {
  const onClick = useCallback(() => {
    window.dispatchEvent(
      new CustomEvent<FilmSeekDetail>(FILM_SEEK_EVENT, { detail: { seconds } }),
    );
    document.getElementById('film')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [seconds]);

  return (
    <button type="button" onClick={onClick} aria-label={label} className={className}>
      {children}
    </button>
  );
}
