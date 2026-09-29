/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { useAgendaPreferences, clearStoredAgendaTimezone } from '../useAgendaPreferences';
import { applyDisplayTimeZone, clearDisplayTimeZone } from '@/lib/utils/timezone';

const STORAGE_KEY = 'lc.agenda.preferences.v1';

/**
 * The agenda's view preferences.
 *
 * Most of this file is about a stored payload being WRONG rather than absent. The value
 * is written by whatever build the user last ran, survives upgrades, and is editable by
 * hand; a blind spread of it puts `undefined` into `view` and the page renders nothing,
 * with no way for the user to recover short of clearing site data.
 */
describe('useAgendaPreferences', () => {
  beforeEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
    // The display zone is module state: a test that applies one would otherwise decide what the next
    // one reads, and this file now has cases that depend on it differing from the seed.
    clearDisplayTimeZone();
  });

  afterEach(() => {
    window.localStorage.clear();
    clearDisplayTimeZone();
  });

  it('starts from the defaults when nothing is stored', async () => {
    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    expect(result.current.preferences.view).toBe('week');
    expect(result.current.preferences.weekStartsOn).toBe(1);
    expect(result.current.preferences.resourceTypes).toEqual(['WORKFLOW', 'APPLICATION', 'AGENT']);
    expect(result.current.preferences.dayStartHour).toBe(0);
    expect(result.current.preferences.dayEndHour).toBe(24);
  });

  it('restores a stored preference', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ view: 'week', density: 'compact' }));

    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    expect(result.current.preferences.view).toBe('week');
    expect(result.current.preferences.density).toBe('compact');
    // Fields the payload did not mention keep their defaults rather than becoming undefined.
    expect(result.current.preferences.timezone).toBeTruthy();
    expect(result.current.preferences.showPast).toBe(true);
  });

  it('falls back per field when a stored value is not something the UI can draw', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({
      view: 'gantt',              // a view that does not exist
      weekStartsOn: 3,            // only 0 and 1 are meaningful
      density: 'roomy',           // unknown density
      showWeekends: 'yes',        // wrong type
      resourceTypes: ['WORKFLOW', 'DRAGON'],
    }));

    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    expect(result.current.preferences.view).toBe('week');
    expect(result.current.preferences.weekStartsOn).toBe(1);
    expect(result.current.preferences.density).toBe('comfortable');
    expect(result.current.preferences.showWeekends).toBe(true);
    // The one valid entry survives; the unknown one is dropped rather than rendered.
    expect(result.current.preferences.resourceTypes).toEqual(['WORKFLOW']);
  });

  it('ends up on the ACCOUNT zone, not the UTC placeholder it starts from', async () => {
    // The change this hook exists for, and it was asserted nowhere: with the suite pinned to UTC,
    // `getClientTimeZone()` and the seed are the same string, so reverting all three call sites to
    // the seed left the file green. Applying a display zone first is what makes them differ.
    applyDisplayTimeZone('Asia/Tokyo');

    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    expect(result.current.preferences.timezone).toBe('Asia/Tokyo');
  });

  it('recovers the account zone even when storage cannot be read at all', async () => {
    // The seed is a placeholder now, so the hydrate effect is the only thing that puts the real zone
    // in place - and it wrapped the read AND the hydrate in one try. On a storage that throws
    // (private browsing, blocked site data) the catch left UTC in place for the life of the page: a
    // Tokyo reader's whole agenda nine hours off, labelled UTC so it looked deliberate. Before the
    // seed changed, the same path gave the device zone.
    applyDisplayTimeZone('Asia/Tokyo');
    const getItem = vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });

    try {
      const { result } = renderHook(() => useAgendaPreferences());
      await waitFor(() => expect(result.current.hydrated).toBe(true));

      expect(result.current.preferences.timezone).toBe('Asia/Tokyo');
    } finally {
      getItem.mockRestore();
    }
  });

  it('is formattable on the FIRST render, before any effect has run', () => {
    // The seed, not the hydrated value. This is the assertion that was missing, and its absence let
    // a whole page ship broken: the seed was changed to '' to stop `defaults()` reading a cookie
    // during render, and every render path in the agenda reaches
    // `new Intl.DateTimeFormat(..., { timeZone })`, which throws a RangeError on the empty string.
    // The week grid is computed in a `useMemo`, so it runs BEFORE the hydrate effect that would
    // have replaced the value: the view threw on its first paint for every visitor.
    //
    // The test below it, "rejects a timezone the platform cannot format in", is about the same
    // hazard and could not see this one, because it waits for `hydrated` first. Nothing asserted
    // what the hook returns before that.
    // Rendered WITHOUT effects, which is the only way to observe the seed:  flushes
    // effects synchronously, so by the time it returns the hydrate effect has already replaced the
    // value. A server pass is also the real failure path - it is the first paint.
    function Probe() {
      const { preferences } = useAgendaPreferences();
      // Formatting here is the actual crash site: the agenda computes its grid in a useMemo.
      return <>{new Intl.DateTimeFormat('en-US', { timeZone: preferences.timezone }).format(0)}</>;
    }

    expect(() => renderToStaticMarkup(<Probe />)).not.toThrow();
  });

  it('is still formattable after reset(), which runs long after hydration', () => {
    // `reset()` rebuilt its state from `defaults()`, so it put the render-time seed back into live
    // state at the moment somebody clicked a button - the one path where reading the cookie is
    // exactly right.
    const { result } = renderHook(() => useAgendaPreferences());

    act(() => result.current.reset());

    expect(() => new Intl.DateTimeFormat('en-US', { timeZone: result.current.preferences.timezone }))
      .not.toThrow();
    expect(result.current.preferences.timezone).toBeTruthy();
  });

  it('rejects a timezone the platform cannot format in', async () => {
    // A zone id can go stale between browser versions, and every formatter in the agenda
    // would then throw on the first render.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ timezone: 'Mars/Olympus_Mons' }));

    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    expect(() => new Intl.DateTimeFormat('en-US', { timeZone: result.current.preferences.timezone }))
      .not.toThrow();
    expect(result.current.preferences.timezone).not.toBe('Mars/Olympus_Mons');
  });

  it('refuses an inverted hour range, which would render an empty grid', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ dayStartHour: 20, dayEndHour: 4 }));

    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    expect(result.current.preferences.dayStartHour).toBe(0);
    expect(result.current.preferences.dayEndHour).toBe(24);
  });

  it('clamps out-of-range hours instead of drawing a grid of negative height', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ dayStartHour: -5, dayEndHour: 99 }));

    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    expect(result.current.preferences.dayStartHour).toBe(0);
    expect(result.current.preferences.dayEndHour).toBe(24);
  });

  it('survives a corrupt payload', async () => {
    window.localStorage.setItem(STORAGE_KEY, '{ not json');

    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    expect(result.current.preferences.view).toBe('week');
  });

  it('persists an update and keeps it in state', async () => {
    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    act(() => result.current.update({ view: 'day', showWeekends: false }));

    expect(result.current.preferences.view).toBe('day');
    expect(result.current.preferences.showWeekends).toBe(false);
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toMatchObject({
      view: 'day',
      showWeekends: false,
    });
  });

  it('keeps working when storage refuses to be written', async () => {
    // Private mode and blocked site data both throw on setItem. Losing persistence is
    // acceptable; losing the user's click is not.
    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });

    act(() => result.current.update({ view: 'list' }));

    expect(result.current.preferences.view).toBe('list');
  });

  it('toggles a resource type off and back on', async () => {
    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    act(() => result.current.toggleResourceType('AGENT'));
    expect(result.current.preferences.resourceTypes).not.toContain('AGENT');

    act(() => result.current.toggleResourceType('AGENT'));
    expect(result.current.preferences.resourceTypes).toContain('AGENT');
  });

  it('reset clears storage and returns to the defaults', async () => {
    const { result } = renderHook(() => useAgendaPreferences());
    await waitFor(() => expect(result.current.hydrated).toBe(true));
    act(() => result.current.update({ view: 'list', density: 'compact' }));

    act(() => result.current.reset());

    expect(result.current.preferences.view).toBe('week');
    expect(result.current.preferences.density).toBe('comfortable');
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
  /**
   * The first view on a phone.
   *
   * Week is the right default on a laptop and the worst of the four on a phone: seven
   * columns of a 390px screen are ~50px each, and a chip that narrow can only draw its
   * time. The screen therefore gets a say, but only while nothing has been stored: a
   * choice, once made, is the whole point of a preference.
   *
   * jsdom ships no `matchMedia` at all, which is why the hook guards for it and why every
   * test above still gets `week`.
   */
  describe('the first view follows the screen', () => {
    function pretendNarrow(narrow: boolean) {
      vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
        matches: narrow && query.includes('900px'),
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })));
    }

    afterEach(() => {
      vi.unstubAllGlobals();
    });

    it('opens a narrow screen on the list rather than a seven column week', async () => {
      pretendNarrow(true);
      const { result } = renderHook(() => useAgendaPreferences());
      await waitFor(() => expect(result.current.hydrated).toBe(true));

      expect(result.current.preferences.view).toBe('list');
      // Only the view is screen-dependent; nothing else about the agenda changes.
      expect(result.current.preferences.weekStartsOn).toBe(1);
      expect(result.current.preferences.showWeekends).toBe(true);
    });

    it('keeps a wide screen on the week', async () => {
      pretendNarrow(false);
      const { result } = renderHook(() => useAgendaPreferences());
      await waitFor(() => expect(result.current.hydrated).toBe(true));

      expect(result.current.preferences.view).toBe('week');
    });

    it('never overrules a stored choice, however narrow the screen is', async () => {
      pretendNarrow(true);
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ view: 'month' }));

      const { result } = renderHook(() => useAgendaPreferences());
      await waitFor(() => expect(result.current.hydrated).toBe(true));

      expect(result.current.preferences.view).toBe('month');
    });

    it('resets to what this screen would have opened on', async () => {
      pretendNarrow(true);
      const { result } = renderHook(() => useAgendaPreferences());
      await waitFor(() => expect(result.current.hydrated).toBe(true));

      act(() => result.current.update({ view: 'week' }));
      expect(result.current.preferences.view).toBe('week');

      act(() => result.current.reset());
      expect(result.current.preferences.view).toBe('list');
      expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
    });
  });
});

