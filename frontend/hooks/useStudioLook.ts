'use client';

import * as React from 'react';

/**
 * Which ground the studio draws itself on.
 *
 * <p>`app` is the application's own theme, unchanged. `darkroom` is the studio
 * treatment: a neutral, desaturated surround in both light and dark, so the
 * asset is the brightest and the most saturated thing on screen rather than
 * competing with the page around it. See the `.studio-darkroom` block in
 * globals.css for what each one redefines and why.
 */
export type StudioLook = 'app' | 'darkroom';

const STORAGE_KEY = 'lc.studio.look';

/** The look a reader has not chosen yet. */
const DEFAULT_LOOK: StudioLook = 'app';

function isLook(value: unknown): value is StudioLook {
  return value === 'app' || value === 'darkroom';
}

/**
 * The reader's studio look, remembered in this browser.
 *
 * <p><b>Per browser, not per account.</b> It is a viewing preference about the
 * screen in front of them, like a zoom level: the same person on a laptop in a
 * bright room and on a phone at night does not want one answer for both, and
 * storing it server-side would give them one and cost a request on every open.
 *
 * <p><b>It starts on the DEFAULT and adopts the stored value after mount, which
 * is why this is not {@code usePersistentState}.</b> That hook reads storage in
 * the state INITIALISER, which is the right shape for a value read inside a
 * dialog or a panel that only ever mounts on the client. This one paints the
 * whole page of a server-rendered route: reading during the first render makes
 * the server markup and the first client render disagree, and React resolves
 * that by discarding the markup. Teaching the shared hook a second reading
 * strategy would put a hydration decision behind a boolean on every one of its
 * callers, so the divergence stays here, in the one place that needs it.
 *
 * <p><b>The cost of that, stated: a reader who chose the darkroom gets one
 * frame of the application's theme on every load.</b> The comment above used to
 * describe the hydration trade without naming what it buys on screen, which
 * reads as though the choice were free. It is not: the surface paints the
 * default ground, the effect runs, and it repaints. Accepted because the
 * alternative is worse in kind rather than in degree - a mismatched first
 * render discards the server markup for the whole route, and the flash is
 * bounded, once per load, on a preference about colour.
 *
 * <p>The real remedy is a pre-hydration inline script stamping the class on the
 * document before first paint, the way {@code app/[locale]/layout.tsx} already
 * stamps `lang`. Not done here: it would put a studio-only preference into the
 * layout of every localised page, and that is a decision about the document,
 * not about this control.
 *
 * <p>Every read and write is guarded: a private window, cleared site data or a
 * browser set to block storage throws on access rather than answering null, and
 * a studio that cannot render because of a colour preference is a worse outcome
 * than one that forgets it.
 */
export function useStudioLook(): [StudioLook, (next: StudioLook) => void] {
  const [look, setLook] = React.useState<StudioLook>(DEFAULT_LOOK);

  React.useEffect(() => {
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY);
      if (isLook(stored)) setLook(stored);
    } catch {
      // Storage unavailable: keep the default rather than failing the surface.
    }
  }, []);

  const choose = React.useCallback((next: StudioLook) => {
    setLook(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // The choice still applies for this session; only its memory is lost.
    }
  }, []);

  return [look, choose];
}

/**
 * The class that carries the look, or nothing at all for the app's own theme.
 *
 * <p>The ONE spelling of the rule. It carries the ground as well as the tokens
 * (see the `.studio-darkroom` block in globals.css), so no caller has to
 * remember to paint a background beside it: two spellings is how a surface ends
 * up with the tokens and not the ground, or the reverse.
 */
export function studioLookClass(look: StudioLook): string {
  return look === 'darkroom' ? 'studio-darkroom' : '';
}

/**
 * The look for everything under one surface, so a menu portalled OUT of that
 * surface can still be drawn on it.
 *
 * <p>A popover renders in a portal on the document, not inside the element that
 * opened it, so it reads the app's tokens however deeply nested its trigger
 * was: on the darkroom ground every menu came back as a bright panel over a
 * dark page. The class therefore travels through React rather than through the
 * DOM, and {@code StudioPopoverContent} is what puts it back on.
 *
 * <p>Defaults to the app's own look, so a studio component rendered outside a
 * provider (a test, a preview) is exactly what it was.
 */
const StudioLookContext = React.createContext<StudioLook>(DEFAULT_LOOK);

export const StudioLookProvider = StudioLookContext.Provider;

/** The class for the surface this component is inside, or '' on the app's look. */
export function useStudioLookClass(): string {
  return studioLookClass(React.useContext(StudioLookContext));
}
