import { describe, it, expect, beforeEach } from 'vitest';
import { useInterfacePaginationStore } from '../interface-pagination-store';

// Reset the Zustand store before each test to ensure isolation
beforeEach(() => {
  useInterfacePaginationStore.setState({ pages: {} });
});

describe('useInterfacePaginationStore', () => {
  // ---------------------------------------------------------------------------
  // Initial state
  // ---------------------------------------------------------------------------
  describe('initial state', () => {
    it('should start with an empty pages record', () => {
      const state = useInterfacePaginationStore.getState();
      expect(state.pages).toEqual({});
    });

    it('should expose a setPage function', () => {
      const state = useInterfacePaginationStore.getState();
      expect(typeof state.setPage).toBe('function');
    });
  });

  // ---------------------------------------------------------------------------
  // setPage
  // ---------------------------------------------------------------------------
  describe('setPage', () => {
    it('should add a page entry for a new interfaceId', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('iface-1', 3);

      const state = useInterfacePaginationStore.getState();
      expect(state.pages).toEqual({ 'iface-1': 3 });
    });

    it('should update the page for an existing interfaceId', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('iface-1', 1);
      setPage('iface-1', 5);

      const state = useInterfacePaginationStore.getState();
      expect(state.pages['iface-1']).toBe(5);
    });

    it('should track multiple interfaces independently', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('iface-a', 2);
      setPage('iface-b', 7);
      setPage('iface-c', 0);

      const state = useInterfacePaginationStore.getState();
      expect(state.pages).toEqual({
        'iface-a': 2,
        'iface-b': 7,
        'iface-c': 0,
      });
    });

    it('should not affect other interface pages when updating one', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('iface-a', 1);
      setPage('iface-b', 2);
      setPage('iface-a', 10);

      const state = useInterfacePaginationStore.getState();
      expect(state.pages['iface-a']).toBe(10);
      expect(state.pages['iface-b']).toBe(2);
    });

    it('should allow setting page to 0', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('iface-1', 5);
      setPage('iface-1', 0);

      const state = useInterfacePaginationStore.getState();
      expect(state.pages['iface-1']).toBe(0);
    });

    it('should handle empty string as interfaceId', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('', 3);

      const state = useInterfacePaginationStore.getState();
      expect(state.pages['']).toBe(3);
    });
  });

  // ---------------------------------------------------------------------------
  // clear (run-switch invalidation)
  // ---------------------------------------------------------------------------
  describe('clear (run-switch invalidation)', () => {
    it('should reset all pages to empty', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('iface-a', 3);
      setPage('iface-b', 7);
      setPage('iface-c', 1);

      useInterfacePaginationStore.getState().clear();

      expect(useInterfacePaginationStore.getState().pages).toEqual({});
    });

    it('should allow setting pages after clear', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('old-iface', 5);

      useInterfacePaginationStore.getState().clear();
      useInterfacePaginationStore.getState().setPage('new-iface', 2);

      const state = useInterfacePaginationStore.getState();
      expect(state.pages).toEqual({ 'new-iface': 2 });
      expect(state.pages['old-iface']).toBeUndefined();
    });

    it('should be safe to clear when already empty', () => {
      expect(() => {
        useInterfacePaginationStore.getState().clear();
      }).not.toThrow();
      expect(useInterfacePaginationStore.getState().pages).toEqual({});
    });

    it('should be safe to clear multiple times', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('iface-1', 3);

      useInterfacePaginationStore.getState().clear();
      useInterfacePaginationStore.getState().clear();
      useInterfacePaginationStore.getState().clear();

      expect(useInterfacePaginationStore.getState().pages).toEqual({});
    });

    it('should create new object reference on clear', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('iface-1', 3);
      const pagesBefore = useInterfacePaginationStore.getState().pages;

      useInterfacePaginationStore.getState().clear();
      const pagesAfter = useInterfacePaginationStore.getState().pages;

      expect(pagesBefore).not.toBe(pagesAfter);
      expect(pagesBefore).toEqual({ 'iface-1': 3 }); // old reference unchanged
      expect(pagesAfter).toEqual({});
    });

    it('cleared page should return 0 via ?? fallback', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      setPage('iface-1', 5);

      useInterfacePaginationStore.getState().clear();

      const page = useInterfacePaginationStore.getState().pages['iface-1'] ?? 0;
      expect(page).toBe(0);
    });

    it('simulated run switch: old pagination cleared before new run', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      // Run A sets pagination positions
      setPage('runA-iface', 3);
      setPage('shared-iface', 7);

      // User switches to Run B → clear
      useInterfacePaginationStore.getState().clear();

      // Run B sets its own pagination
      useInterfacePaginationStore.getState().setPage('shared-iface', 0);
      useInterfacePaginationStore.getState().setPage('runB-iface', 1);

      const state = useInterfacePaginationStore.getState();
      expect(state.pages['runA-iface']).toBeUndefined();
      expect(state.pages['shared-iface']).toBe(0);
      expect(state.pages['runB-iface']).toBe(1);
    });
  });
});