describe('the stored zone is forgettable, because it belongs to an account', () => {
  it('clearStoredAgendaTimezone drops only the zone, keeping the device habits', () => {
    // The zone is the one field of this blob that belongs to the ACCOUNT. On a shared browser the
    // previous person's pinned zone would otherwise open the next person's agenda, and an account
    // that later changes its zone would never see the agenda follow. Everything else here (view,
    // week start, which resource types to show) is a per-device habit worth keeping across a
    // sign-out.
    window.localStorage.setItem(
      'lc.agenda.preferences.v1',
      JSON.stringify({ timezone: 'Asia/Tokyo', view: 'day', showWeekends: false }),
    );

    clearStoredAgendaTimezone();

    const stored = JSON.parse(window.localStorage.getItem('lc.agenda.preferences.v1') as string);
    expect(stored.timezone).toBeUndefined();
    expect(stored.view).toBe('day');
    expect(stored.showWeekends).toBe(false);
  });

  it('does nothing when there is nothing stored, rather than writing an empty blob', () => {
    window.localStorage.removeItem('lc.agenda.preferences.v1');

    clearStoredAgendaTimezone();

    expect(window.localStorage.getItem('lc.agenda.preferences.v1')).toBeNull();
  });

  it('survives a blob that is not readable JSON', () => {
    // Storage is shared with whatever else wrote there, and a session ending must not throw.
    window.localStorage.setItem('lc.agenda.preferences.v1', 'not json');

    expect(() => clearStoredAgendaTimezone()).not.toThrow();
  });

  /**
   * The body of one `useCallback` in the provider, sliced out by name.
   *
   * <p>Per FUNCTION, not per file, and that distinction is the whole point of this helper. The
   * version before it counted `clearStoredAgendaTimezone()` across the whole source and required 2.
   * The provider had 2: BOTH inside `markSessionExpired`, and none in `logout`. The count was right,
   * the pairing was not, and the guard written to keep the two paths in step is what certified them
   * out of step - on the path people actually take, pressing sign out.
   *
   * <p>Bounded by the NEXT declaration, not by a closing brace. Matching the dependency array
   * (`}, []);`) looked safer and was not: that string occurs all over a 1200-line provider, so
   * `indexOf` from the declaration finds the next one ANYWHERE below - a changed dependency array
   * just moves the end marker further down, the slice swallows unrelated callbacks, and a call
   * belonging to one of them satisfies the count. The end assertion did not close that, because the
   * end was still found; it was simply the wrong end. Two declarations at the same indent cannot
   * overlap, so that boundary is the honest one, and it fails CLOSED: a renamed declaration is not
   * found and the assertion below says so.
   */
  function callbackBody(source: string, declaration: string): string {
    const start = source.indexOf(declaration);
    expect(start, `${declaration} must still exist`).toBeGreaterThan(-1);

    // The next `const <name> = ` at this declaration's own indentation, which is where the next
    // member of the provider begins.
    const indent = declaration.slice(0, declaration.length - declaration.trimStart().length);
    const next = new RegExp(`\\n${indent}const [A-Za-z_]`);
    const rest = source.slice(start + declaration.length);
    const match = next.exec(rest);
    expect(match, `${declaration} must be followed by another declaration`).not.toBeNull();
    return rest.slice(0, match!.index);
  }

  it('is called in EACH path that forgets the display zone, not twice in one of them', () => {
    // A source assertion, because the provider needs the OIDC client, the query client and a dozen
    // contexts to mount, and what matters is that neither clearing site forgets the other store.
    const source = readFileSync(
      join(process.cwd(), 'lib', 'providers', 'smart-providers.tsx'),
      'utf8',
    );

    // Both stores, in both paths, counted inside each body. Exactly one call each: a second one in
    // the same body is what let a missing one elsewhere look like the right total.
    for (const declaration of [
      '  const logout = useCallback',
      '  const markSessionExpired = useCallback',
    ] as const) {
      const body = callbackBody(source, declaration);

      expect(body.split('clearDisplayTimeZone()').length - 1, `${declaration} clears the zone once`)
        .toBe(1);
      expect(body.split('clearStoredAgendaTimezone()').length - 1,
        `${declaration} clears the agenda copy once`).toBe(1);
    }
  });
});
