'use client';

/**
 * useAnchorScrollToBottom - anchor a scroll container to its bottom edge for the
 * first paint(s) of a freshly-loaded conversation, then step out of the way.
 *
 * Why this is more than a one-liner:
 *  - When the user opens a conversation, page=0 returns the latest N messages.
 *    A naive `scrollTop = scrollHeight` set in useEffect lands BEFORE images,
 *    code blocks and markdown finish their async layout - the reader ends up
 *    mid-conversation rather than on the most recent message.
 *  - We watch the container's scrollHeight via ResizeObserver and re-pin to the
 *    bottom on every growth, until the height has been stable for STABLE_MS or
 *    the absolute CEILING_MS has elapsed. Async <img> loads inside the
 *    container also re-pin via a capture-phase 'load' listener (covers nested
 *    <img> in markdown).
 *  - The user can preempt the anchor at any time: a wheel/touch/keyboard input
 *    cancels the window so we never yank a reader who has already started
 *    scrolling.
 *  - The anchor is gated by an LRU keyed on conversation id, so it fires once
 *    per conversation switch and not on every re-render.
 *
 * Out of scope (handled elsewhere):
 *  - Streaming chunk auto-scroll (see ChatPageV2 - only scrolls if near bottom)
 *  - User-send override (see ChatPageV2 - pendingLocal flag)
 *  - Lazy-load older messages position preservation (see MessageHistory)
 */

import { useEffect, useRef } from 'react';

export interface AnchorOptions {
  /** Maximum time we keep watching scrollHeight after first ready signal. */
  ceilingMs?: number;
  /** Stop re-pinning once scrollHeight has been stable for this long. */
  stableMs?: number;
  /** LRU capacity - guards against power users opening many conversations in one session. */
  lruCap?: number;
}

const DEFAULT_OPTIONS: Required<AnchorOptions> = {
  ceilingMs: 3000,
  stableMs: 200,
  lruCap: 50,
};

/**
 * Anchors `containerRef` to the bottom edge once per `conversationId`, with
 * stabilization against async layout (images, markdown, code blocks).
 *
 * @param containerRef the scroll container
 * @param conversationId the keying scope; null/empty disables the anchor
 * @param ready true once the initial message page has been hydrated; while
 *              false the anchor stays armed but does not start its window
 */
export function useAnchorScrollToBottom(
  containerRef: React.RefObject<HTMLElement | null>,
  conversationId: string | null | undefined,
  ready: boolean,
  options: AnchorOptions = {},
): void {
  const opts = { ...DEFAULT_OPTIONS, ...options };

  // LRU<conversationId, true> - we only need to record "anchored once" per id.
  // Map preserves insertion order; eviction = delete oldest key on overflow.
  const seenRef = useRef<Map<string, true>>(new Map());

  useEffect(() => {
    if (!conversationId || !ready) return;
    const container = containerRef.current;
    if (!container) return;
    if (seenRef.current.has(conversationId)) return;

    // Empty conversation - nothing to anchor; record as seen and exit.
    if (container.scrollHeight <= container.clientHeight) {
      seenRef.current.set(conversationId, true);
      enforceLru(seenRef.current, opts.lruCap);
      return;
    }

    seenRef.current.set(conversationId, true);
    enforceLru(seenRef.current, opts.lruCap);

    let cancelled = false;
    let lastChangeAt = performance.now();
    const startedAt = lastChangeAt;
    let lastSeenHeight = container.scrollHeight;
    let rafId = 0;

    const pinToBottom = () => {
      if (cancelled) return;
      // capture height before mutation so the comparator below sees a real change
      lastSeenHeight = container.scrollHeight;
      container.scrollTop = container.scrollHeight;
    };

    const cancel = () => {
      if (cancelled) return;
      cancelled = true;
      if (rafId) cancelAnimationFrame(rafId);
      ro.disconnect();
      container.removeEventListener('wheel', cancel);
      container.removeEventListener('touchstart', cancel, { capture: true } as EventListenerOptions);
      window.removeEventListener('keydown', onKeyDown, true);
      container.removeEventListener('load', pinToBottom, true);
    };

    const onKeyDown = (e: KeyboardEvent) => {
      // Modifier-only presses (Shift/Ctrl/Alt/Meta held alone) are a no-op for scrolling.
      // Any other keypress could trigger user-driven scroll (Tab, Space, PageDown,
      // Cmd+End, arrow keys, etc.) - release the anchor immediately.
      if (e.key === 'Shift' || e.key === 'Control' || e.key === 'Alt' || e.key === 'Meta') {
        return;
      }
      cancel();
    };

    const ro = new ResizeObserver(() => {
      if (cancelled) return;
      if (container.scrollHeight !== lastSeenHeight) {
        lastChangeAt = performance.now();
        pinToBottom();
      }
      const now = performance.now();
      if (now - lastChangeAt >= opts.stableMs || now - startedAt >= opts.ceilingMs) {
        cancel();
      }
    });

    // Double-rAF: first frame after the effect runs may not have committed
    // the painted layout yet on slower devices; the second is a defensive
    // belt for browsers that paint asynchronously.
    rafId = requestAnimationFrame(() => {
      rafId = requestAnimationFrame(() => {
        if (cancelled) return;
        pinToBottom();
        ro.observe(container);
      });
    });

    container.addEventListener('wheel', cancel, { passive: true });
    container.addEventListener('touchstart', cancel, { passive: true, capture: true });
    window.addEventListener('keydown', onKeyDown, true);
    // Capture <img>/<video>/<iframe> load events bubbling up to container during the window.
    container.addEventListener('load', pinToBottom, true);

    // Hard ceiling - if ResizeObserver never fires (very fast layout), still cancel cleanly.
    const ceilingTimer = window.setTimeout(cancel, opts.ceilingMs);

    return () => {
      cancel();
      window.clearTimeout(ceilingTimer);
    };
  }, [conversationId, ready, containerRef, opts.ceilingMs, opts.stableMs, opts.lruCap]);
}

function enforceLru(map: Map<string, true>, cap: number): void {
  while (map.size > cap) {
    const oldest = map.keys().next().value;
    if (oldest === undefined) break;
    map.delete(oldest);
  }
}
