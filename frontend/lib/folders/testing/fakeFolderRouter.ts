/**
 * A fake `next/navigation` for the list tests, backed by a real (in-memory) URL.
 *
 * The lists keep the open folder in the address (`?folder=<id>`), so a test that opens a
 * folder has to see the address change AND the component re-render - a plain `vi.fn()`
 * router would record the call and leave the page showing the old level, which is exactly
 * the bug such a test exists to catch. This store notifies React through
 * {@code useSyncExternalStore}, so navigating in a test behaves like navigating for real.
 *
 * It is a module singleton so the mock factory and the test body share one instance:
 *
 *   vi.mock('next/navigation', async () => {
 *     const mod = await import('@/lib/folders/testing/fakeFolderRouter');
 *     return mod.fakeFolderRouter.nextNavigationModule();
 *   });
 *   import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
 *
 *   beforeEach(() => fakeFolderRouter.reset('/en/app/workflow'));
 */
import { useSyncExternalStore } from 'react';

class FakeFolderRouter {
  private pathname = '/en/app/list';
  private currentSearch = '';
  // useSyncExternalStore compares snapshots by identity, so the params object is rebuilt
  // only when the query actually changes - a fresh one per call would loop forever.
  private snapshot = new URLSearchParams();
  private readonly listeners = new Set<() => void>();
  // Once bridged, it stays bridged for the rest of the file. Each vitest file gets its own
  // environment, and every test in a file that mocks the router wants the same fake.
  private historyBridged = false;
  private realReplaceState: History['replaceState'] | null = null;

  /** Every path the code asked to navigate to, in order. */
  readonly visited: string[] = [];

  /**
   * The same navigations, each tagged with HOW it was made. The difference decides whether
   * the browser's Back button can return to where the user was: `push` leaves a step
   * behind, `replace` overwrites it. A test that only looks at the resulting address
   * cannot tell a working Back from a broken one.
   */
  readonly navigations: Array<{ url: string; method: 'push' | 'replace' }> = [];

  /** Start a test at a given page with an empty query. */
  reset(pathname = '/en/app/list'): void {
    this.bridgeHistory();
    this.realReplaceState?.(null, '', pathname);
    this.pathname = pathname;
    this.currentSearch = '';
    this.snapshot = new URLSearchParams();
    this.visited.length = 0;
    this.navigations.length = 0;
    this.listeners.forEach((listener) => listener());
  }

  /** The current query string, without the leading "?". */
  search(): string {
    return this.currentSearch;
  }

  /** Navigate, as the router would: record it, and notify React if the query changed. */
  navigate = (url: string, method: 'push' | 'replace' = 'push'): void => {
    this.visited.push(url);
    this.navigations.push({ url, method });
    // The browser's own address follows too, as it does for real: code that reads
    // `window.location` right after a navigation must not see the page it just left.
    this.realReplaceState?.(null, '', url);
    const next = url.includes('?') ? url.slice(url.indexOf('?') + 1) : '';
    if (next === this.currentSearch) return;
    this.currentSearch = next;
    this.snapshot = new URLSearchParams(next);
    this.listeners.forEach((listener) => listener());
  };

  /**
   * Move the browser's address WITHOUT telling React, the state a real page is in between a
   * `pushState` and the transition in which `useSearchParams` catches up with it.
   */
  moveAddressOnly(url: string): void {
    this.bridgeHistory();
    this.realReplaceState?.(null, '', url);
  }

  /**
   * Play the part Next plays in a browser: `history.pushState`/`replaceState` for the page
   * already on screen IS a navigation, and `useSearchParams` has to see it. The lists change
   * the open folder that way (see {@code showFolderLevel}), because a router push of the bare
   * pathname is dropped when the page was loaded straight into a folder. Without this bridge a
   * test would watch the address change and the page keep showing the level it had.
   */
  private bridgeHistory(): void {
    if (this.historyBridged || typeof window === 'undefined') return;
    this.historyBridged = true;
    this.realReplaceState = window.history.replaceState.bind(window.history);
    // A call with no url changes only the state object and never the address; forwarding it
    // would navigate to the literal string "undefined".
    window.history.pushState = (_data: unknown, _unused: string, url?: string | URL | null) => {
      if (url != null) this.navigate(String(url), 'push');
    };
    window.history.replaceState = (_data: unknown, _unused: string, url?: string | URL | null) => {
      if (url != null) this.navigate(String(url), 'replace');
    };
  }

  private subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  private getSnapshot = (): URLSearchParams => this.snapshot;

  /** The module object to hand to `vi.mock('next/navigation', ...)`. */
  nextNavigationModule(): Record<string, unknown> {
    return {
      useSearchParams: () => useSyncExternalStore(this.subscribe, this.getSnapshot, this.getSnapshot),
      usePathname: () => this.pathname,
      useParams: () => ({}),
      useRouter: () => ({
        replace: (url: string) => this.navigate(url, 'replace'),
        push: (url: string) => this.navigate(url, 'push'),
        back: () => {},
        forward: () => {},
        refresh: () => {},
        prefetch: () => {},
      }),
    };
  }
}

export const fakeFolderRouter = new FakeFolderRouter();