// ---------------------------------------------------------------------------
// useSharedInterfacePage hook (tested via store internals since hooks require
// a React render environment - we test the logic directly here)
// ---------------------------------------------------------------------------
describe('useSharedInterfacePage logic (store-level)', () => {
  beforeEach(() => {
    useInterfacePaginationStore.setState({ pages: {} });
  });

  describe('page reading', () => {
    it('should return 0 for an interfaceId with no stored page', () => {
      const state = useInterfacePaginationStore.getState();
      const page = state.pages['unknown-id'] ?? 0;
      expect(page).toBe(0);
    });

    it('should return the stored page for an existing interfaceId', () => {
      useInterfacePaginationStore.getState().setPage('iface-x', 4);
      const page = useInterfacePaginationStore.getState().pages['iface-x'] ?? 0;
      expect(page).toBe(4);
    });
  });

  describe('null interfaceId handling', () => {
    it('should return 0 when interfaceId is null (selector logic)', () => {
      // When interfaceId is null, the selector returns 0
      const interfaceId: string | null = null;
      const state = useInterfacePaginationStore.getState();
      const currentPage = interfaceId ? state.pages[interfaceId] ?? 0 : 0;
      expect(currentPage).toBe(0);
    });

    it('should no-op on setPage when interfaceId is null (callback logic)', () => {
      // The useSharedInterfacePage hook returns early if interfaceId is null
      const interfaceId: string | null = null;
      const { setPage } = useInterfacePaginationStore.getState();

      // Simulate the guard in the hook
      if (interfaceId) {
        setPage(interfaceId, 5);
      }

      const state = useInterfacePaginationStore.getState();
      expect(state.pages).toEqual({});
    });
  });

  describe('functional updater logic', () => {
    it('should support a functional updater pattern (prev => prev + 1)', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      const interfaceId = 'iface-func';
      setPage(interfaceId, 3);

      // Simulate functional updater as used in useSharedInterfacePage
      const current = useInterfacePaginationStore.getState().pages[interfaceId] ?? 0;
      const updater = (prev: number) => prev + 1;
      setPage(interfaceId, updater(current));

      expect(useInterfacePaginationStore.getState().pages[interfaceId]).toBe(4);
    });

    it('should handle functional updater from initial 0 (no stored page)', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      const interfaceId = 'iface-new';

      // Simulate: no page stored yet, functional updater prev => prev + 1
      const current = useInterfacePaginationStore.getState().pages[interfaceId] ?? 0;
      setPage(interfaceId, current + 1);

      expect(useInterfacePaginationStore.getState().pages[interfaceId]).toBe(1);
    });

    it('should handle functional updater that decrements', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      const interfaceId = 'iface-dec';
      setPage(interfaceId, 5);

      const current = useInterfacePaginationStore.getState().pages[interfaceId] ?? 0;
      setPage(interfaceId, Math.max(0, current - 1));

      expect(useInterfacePaginationStore.getState().pages[interfaceId]).toBe(4);
    });

    it('should handle functional updater that resets to 0', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      const interfaceId = 'iface-reset';
      setPage(interfaceId, 10);

      setPage(interfaceId, 0);

      expect(useInterfacePaginationStore.getState().pages[interfaceId]).toBe(0);
    });
  });

  describe('state immutability', () => {
    it('should create a new pages object reference on each setPage call', () => {
      const { setPage } = useInterfacePaginationStore.getState();
      const pagesBefore = useInterfacePaginationStore.getState().pages;
      setPage('iface-1', 1);
      const pagesAfter = useInterfacePaginationStore.getState().pages;

      expect(pagesBefore).not.toBe(pagesAfter);
    });
  });
});
