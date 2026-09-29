// @vitest-environment jsdom
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import HashScroller, { HASH_CHECK_MS, HASH_SETTLE_MS } from '../HashScroller';

/**
 * Landing on `/#section` must end at the section, even while sections above it
 * are still changing height. Safari has no scroll anchoring: measured in WebKit on
 * an iPhone viewport, `/#pricing` and `/#faq` missed their section by 1,346px on
 * two runs out of three. A single scroll after mount was not enough.
 *
 * The page is modelled with two numbers: `scrollY` (where the page is scrolled)
 * and `top` (where the section sits in the viewport). A scroll to the section puts
 * it at 96px, its scroll margin.
 */
// The effect waits two animation frames (after first paint); rAF is faked as 16 ms.
const AFTER_FIRST_SCROLL = 40;

describe('HashScroller', () => {
  let target: HTMLElement;
  let scrolls: number;
  let top: number;
  let scrollY: number;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => setTimeout(() => cb(0), 16) as unknown as number);
    vi.stubGlobal('cancelAnimationFrame', (handle: number) => clearTimeout(handle));
    scrollY = 0;
    Object.defineProperty(window, 'scrollY', { configurable: true, get: () => scrollY });
    target = document.createElement('section');
    target.id = 'pricing';
    scrolls = 0;
    top = 500;
    target.scrollIntoView = vi.fn(() => { scrolls += 1; scrollY += top - 96; top = 96; });
    target.getBoundingClientRect = () => ({ top } as DOMRect);
    document.body.appendChild(target);
    window.history.replaceState(null, '', '/#pricing');
  });

  afterEach(() => {
    cleanup();
    target.remove();
    vi.unstubAllGlobals();
    vi.useRealTimers();
    window.history.replaceState(null, '', '/');
  });

  it('scrolls to the hash target once the page has painted', () => {
    render(<HashScroller />);
    expect(scrolls).toBe(0);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL);
    expect(scrolls).toBe(1);
    expect(target.scrollIntoView).toHaveBeenCalledWith({ block: 'start' });
  });

  it('scrolls back when content above shrinks and the page did not compensate (Safari)', () => {
    render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL);
    top = -1238; // same scroll position, but the section moved up past the reader
    vi.advanceTimersByTime(HASH_CHECK_MS);
    expect(scrolls).toBe(2);
    expect(top).toBe(96);
  });

  it('still corrects when WebKit nudges the scroll position while the page above shrinks', () => {
    // Measured in WebKit: the page shrank by 1,739px while scrollY went 18,443 -> 18,038,
    // leaving #faq 1,238px above the viewport. A scroll change alone is not the reader.
    render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL);
    scrollY -= 405;
    top = -1238;
    vi.advanceTimersByTime(HASH_CHECK_MS);
    expect(scrolls).toBe(2);
    expect(top).toBe(96);
  });

  it('does not take Chrome scroll anchoring for the reader, and keeps correcting after it', () => {
    render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL);
    scrollY -= 300; // anchoring compensates a shrink above: the section stays at 96px
    vi.advanceTimersByTime(HASH_CHECK_MS);
    top = 400; // later drift the browser did not compensate
    vi.advanceTimersByTime(HASH_CHECK_MS);
    expect(top).toBe(96);
  });

  it('when a scroll and drift land in the same check it realigns once, then yields to the next scroll', () => {
    // The one case two numbers cannot separate: documented as the trade-off.
    render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL);
    scrollY += 600;
    top = -900;
    vi.advanceTimersByTime(HASH_CHECK_MS);
    const afterRealign = scrolls;
    scrollY += 500; // a pure scroll now: page and section move together
    top -= 500;
    vi.advanceTimersByTime(HASH_CHECK_MS * 4);
    expect(scrolls).toBe(afterRealign);
  });

  it('ignores a sub-pixel move, and acts on a 2px one', () => {
    render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL);
    top = 96.8;
    vi.advanceTimersByTime(HASH_CHECK_MS);
    expect(scrolls).toBe(1);
    top = 98;
    vi.advanceTimersByTime(HASH_CHECK_MS);
    expect(scrolls).toBe(2);
  });

  it('leaves the page alone while the section stays put', () => {
    render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL + HASH_CHECK_MS * 10);
    expect(scrolls).toBe(1);
  });

  it('never fights a scroll that fired no input event (scrollbar thumb, screen reader)', () => {
    render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL);
    scrollY += 600; // the reader dragged the scrollbar: page and section moved together
    top -= 600;
    vi.advanceTimersByTime(HASH_CHECK_MS);
    expect(scrolls).toBe(1);
    // ...and it has stopped for good: later drift is not corrected either.
    top -= 300;
    vi.advanceTimersByTime(HASH_CHECK_MS * 4);
    expect(scrolls).toBe(1);
  });

  it.each(['wheel', 'touchstart', 'keydown', 'pointerdown'])('stops for good on %s', (type) => {
    render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL);
    window.dispatchEvent(new Event(type));
    top = 900;
    vi.advanceTimersByTime(HASH_CHECK_MS * 4);
    expect(scrolls).toBe(1);
  });

  it('stops realigning once the settle window is over', () => {
    render(<HashScroller />);
    vi.advanceTimersByTime(HASH_SETTLE_MS + 1);
    const before = scrolls;
    top = 900;
    vi.advanceTimersByTime(HASH_CHECK_MS * 4);
    expect(scrolls).toBe(before);
  });

  it('cleans everything up on unmount (navigating away)', () => {
    const removed = vi.spyOn(window, 'removeEventListener');
    const { unmount } = render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL);
    unmount();
    top = 900;
    vi.advanceTimersByTime(HASH_CHECK_MS * 4);
    expect(scrolls).toBe(1);
    for (const type of ['wheel', 'touchstart', 'keydown', 'pointerdown']) {
      expect(removed).toHaveBeenCalledWith(type, expect.any(Function), expect.objectContaining({ capture: true }));
    }
  });

  it('stops at once when the hash names no element', () => {
    window.history.replaceState(null, '', '/#typo');
    render(<HashScroller />);
    vi.advanceTimersByTime(AFTER_FIRST_SCROLL + HASH_CHECK_MS * 4);
    expect(scrolls).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does nothing without a hash', () => {
    window.history.replaceState(null, '', '/');
    render(<HashScroller />);
    vi.advanceTimersByTime(HASH_SETTLE_MS + 100);
    expect(scrolls).toBe(0);
  });
});
