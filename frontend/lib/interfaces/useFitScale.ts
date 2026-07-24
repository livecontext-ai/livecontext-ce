'use client';

import * as React from 'react';
import type { FormatViewport } from '@/lib/interfaces/interfaceFormats';

export interface MeasuredBox {
  width: number;
  height: number;
}

/**
 * Measures the element the returned ref is attached to, and keeps the size up to date via a
 * ResizeObserver. Both dimensions are always observed so a caller can switch fit mode at runtime
 * without remounting.
 *
 * Shared by every surface that renders an interface at its own fixed viewport and CSS-scales it
 * into whatever box the layout gives it (canvas node, list card, side panel, fullscreen, share).
 */
export function useMeasuredBox<T extends HTMLElement>(): [React.RefObject<T | null>, MeasuredBox] {
  const ref = React.useRef<T>(null);
  const [box, setBox] = React.useState<MeasuredBox>({ width: 0, height: 0 });

  React.useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    setBox({ width: r.width, height: r.height });
    const obs = new ResizeObserver(([entry]) => {
      const cr = entry.contentRect;
      setBox({ width: cr.width, height: cr.height });
    });
    obs.observe(el);
    return () => obs.disconnect();
  }, []);

  return [ref, box];
}

/**
 * Scale factor that fits an interface's fixed `viewport` into a measured `box`.
 *
 * - `contain`: letterbox, the whole interface stays visible (min of both ratios).
 * - `width`: fill the width, height follows the viewport's aspect ratio.
 *
 * `allowUpscale=false` clamps at 1, so a small interface is never blown up past its natural size
 * (what the side panel and the fullscreen view want); thumbnails upscale so a card is filled.
 *
 * Returns 0 when the box has not been measured yet - callers render a placeholder for that frame.
 */
export function fitScale(
  box: MeasuredBox,
  viewport: FormatViewport,
  mode: 'contain' | 'width',
  allowUpscale = true,
): number {
  if (viewport.width <= 0 || viewport.height <= 0) return 0;
  if (box.width <= 0) return 0;
  if (mode === 'contain' && box.height <= 0) return 0;

  const raw = mode === 'contain'
    ? Math.min(box.width / viewport.width, box.height / viewport.height)
    : box.width / viewport.width;

  return allowUpscale ? raw : Math.min(1, raw);
}
