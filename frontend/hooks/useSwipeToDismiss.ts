import { useCallback, useRef } from 'react';
import type { RefObject, TouchEvent } from 'react';

/** Movement (px) before a touch is committed to an axis. Below it, a tap stays a tap. */
const AXIS_LOCK_PX = 8;
/** Share of the panel width a slow drag must cover to dismiss. */
const DISTANCE_RATIO = 0.3;
/** A flick faster than this (px/ms) dismisses whatever the distance covered. */
const FLICK_VELOCITY = 0.5;
/** ...as long as it travelled at least this far, so a jittery tap never counts as a flick. */
const FLICK_MIN_PX = 24;

interface Options {
  /** Off: every handler is a no-op (desktop, or a closed drawer). */
  enabled: boolean;
  /** The sliding panel. Its transform follows the finger while dragging. */
  panelRef: RefObject<HTMLElement | null>;
  /** Optional backdrop, faded in step with the drag. */
  backdropRef?: RefObject<HTMLElement | null>;
  onDismiss: () => void;
}

interface Gesture {
  x0: number;
  y0: number;
  t0: number;
  dx: number;
  axis: 'x' | 'y' | null;
}

/**
 * Swipe-left-to-close for a drawer anchored on the left edge.
 *
 * The drag writes the panel's inline transform directly instead of going through
 * React state: the sidebar is a heavy tree, and a render per touchmove would drop
 * frames. On release the inline styles are cleared, so the class-driven transition
 * takes over from wherever the finger left the panel, either back to open or on
 * to closed (the dismiss re-render lands in the same style recalculation).
 *
 * A vertical gesture is left alone once the axis locks, so the conversation list
 * keeps scrolling normally.
 */
export function useSwipeToDismiss({ enabled, panelRef, backdropRef, onDismiss }: Options) {
  const gesture = useRef<Gesture | null>(null);

  const resetInlineStyles = useCallback(() => {
    const panel = panelRef.current;
    if (panel) {
      panel.style.transform = '';
      panel.style.transition = '';
    }
    const backdrop = backdropRef?.current;
    if (backdrop) {
      backdrop.style.opacity = '';
      backdrop.style.transition = '';
    }
  }, [panelRef, backdropRef]);

  const onTouchStart = useCallback((e: TouchEvent<HTMLElement>) => {
    // A portalled child (a menu opened from the drawer) bubbles its touches through
    // the React tree to here; only a touch that is physically on this element counts.
    if (!enabled || e.touches.length !== 1 || !e.currentTarget.contains(e.target as Node)) {
      gesture.current = null;
      return;
    }
    const touch = e.touches[0];
    gesture.current = { x0: touch.clientX, y0: touch.clientY, t0: Date.now(), dx: 0, axis: null };
  }, [enabled]);

  const onTouchMove = useCallback((e: TouchEvent<HTMLElement>) => {
    const g = gesture.current;
    if (!g || e.touches.length !== 1) return;
    const touch = e.touches[0];
    const dx = touch.clientX - g.x0;
    const dy = touch.clientY - g.y0;

    if (g.axis === null) {
      if (Math.abs(dx) < AXIS_LOCK_PX && Math.abs(dy) < AXIS_LOCK_PX) return;
      g.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
    }
    if (g.axis !== 'x') return;

    // Only towards the edge it hides behind: dragging right past fully open does nothing.
    g.dx = Math.min(0, dx);
    const panel = panelRef.current;
    if (!panel) return;
    panel.style.transition = 'none';
    panel.style.transform = `translateX(${g.dx}px)`;
    const backdrop = backdropRef?.current;
    if (backdrop) {
      const width = panel.offsetWidth || 1;
      backdrop.style.transition = 'none';
      backdrop.style.opacity = String(Math.max(0, 1 - Math.abs(g.dx) / width));
    }
  }, [panelRef, backdropRef]);

  const onTouchEnd = useCallback(() => {
    const g = gesture.current;
    gesture.current = null;
    if (!g || g.axis !== 'x') return;

    const width = panelRef.current?.offsetWidth || 0;
    const distance = -g.dx;
    const velocity = distance / Math.max(1, Date.now() - g.t0);
    const dismiss = (width > 0 && distance > width * DISTANCE_RATIO)
      || (velocity > FLICK_VELOCITY && distance > FLICK_MIN_PX);

    resetInlineStyles();
    if (dismiss) onDismiss();
  }, [panelRef, resetInlineStyles, onDismiss]);

  // A cancelled touch (the OS took the gesture) was never a decision: snap back open.
  const onTouchCancel = useCallback(() => {
    gesture.current = null;
    resetInlineStyles();
  }, [resetInlineStyles]);

  return { onTouchStart, onTouchMove, onTouchEnd, onTouchCancel };
}
