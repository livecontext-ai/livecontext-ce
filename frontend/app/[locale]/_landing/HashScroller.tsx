'use client';

import { useEffect } from 'react';

/**
 * Scrolls to `location.hash` once the landing page has mounted. Handles the
 * "navigate from another public page then scroll to a section" case, where the
 * dynamic landing content (hero stack, marketplace preview, pricing) settles
 * its height after hydration and the browser's native hash scroll lands short.
 */
export default function HashScroller() {
  useEffect(() => {
    const id = window.location.hash.replace('#', '');
    if (!id) return;
    let raf2 = 0;
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        document.getElementById(id)?.scrollIntoView({ block: 'start' });
      });
    });
    return () => {
      cancelAnimationFrame(raf1);
      cancelAnimationFrame(raf2);
    };
  }, []);
  return null;
}
