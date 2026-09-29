/**
 * @vitest-environment jsdom
 *
 * The gate's actual job: re-render what it wraps when the zone changes, and only then.
 *
 * This is the mechanism an audit found untested. The resolver had tests, the hook had tests, and
 * the one thing that makes a preference arriving late VISIBLE - the re-render - had none; the
 * layout test asserts containment against a stub of this component, so it pins JSX shape and
 * nothing here.
 *
 * The remount is the cost of that visibility (~250 call sites format dates with plain functions
 * and subscribe to nothing), so what matters is that it happens on a real change and NOT on the
 * initial mount, because this wraps the shell that owns running canvases and SSE streams.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act, cleanup } from '@testing-library/react';

// The hook is exercised by its own file; here it must simply not fetch anything.
vi.mock('@/hooks/useDisplayPreferences', () => ({ useDisplayPreferences: () => {} }));

/**
 * Every subscription the gate opens, and whether it was disposed.
 *
 * <p>The only way to see the cleanup. The gate's effect returns the module's own unsubscribe, and
 * nothing observable happens when that return goes missing: React 19 swallows a setState on an
 * unmounted component, so a leak is silent. Wrapping the real function records the disposer so the
 * test can assert it was called.
 */
const subscriptions = vi.hoisted(() => [] as { disposed: boolean }[]);

vi.mock('@/lib/utils/timezone', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/utils/timezone')>();
  return {
    ...actual,
    subscribeToDisplayTimeZone: (listener: () => void) => {
      const record = { disposed: false };
      subscriptions.push(record);
      const stop = actual.subscribeToDisplayTimeZone(listener);
      return () => {
        record.disposed = true;
        stop();
      };
    },
  };
});

import DisplayPreferencesGate from '../DisplayPreferencesGate';
import {
  applyDisplayTimeZone,
  clearDisplayTimeZone,
  getBrowserTimeZone,
  subscribeToDisplayTimeZone as realSubscribe,
  DISPLAY_TIME_ZONE_COOKIE,
} from '@/lib/utils/timezone';

/**
 * A zone that is NOT this machine's.
 *
 * <p>Several tests below need a change to BE a change. Hardcoding Asia/Tokyo made them depend on
 * where they run: on a runner in Tokyo the remount test fails and the no-remount test passes for
 * the wrong reason. (TZ= cannot fix it either - on Windows it does not move what Node resolves.)
 *
 * <p>Every test that needs a zone OTHER than this device uses this, including the three that were
 * still spelling Asia/Tokyo out after it existed. The worst of those was the unsubscribe test: it
 * asserts that a notification still reaches a live listener, and on a Tokyo runner the zone it
 * applied was already in use, so nothing was notified and the test FAILED - the only environment
 * where the constant was left unused was the one it was written for.
 */
const OTHER_ZONE = getBrowserTimeZone() === 'Asia/Tokyo' ? 'America/Denver' : 'Asia/Tokyo';

/** The same zone as the cookie carries it: percent-encoded, as a browser would write it. */
const OTHER_ZONE_COOKIE_VALUE = encodeURIComponent(OTHER_ZONE);

/** Counts its own mounts, so a remount is distinguishable from a re-render. */
function Child({ onMount }: { onMount: () => void }) {
  React.useEffect(() => {
    onMount();
  }, [onMount]);
  return <p>child</p>;
}

beforeEach(() => {
  subscriptions.length = 0;
  clearDisplayTimeZone();
});
afterEach(() => {
  cleanup();
  clearDisplayTimeZone();
});

