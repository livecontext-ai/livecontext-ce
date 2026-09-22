'use client';

/**
 * The OS "reduce motion" setting, as a boolean that stays live.
 *
 * It had been written twice before a third surface needed it (the credit ring's
 * opening sweep): once inside `ChangelogMediaView` in this subscribed form, and
 * once inside `HeroPhotoStack` as the shape everyone writes first - `useState`
 * plus an effect that reads the query. That second shape is not merely
 * duplication, it paints one frame with the wrong value on every mount, because
 * an effect runs after the first render. Both now import this.
 *
 * NOT every read belongs here. `DataTableGrid` checks the setting imperatively
 * inside an event-driven effect, to choose a `ScrollBehavior` at the moment it
 * scrolls. It wants a value, not a subscription: routing it through a hook would
 * re-render the whole grid whenever the reader flips an OS setting, to decide
 * something that is only ever read later. A one-shot `window.matchMedia(...)`
 * call is the right tool there.
 *
 * Subscribed rather than read into state: the value is external browser state,
 * so `useSyncExternalStore` reads it DURING render (no first paint with the
 * wrong value, no cascading re-render), and re-renders when the reader flips
 * the setting mid-session.
 *
 * The server snapshot is `false` - the same answer a browser without
 * `matchMedia` gives. A component whose markup differs on this value must
 * therefore reach its reduced state from an effect, not from the first render,
 * or the server HTML and the hydrated tree disagree for that reader.
 */

import { useSyncExternalStore } from 'react';

const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

/** Null when the browser has no matchMedia (jsdom, older embedded webviews). */
function motionQuery(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null;
  return window.matchMedia(REDUCED_MOTION_QUERY);
}

function subscribe(onChange: () => void): () => void {
  const query = motionQuery();
  if (!query) return () => {};
  // addListener is the pre-Safari-14 spelling; both are handled because a self-hosted install is
  // opened with whatever browser its users have, not only the ones we test.
  if (typeof query.addEventListener === 'function') {
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }
  if (typeof query.addListener === 'function') {
    query.addListener(onChange);
    return () => query.removeListener(onChange);
  }
  return () => {};
}

export function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => motionQuery()?.matches ?? false,
    () => false,
  );
}
