/**
 * @vitest-environment jsdom
 *
 * The shared "reduce motion" hook.
 *
 * It had no test of its own while it was private to one component, and neither
 * consumer exercised the part that can actually break: `ChangelogMediaView`'s
 * suite hands the mock inert `vi.fn()` listeners and never fires a change, and
 * the credit ring's suite mocks the hook away entirely. So the two properties
 * the hook exists FOR were asserted nowhere:
 *
 *  - it reads the query DURING render, so no frame is ever painted with the
 *    wrong answer (the effect-based shape everyone writes first fails this, and
 *    that shape was in the codebase);
 *  - it stays subscribed, so flipping the OS setting mid-session re-renders.
 *
 * Plus the two spellings of the listener API, which no consumer touches and
 * which decide whether the hook works at all on a Safari older than 14 - the
 * kind of browser a self-hosted install gets opened with.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import React from 'react';
import { render, screen, act, cleanup } from '@testing-library/react';
import { usePrefersReducedMotion } from '../usePrefersReducedMotion';

const REDUCED = '(prefers-reduced-motion: reduce)';

type Listener = () => void;

/** A matchMedia stand-in whose value can be flipped, like the OS setting. */
function fakeMatchMedia(options: { matches?: boolean; legacy?: boolean } = {}) {
  const listeners = new Set<Listener>();
  const state = { matches: options.matches ?? false, queried: [] as string[] };

  const query = {
    get matches() {
      return state.matches;
    },
    ...(options.legacy
      ? {
          addListener: (fn: Listener) => listeners.add(fn),
          removeListener: (fn: Listener) => listeners.delete(fn),
        }
      : {
          addEventListener: (_: string, fn: Listener) => listeners.add(fn),
          removeEventListener: (_: string, fn: Listener) => listeners.delete(fn),
        }),
  };

  window.matchMedia = ((q: string) => {
    state.queried.push(q);
    return query;
  }) as unknown as typeof window.matchMedia;

  return {
    state,
    listenerCount: () => listeners.size,
    flip(next: boolean) {
      state.matches = next;
      act(() => {
        listeners.forEach((fn) => fn());
      });
    },
  };
}

function Probe() {
  return <span data-testid="answer">{String(usePrefersReducedMotion())}</span>;
}

/**
 * Records the value the hook returned on EVERY render pass, in render order.
 *
 * `render()` wraps in `act()`, which flushes effects before it returns, so
 * reading the DOM afterwards only ever sees the SETTLED value. That is why a
 * DOM assertion cannot tell this hook apart from the `useState` + `useEffect`
 * shape it exists to replace - the effect-based one renders `false`, corrects
 * itself, and looks identical by the time the test looks. Pushing during render
 * is what makes the first pass observable.
 */
function renderPasses(): boolean[] {
  const seen: boolean[] = [];
  function Recorder() {
    seen.push(usePrefersReducedMotion());
    return null;
  }
  render(<Recorder />);
  return seen;
}

const answer = () => screen.getByTestId('answer').textContent;
const originalMatchMedia = window.matchMedia;

afterEach(() => {
  cleanup();
  window.matchMedia = originalMatchMedia;
  vi.restoreAllMocks();
});

describe('usePrefersReducedMotion', () => {
  it('answers false when the reader has asked for no such thing', () => {
    fakeMatchMedia({ matches: false });
    render(<Probe />);
    expect(answer()).toBe('false');
  });

  it('answers true ON THE FIRST RENDER, not one pass later', () => {
    // The whole reason this is useSyncExternalStore. An effect-based version
    // renders `false` first and corrects itself afterwards, which is one frame
    // of animation played at a reader who asked for none.
    //
    // Asserted on the FIRST recorded pass, not on the DOM: `render()` flushes
    // effects, so a settled-value assertion passes for both shapes. Verified by
    // mutation - swapping the hook for useState+useEffect leaves a DOM-only
    // version of this test green.
    fakeMatchMedia({ matches: true });
    expect(renderPasses()[0]).toBe(true);
  });

  it('needs no second pass to get there, on either answer', () => {
    // The complement: a hook that returned the right value but re-rendered to
    // reach it would pass the test above under StrictMode-ish double-invocation
    // while still being the shape this replaces. One pass, one answer.
    fakeMatchMedia({ matches: true });
    expect(renderPasses()).toEqual([true]);

    cleanup();
    fakeMatchMedia({ matches: false });
    expect(renderPasses()).toEqual([false]);
  });

  it('asks the browser for the reduced-motion query, not some other one', () => {
    const media = fakeMatchMedia();
    render(<Probe />);
    expect(media.state.queried).toContain(REDUCED);
  });

  it('re-renders when the setting is flipped mid-session', () => {
    const media = fakeMatchMedia({ matches: false });
    render(<Probe />);
    expect(answer()).toBe('false');

    media.flip(true);
    expect(answer()).toBe('true');
  });

  it('subscribes through the pre-Safari-14 spelling too', () => {
    // A self-hosted install is opened with whatever browser its users have, not
    // only the ones we test. On the legacy API the modern branch silently
    // subscribes to nothing, and the value freezes at its first answer.
    const media = fakeMatchMedia({ matches: false, legacy: true });
    render(<Probe />);
    expect(media.listenerCount()).toBe(1);

    media.flip(true);
    expect(answer()).toBe('true');
  });

  it('unsubscribes on unmount, on both spellings', () => {
    for (const legacy of [false, true]) {
      const media = fakeMatchMedia({ legacy });
      const view = render(<Probe />);
      expect(media.listenerCount()).toBe(1);
      view.unmount();
      expect(media.listenerCount()).toBe(0);
    }
  });

  it('answers false, and does not throw, where matchMedia does not exist', () => {
    // jsdom without the shim, and older embedded webviews. A hook that throws
    // here takes down every component that reads it.
    delete (window as Partial<Window>).matchMedia;
    expect(() => render(<Probe />)).not.toThrow();
    expect(answer()).toBe('false');
  });

  it('survives a query object that supports neither listener API', () => {
    // Same class of environment as above, one step less broken. The value is
    // still readable; only the live updating is lost.
    window.matchMedia = (() => ({ matches: true })) as unknown as typeof window.matchMedia;
    expect(() => render(<Probe />)).not.toThrow();
    expect(answer()).toBe('true');
  });
});