describe('what the gate does to its subtree', () => {
  it('mounts it once, and does not re-key on the first render', () => {
    const onMount = vi.fn();

    render(
      <DisplayPreferencesGate>
        <Child onMount={onMount} />
      </DisplayPreferencesGate>
    );

    expect(onMount).toHaveBeenCalledTimes(1);
  });

  it('re-mounts it when the zone actually changes, which is what makes dates follow', () => {
    const onMount = vi.fn();
    render(
      <DisplayPreferencesGate>
        <Child onMount={onMount} />
      </DisplayPreferencesGate>
    );
    onMount.mockClear();

    act(() => applyDisplayTimeZone(OTHER_ZONE));

    expect(onMount).toHaveBeenCalledTimes(1);
  });

  it('does NOT re-mount when the same zone is applied again', () => {
    render(
      <DisplayPreferencesGate>
        <Child onMount={() => {}} />
      </DisplayPreferencesGate>
    );
    act(() => applyDisplayTimeZone(OTHER_ZONE));

    const onMount = vi.fn();
    cleanup();
    render(
      <DisplayPreferencesGate>
        <Child onMount={onMount} />
      </DisplayPreferencesGate>
    );
    onMount.mockClear();

    // Already applied, so nothing is notified. (It does NOT return early - it records the zone and
    // the cookie either way and skips only the notification, which is the distinction a reader
    // chasing the guard needs.) A gate that re-keyed on every notification would tear the shell
    // down for no reason.
    act(() => applyDisplayTimeZone(OTHER_ZONE));

    expect(onMount).not.toHaveBeenCalled();
  });

  it('does NOT re-mount for a returning person whose cookie already painted that zone', () => {
    // THE common case, and the one a guard on "have I applied a zone yet" gets wrong. A person who
    // picked Tokyo yesterday arrives with `LC_TZ=Asia/Tokyo`: the first paint is already in Tokyo,
    // then the profile lands a moment later and applies Tokyo again. Nothing changed for the
    // reader, so nothing may be torn down - and this subtree is the shell, canvases and SSE
    // streams included. Guarding on the module's own "applied" field instead of on the zone
    // actually in use remounted the whole app once per page load, for everybody.
    document.cookie = `${DISPLAY_TIME_ZONE_COOKIE}=${OTHER_ZONE_COOKIE_VALUE}; path=/`;
    const onMount = vi.fn();
    render(
      <DisplayPreferencesGate>
        <Child onMount={onMount} />
      </DisplayPreferencesGate>
    );
    onMount.mockClear();

    act(() => applyDisplayTimeZone(OTHER_ZONE));

    expect(onMount).not.toHaveBeenCalled();
  });

  it('does NOT re-mount when the stored zone IS this device zone, the "auto" default', () => {
    // Nobody pinned anything: the account carries the zone the browser reported at sign-in, which
    // is the zone the first paint used anyway. Re-applying it must be free.
    const device = getBrowserTimeZone();
    const onMount = vi.fn();
    render(
      <DisplayPreferencesGate>
        <Child onMount={onMount} />
      </DisplayPreferencesGate>
    );
    onMount.mockClear();

    act(() => applyDisplayTimeZone(device));

    expect(onMount).not.toHaveBeenCalled();
  });

  it('ignores a zone the runtime cannot use', () => {
    const onMount = vi.fn();
    render(
      <DisplayPreferencesGate>
        <Child onMount={onMount} />
      </DisplayPreferencesGate>
    );
    onMount.mockClear();

    act(() => applyDisplayTimeZone('Mars/Base'));

    expect(onMount).not.toHaveBeenCalled();
  });

  it('stops listening once unmounted, so a later change touches no dead tree', () => {
    // Asserted by watching the UNSUBSCRIBE, not by `not.toThrow()`. React 19 makes setState on an
    // unmounted component a silent no-op and this suite installs no console.error guard, so a leaked
    // subscription throws nothing: the old assertion passed against a gate whose effect returned
    // nothing at all.
    const { unmount } = render(
      <DisplayPreferencesGate>
        <Child onMount={() => {}} />
      </DisplayPreferencesGate>
    );
    expect(subscriptions).toHaveLength(1);
    expect(subscriptions[0].disposed).toBe(false);

    unmount();

    expect(subscriptions[0].disposed).toBe(true);
    // And the notification still reaches everyone else, so the zero above is an unsubscribe rather
    // than a module that stopped notifying.
    let othersTold = 0;
    const stop = realSubscribe(() => { othersTold += 1; });
    act(() => applyDisplayTimeZone(OTHER_ZONE));
    expect(othersTold).toBe(1);
    stop();
  });

  it('renders its children, rather than replacing them', () => {
    const { container } = render(
      <DisplayPreferencesGate>
        <Child onMount={() => {}} />
      </DisplayPreferencesGate>
    );

    expect(container.textContent).toContain('child');
  });
});
