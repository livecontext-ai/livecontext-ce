// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import {
  URL_SEARCH_DEBOUNCE_MS,
  urlEnum,
  urlJson,
  urlList,
  urlNullable,
  urlString,
  useUrlSearchState,
  useUrlState,
} from '../useUrlState';

const PAGE = '/en/app/tables';

/** Open the page on a given query, the way a reload or a shared link does. */
function openAt(query: string): void {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
  fakeFolderRouter.navigations.length = 0;
}

describe('useUrlState', () => {
  beforeEach(() => openAt(''));
  afterEach(() => vi.useRealTimers());

  it('starts on the default and leaves the address clean', () => {
    const { result } = renderHook(() => useUrlState('sort', 'name'));
    expect(result.current[0]).toBe('name');
    expect(fakeFolderRouter.search()).toBe('');
    expect(fakeFolderRouter.navigations).toHaveLength(0);
  });

  it('restores the value from the address on load (the reload case)', () => {
    openAt('sort=updated&page=3');
    const sort = renderHook(() => useUrlState('sort', 'name'));
    const page = renderHook(() => useUrlState('page', 1));
    expect(sort.result.current[0]).toBe('updated');
    expect(page.result.current[0]).toBe(3);
  });

  it('writes a change to the address with replace, so Back still leaves the page', () => {
    const { result } = renderHook(() => useUrlState('sort', 'name'));
    act(() => result.current[1]('updated'));
    expect(result.current[0]).toBe('updated');
    expect(fakeFolderRouter.navigations).toEqual([
      { url: `${PAGE}?sort=updated`, method: 'replace' },
    ]);
  });

  it('uses push when asked, so Back undoes the step', () => {
    const { result } = renderHook(() => useUrlState('tab', 'all', { history: 'push' }));
    act(() => result.current[1]('mine'));
    expect(fakeFolderRouter.navigations).toEqual([{ url: `${PAGE}?tab=mine`, method: 'push' }]);
  });

  it('removes the parameter when the value goes back to its default', () => {
    openAt('sort=updated');
    const { result } = renderHook(() => useUrlState('sort', 'name'));
    act(() => result.current[1]('name'));
    expect(fakeFolderRouter.search()).toBe('');
  });

  it('preserves every other parameter of the page', () => {
    openAt('folder=f1&view=skills');
    const { result } = renderHook(() => useUrlState('sort', 'name'));
    act(() => result.current[1]('updated'));
    expect(fakeFolderRouter.search()).toBe('folder=f1&view=skills&sort=updated');
  });

  it('keeps both writes when two states are set in the same handler', () => {
    const { result } = renderHook(() => ({
      search: useUrlState('q', ''),
      page: useUrlState('page', 1),
    }));
    act(() => {
      result.current.search[1]('invoice');
      result.current.page[1](4);
    });
    expect(fakeFolderRouter.search()).toBe('q=invoice&page=4');
  });

  it('supports a functional update, including two in the same handler', () => {
    const { result } = renderHook(() => useUrlState('page', 1));
    act(() => {
      result.current[1]((p) => p + 1);
      result.current[1]((p) => p + 1);
    });
    expect(result.current[0]).toBe(3);
    expect(fakeFolderRouter.search()).toBe('page=3');
  });

  it('drops the parameters a tab owns when the tab changes, and resets their state', () => {
    openAt('tab=mine&q=invoice&page=3&folder=f1');
    const { result } = renderHook(() => ({
      tab: useUrlState('tab', 'all', { history: 'push', clears: ['q', 'page'] }),
      page: useUrlState('page', 1),
    }));
    act(() => result.current.tab[1]('shared'));
    expect(fakeFolderRouter.search()).toBe('tab=shared&folder=f1');
    expect(result.current.page[0]).toBe(1);
  });

  it('a component mounting right after a tab switch reads the address as written', () => {
    openAt('tab=mine&q=invoice');
    const tab = renderHook(() =>
      useUrlState('tab', 'all', { history: 'push', clears: ['q'] }));
    // The fake router notifies synchronously; hold it back the way a transition does, so the
    // next mount still renders the query being left.
    const navigate = fakeFolderRouter.navigate;
    const held: Array<() => void> = [];
    window.history.pushState = (_d: unknown, _u: string, url?: string | URL | null) => {
      if (url == null) return;
      fakeFolderRouter.moveAddressOnly(String(url));
      held.push(() => navigate(String(url), 'push'));
    };
    act(() => tab.result.current[1]('shared'));
    const search = renderHook(() => useUrlState('q', ''));
    expect(search.result.current[0]).toBe('');
    act(() => held.forEach((run) => run()));
    expect(search.result.current[0]).toBe('');
    expect(fakeFolderRouter.search()).toBe('tab=shared');
    window.history.pushState = (_d: unknown, _u: string, url?: string | URL | null) => {
      if (url != null) navigate(String(url), 'push');
    };
  });

  it('a tab switch also resets a search still inside its debounce, which is not in the address yet', () => {
    vi.useFakeTimers();
    openAt('tab=mine');
    const { result } = renderHook(() => ({
      tab: useUrlState('tab', 'all', { history: 'push', clears: ['q'] }),
      search: useUrlSearchState(),
    }));
    act(() => result.current.search[1]('invoice'));
    act(() => result.current.tab[1]('shared'));
    act(() => vi.advanceTimersByTime(URL_SEARCH_DEBOUNCE_MS * 2));
    expect(result.current.search[0]).toBe('');
    expect(fakeFolderRouter.search()).toBe('tab=shared');
  });

  it('follows Back to a value it wrote earlier and then replaced', () => {
    const { result } = renderHook(() => useUrlState('tab', 'all', { history: 'push' }));
    act(() => {
      result.current[1]('mine');
      result.current[1]('shared');
      result.current[1]('mine');
    });
    expect(result.current[0]).toBe('mine');
    act(() => fakeFolderRouter.navigate(`${PAGE}?tab=shared`, 'push'));
    expect(result.current[0]).toBe('shared');
  });

  it('builds a write on the address as it is now, not as it was at the last render', () => {
    // Someone else changed the address (the folder navigation, a one-shot parameter being
    // stripped) and React has not re-rendered yet. A write built from the rendered query
    // would silently put the old value back.
    openAt('folder=f1&page=3');
    const { result } = renderHook(() => useUrlState('page', 1));
    fakeFolderRouter.moveAddressOnly(`${PAGE}?page=3`);
    act(() => result.current[1](1));
    expect(fakeFolderRouter.search()).toBe('');
  });

  it('does not graft a pending write onto another page showing the same query', () => {
    const first = renderHook(() => useUrlState('sort', 'name'));
    act(() => first.result.current[1]('updated'));
    first.unmount();
    fakeFolderRouter.reset('/en/app/agent');
    const second = renderHook(() => useUrlState('page', 1));
    act(() => second.result.current[1](2));
    expect(fakeFolderRouter.search()).toBe('page=2');
  });

  it('follows the address when it changes from outside (Back, Forward, a link)', () => {
    const { result } = renderHook(() => useUrlState('sort', 'name'));
    act(() => result.current[1]('updated'));
    act(() => fakeFolderRouter.navigate(PAGE, 'push'));
    expect(result.current[0]).toBe('name');
    act(() => fakeFolderRouter.navigate(`${PAGE}?sort=created`, 'push'));
    expect(result.current[0]).toBe('created');
  });

  it('falls back to the default on a value it does not recognise', () => {
    openAt('tab=gone&page=abc');
    const tab = renderHook(() =>
      useUrlState('tab', 'all', { codec: urlEnum(['all', 'mine'] as const) }));
    const page = renderHook(() => useUrlState('page', 1));
    expect(tab.result.current[0]).toBe('all');
    expect(page.result.current[0]).toBe(1);
  });

  it('keeps the setter stable when the caller passes inline defaults', () => {
    const { result, rerender } = renderHook(() =>
      useUrlState<string[]>('tags', [], { codec: urlList() }));
    const first = result.current[1];
    rerender();
    expect(result.current[1]).toBe(first);
  });

  it('stays local and never touches the address when disabled', () => {
    openAt('sort=updated');
    const { result } = renderHook(() => useUrlState('sort', 'name', { enabled: false }));
    expect(result.current[0]).toBe('name');
    act(() => result.current[1]('created'));
    expect(result.current[0]).toBe('created');
    expect(fakeFolderRouter.search()).toBe('sort=updated');
  });

  describe('debounced text', () => {
    beforeEach(() => vi.useFakeTimers());

    it('updates the value at once and the address only once the typing pauses', () => {
      const { result } = renderHook(() => useUrlSearchState());
      act(() => result.current[1]('i'));
      act(() => result.current[1]('in'));
      act(() => result.current[1]('inv'));
      expect(result.current[0]).toBe('inv');
      expect(fakeFolderRouter.navigations).toHaveLength(0);
      act(() => vi.advanceTimersByTime(URL_SEARCH_DEBOUNCE_MS));
      expect(fakeFolderRouter.navigations).toEqual([{ url: `${PAGE}?q=inv`, method: 'replace' }]);
      expect(result.current[0]).toBe('inv');
    });

    it('drops a pending write when the component goes away', () => {
      const { result, unmount } = renderHook(() => useUrlSearchState());
      act(() => result.current[1]('inv'));
      unmount();
      act(() => vi.advanceTimersByTime(URL_SEARCH_DEBOUNCE_MS));
      expect(fakeFolderRouter.navigations).toHaveLength(0);
    });

    it('lets an outside change win over a pending write', () => {
      const { result } = renderHook(() => useUrlSearchState());
      act(() => result.current[1]('inv'));
      act(() => fakeFolderRouter.navigate(`${PAGE}?q=report`, 'push'));
      act(() => vi.advanceTimersByTime(URL_SEARCH_DEBOUNCE_MS));
      expect(result.current[0]).toBe('report');
      expect(fakeFolderRouter.search()).toBe('q=report');
    });
  });

  describe('codecs', () => {
    it('round-trips a list, including a value that contains a comma', () => {
      const codec = urlList();
      const raw = codec.serialize(['a,b', 'c']);
      expect(codec.parse(raw)).toEqual(['a,b', 'c']);
      expect(codec.parse('')).toEqual([]);
    });

    it('reads a stray "%" as text instead of throwing on it', () => {
      // The address arrives already decoded, so a typed "100%25" reaches the codec as "100%".
      openAt('types=100%25');
      const { result } = renderHook(() => useUrlState<string[]>('types', [], { codec: urlList() }));
      expect(result.current[0]).toEqual(['100%']);
    });

    it('round-trips values holding the escape character, and leaves a colon to the address', () => {
      const codec = urlList();
      expect(codec.parse(codec.serialize(['50%', 'a%2Cb', 'x,y']))).toEqual(['50%', 'a%2Cb', 'x,y']);
      expect(codec.serialize(['mcp:gmail', 'core:loop'])).toBe('mcp:gmail,core:loop');
    });

    it('drops list values outside the allowed set', () => {
      expect(urlList(['x', 'y'] as const).parse('x,z,y')).toEqual(['x', 'y']);
    });

    it('round-trips a structured value and refuses a wrong shape or broken JSON', () => {
      const codec = urlJson<{ field: string }[]>(Array.isArray);
      expect(codec.parse(codec.serialize([{ field: 'name' }]))).toEqual([{ field: 'name' }]);
      expect(codec.parse('{"field":"name"}')).toBeUndefined();
      expect(codec.parse('[{')).toBeUndefined();
    });

    it('spells an unset nullable value by absence', () => {
      openAt('row=42');
      const { result } = renderHook(() =>
        useUrlState<string | null>('row', null, { codec: urlNullable(urlString) }));
      expect(result.current[0]).toBe('42');
      act(() => result.current[1](null));
      expect(fakeFolderRouter.search()).toBe('');
    });
  });
});
