/**
 * @vitest-environment jsdom
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAgendaPreferences } from '../useAgendaPreferences';

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
  });

  afterEach(() => {
    window.localStorage.clear();
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
