'use client';

import { useEffect, useRef, useState } from 'react';

/**
 * True when the referenced element is wider than its box (it scrolls sideways).
 *
 * Used to make ONLY the tables and code blocks that actually scroll into a
 * focusable, labelled region (axe: scrollable-region-focusable). Marking every
 * one of them as a region would flood the landmark list (a page can hold a dozen
 * tables), and a focusable element that does not scroll is a useless Tab stop.
 * Starts false on the server and the first client render, so hydration matches.
 */
export function useHorizontalOverflow<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [overflowing, setOverflowing] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const check = () => setOverflowing(el.scrollWidth > el.clientWidth + 1);
    check();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(check);
    observer.observe(el);
    // Also watch the content: it can grow wider after mount (a late web font).
    if (el.firstElementChild) observer.observe(el.firstElementChild);
    return () => observer.disconnect();
  }, []);

  return [ref, overflowing] as const;
}
