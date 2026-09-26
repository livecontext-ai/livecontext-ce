'use client';

import React, { useEffect, useState } from 'react';
import { usePrefersReducedMotion } from '@/hooks/usePrefersReducedMotion';

export const THINKING_GLYPH_FRAMES = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'] as const;
export const THINKING_GLYPH_FRAME_MS = 80;

/**
 * The braille spinner in front of the "Thinking…" label.
 *
 * <p>It sets no color and no background of its own on purpose: it is rendered INSIDE the
 * label's `shimmer-text` element, whose gradient is clipped to the text of every descendant,
 * so the glyph and the word are painted by one sweep in both themes. Giving it a color would
 * take it out of that gradient.
 *
 * <p>The box has a fixed width (braille cells differ in visible ink, not in advance, but the
 * fallback font is not guaranteed) so the label after it never moves between frames.
 */
export function ThinkingGlyph({ className = '' }: { className?: string }) {
  const reduceMotion = usePrefersReducedMotion();
  const [frame, setFrame] = useState(0);

  useEffect(() => {
    if (reduceMotion) return;
    const id = window.setInterval(
      () => setFrame(f => (f + 1) % THINKING_GLYPH_FRAMES.length),
      THINKING_GLYPH_FRAME_MS,
    );
    return () => window.clearInterval(id);
  }, [reduceMotion]);

  return (
    <span
      aria-hidden="true"
      data-testid="thinking-glyph"
      className={`inline-block w-[1em] text-center ${className}`}
    >
      {THINKING_GLYPH_FRAMES[reduceMotion ? 0 : frame]}
    </span>
  );
}

export default ThinkingGlyph;
