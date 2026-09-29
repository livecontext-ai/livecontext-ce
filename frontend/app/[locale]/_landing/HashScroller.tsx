'use client';

import { useEffect } from 'react';

/**
 * How long the target is kept in place while the page settles, at most. Generous on
 * purpose: on a slow phone the sections above can still be changing height several
 * seconds in, and it only ever acts when the target moved while the page did not.
 */
export const HASH_SETTLE_MS = 15000;

/** How often the target's position is checked while the page settles. */
export const HASH_CHECK_MS = 250;

/** A move smaller than this is rounding, not layout drift. */
const DRIFT_PX = 1;

/** Input that means the visitor is taking over. Belt and braces: see `scrolledSince`. */
const USER_INPUT_EVENTS = ['wheel', 'touchstart', 'keydown', 'pointerdown'] as const;
const LISTENER_OPTIONS: AddEventListenerOptions = { capture: true, passive: true };

/**
 * Scrolls to `location.hash` once the landing page has mounted. Handles the
 * "navigate from another public page then scroll to a section" case, where the
 * dynamic landing content (hero stack, marketplace preview, pricing) settles
 * its height after hydration and the browser's native hash scroll lands short.
 *
 * <p>One scroll is not enough: sections above the target keep changing height after
 * it (the agenda swaps its month grid for a list on a phone, the marketplace cards
 * arrive, pricing renders its full table), which moves the target. Chrome and
 * Firefox compensate with scroll anchoring; Safari does not, and measured in WebKit
 * on an iPhone viewport, `/#pricing` and `/#faq` missed their section by 1,346px on
 * two runs out of three.
 *
 * <p>So while the page settles, the target is checked every {@link HASH_CHECK_MS}. A
 * scroll, by anyone (a wheel, the scrollbar thumb, a screen reader), moves the page
 * and the section by the same amount in opposite directions; when that is all that
 * happened, it stops for good, so it does not fight the reader. (The one case two
 * numbers cannot separate, a scroll with no input event landing in the same check as
 * real drift, realigns once; the next scroll then stops it.) When the section moved
 * by anything else, the layout changed under it (WebKit even nudges the scroll
 * position while the page above shrinks, which is why "the page scrolled" alone is
 * not a user signal), and it is scrolled back. It also stops on any input, on
 * unmount, when the hash names no element, and after {@link HASH_SETTLE_MS}.
 */
export default function HashScroller() {
  useEffect(() => {
    const id = window.location.hash.replace('#', '');
    if (!id) return;

    let stopped = false;
    let alignedTop: number | null = null;
    let alignedScrollY: number | null = null;
    let raf1 = 0;
    let raf2 = 0;
    let interval = 0;
    let timer = 0;

    const stop = () => {
      stopped = true;
      cancelAnimationFrame(raf1);
      cancelAnimationFrame(raf2);
      window.clearInterval(interval);
      window.clearTimeout(timer);
      for (const type of USER_INPUT_EVENTS) window.removeEventListener(type, stop, LISTENER_OPTIONS);
    };

    const align = () => {
      if (stopped) return;
      const target = document.getElementById(id);
      if (!target) {
        stop();
        return;
      }
      target.scrollIntoView({ block: 'start' });
      alignedTop = target.getBoundingClientRect().top;
      alignedScrollY = window.scrollY;
    };

    const check = () => {
      if (stopped || alignedTop === null || alignedScrollY === null) return;
      const target = document.getElementById(id);
      if (!target) {
        stop();
        return;
      }
      const scrolled = window.scrollY - alignedScrollY;
      const moved = target.getBoundingClientRect().top - alignedTop;
      // A scroll moves the page and the section by the same amount in opposite
      // directions, so the two cancel out. Anything left over is the layout moving.
      if (Math.abs(scrolled + moved) <= DRIFT_PX) {
        if (Math.abs(scrolled) > DRIFT_PX) stop();
        return;
      }
      align();
    };

    for (const type of USER_INPUT_EVENTS) window.addEventListener(type, stop, LISTENER_OPTIONS);
    timer = window.setTimeout(stop, HASH_SETTLE_MS);
    interval = window.setInterval(check, HASH_CHECK_MS);
    // Two frames: after the first paint, so the native hash jump has happened.
    raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(align);
    });

    return stop;
  }, []);
  return null;
}
